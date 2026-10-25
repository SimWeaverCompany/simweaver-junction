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
 * channels of a Pitch Talk federation (joining it briefly).
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

    /** Joins the Talk federation briefly and lists its channels by name. */
    private static Result talkChannels(EndpointConfig cfg, File runtimeDir, String federation, String lsd) {
        RtiSession.Version v = cfg.isHla4() ? RtiSession.Version.HLA_1516_2025 : RtiSession.Version.HLA_1516E;
        RtiSession s = null;
        try {
            // a dynamic Talk endpoint joins without modules; the channel names are read with the standard Talk FOM
            List<String> foms = cfg.isDynamicFom() ? cfg.getTalkFomPaths() : cfg.getFomPaths();
            InformationExchangeModel iem = cfg.isHla4() ? InformationExchangeModel.fromHla4(foms)
                    : InformationExchangeModel.fromHlaEvolved(foms);
            ObjectClass channel = iem.getObjectClass("HLAobjectRoot.PitchTalk.Channel");
            if (channel == null) {
                return new Result("channel", Collections.emptyList(), "the FOM has no PitchTalk.Channel class");
            }
            final HlaCodec codec = new HlaCodec(iem);
            final ClassAttribute nameAttr = channel.getAttribute("Name");
            RtiLibrary lib = RtiLibrary.fromSetting(cfg.getRtiJar(), runtimeDir);
            s = lib.createSession(v);
            final Map<Long, String[]> found = new ConcurrentHashMap<Long, String[]>();
            final Map<Integer, String> classNames = new ConcurrentHashMap<Integer, String>();
            final int[] nameHandle = new int[1];
            final RtiSession probe = s;
            final Quiet listener = new Quiet() {
                @Override
                public void discovered(long instance, int objectClass, String name, String producer) {
                    String cn = classNames.get(objectClass);
                    found.put(instance, new String[] {"", cn == null ? "Channel" : cn});
                }

                @Override
                public void reflected(long instance, int[] attributes, byte[][] values, byte[] tag, String producer) {
                    for (int i = 0; i < attributes.length; i++) {
                        if (attributes[i] == nameHandle[0]) {
                            try {
                                HValue n = codec.decode(nameAttr.getDataType(), values[i]);
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
            };
            synchronized (lib.connectLock()) {
                probe.connect(settings(cfg, lsd), listener);
                probe.join(federation, "SimwJunctionProbe", null, cfg.isDynamicFom() ? Collections.<String>emptyList() : cfg.getFomPaths(), false);
            }
            for (String sub : new String[] {"HLAobjectRoot.PitchTalk.Channel.AudioChannel.RadioChannel",
                    "HLAobjectRoot.PitchTalk.Channel.AudioChannel.VoiceChannel", "HLAobjectRoot.PitchTalk.Channel.ChatChannel",
                    "HLAobjectRoot.PitchTalk.Channel"}) {
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
            return new Result("channel", Collections.emptyList(), e.getMessage());
        } finally {
            if (s != null) {
                s.close();
            }
        }
    }

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
