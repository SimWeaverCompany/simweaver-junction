package com.simweaver.rift;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.codec.HlaCodec;
import com.simweaver.common.hla.session.ConnectSettings;
import com.simweaver.common.hla.session.RtiLibrary;
import com.simweaver.common.hla.session.RtiSession;
import com.simweaver.common.hla.session.RtiSessionListener;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.infra.InfraApi;
import com.simweaver.common.infra.InfraRegistry;
import com.simweaver.common.rest.Jsons;
import com.simweaver.common.rest.XrResultPair;
import com.simweaver.junction.Gateway;
import com.simweaver.junction.config.ConfigLoader;
import com.simweaver.junction.config.GatewayConfig;
import com.simweaver.rift.api.DataSource;
import com.simweaver.rift.api.DatasourceFactory;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Infra view end to end (0.9.20): a Junction gateway with an HLA 1516-2010 endpoint (the class
 * path test RTI), an HLA 1516-2025 endpoint (an isolated test RTI) and a DIS 7 endpoint, a Rift HLA
 * datasource in the same 1516-2010 federation and a simulator there; DIS PDUs from a test socket.
 * Checks the items, the MOM's federates (names, host, type, rates), the traffic counted per
 * producer, publications and subscriptions through the MOM's reports, the senders of a DIS
 * exercise, the REST API, and that items go when their sources stop.
 *
 * <pre>java -cp out/classes:out/test:fakerti/build/fakerti-1516e.jar com.simweaver.rift.InfraBridgeSelfTest</pre>
 */
public final class InfraBridgeSelfTest {

    private static final String AIRCRAFT = "HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft";
    private static int checks;
    private static int failures;

    private InfraBridgeSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File fomRoot = new File(args.length > 0 ? args[0] : "simwrift/rtdir/etc/fom").getAbsoluteFile();
        File rtiDir = new File(args.length > 1 ? args[1] : "fakerti/build").getAbsoluteFile();
        try {
            run(fomRoot, rtiDir);
        } catch (Throwable t) {
            t.printStackTrace();
            failures++;
        }
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run(File fomRoot, File rtiDir) throws Exception {
        InfraRegistry reg = InfraRegistry.global();
        reg.setPollMillis(300, 300);
        InfraApi api = new InfraApi(reg);
        File rpr2e = new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml");
        List<String> rpr2h4 = new ArrayList<String>();
        for (String m : new String[] {"RPR-Base_v2.0.xml", "RPR-Foundation_v2.0.xml", "RPR-Enumerations_v2.0.xml",
            "RPR-Switches_v2.0.xml", "RPR-Physical_v2.0.xml", "RPR-Warfare_v2.0.xml", "RPR-Communication_v2.0.xml"}) {
            rpr2h4.add(new File(fomRoot, "ieee1516-2025/" + m).getPath());
        }
        File jar4 = new File(rtiDir, "fakerti-2025.jar");
        DatagramSocket sink = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        int disPort;
        try (DatagramSocket probe = new DatagramSocket(0)) {
            disPort = probe.getLocalPort();
        }

        File dir = Files.createTempDirectory("infra-test").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "Infra test");
        p.set("junction.endpoints", "blue;red;dis");
        p.set("junction.endpoint.blue.standard", "ieee1516-2010");
        p.set("junction.endpoint.blue.hla.foms", rpr2e.getPath());
        p.set("junction.endpoint.blue.hla.federation", "InfraBlue");
        p.set("junction.endpoint.red.standard", "ieee1516-2025");
        p.set("junction.endpoint.red.hla.foms", String.join(";", rpr2h4));
        p.set("junction.endpoint.red.hla.federation", "InfraRed");
        p.set("junction.endpoint.red.hla.rtijar", jar4.getPath());
        p.set("junction.endpoint.dis.standard", "dis7");
        p.set("junction.endpoint.dis.hla.foms", rpr2e.getPath());
        p.set("junction.endpoint.dis.dis.interface", "127.0.0.1");
        p.set("junction.endpoint.dis.dis.port", String.valueOf(disPort));
        p.set("junction.endpoint.dis.dis.exid", "0");
        p.set("junction.endpoint.dis.dis.sendaddress", "127.0.0.1:" + sink.getLocalPort());
        p.set("junction.router.objects.block", "EmbeddedSystem.*");
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "infra.junction.settings"), p);
        check("config valid " + gc.getErrors(), gc.isValid());
        // a DIS simulator already running when the gateway starts: its entity is heard before the router
        // declares (0.9.20 fix: it used to stay unbridged while it kept sending)
        final int port = disPort;
        final java.util.concurrent.atomic.AtomicBoolean early = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread earlySim = new Thread(() -> {
            try (DatagramSocket es = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
                byte[] pdu = pdu(7, 1, 1, 144, 12, 3001, 99);
                pdu[20] = 1;                                   // entity type: kind platform
                pdu[21] = 2;                                   // domain air
                while (early.get()) {
                    es.send(new DatagramPacket(pdu, pdu.length, InetAddress.getLoopbackAddress(), port));
                    Thread.sleep(5);
                }
            } catch (Exception ignored) {
                // test ends
            }
        });
        earlySim.setDaemon(true);
        earlySim.start();
        Gateway gw = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
        gw.start();
        check("gateway running", gw.getState() == Gateway.State.RUNNING);

        // a simulator in the 1516-2010 federation
        InformationExchangeModel model = InformationExchangeModel.fromHlaEvolved(Collections.singletonList(rpr2e.getPath()));
        HlaCodec codec = new HlaCodec(model);
        Fed sim = new Fed(RtiLibrary.classPath(), "InfraBlue", "BlueSim", Collections.singletonList(rpr2e.getPath()));
        int ac = sim.s.objectClass(AIRCRAFT);
        int marking = sim.s.attribute(ac, "Marking");
        int spatial = sim.s.attribute(ac, "Spatial");
        sim.s.publishObjectClass(ac, new int[] {marking, spatial});
        sim.s.subscribeObjectClass(ac, new int[] {marking});
        int fire = sim.s.interactionClass("HLAinteractionRoot.WeaponFire");
        sim.s.publishInteractionClass(fire);
        sim.s.subscribeInteractionClass(sim.s.interactionClass("HLAinteractionRoot.Detonation"));
        sim.reserve("Infra-1");
        long obj = sim.s.registerObject(ac, "Infra-1");

        // a Rift datasource in the same federation
        SimwProperties dp = new SimwProperties();
        dp.set("datasource.name", "infra-blue");
        dp.set("datasource.standard", "ieee1516-2010");
        dp.set("datasource.hla.foms", rpr2e.getPath());
        dp.set("datasource.hla.federation", "InfraBlue");
        DatasourceConfig dcfg = new DatasourceConfig(7, new File("infra-blue.datasource.settings"), dp);
        DataSource ds = DatasourceFactory.createDatasource(dcfg, null, null);
        DatasourceManager.OpResult jr = ds.connectJoinSub();
        check("Rift datasource joined: " + jr.getMessage(), jr.isSuccess());

        byte[] mk = codec.encode(model.getClassAttribute(AIRCRAFT, "Marking").getDataType(), marking("INFRA1"));
        long until = System.currentTimeMillis() + 2500;
        int sent = 0;
        while (System.currentTimeMillis() < until) {
            sim.s.updateAttributes(obj, new int[] {marking}, new byte[][] {mk}, null);
            sent++;
            Thread.sleep(40);
        }
        sim.s.sendInteraction(fire, new int[0], new byte[0][], null);
        early.set(false);
        check("a DIS entity heard before the router declared is bridged", sim.discovered.get() >= 1);

        // DIS: two PDUs of exercise 1 (DIS 7) and one of exercise 4 (DIS 6) from a test socket
        try (DatagramSocket out = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            for (int i = 0; i < 2; i++) {
                byte[] es = pdu(7, 1, 1, 144, 12, 3001, 7 + i);
                out.send(new DatagramPacket(es, es.length, InetAddress.getLoopbackAddress(), disPort));
            }
            byte[] old = pdu(6, 4, 1, 144, 12, 3002, 1);
            out.send(new DatagramPacket(old, old.length, InetAddress.getLoopbackAddress(), disPort));
            Thread.sleep(800);

            Map<String, Object> o = get(api, "/api/infra");
            List<?> hla3 = (List<?>) o.get("hla3");
            List<?> hla4 = (List<?>) o.get("hla4");
            check("two HLA 1516-2010 items (Junction and Rift) " + hla3.size(), hla3.size() == 2);
            check("one HLA 1516-2025 item", hla4.size() == 1 && "InfraRed".equals(((Map<?, ?>) hla4.get(0)).get("federation")));
            Map<?, ?> jx = null;
            Map<?, ?> rift = null;
            for (Object x : hla3) {
                Map<?, ?> m = (Map<?, ?>) x;
                String kind = String.valueOf(((Map<?, ?>) m.get("source")).get("kind"));
                if ("junction".equals(kind)) {
                    jx = m;
                } else {
                    rift = m;
                }
            }
            check("Junction item: source and federation", jx != null && "InfraBlue".equals(jx.get("federation"))
                    && String.valueOf(((Map<?, ?>) jx.get("source")).get("label")).contains("blue"));
            check("Rift item: link to the datasource page", rift != null
                    && String.valueOf(((Map<?, ?>) rift.get("source")).get("link")).startsWith("datasource.html?id="));
            check("MOM ok", jx != null && "ok".equals(jx.get("mom")));
            check("traffic from the others counted", jx != null && ((Number) jx.get("rate")).doubleValue() > 0);

            List<?> dis7 = (List<?>) o.get("dis7");
            List<?> dis6 = (List<?>) o.get("dis6");
            check("DIS 7 exercise 1 and DIS 6 exercise 4 heard by the gateway's DIS endpoint", dis7.size() == 1 && dis6.size() == 1
                    && num(((Map<?, ?>) dis7.get(0)).get("exercise")) == 1 && num(((Map<?, ?>) dis6.get(0)).get("exercise")) == 4);
            String disKey = String.valueOf(((Map<?, ?>) dis7.get(0)).get("key"));
            Map<?, ?> disItem = (Map<?, ?>) get(api, "/api/infra/items/" + disKey).get("item");
            List<?> senders = (List<?>) disItem.get("senderlist");
            Map<?, ?> s0 = senders.isEmpty() ? null : (Map<?, ?>) senders.get(0);
            Map<?, ?> s1 = senders.size() < 2 ? null : (Map<?, ?>) senders.get(1);
            if (s0 != null && s1 != null && num(s0.get("entities")) != 2) {
                Map<?, ?> t = s0;
                s0 = s1;
                s1 = t;
            }
            check("two senders (the early one stopped), loopback, local, site 12 app 3001, two entities", senders.size() == 2
                    && s0 != null && "127.0.0.1".equals(s0.get("address")) && Boolean.TRUE.equals(s0.get("local"))
                    && num(((Map<?, ?>) ((List<?>) s0.get("siteapps")).get(0)).get("app")) == 3001 && num(s0.get("entities")) == 2
                    && s1 != null && num(s1.get("entities")) == 1);
            check("the endpoint's note", String.valueOf(disItem.get("note")).contains("sends as exercise"));

            // the HLA detail: federates from the MOM
            String key = jx == null ? "" : String.valueOf(jx.get("key"));
            Map<?, ?> d = waitFor(api, key, it -> federate(it, "BlueSim") != null
                    && ((Map<?, ?>) federate(it, "BlueSim").get("momrates")).get("updatessent") != null, 6000);
            Map<?, ?> blueSim = d == null ? null : federate(d, "BlueSim");
            if (Boolean.getBoolean("infra.dump")) {
                System.out.println(com.simweaver.common.json.Json.toPrettyJson(d));
                System.out.println(com.simweaver.common.json.Json.toPrettyJson(disItem));
            }
            check("BlueSim from the MOM: type, host, handle", blueSim != null && "Tester".equals(blueSim.get("type"))
                    && "fakehost".equals(blueSim.get("host")) && blueSim.get("handle") != null && Boolean.TRUE.equals(blueSim.get("frommom")));
            check("BlueSim's MOM update rate", blueSim != null
                    && ((Number) ((Map<?, ?>) blueSim.get("momrates")).get("updatessent")).doubleValue() >= 0);
            check("BlueSim's updates as received by the gateway", blueSim != null
                    && num(((Map<?, ?>) blueSim.get("observed")).get("updates")) > 0);
            boolean self = false;
            boolean riftFed = false;
            for (Object x : (List<?>) d.get("federatelist")) {
                Map<?, ?> f = (Map<?, ?>) x;
                self |= Boolean.TRUE.equals(f.get("self"));
                riftFed |= "SimwRift".equals(f.get("type"));
            }
            check("the gateway's own federate marked, the Rift datasource listed", self && riftFed);
            check("MOM counters from the RTI", blueSim != null && num(((Map<?, ?>) blueSim.get("mom")).get("HLAupdatesSent")) >= 1);

            // publications and subscriptions through the MOM
            String handle = blueSim == null ? "00" : String.valueOf(blueSim.get("handle"));
            XrResultPair r = api.handleCallToJson("POST", "/api/infra/items/" + key + "/declarations", "/api", null,
                    "{\"federate\":\"" + handle + "\"}");
            check("declarations asked: 202 " + r.getText(), r.getValue() == 202);
            Map<?, ?> dd = waitFor(api, key, it -> {
                Map<?, ?> f = federate(it, "BlueSim");
                Map<?, ?> dec = f == null ? null : (Map<?, ?>) f.get("declarations");
                return dec != null && dec.get("publications") != null && dec.get("subscriptions") != null;
            }, 4000);
            Map<?, ?> dec = dd == null ? null : (Map<?, ?>) federate(dd, "BlueSim").get("declarations");
            Map<?, ?> pubs = dec == null ? null : (Map<?, ?>) dec.get("publications");
            Map<?, ?> po = pubs == null || ((List<?>) pubs.get("objects")).isEmpty() ? null : (Map<?, ?>) ((List<?>) pubs.get("objects")).get(0);
            check("published Aircraft with Marking and Spatial", po != null && AIRCRAFT.equals(po.get("class"))
                    && ((List<?>) po.get("attributes")).containsAll(java.util.Arrays.asList("Marking", "Spatial")));
            check("published WeaponFire", pubs != null && String.valueOf(pubs.get("interactions")).contains("HLAinteractionRoot.WeaponFire"));
            Map<?, ?> subs = dec == null ? null : (Map<?, ?>) dec.get("subscriptions");
            check("subscribed Detonation, active", subs != null && String.valueOf(subs.get("interactions")).contains("Detonation")
                    && String.valueOf(subs.get("interactions")).contains("active=true"));
            check("refresh 202", api.handleCallToJson("POST", "/api/infra/items/" + key + "/refresh", "/api", null, null).getValue() == 202);
            check("bad federate handle 409", api.handleCallToJson("POST", "/api/infra/items/" + key + "/declarations", "/api", null,
                    "{\"federate\":\"ffffffff\"}").getValue() == 409);
            Map<String, String> q = new LinkedHashMap<String, String>();
            q.put("keys", key + "," + disKey);
            Map<String, Object> both = Jsons.fromJsonObject(api.handleCallToJson("GET", "/api/infra/items?keys=" + key + "," + disKey,
                    "/api", q, null).getText());
            check("two items in one call (breakout)", ((List<?>) both.get("items")).size() == 2);

            // stopping: the items go
            ds.disconnect();
            gw.stop();
            Map<String, Object> after = get(api, "/api/infra");
            check("items gone when the sources stop", ((List<?>) after.get("hla3")).isEmpty() && ((List<?>) after.get("hla4")).isEmpty()
                    && ((List<?>) after.get("dis7")).isEmpty());
        }
        sim.s.close();
        sink.close();
    }

    private interface Test {
        boolean ok(Map<?, ?> item);
    }

    private static Map<?, ?> waitFor(InfraApi api, String key, Test t, long millis) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        Map<?, ?> last = null;
        while (System.currentTimeMillis() < end) {
            Map<String, Object> o = get(api, "/api/infra/items/" + key);
            last = (Map<?, ?>) o.get("item");
            if (last != null && t.ok(last)) {
                return last;
            }
            Thread.sleep(150);
        }
        return last;
    }

    private static Map<?, ?> federate(Map<?, ?> item, String name) {
        Object l = item.get("federatelist");
        if (!(l instanceof List)) {
            return null;
        }
        for (Object x : (List<?>) l) {
            if (name.equals(((Map<?, ?>) x).get("name"))) {
                return (Map<?, ?>) x;
            }
        }
        return null;
    }

    private static Map<String, Object> get(InfraApi api, String uri) {
        XrResultPair r = api.handleCallToJson("GET", uri, "/api", null, null);
        return r.getValue() == 200 ? Jsons.fromJsonObject(r.getText()) : new LinkedHashMap<String, Object>();
    }

    private static HValue marking(String text) {
        Map<String, HValue> m = new LinkedHashMap<String, HValue>();
        m.put("MarkingEncodingType", HValue.ofEnum(1, "ASCII"));
        m.put("MarkingData", HValue.ofText(text));
        return HValue.ofRecord(m);
    }

    private static byte[] pdu(int version, int exercise, int type, int length, int site, int app, int entity) {
        byte[] b = new byte[length];
        b[0] = (byte) version;
        b[1] = (byte) exercise;
        b[2] = (byte) type;
        b[3] = 1;
        b[8] = (byte) (length >> 8);
        b[9] = (byte) length;
        b[12] = (byte) (site >> 8);
        b[13] = (byte) site;
        b[14] = (byte) (app >> 8);
        b[15] = (byte) app;
        b[16] = (byte) (entity >> 8);
        b[17] = (byte) entity;
        return b;
    }

    private static long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : -1;
    }

    /** A test federate. */
    private static final class Fed implements RtiSessionListener {
        final RtiSession s;
        private final java.util.concurrent.BlockingQueue<String[]> q = new java.util.concurrent.LinkedBlockingQueue<String[]>();

        Fed(RtiLibrary lib, String federation, String name, List<String> foms) throws Exception {
            s = lib.createSession(RtiSession.Version.HLA_1516E);
            s.connect(ConnectSettings.defaults(), this);
            s.join(federation, "Tester", name, foms, true);
        }

        void reserve(String name) throws Exception {
            s.reserveName(name);
            String[] r = q.poll(3, java.util.concurrent.TimeUnit.SECONDS);
            if (r == null || !"true".equals(r[1])) {
                throw new IllegalStateException("could not reserve " + name);
            }
        }

        final java.util.concurrent.atomic.AtomicInteger discovered = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public void discovered(long instance, int objectClass, String name, String producer) {
            discovered.incrementAndGet();
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
            q.add(new String[] {name, String.valueOf(success)});
        }

        @Override
        public void connectionLost(String reason) {
        }
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }
}
