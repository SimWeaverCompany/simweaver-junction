package com.simweaver.junction;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.codec.HlaCodec;
import com.simweaver.common.hla.session.ConnectSettings;
import com.simweaver.common.hla.session.RtiLibrary;
import com.simweaver.common.hla.session.RtiSession;
import com.simweaver.common.hla.session.RtiSessionListener;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.json.Json;
import com.simweaver.j2j.J2jNode;
import com.simweaver.j2j.config.J2jConfig;
import com.simweaver.j2j.crypto.J2jCrypto;
import com.simweaver.j2j.session.J2jSessions;
import com.simweaver.junction.config.ConfigLoader;
import com.simweaver.junction.config.GatewayConfig;
import com.simweaver.junction.endpoint.j2j.J2jEndpoint;

import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * J2J object sessions (0.10.0, design §9.1–9.5): HLA federations on two J2J servers (in one JVM,
 * test RTI) bridged through a session — Blue on server a, Green and later Red on server b. An
 * aircraft and its values, a WeaponFire, a late joiner's snapshot, provide requests, deleting, a
 * leaving member, a lost server and its return; the settings.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.junction.J2jObjectSelfTest [fom root] [fake rti folder]</pre>
 */
public final class J2jObjectSelfTest {

    private static final String AIRCRAFT = "HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft";
    private static final String FIRE = "HLAinteractionRoot.WeaponFire";
    private static int checks;
    private static int failures;

    private J2jObjectSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File fomRoot = new File(args.length > 0 ? args[0] : "simwrift/rtdir/etc/fom").getAbsoluteFile();
        File rtiDir = new File(args.length > 1 ? args[1] : "fakerti/build").getAbsoluteFile();
        String rpr2 = new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath();
        File jar3 = new File(rtiDir, "fakerti-1516e.jar");
        File dir = Files.createTempDirectory("junction-j2j-objects").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        ConfigLoader loader = new ConfigLoader(junctions, dir);

        // ---- settings
        GatewayConfig ga = loader.gateway(new File(junctions, "ga.junction.settings"), gateway("Blue", jar3, rpr2, null));
        check("an HLA endpoint and a J2J endpoint in an IEM gateway: valid " + ga.getErrors(), ga.isValid());
        String json = Json.toJson(ga.getEndpoints().get("j2j"));
        check("the J2J endpoint carries objects: " + json, json.contains("\"j2jkind\":\"objects\"") && json.contains("RPR_FOM_v2.0"));
        SimwProperties dyn = gateway("Blue", jar3, rpr2, null);
        dyn.set("junction.endpoint.j2j.hla.foms", "dynamic");
        GatewayConfig gdyn = loader.gateway(new File(junctions, "dyn.junction.settings"), dyn);
        check("a J2J endpoint cannot take a dynamic FOM: " + gdyn.getErrors(), !gdyn.isValid()
                && String.join(" ", gdyn.getErrors()).contains("fixed model"));
        SimwProperties talk = new SimwProperties();
        talk.set("junction.endpoints", "t;j2j");
        talk.set("junction.endpoint.t.standard", "comms");
        talk.set("junction.endpoint.t.hla.federation", "TalkFed");
        talk.set("junction.endpoint.j2j.standard", "j2j");
        talk.set("junction.endpoint.j2j.j2j.session", "radio");
        talk.set("junction.router.kind", "comms");
        GatewayConfig gtalk = loader.gateway(new File(junctions, "talk.junction.settings"), talk);
        check("a J2J endpoint in an HLA Comms gateway needs the HLA Comms FOM files: " + gtalk.getErrors(), !gtalk.isValid()
                && String.join(" ", gtalk.getErrors()).contains("needs the HLA Comms FOM"));
        GatewayConfig gb = loader.gateway(new File(junctions, "gb.junction.settings"), gateway("Green", jar3, rpr2, null));
        GatewayConfig gc = loader.gateway(new File(junctions, "gc.junction.settings"), gateway("Red", jar3, rpr2, null));

        // ---- two J2J servers
        int pa = freePort(), pb = freePort();
        Path d = Files.createTempDirectory("j2jobj");
        Files.write(d.resolve("network.settings"), ("network.id=obj-net\nmember.a.addresses=127.0.0.1:" + pa
                + "\nmember.b.addresses=127.0.0.1:" + pb + "\n").getBytes(StandardCharsets.UTF_8));
        Files.write(d.resolve("network.key"), ("k1=" + Base64.getEncoder().encodeToString(J2jCrypto.sha256("obj test".getBytes(StandardCharsets.UTF_8)))
                + "\n").getBytes(StandardCharsets.UTF_8));
        J2jNode[] a = {J2jNode.start(cfg(d, "a", pa), "SimWeaver test")};
        J2jNode b = J2jNode.start(cfg(d, "b", pb), "SimWeaver test");
        J2jSessions ha = new J2jSessions(2000), hb = new J2jSessions(2000);
        ha.attach(a[0]);
        hb.attach(b);
        J2jEndpoint.setHubs(g -> g.startsWith("ga") ? ha : hb);

        RtiLibrary lib = RtiLibrary.isolated(Collections.singletonList(jar3));
        InformationExchangeModel m = InformationExchangeModel.fromHlaEvolved(Collections.singletonList(rpr2));
        HlaCodec codec = new HlaCodec(m);
        Gateway gwA = new Gateway(ga, Collections.<String, String>emptyMap(), dir, null);
        Gateway gwB = new Gateway(gb, Collections.<String, String>emptyMap(), dir, null);
        Gateway gwC = null;
        Fed blue = null, green = null, red = null;
        try {
            check("the servers are linked", await(() -> a[0].links().link("b") != null, 10000));
            gwA.start();
            gwB.start();
            check("both gateways run", gwA.getState() == Gateway.State.RUNNING && gwB.getState() == Gateway.State.RUNNING);
            check("one object session, two members, active", await(() -> {
                List<Map<String, Object>> ms = members(ha);
                return ms.size() == 2 && "active".equals(ms.get(1).get("state")) && "objects".equals(ha.sessions().get(0).get("kind"));
            }, 5000));

            blue = new Fed(lib, "Blue", "BlueSim", rpr2);
            green = new Fed(lib, "Green", "GreenSim", rpr2);
            int acB = blue.s.objectClass(AIRCRAFT);
            int[] hB = {blue.s.attribute(acB, "Marking"), blue.s.attribute(acB, "ForceIdentifier")};
            blue.s.publishObjectClass(acB, hB);
            int fireB = blue.s.interactionClass(FIRE);
            blue.s.publishInteractionClass(fireB);
            int acG = green.s.objectClass(AIRCRAFT);
            int[] hG = {green.s.attribute(acG, "Marking"), green.s.attribute(acG, "ForceIdentifier")};
            green.s.subscribeObjectClass(acG, hG);
            int fireG = green.s.interactionClass(FIRE);
            green.s.subscribeInteractionClass(fireG);

            // Blue -> session -> Green
            blue.reserve("Viper-1");
            long viper = blue.s.registerObject(acB, "Viper-1");
            byte[] mk = codec.encode(m.getClassAttribute(AIRCRAFT, "Marking").getDataType(), marking("VIPER1"));
            byte[] force = codec.encode(m.getClassAttribute(AIRCRAFT, "ForceIdentifier").getDataType(), HValue.ofInt(1));
            blue.s.updateAttributes(viper, hB, new byte[][] {mk, force}, null);
            String[] disc = green.take("discovered", 6000);
            check("Green discovers Viper-1 through the session", disc != null && "Viper-1".equals(disc[3]));
            long gViper = disc == null ? 0 : Long.parseLong(disc[1]);
            check("Green gets its Marking and ForceIdentifier", Arrays.equals(green.waitValue(gViper, hG[0], 5000), mk)
                    && Arrays.equals(green.waitValue(gViper, hG[1], 5000), force));

            int pIdx = blue.s.parameter(fireB, "FireMissionIndex");
            byte[] idx = codec.encode(m.getClassParameter(FIRE, "FireMissionIndex").getDataType(), HValue.ofInt(4711));
            blue.s.sendInteraction(fireB, new int[] {pIdx}, new byte[][] {idx}, null);
            check("Green receives the WeaponFire", green.take("received", 5000) != null && green.lastInteraction != null
                    && green.lastInteraction.values().stream().anyMatch(v -> Arrays.equals(v, idx)));

            // updates go on
            byte[] mk2 = codec.encode(m.getClassAttribute(AIRCRAFT, "Marking").getDataType(), marking("VIPER9"));
            blue.s.updateAttributes(viper, new int[] {hB[0]}, new byte[][] {mk2}, null);
            Fed g2 = green;
            check("an update reaches Green", await(() -> Arrays.equals(g2.byInstance.getOrDefault(gViper, Collections.emptyMap()).get(hG[0]), mk2), 5000));

            // a late joiner on server b: gets the session's objects as a snapshot
            red = new Fed(lib, "Red", "RedSim", rpr2);
            int acR = red.s.objectClass(AIRCRAFT);
            int[] hR = {red.s.attribute(acR, "Marking"), red.s.attribute(acR, "ForceIdentifier")};
            red.s.subscribeObjectClass(acR, hR);
            gwC = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
            gwC.start();
            String[] discR = red.take("discovered", 8000);
            if (discR == null) {
                System.out.println("DEBUG C: " + Json.toJson(gwC));
                System.out.println("DEBUG A: " + Json.toJson(gwA));
                System.out.println("DEBUG hb: " + Json.toJson(hb.sessions()));
            }
            check("a gateway joining later (Red): Viper-1 discovered from the snapshot", discR != null && "Viper-1".equals(discR[3]));
            long rViper = discR == null ? 0 : Long.parseLong(discR[1]);
            check("with its latest Marking", Arrays.equals(red.waitValue(rViper, hR[0], 5000), mk2));

            String status = Json.toJson(gwB);
            check("gateway status: the object session", status.contains("\"kind\":\"objects\"") && status.contains("\"remoteobjects\":1")
                    && status.contains("\"gateway\":\"ga\""));

            // provide: Green asks for Viper-1's values; they come from the owner side
            green.values.clear();
            green.byInstance.remove(gViper);
            green.s.requestAttributeUpdate(gViper, hG, null);
            check("a provide request in Green is answered", Arrays.equals(green.waitValue(gViper, hG[0], 5000), mk2));

            // the owner deletes it
            blue.s.deleteObject(viper, null);
            check("deleted in Blue: removed in Green and Red", green.take("removed", 5000) != null && red.take("removed", 5000) != null);

            // a lost server: its objects go; when it is back they come again
            blue.reserve("Viper-2");
            long viper2 = blue.s.registerObject(acB, "Viper-2");
            blue.s.updateAttributes(viper2, hB, new byte[][] {mk, force}, null);
            check("Viper-2 discovered in Green", green.take("discovered", 6000) != null);
            ha.detach();
            a[0].stop();
            check("server a lost: Viper-2 removed in Green (lost after 2 s)", green.take("removed", 15000) != null);
            a[0] = J2jNode.start(cfg(d, "a", pa), "SimWeaver test");
            ha.attach(a[0]);
            String[] again = green.take("discovered", 15000);
            check("server a back: Viper-2 discovered again", again != null && "Viper-2".equals(again[3]));

            // a member leaves: its objects go
            gwA.stop();
            check("Blue's gateway stopped: Viper-2 removed in Green", green.take("removed", 6000) != null);
            check("and the session has two members", await(() -> members(hb).size() == 2, 5000));
        } finally {
            if (gwC != null) {
                gwC.stop();
            }
            gwA.stop();
            gwB.stop();
            for (Fed f : new Fed[] {blue, green, red}) {
                if (f != null) {
                    f.s.resign(false);
                    f.s.disconnect();
                }
            }
            ha.close();
            hb.close();
            a[0].stop();
            b.stop();
        }
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    static SimwProperties gateway(String federation, File jar, String fom, String session) {
        SimwProperties p = new SimwProperties();
        p.set("junction.endpoints", "hla;j2j");
        p.set("junction.endpoint.hla.standard", "ieee1516-2010");
        p.set("junction.endpoint.hla.hla.foms", fom);
        p.set("junction.endpoint.hla.hla.federation", federation);
        p.set("junction.endpoint.hla.hla.rtijar", jar.getPath());
        p.set("junction.endpoint.j2j.standard", "j2j");
        p.set("junction.endpoint.j2j.j2j.session", session == null ? "air" : session);
        p.set("junction.endpoint.j2j.hla.foms", fom);
        return p;
    }

    static J2jConfig cfg(Path d, String id, int port) {
        SimwProperties s = new SimwProperties().set("j2j.node.id", id).set("j2j.port", String.valueOf(port)).set("j2j.deadseconds", "2")
                .set("j2j.fallback.port", "0");
        return J2jConfig.load(s, d.resolve("network.settings"), d.resolve("network.key"));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> members(J2jSessions h) {
        for (Map<String, Object> s : h.sessions()) {
            if ("air".equals(s.get("name"))) {
                return (List<Map<String, Object>>) s.get("members");
            }
        }
        return Collections.emptyList();
    }

    static HValue marking(String text) {
        Map<String, HValue> mk = new LinkedHashMap<String, HValue>();
        mk.put("MarkingEncodingType", HValue.ofEnum(1, "ASCII"));
        mk.put("MarkingData", HValue.ofText(text));
        return HValue.ofRecord(mk);
    }

    /** A test federate. */
    static final class Fed implements RtiSessionListener {
        final RtiSession s;
        final java.util.concurrent.BlockingQueue<String[]> q = new java.util.concurrent.LinkedBlockingQueue<String[]>();
        final Map<Integer, byte[]> values = new ConcurrentHashMap<Integer, byte[]>();
        final Map<Long, Map<Integer, byte[]>> byInstance = new ConcurrentHashMap<Long, Map<Integer, byte[]>>();
        volatile Map<Integer, byte[]> lastInteraction;

        Fed(RtiLibrary lib, String federation, String name, String fom) throws Exception {
            s = lib.createSession(RtiSession.Version.HLA_1516E);
            s.connect(ConnectSettings.defaults(), this);
            s.join(federation, "Tester", name, Collections.singletonList(fom), true);
        }

        void reserve(String name) throws Exception {
            s.reserveName(name);
            String[] r = take("name", 3000);
            if (r == null || !r[2].equals("true")) {
                throw new IllegalStateException("could not reserve " + name);
            }
        }

        String[] take(String kind, long millis) throws InterruptedException {
            long end = System.currentTimeMillis() + millis;
            List<String[]> skipped = new java.util.ArrayList<String[]>();
            try {
                while (System.currentTimeMillis() < end) {
                    String[] e = q.poll(Math.max(1, end - System.currentTimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (e == null) {
                        return null;
                    }
                    if (e[0].equals(kind)) {
                        return e;
                    }
                    skipped.add(e);
                }
                return null;
            } finally {
                q.addAll(skipped);
            }
        }

        byte[] waitValue(long instance, int attr, long millis) throws InterruptedException {
            long end = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < end) {
                Map<Integer, byte[]> mine = byInstance.get(instance);
                byte[] v = mine == null ? null : mine.get(attr);
                if (v != null) {
                    return v;
                }
                Thread.sleep(20);
            }
            return null;
        }

        @Override
        public void discovered(long instance, int objectClass, String name, String producer) {
            q.add(new String[] {"discovered", String.valueOf(instance), String.valueOf(objectClass), name});
        }

        @Override
        public void reflected(long instance, int[] attributes, byte[][] vals, byte[] tag, String producer) {
            Map<Integer, byte[]> mine = byInstance.computeIfAbsent(instance, k -> new ConcurrentHashMap<Integer, byte[]>());
            for (int i = 0; i < attributes.length; i++) {
                values.put(attributes[i], vals[i]);
                mine.put(attributes[i], vals[i]);
            }
        }

        @Override
        public void removed(long instance, byte[] tag, String producer) {
            q.add(new String[] {"removed", String.valueOf(instance)});
        }

        @Override
        public void received(int interactionClass, int[] parameters, byte[][] vals, byte[] tag, String producer) {
            Map<Integer, byte[]> mm = new LinkedHashMap<Integer, byte[]>();
            for (int i = 0; i < parameters.length; i++) {
                mm.put(parameters[i], vals[i]);
            }
            lastInteraction = mm;
            q.add(new String[] {"received", String.valueOf(interactionClass)});
        }

        @Override
        public void provideRequested(long instance, int[] attributes, byte[] tag) {
        }

        @Override
        public void nameReserved(String name, boolean success) {
            q.add(new String[] {"name", name, String.valueOf(success)});
        }

        @Override
        public void connectionLost(String reason) {
        }
    }

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    static boolean await(BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return c.getAsBoolean();
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
