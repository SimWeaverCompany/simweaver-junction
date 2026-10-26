package com.simweaver.junction;

import com.simweaver.common.dis.DisNetwork;
import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.codec.HlaCodec;
import com.simweaver.common.hla.session.ConnectSettings;
import com.simweaver.common.hla.session.RtiLibrary;
import com.simweaver.common.hla.session.RtiSession;
import com.simweaver.common.hla.session.RtiSessionException;
import com.simweaver.common.hla.session.RtiSessionListener;
import com.simweaver.common.iem.ClassAttribute;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.iem.ObjectClass;
import com.simweaver.common.json.Json;
import com.simweaver.junction.config.EndpointConfig;
import com.simweaver.junction.config.EndpointType;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds what an endpoint could be started on, for the start dialog: the federations its RTI
 * knows (HLA), the exercise ids heard on its network (DIS, listening for a moment), or the
 * channels of a Talk federation (joining it briefly).
 */
public final class TargetProbe {

    private TargetProbe() {
    }

    /** What was found. */
    public static final class Result implements Json.Writable {
        final String kind;
        final List<Object> targets;
        final String error;

        Result(String kind, List<Object> targets, String error) {
            this.kind = kind;
            this.targets = targets;
            this.error = error;
        }

        @Override
        public Object toJson() {
            return Json.object().put("kind", kind).put("targets", targets).putIfNotNull("error", error);
        }
    }

    /**
     * @param cfg        the endpoint
     * @param runtimeDir the runtime folder
     * @param federation TALK: the federation whose channels to list; null = list federations
     * @return what was found (an error text instead when the probe failed)
     */
    public static Result probe(EndpointConfig cfg, File runtimeDir, String federation) {
        return probe(cfg, runtimeDir, federation, null);
    }

    /**
     * @param lsd HLA: the LSD / RTI configuration name to connect with; null = the configured default
     */
    public static Result probe(EndpointConfig cfg, File runtimeDir, String federation, String lsd) {
        EndpointType t = cfg.getType();
        if (t == null) {
            return new Result("none", Collections.emptyList(), "unknown standard");
        }
        if (t.isDis()) {
            return exercises(cfg);
        }
        if (t == EndpointType.TALK && federation != null && !federation.trim().isEmpty()) {
            return talkChannels(cfg, runtimeDir, federation.trim(), lsd);
        }
        return federations(cfg, runtimeDir, lsd);
    }

    private static Result federations(EndpointConfig cfg, File runtimeDir, String lsd) {
        RtiSession.Version v = cfg.isHla4() ? RtiSession.Version.HLA_1516_2025 : RtiSession.Version.HLA_1516E;
        RtiSession s = null;
        try {
            RtiLibrary lib = RtiLibrary.fromSetting(cfg.getRtiJar(), runtimeDir);
            s = lib.createSession(v);
            synchronized (lib.connectLock()) {
                s.connect(settings(cfg, lsd), new Quiet());
            }
            List<Object> l = new ArrayList<Object>(s.listFederations(3000));
            return new Result("federation", l, null);
        } catch (RtiSessionException | IllegalArgumentException e) {
            return new Result("federation", Collections.emptyList(), e.getMessage());
        } finally {
            if (s != null) {
                s.disconnect();
            }
        }
    }

    private static ConnectSettings settings(EndpointConfig cfg, String lsd) {
        return ConnectSettings.of(lsd == null ? cfg.getLsd() : lsd.trim()).withRtiAddress(cfg.getRtiAddress())
                .withAdditionalSettings(cfg.getRtiSettings()).withFactoryName(cfg.getRtiFactory());
    }

    /** Listens 1.5 s and reports the exercise ids heard, with PDU counts. */
    private static Result exercises(EndpointConfig cfg) {
        DisNetwork net;
        try {
            net = DisNetwork.parse(cfg.getDisInterface(), cfg.getDisPort());
        } catch (IllegalArgumentException e) {
            return new Result("exercise", Collections.emptyList(), e.getMessage());
        }
        Map<Integer, Integer> heard = new TreeMap<Integer, Integer>();
        try (MulticastSocket s = net.open(200)) {
            byte[] buf = new byte[65536];
            long end = System.currentTimeMillis() + 1500;
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (p.getLength() >= 12 && (buf[0] >= 4 && buf[0] <= 7)) {
                    heard.merge(buf[1] & 0xFF, 1, Integer::sum);
                }
            }
        } catch (IOException e) {
            return new Result("exercise", Collections.emptyList(), "cannot listen on " + net + ": " + e.getMessage());
        }
        List<Object> l = new ArrayList<Object>();
        for (Map.Entry<Integer, Integer> e : heard.entrySet()) {
            l.add(Json.object().put("id", String.valueOf(e.getKey())).put("pdus", e.getValue()));
        }
        return new Result("exercise", l, null);
    }

    /**
     * Joins the Talk federation briefly and lists its channels by name. A dynamic endpoint (the
     * default for Talk, 0.9.21) joins without modules and reads the Talk FOM from the federation.
     */
    private static Result talkChannels(EndpointConfig cfg, File runtimeDir, String federation, String lsd) {
        RtiSession.Version v = cfg.isHla4() ? RtiSession.Version.HLA_1516_2025 : RtiSession.Version.HLA_1516E;
        RtiSession s = null;
        try {
            final java.util.concurrent.atomic.AtomicReference<HlaCodec> codec = new java.util.concurrent.atomic.AtomicReference<HlaCodec>();
            final java.util.concurrent.atomic.AtomicReference<ClassAttribute> nameAttr = new java.util.concurrent.atomic.AtomicReference<ClassAttribute>();
            InformationExchangeModel fileModel = null;
            if (!cfg.isDynamicFom()) {
                fileModel = cfg.isHla4() ? InformationExchangeModel.fromHla4(cfg.getFomPaths())
                        : InformationExchangeModel.fromHlaEvolved(cfg.getFomPaths());
            }
            RtiLibrary lib = RtiLibrary.fromSetting(cfg.getRtiJar(), runtimeDir);
            s = lib.createSession(v);
            final Map<Long, String[]> found = new ConcurrentHashMap<Long, String[]>();
            final Map<Integer, String> classNames = new ConcurrentHashMap<Integer, String>();
            final int[] nameHandle = new int[1];
            final RtiSession probe = s;
            final java.util.concurrent.atomic.AtomicReference<com.simweaver.common.hla.session.DynamicFom.Reader> fdd =
                    new java.util.concurrent.atomic.AtomicReference<com.simweaver.common.hla.session.DynamicFom.Reader>();
            final Quiet listener = new Quiet() {
                @Override
                public void discovered(long instance, int objectClass, String name, String producer) {
                    com.simweaver.common.hla.session.DynamicFom.Reader r = fdd.get();
                    if (r != null && r.discovered(instance, objectClass)) {
                        return;
                    }
                    String cn = classNames.get(objectClass);
                    if (cn != null) {
                        found.put(instance, new String[] {"", cn});
                    }
                }

                @Override
                public void reflected(long instance, int[] attributes, byte[][] values, byte[] tag, String producer) {
                    com.simweaver.common.hla.session.DynamicFom.Reader r = fdd.get();
                    if (r != null && r.reflected(instance, attributes, values)) {
                        return;
                    }
                    HlaCodec c = codec.get();
                    ClassAttribute na = nameAttr.get();
                    for (int i = 0; i < attributes.length && c != null && na != null; i++) {
                        if (attributes[i] == nameHandle[0]) {
                            try {
                                HValue n = c.decode(na.getDataType(), values[i]);
                                String[] e = found.get(instance);
                                if (e != null) {
                                    e[0] = n.asTrimmedText();
                                }
                            } catch (RuntimeException ignored) {
                                // not a name
                            }
                        }
                    }
                }

                @Override
                public void removed(long instance, byte[] tag, String producer) {
                    com.simweaver.common.hla.session.DynamicFom.Reader r = fdd.get();
                    if (r != null) {
                        r.removed(instance);
                    }
                }
            };
            synchronized (lib.connectLock()) {
                probe.connect(settings(cfg, lsd), listener);
                probe.join(federation, "SimwJunctionProbe", null, cfg.isDynamicFom() ? Collections.<String>emptyList() : cfg.getFomPaths(), false);
            }
            InformationExchangeModel iem = fileModel;
            if (iem == null) {
                com.simweaver.common.hla.session.DynamicFom.Reader r =
                        new com.simweaver.common.hla.session.DynamicFom.Reader(s, cfg.getFddClass(), cfg.getFddAttribute());
                fdd.set(r);
                r.start();
                iem = com.simweaver.common.hla.session.DynamicFom.toModel(r.await(cfg.getFddTimeoutSecs() * 1000L), cfg.isHla4());
            }
            ObjectClass channel = iem.getObjectClass(TALK_CHANNEL);
            if (channel == null) {
                return new Result("channel", Collections.emptyList(), "the federation's FOM has no Talk channel class (" + TALK_CHANNEL + ")");
            }
            codec.set(new HlaCodec(iem));
            nameAttr.set(channel.getAttribute("Name"));
            for (String sub : new String[] {TALK_CHANNEL + ".AudioChannel.RadioChannel", TALK_CHANNEL + ".AudioChannel.VoiceChannel",
                TALK_CHANNEL + ".ChatChannel", TALK_CHANNEL}) {
                if (iem.getObjectClass(sub) == null) {
                    continue;
                }
                int c = s.objectClass(sub);
                classNames.put(c, sub.substring(sub.lastIndexOf('.') + 1));
                int a = s.attribute(c, "Name");
                nameHandle[0] = a;
                s.subscribeObjectClass(c, new int[] {a});
            }
            Thread.sleep(800);
            for (Long inst : found.keySet()) {
                s.requestAttributeUpdate(inst, new int[] {nameHandle[0]}, null);
            }
            Thread.sleep(1200);
            List<Object> l = new ArrayList<Object>();
            for (String[] e : found.values()) {
                if (!e[0].isEmpty()) {
                    l.add(Json.object().put("id", e[0]).put("type", e[1]));
                }
            }
            return new Result("channel", l, null);
        } catch (Exception e) {
            String m = e.getMessage();
            if (cfg.isDynamicFom() && String.valueOf(m).contains("DoesNotExist")) {
                m = m + " (the Talk federation is not running)";
            }
            return new Result("channel", Collections.emptyList(), m);
        } finally {
            if (s != null) {
                s.close();
            }
        }
    }

    /** The channel class of the Talk FOM (its name in that FOM). */
    private static final String TALK_CHANNEL = "HLAobjectRoot.PitchTalk.Channel";

    /** Ignores every callback. */
    private static class Quiet implements RtiSessionListener {
        @Override
        public void discovered(long instance, int objectClass, String name, String producer) {
        }

        @Override
        public void reflected(long instance, int[] attributes, byte[][] values, byte[] tag, String producer) {
        }

        @Override
        public void removed(long instance, byte[] tag, String producer) {
        }

        @Override
        public void received(int interactionClass, int[] parameters, byte[][] values, byte[] tag, String producer) {
        }

        @Override
        public void provideRequested(long instance, int[] attributes, byte[] tag) {
        }

        @Override
        public void nameReserved(String name, boolean success) {
        }

        @Override
        public void connectionLost(String reason) {
        }
    }
}
