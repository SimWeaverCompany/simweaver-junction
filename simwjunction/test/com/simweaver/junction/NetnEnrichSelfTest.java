package com.simweaver.junction;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.codec.HlaCodec;
import com.simweaver.common.hla.session.ConnectSettings;
import com.simweaver.common.hla.session.RtiLibrary;
import com.simweaver.common.hla.session.RtiSession;
import com.simweaver.common.hla.session.RtiSessionListener;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.junction.config.ConfigLoader;
import com.simweaver.junction.config.GatewayConfig;
import com.simweaver.junction.config.RouterConfig;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * NETN properties for RPR objects (0.12.0, {@code router.netn.*}): an RPR v2 federation and a
 * NETN (RPR v2 + NETN modules) federation on the test RTI. Copies in the NETN federation get a
 * UniqueId (stable or random) and a Callsign from the Marking; NETN objects keep their own; the
 * class filter; settings errors; a restart keeps stable UUIDs.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.junction.NetnEnrichSelfTest &lt;fom root&gt; &lt;fake rti folder&gt;</pre>
 */
public final class NetnEnrichSelfTest {

    private static final String AIRCRAFT = "HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft";
    private static final String RADIO = "HLAobjectRoot.EmbeddedSystem.RadioTransmitter";
    private static int checks;
    private static int failures;

    private NetnEnrichSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File fomRoot = new File(args.length > 0 ? args[0] : "simwrift/rtdir/etc/fom").getAbsoluteFile();
        File rtiDir = new File(args.length > 1 ? args[1] : "fakerti/build").getAbsoluteFile();
        String rpr2 = new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath();
        List<String> netn = new ArrayList<String>();
        netn.add(rpr2);
        for (String n : new String[] {"NETN-BASE", "NETN-ENTITY", "NETN-ORG", "NETN-LOG", "NETN-SMC", "NETN-TMR",
                "NETN-MRM", "NETN-ETR", "NETN-COM", "NETN-CBRN", "NETN-METOC", "NETN-AIS", "NETN-SE", "NETN-DIM"}) {
            File f = new File(fomRoot, "ieee1516-2010/netn4-hla3/" + n + ".xml");
            if (f.isFile()) netn.add(f.getPath());
        }
        File jar = new File(rtiDir, "fakerti-1516e.jar");

        settings();

        File dir = Files.createTempDirectory("junction-netn-test").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        RtiLibrary lib = RtiLibrary.isolated(Collections.singletonList(jar));
        InformationExchangeModel mr = InformationExchangeModel.fromHlaEvolved(Collections.singletonList(rpr2));
        InformationExchangeModel mn = InformationExchangeModel.fromHlaEvolved(netn);
        HlaCodec cr = new HlaCodec(mr), cn = new HlaCodec(mn);

        // ---- stable UUIDs, callsigns, default classes
        Map<String, String> netnKeys = new LinkedHashMap<String, String>();
        netnKeys.put("router.netn.enabled", "true");
        Gateway gw = gateway(dir, junctions, jar, rpr2, netn, "NetnR1", "NetnN1", netnKeys);
        Fed r = new Fed(lib, "NetnR1", "RprSim", Collections.singletonList(rpr2));
        Fed n = new Fed(lib, "NetnN1", "NetnSim", netn);
        int na = n.s.objectClass(AIRCRAFT), nr = n.s.objectClass(RADIO);
        int[] nah = handles(n, na, "UniqueId", "Callsign", "Marking");
        int[] nrh = handles(n, nr, "UniqueId", "Frequency");
        n.s.subscribeObjectClass(na, nah);
        n.s.subscribeObjectClass(nr, nrh);
        sleep(300);

        int ra = r.s.objectClass(AIRCRAFT), rr = r.s.objectClass(RADIO);
        int[] rah = handles(r, ra, "Marking", "ForceIdentifier");
        int[] rrh = handles(r, rr, "Frequency", "HostObjectIdentifier");
        r.s.publishObjectClass(ra, rah);
        r.s.publishObjectClass(rr, rrh);
        r.reserve("Viper11");
        long viper = r.s.registerObject(ra, "Viper11");
        r.s.updateAttributes(viper, rah, new byte[][] {marking(cr, mr, "VIPER 11"), cr.encode(mr.getClassAttribute(AIRCRAFT, "ForceIdentifier")
                .getAttribute().getDataType(), HValue.ofInt(1))}, null);
        r.reserve("Viper11-uhf");
        long radio = r.s.registerObject(rr, "Viper11-uhf");
        r.s.updateAttributes(radio, rrh, new byte[][] {cr.encode(mr.getClassAttribute(RADIO, "Frequency").getAttribute().getDataType(),
                HValue.ofInt(251_000_000L)), cr.encode(mr.getClassAttribute(RADIO, "HostObjectIdentifier").getAttribute().getDataType(), HValue.ofText("Viper11"))}, null);

        Map<String, String[]> found = discoveries(n, 2);
        String[] dv = found.get("Viper11"), dr = found.get("Viper11-uhf");
        check("NETN discovers Viper11 and its radio " + found.keySet(), dv != null && dr != null);
        UUID want = UUID.nameUUIDFromBytes(("netn|rpr|Viper11").getBytes(StandardCharsets.UTF_8));
        UUID uid = null;
        if (dv != null) {
            long k = Long.parseLong(dv[1]);
            byte[] u = n.waitValue(k, nah[0], 3000);
            uid = u == null ? null : uuid(cn.decode(mn.getClassAttribute(AIRCRAFT, "UniqueId").getAttribute().getDataType(), u).asBytes());
            check("Viper11 has a stable UniqueId (" + uid + ")", want.equals(uid));
            check("Callsign = the Marking 'VIPER 11'", "VIPER 11".equals(text(n, cn, mn, k, nah[1], "VIPER 11")));
            r.s.updateAttributes(viper, new int[] {rah[0]}, new byte[][] {marking(cr, mr, "VIPER 12")}, null);
            check("a new Marking: Callsign 'VIPER 12'", "VIPER 12".equals(text(n, cn, mn, k, nah[1], "VIPER 12")));
            r.s.updateAttributes(viper, new int[] {rah[0]}, new byte[][] {marking(cr, mr, "")}, null);
            sleep(400);
            check("an empty Marking leaves the Callsign", "VIPER 12".equals(text(n, cn, mn, k, nah[1], "VIPER 12")));
        }
        if (dr != null) {
            byte[] u = n.waitValue(Long.parseLong(dr[1]), nrh[0], 3000);
            check("the radio gets a UniqueId too (classes *)", u != null && u.length == 16
                    && uuid(u).equals(UUID.nameUUIDFromBytes(("netn|rpr|Viper11-uhf").getBytes(StandardCharsets.UTF_8))));
        }
        String st = status(gw);
        check("gateway objects JSON has netn " + st, count(st, "objects") == 2 && count(st, "callsigns") >= 2
                && count(st, "uniqueids") >= 2);

        // a NETN object keeps its own UniqueId and Callsign (the RPR side gets the copy, no NETN attributes there)
        n.s.publishObjectClass(na, nah);
        int[] rsub = handles(r, ra, "Marking");
        r.s.subscribeObjectClass(ra, rsub);
        sleep(200);
        n.reserve("Netn1");
        long own = n.s.registerObject(na, "Netn1");
        UUID ownUid = UUID.randomUUID();
        n.s.updateAttributes(own, nah, new byte[][] {
            cn.encode(mn.getClassAttribute(AIRCRAFT, "UniqueId").getAttribute().getDataType(), HValue.ofBytes(bytes(ownUid))),
            cn.encode(mn.getClassAttribute(AIRCRAFT, "Callsign").getAttribute().getDataType(), HValue.ofText("EAGLE")),
            marking(cn, mn, "EAGLE 1")}, null);
        String[] dOwn = r.take("discovered", 3000);
        check("RPR side gets Netn1", dOwn != null && "Netn1".equals(dOwn[3]));
        if (dOwn != null) {
            byte[] mk = r.waitValue(Long.parseLong(dOwn[1]), rsub[0], 3000);
            check("with its Marking", mk != null && "EAGLE 1".equals(markingText(cr, mr, mk)));
        }
        gw.stop();

        // ---- restart: the same UUID again
        gw = gateway(dir, junctions, jar, rpr2, netn, "NetnR1", "NetnN1", netnKeys);
        found = discoveries(n, 2);
        dv = found.get("Viper11");
        if (dv != null) {
            byte[] u = n.waitValue(Long.parseLong(dv[1]), nah[0], 3000);
            check("after a restart Viper11 has the same UniqueId", u != null && uuid(u).equals(want));
        } else {
            check("after a restart NETN discovers Viper11 again " + found.keySet(), false);
        }
        gw.stop();
        r.s.close();
        n.s.close();

        // ---- random UUIDs, entities only, no callsign
        Map<String, String> k2 = new LinkedHashMap<String, String>();
        k2.put("router.netn.enabled", "true");
        k2.put("router.netn.uniqueid", "random");
        k2.put("router.netn.callsign", "off");
        k2.put("router.netn.classes", "BaseEntity.*");
        gw = gateway(dir, junctions, jar, rpr2, netn, "NetnR2", "NetnN2", k2);
        r = new Fed(lib, "NetnR2", "RprSim", Collections.singletonList(rpr2));
        n = new Fed(lib, "NetnN2", "NetnSim", netn);
        na = n.s.objectClass(AIRCRAFT);
        nr = n.s.objectClass(RADIO);
        nah = handles(n, na, "UniqueId", "Callsign", "Marking");
        nrh = handles(n, nr, "UniqueId", "Frequency");
        ra = r.s.objectClass(AIRCRAFT);
        rr = r.s.objectClass(RADIO);
        rah = handles(r, ra, "Marking", "ForceIdentifier");
        rrh = handles(r, rr, "Frequency", "HostObjectIdentifier");
        n.s.subscribeObjectClass(na, nah);
        n.s.subscribeObjectClass(nr, nrh);
        sleep(300);
        r.s.publishObjectClass(ra, rah);
        r.s.publishObjectClass(rr, rrh);
        r.reserve("Hawk1");
        long hawk = r.s.registerObject(ra, "Hawk1");
        r.s.updateAttributes(hawk, rah, new byte[][] {marking(cr, mr, "HAWK 1"), cr.encode(mr.getClassAttribute(AIRCRAFT, "ForceIdentifier")
                .getAttribute().getDataType(), HValue.ofInt(2))}, null);
        r.reserve("Hawk1-vhf");
        long hr = r.s.registerObject(rr, "Hawk1-vhf");
        r.s.updateAttributes(hr, rrh, new byte[][] {cr.encode(mr.getClassAttribute(RADIO, "Frequency").getAttribute().getDataType(),
                HValue.ofInt(30_000_000L)), cr.encode(mr.getClassAttribute(RADIO, "HostObjectIdentifier").getAttribute().getDataType(), HValue.ofText("Hawk1"))}, null);
        found = discoveries(n, 2);
        dv = found.get("Hawk1");
        dr = found.get("Hawk1-vhf");
        if (dv != null) {
            long k = Long.parseLong(dv[1]);
            byte[] u = n.waitValue(k, nah[0], 3000);
            UUID ru = u == null ? null : uuid(u);
            check("random: a version 4 UUID (" + ru + ")", ru != null && ru.version() == 4);
            sleep(400);
            check("callsign off: no Callsign", n.value(k, nah[1]) == null);
        } else {
            check("random: Hawk1 discovered " + found.keySet(), false);
        }
        if (dr != null) {
            long k = Long.parseLong(dr[1]);
            check("BaseEntity.* only: the radio has a Frequency but no UniqueId", n.waitValue(k, nrh[1], 3000) != null && n.value(k, nrh[0]) == null);
        }
        gw.stop();
        r.s.close();
        n.s.close();

        // ---- off by default
        gw = gateway(dir, junctions, jar, rpr2, netn, "NetnR3", "NetnN3", Collections.<String, String>emptyMap());
        r = new Fed(lib, "NetnR3", "RprSim", Collections.singletonList(rpr2));
        n = new Fed(lib, "NetnN3", "NetnSim", netn);
        na = n.s.objectClass(AIRCRAFT);
        nah = handles(n, na, "UniqueId", "Callsign", "Marking");
        ra = r.s.objectClass(AIRCRAFT);
        rah = handles(r, ra, "Marking", "ForceIdentifier");
        n.s.subscribeObjectClass(na, nah);
        sleep(300);
        r.s.publishObjectClass(ra, rah);
        r.reserve("Plain1");
        long plain = r.s.registerObject(ra, "Plain1");
        r.s.updateAttributes(plain, rah, new byte[][] {marking(cr, mr, "PLAIN"), cr.encode(mr.getClassAttribute(AIRCRAFT, "ForceIdentifier")
                .getAttribute().getDataType(), HValue.ofInt(1))}, null);
        String[] dp = n.take("discovered", 3000);
        if (dp != null) {
            long k = Long.parseLong(dp[1]);
            n.waitValue(k, nah[2], 3000);
            sleep(300);
            check("off by default: Marking passes, no UniqueId or Callsign", n.value(k, nah[2]) != null && n.value(k, nah[0]) == null
                    && n.value(k, nah[1]) == null);
        } else {
            check("off: Plain1 discovered", false);
        }
        check("off: no netn in the objects JSON", status(gw) == null);
        gw.stop();
        r.s.close();
        n.s.close();

        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void settings() {
        SimwProperties p = new SimwProperties();
        RouterConfig c = new RouterConfig("x", p, "x", null);
        check("settings: off by default, stable, marking, *", !c.getNetn().isEnabled() && c.getNetn().getUniqueIdMode() == RouterConfig.Netn.UniqueIdMode.STABLE
                && c.getNetn().isCallsign() && c.getNetn().getClasses().equals(Collections.singletonList("*")));
        p.set("router.netn.enabled", "true");
        p.set("router.netn.uniqueid", "sometimes");
        p.set("router.netn.callsign", "name");
        c = new RouterConfig("x", p, "x", null);
        check("settings: bad values are errors " + c.getErrors(), c.getErrors().size() == 2);
        p.set("router.netn.uniqueid", "off");
        p.set("router.netn.callsign", "off");
        c = new RouterConfig("x", p, "x", null);
        check("settings: both off = not enabled", c.getErrors().isEmpty() && !c.getNetn().isEnabled());
        // the delivered sample: valid, and its NETN endpoint's FOM has both attributes
        java.nio.file.Path etc = com.simweaver.common.config.SimwPaths.etc();
        com.simweaver.common.config.SimwPaths.setEtc(null);
        try {
            File dist = new File("simwrift/rtdir/etc/junctions.dist");
            File f = new File(dist, "hla3-hla3-rpr2-netn.junction.settings");
            SimwProperties raw = new SimwProperties();
            raw.load(f);
            GatewayConfig gc = new ConfigLoader(dist, new File("simwrift/rtdir")).gateway(f, raw);
            check("sample hla3-hla3-rpr2-netn is valid " + gc.getErrors(), gc.isValid() && gc.getRouter().getNetn().isEnabled());
            for (com.simweaver.junction.config.EndpointConfig e : gc.getEndpoints().values()) {
                if (e.getId().equals("netn")) {
                    InformationExchangeModel m = InformationExchangeModel.fromHlaEvolved(e.getFomPaths());
                    check("the sample NETN FOM has UniqueId and BaseEntity.Callsign", m.getObjectClassRoot().getAttribute("UniqueId") != null
                            && m.getClassAttribute("HLAobjectRoot.BaseEntity", "Callsign") != null);
                }
            }
        } catch (Exception e) {
            check("sample hla3-hla3-rpr2-netn loads: " + e, false);
        } finally {
            com.simweaver.common.config.SimwPaths.setEtc(etc);
        }
    }

    /** The netn object of the router's objects JSON as text, or null when there is none. */
    private static String status(Gateway gw) {
        String j = com.simweaver.common.json.Json.toJson(gw.getRouter().objects());
        int i = j.indexOf("\"netn\"");
        return i < 0 ? null : j.substring(i, j.indexOf('}', i) + 1);
    }

    private static int count(String status, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\":(\\d+)").matcher(status == null ? "" : status);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static Gateway gateway(File dir, File junctions, File jar, String rpr2, List<String> netn, String fr, String fn,
                                   Map<String, String> router) throws Exception {
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "RPR - NETN");
        p.set("junction.endpoints", "rpr;netn");
        p.set("junction.endpoint.rpr.standard", "ieee1516-2010");
        p.set("junction.endpoint.rpr.hla.foms", rpr2);
        p.set("junction.endpoint.rpr.hla.federation", fr);
        p.set("junction.endpoint.rpr.hla.rtijar", jar.getPath());
        p.set("junction.endpoint.netn.standard", "ieee1516-2010");
        p.set("junction.endpoint.netn.hla.foms", String.join(";", netn));
        p.set("junction.endpoint.netn.hla.federation", fn);
        p.set("junction.endpoint.netn.hla.rtijar", jar.getPath());
        for (Map.Entry<String, String> e : router.entrySet()) {
            p.set("junction." + e.getKey(), e.getValue());
        }
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "netn.junction.settings"), p);
        check("config valid " + gc.getErrors(), gc.isValid());
        Gateway gw = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
        gw.start();
        check("gateway running: " + gw.getState(), gw.getState() == Gateway.State.RUNNING);
        return gw;
    }

    private static Map<String, String[]> discoveries(Fed f, int n) throws InterruptedException {
        Map<String, String[]> found = new LinkedHashMap<String, String[]>();
        for (int i = 0; i < n; i++) {
            String[] d = f.take("discovered", 4000);
            if (d != null) found.put(d[3], d);
        }
        return found;
    }

    private static byte[] marking(HlaCodec c, InformationExchangeModel m, String text) {
        Map<String, HValue> f = new LinkedHashMap<String, HValue>();
        f.put("MarkingEncodingType", HValue.ofInt(1));
        f.put("MarkingData", HValue.ofText(text));
        return c.encode(m.getClassAttribute(AIRCRAFT, "Marking").getAttribute().getDataType(), HValue.ofRecord(f));
    }

    private static String markingText(HlaCodec c, InformationExchangeModel m, byte[] b) {
        return c.decode(m.getClassAttribute(AIRCRAFT, "Marking").getAttribute().getDataType(), b).field("MarkingData").asTrimmedText();
    }

    /** Waits for the Callsign to become {@code want}; returns the last value seen. */
    private static String text(Fed f, HlaCodec c, InformationExchangeModel m, long k, int attr, String want) {
        String last = null;
        for (int i = 0; i < 60; i++) {
            byte[] b = f.value(k, attr);
            if (b != null) {
                last = c.decode(m.getClassAttribute(AIRCRAFT, "Callsign").getAttribute().getDataType(), b).asTrimmedText();
                if (last.equals(want)) return last;
            }
            sleep(50);
        }
        return last;
    }

    private static UUID uuid(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b);
        return new UUID(bb.getLong(), bb.getLong());
    }

    private static byte[] bytes(UUID u) {
        return ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array();
    }

    private static int[] handles(Fed f, int cls, String... names) throws Exception {
        int[] h = new int[names.length];
        for (int i = 0; i < names.length; i++) h[i] = f.s.attribute(cls, names[i]);
        return h;
    }

    private static final class Fed implements RtiSessionListener {
        final RtiSession s;
        final BlockingQueue<String[]> q = new LinkedBlockingQueue<String[]>();
        final Map<Long, Map<Integer, byte[]>> byInstance = new ConcurrentHashMap<Long, Map<Integer, byte[]>>();

        Fed(RtiLibrary lib, String federation, String name, List<String> foms) throws Exception {
            s = lib.createSession(RtiSession.Version.HLA_1516E);
            s.connect(ConnectSettings.defaults(), this);
            s.join(federation, "Tester", name, foms, true);
        }

        void reserve(String name) throws Exception {
            s.reserveName(name);
            String[] r = take("name", 3000);
            if (r == null || !r[2].equals("true")) throw new IllegalStateException("could not reserve " + name);
        }

        String[] take(String kind, long millis) throws InterruptedException {
            long end = System.currentTimeMillis() + millis;
            List<String[]> skipped = new ArrayList<String[]>();
            try {
                while (System.currentTimeMillis() < end) {
                    String[] e = q.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                    if (e == null) return null;
                    if (e[0].equals(kind)) return e;
                    skipped.add(e);
                }
                return null;
            } finally {
                q.addAll(skipped);
            }
        }

        byte[] value(long instance, int attr) {
            Map<Integer, byte[]> mine = byInstance.get(instance);
            return mine == null ? null : mine.get(attr);
        }

        byte[] waitValue(long instance, int attr, long millis) {
            long end = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < end) {
                byte[] v = value(instance, attr);
                if (v != null) return v;
                sleep(20);
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
            for (int i = 0; i < attributes.length; i++) mine.put(attributes[i], vals[i]);
        }

        @Override
        public void removed(long instance, byte[] tag, String producer) {
            q.add(new String[] {"removed", String.valueOf(instance)});
        }

        @Override
        public void received(int interactionClass, int[] parameters, byte[][] vals, byte[] tag, String producer) {
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

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
