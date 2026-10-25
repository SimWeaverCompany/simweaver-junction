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

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * IFF between an RPR v2 and an RPR v3 federation (test RTI): the v2 mode attributes of a
 * NatoIFFTransponder become v3 IFFData objects next to its IFFSystem.Transponder copy, and v3
 * IFFData objects are folded into the v2 copy of their system (RprIffBridge).
 *
 * <pre>java -cp out/classes:out/test com.simweaver.junction.IffBridgeSelfTest &lt;fom root&gt; &lt;fake rti folder&gt;</pre>
 */
public final class IffBridgeSelfTest {

    private static final String T2 = "HLAobjectRoot.EmbeddedSystem.IFF.NatoIFF.NatoIFFTransponder";
    private static final String T3 = "HLAobjectRoot.EmbeddedSystem.IFFSystem.Transponder";
    private static final String M3A = "HLAobjectRoot.IFFData.Mode3A.Mode3ATransponder";
    private static final String MC = "HLAobjectRoot.IFFData.ModeC.ModeCTransponder";
    private static final String M4 = "HLAobjectRoot.IFFData.Mode4.Mode4Transponder";
    private static int checks;
    private static int failures;

    private IffBridgeSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File fomRoot = new File(args.length > 0 ? args[0] : "simwrift/rtdir/etc/fom").getAbsoluteFile();
        File rtiDir = new File(args.length > 1 ? args[1] : "fakerti/build").getAbsoluteFile();
        String rpr2 = new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath();
        List<String> rpr3 = new ArrayList<String>();
        for (File f : new File(fomRoot, "ieee1516-2010/rpr3").listFiles()) {
            if (f.getName().endsWith(".xml")) rpr3.add(f.getPath());
        }
        Collections.sort(rpr3);
        File jar = new File(rtiDir, "fakerti-1516e.jar");

        File dir = Files.createTempDirectory("junction-iff23-test").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        File mappings = new File(junctions, "mappings");
        mappings.mkdirs();
        Files.copy(new File("simwrift/rtdir/etc/junctions/mappings/rpr2-rpr3.mapping").toPath(), new File(mappings, "rpr2-rpr3.mapping").toPath());
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "RPR v2 - v3 IFF");
        p.set("junction.endpoints", "two;three");
        p.set("junction.endpoint.two.standard", "ieee1516-2010");
        p.set("junction.endpoint.two.hla.foms", rpr2);
        p.set("junction.endpoint.two.hla.federation", "IffTwo");
        p.set("junction.endpoint.two.hla.rtijar", jar.getPath());
        p.set("junction.endpoint.three.standard", "ieee1516-2010");
        p.set("junction.endpoint.three.hla.foms", String.join(";", rpr3));
        p.set("junction.endpoint.three.hla.federation", "IffThree");
        p.set("junction.endpoint.three.hla.rtijar", jar.getPath());
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "iff23.junction.settings"), p);
        check("config valid " + gc.getErrors(), gc.isValid());
        Gateway gw = new Gateway(gc, Collections.<String, String>emptyMap(), dir,
                new com.simweaver.junction.router.convert.RprConversions(mappings));
        gw.start();
        check("gateway running: " + gw.getState(), gw.getState() == Gateway.State.RUNNING);

        RtiLibrary lib = RtiLibrary.isolated(Collections.singletonList(jar));
        InformationExchangeModel m2 = InformationExchangeModel.fromHlaEvolved(Collections.singletonList(rpr2));
        InformationExchangeModel m3 = InformationExchangeModel.fromHlaEvolved(rpr3);
        HlaCodec c2 = new HlaCodec(m2);
        HlaCodec c3 = new HlaCodec(m3);
        Fed two = new Fed(lib, "IffTwo", "TwoSim", Collections.singletonList(rpr2));
        Fed three = new Fed(lib, "IffThree", "ThreeSim", rpr3);

        // ---- v2 -> v3
        int t3 = three.s.objectClass(T3);
        three.s.subscribeObjectClass(t3, new int[] {three.s.attribute(t3, "SystemType")});
        int m3a = three.s.objectClass(M3A), mc = three.s.objectClass(MC), m4 = three.s.objectClass(M4);
        int[] h3a = handles(three, m3a, "IFFSystemIdentifier", "IsOn", "Code");
        int[] hmc = handles(three, mc, "IFFSystemIdentifier", "IsOn", "Altitude", "AltitudeAvailable");
        int[] hm4 = handles(three, m4, "IFFSystemIdentifier", "PseudoCryptoCodeA", "Reply");
        three.s.subscribeObjectClass(m3a, h3a);
        three.s.subscribeObjectClass(mc, hmc);
        three.s.subscribeObjectClass(m4, hm4);
        sleep(300);

        int t2 = two.s.objectClass(T2);
        String[] n2 = {"SystemType", "SystemIsOn", "Mode1Enabled", "Mode3AEnabled", "Mode3AIsOn", "Mode3ACode", "Mode5CEnabled", "Mode5CIsOn",
            "Mode5CAltitude", "Mode5CAltitudeAvailable", "Mode4Enabled", "Mode4IsOn", "Mode4PseudoCrypto", "Mode4PseudoCryptoAvailable",
            "AlternateMode4"};
        int[] h2 = handles(two, t2, n2);
        two.s.publishObjectClass(t2, h2);
        two.reserve("Tx2");
        long tx2 = two.s.registerObject(t2, "Tx2");
        long[] v = {1, 1, 0, 1, 1, 01234, 1, 1, -5 & 0xFFFF, 1, 1, 1, 0x123, 1, 1};
        byte[][] vals = new byte[n2.length][];
        for (int i = 0; i < n2.length; i++) {
            vals[i] = c2.encode(m2.getClassAttribute(T2, n2[i]).getAttribute().getDataType(), HValue.ofInt(v[i]));
        }
        two.s.updateAttributes(tx2, h2, vals, null);

        Map<String, String[]> found = new LinkedHashMap<String, String[]>();
        for (int i = 0; i < 4; i++) {
            String[] d = three.take("discovered", 4000);
            if (d != null) found.put(d[3], d);
        }
        check("v3 discovers the transponder Tx2 " + found.keySet(), found.containsKey("Tx2"));
        String[] d3a = found.get("Tx2/mode3a"), dmc = found.get("Tx2/modec"), dm4 = found.get("Tx2/mode4");
        check("and IFFData Mode3ATransponder Tx2/mode3a", d3a != null && d3a[2].equals(String.valueOf(m3a)));
        check("and ModeCTransponder Tx2/modec", dmc != null && dmc[2].equals(String.valueOf(mc)));
        check("and Mode4Transponder Tx2/mode4", dm4 != null && dm4[2].equals(String.valueOf(m4)));
        check("no Mode 1 (not enabled)", !found.containsKey("Tx2/mode1"));
        if (d3a != null) {
            long k = Long.parseLong(d3a[1]);
            byte[] sys = three.waitValue(k, h3a[0], 3000), code = three.waitValue(k, h3a[2], 3000);
            check("Mode 3/A names its system Tx2, code 1234", sys != null && code != null
                    && "Tx2".equals(c3.decode(m3.getClassAttribute(M3A, "IFFSystemIdentifier").getAttribute().getDataType(), sys).asTrimmedText())
                    && c3.decode(m3.getClassAttribute(M3A, "Code").getAttribute().getDataType(), code).asLong() == 01234);
        }
        if (dmc != null) {
            byte[] alt = three.waitValue(Long.parseLong(dmc[1]), hmc[2], 3000);
            HValue a = alt == null ? null : c3.decode(m3.getClassAttribute(MC, "Altitude").getAttribute().getDataType(), alt);
            check("Mode C -500 ft: negative, 5", a != null && a.field("PositiveNegativeIndicator").asLong() == 1 && a.field("ModeCAltitude").asLong() == 5);
        }
        if (dm4 != null) {
            byte[] pc = three.waitValue(Long.parseLong(dm4[1]), hm4[1], 3000), rep = three.waitValue(Long.parseLong(dm4[1]), hm4[2], 3000);
            check("Mode 4 pseudo crypto 0x123, reply Valid", pc != null && rep != null
                    && c3.decode(m3.getClassAttribute(M4, "PseudoCryptoCodeA").getAttribute().getDataType(), pc).asLong() == 0x123
                    && c3.decode(m3.getClassAttribute(M4, "Reply").getAttribute().getDataType(), rep).asLong() == 1);
        }
        // a new code, then Mode C disabled
        two.s.updateAttributes(tx2, new int[] {h2[5]}, new byte[][] {
            c2.encode(m2.getClassAttribute(T2, "Mode3ACode").getAttribute().getDataType(), HValue.ofInt(07700))}, null);
        boolean changed = false;
        for (int i = 0; i < 40 && d3a != null; i++) {
            byte[] code = three.byInstance.getOrDefault(Long.parseLong(d3a[1]), Collections.emptyMap()).get(h3a[2]);
            if (code != null && c3.decode(m3.getClassAttribute(M3A, "Code").getAttribute().getDataType(), code).asLong() == 07700) {
                changed = true;
                break;
            }
            sleep(50);
        }
        check("code 7700 reaches the v3 Mode3A object", changed);
        two.s.updateAttributes(tx2, new int[] {h2[6]}, new byte[][] {
            c2.encode(m2.getClassAttribute(T2, "Mode5CEnabled").getAttribute().getDataType(), HValue.ofInt(0))}, null);
        String[] r = three.take("removed", 3000);
        check("Mode C disabled: Tx2/modec removed", r != null && dmc != null && r[1].equals(dmc[1]));
        two.s.deleteObject(tx2, null);
        List<String> gone = new ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            String[] x = three.take("removed", 3000);
            if (x != null) gone.add(x[1]);
        }
        check("Tx2 removed: system and remaining modes go " + gone, gone.size() == 3);

        // ---- v3 -> v2
        int[] sub2 = handles(two, t2, "Mode1Enabled", "Mode3AEnabled", "Mode3AIsOn", "Mode3ACode", "Mode5CAltitude", "Mode5CAltitudeAvailable");
        two.s.subscribeObjectClass(t2, sub2);
        int[] pt3 = handles(three, t3, "SystemType", "SystemIsOn");
        three.s.publishObjectClass(t3, pt3);
        three.s.publishObjectClass(m3a, h3a);
        three.s.publishObjectClass(mc, hmc);
        sleep(300);
        three.reserve("Tx3");
        long tx3 = three.s.registerObject(t3, "Tx3");
        three.s.updateAttributes(tx3, pt3, new byte[][] {
            c3.encode(m3.getClassAttribute(T3, "SystemType").getAttribute().getDataType(), HValue.ofInt(1)),
            c3.encode(m3.getClassAttribute(T3, "SystemIsOn").getAttribute().getDataType(), HValue.ofInt(1))}, null);
        three.reserve("Tx3-3a");
        long o3a = three.s.registerObject(m3a, "Tx3-3a");
        three.s.updateAttributes(o3a, h3a, new byte[][] {
            c3.encode(m3.getClassAttribute(M3A, "IFFSystemIdentifier").getAttribute().getDataType(), HValue.ofText("Tx3")),
            c3.encode(m3.getClassAttribute(M3A, "IsOn").getAttribute().getDataType(), HValue.ofInt(1)),
            c3.encode(m3.getClassAttribute(M3A, "Code").getAttribute().getDataType(), HValue.ofInt(04321))}, null);
        three.reserve("Tx3-c");
        long oc = three.s.registerObject(mc, "Tx3-c");
        Map<String, HValue> alt = new LinkedHashMap<String, HValue>();
        alt.put("PositiveNegativeIndicator", HValue.ofInt(1));
        alt.put("ModeCAltitude", HValue.ofInt(30));
        three.s.updateAttributes(oc, hmc, new byte[][] {
            c3.encode(m3.getClassAttribute(MC, "IFFSystemIdentifier").getAttribute().getDataType(), HValue.ofText("Tx3")),
            c3.encode(m3.getClassAttribute(MC, "IsOn").getAttribute().getDataType(), HValue.ofInt(1)),
            c3.encode(m3.getClassAttribute(MC, "Altitude").getAttribute().getDataType(), HValue.ofRecord(alt)),
            c3.encode(m3.getClassAttribute(MC, "AltitudeAvailable").getAttribute().getDataType(), HValue.ofInt(1))}, null);
        String[] dTx3 = two.take("discovered", 4000);
        check("v2 discovers Tx3 as a NatoIFFTransponder", dTx3 != null && "Tx3".equals(dTx3[3]) && dTx3[2].equals(String.valueOf(t2)));
        check("v2 does not get the IFFData objects as objects", two.take("discovered", 1000) == null);
        if (dTx3 != null) {
            long k = Long.parseLong(dTx3[1]);
            check("Mode 3/A folded in: enabled, on, code 4321", waitLong(two, c2, m2, k, sub2[1], "Mode3AEnabled", 1)
                    && waitLong(two, c2, m2, k, sub2[2], "Mode3AIsOn", 1) && waitLong(two, c2, m2, k, sub2[3], "Mode3ACode", 04321));
            check("Mode C -3000 ft", waitLong(two, c2, m2, k, sub2[4], "Mode5CAltitude", -30)
                    && waitLong(two, c2, m2, k, sub2[5], "Mode5CAltitudeAvailable", 1));
            check("Mode 1 not enabled", waitLong(two, c2, m2, k, sub2[0], "Mode1Enabled", 0));
            three.s.deleteObject(o3a, null);
            check("Mode 3/A object deleted: not enabled in v2", waitLong(two, c2, m2, k, sub2[1], "Mode3AEnabled", 0));
        }
        gw.stop();
        two.s.close();
        three.s.close();

        disToV3(fomRoot, rpr3, jar, dir, mappings);
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** DIS 7 IFF PDU <-> an RPR v3 federation: system and IFFData objects, and the way back. */
    private static void disToV3(File fomRoot, List<String> rpr3, File jar, File dir, File mappings) throws Exception {
        String rpr2 = new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath();
        java.net.DatagramSocket sim = new java.net.DatagramSocket(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0));
        sim.setSoTimeout(200);
        int port;
        try (java.net.DatagramSocket x = new java.net.DatagramSocket(0)) {
            port = x.getLocalPort();
        }
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "DIS - RPR v3 IFF");
        p.set("junction.endpoints", "dis;three");
        p.set("junction.endpoint.dis.standard", "dis7");
        p.set("junction.endpoint.dis.hla.foms", rpr2);
        p.set("junction.endpoint.dis.dis.interface", "127.0.0.1");
        p.set("junction.endpoint.dis.dis.port", String.valueOf(port));
        p.set("junction.endpoint.dis.dis.exid", "3");
        p.set("junction.endpoint.dis.dis.sendaddress", "127.0.0.1:" + sim.getLocalPort());
        p.set("junction.endpoint.dis.dis.heartbeatsecs", "1");
        p.set("junction.endpoint.three.standard", "ieee1516-2010");
        p.set("junction.endpoint.three.hla.foms", String.join(";", rpr3));
        p.set("junction.endpoint.three.hla.federation", "IffDis3");
        p.set("junction.endpoint.three.hla.rtijar", jar.getPath());
        File junctions = new File(dir, "etc/junctions");
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "dis3.junction.settings"), p);
        Gateway gw = new Gateway(gc, Collections.<String, String>emptyMap(), dir, new com.simweaver.junction.router.convert.RprConversions(mappings));
        gw.start();
        check("DIS - v3 gateway running", gw.getState() == Gateway.State.RUNNING);
        RtiLibrary lib = RtiLibrary.isolated(Collections.singletonList(jar));
        InformationExchangeModel m3 = InformationExchangeModel.fromHlaEvolved(rpr3);
        HlaCodec c3 = new HlaCodec(m3);
        Fed three = new Fed(lib, "IffDis3", "Dis3Sim", rpr3);
        int t3 = three.s.objectClass(T3), m3a = three.s.objectClass(M3A);
        three.s.subscribeObjectClass(t3, new int[] {three.s.attribute(t3, "SystemType")});
        int[] h3a = handles(three, m3a, "IFFSystemIdentifier", "IsOn", "Code");
        three.s.subscribeObjectClass(m3a, h3a);
        sleep(300);

        // DIS IFF PDU: tank 1-2-3, Mark X/XII transponder, Mode 3/A 2000 on (capable)
        com.simweaver.common.dis.DisWriter w = new com.simweaver.common.dis.DisWriter(60).header(7, 3, 28, 6, 0, 0);
        w.entityId(new com.simweaver.common.dis.DisEntityId(1, 2, 3)).u16(1).u16(2).u16(5).f32(0).f32(0).f32(0);
        w.u16(1).u16(0).u8(3).u8(0).u8(0).u8(0);
        w.u8(0x1 | (1 << 3)).u8(0).u8(0x2).u8(0);                         // on; Mode 3/A capable
        w.u16(0).u16(0).u16(0x2000 | 02000).u16(0).u16(0).u16(0);
        byte[] pdu = w.finish();
        sim.send(new java.net.DatagramPacket(pdu, pdu.length, new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port)));
        Map<String, String[]> found = new LinkedHashMap<String, String[]>();
        for (int i = 0; i < 2; i++) {
            String[] d = three.take("discovered", 4000);
            if (d != null) found.put(d[3], d);
        }
        check("v3 gets the DIS transponder 1-2-3/iff-1 " + found.keySet(), found.containsKey("1-2-3/iff-1"));
        String[] d3a = found.get("1-2-3/iff-1/mode3a");
        check("and its Mode3ATransponder", d3a != null && d3a[2].equals(String.valueOf(m3a)));
        if (d3a != null) {
            byte[] code = three.waitValue(Long.parseLong(d3a[1]), h3a[2], 3000);
            check("Mode 3/A 2000 from DIS", code != null && c3.decode(m3.getClassAttribute(M3A, "Code").getAttribute().getDataType(), code).asLong() == 02000);
        }

        // the way back: a v3 transponder on DIS entity 1-2-4 with a Mode 3/A IFFData object -> one IFF PDU with the code
        int[] pt3 = handles(three, t3, "HostObjectIdentifier", "SystemType", "SystemIsOn");
        three.s.publishObjectClass(t3, pt3);
        three.s.publishObjectClass(m3a, h3a);
        three.reserve("V3-xpdr");
        long tx = three.s.registerObject(t3, "V3-xpdr");
        three.s.updateAttributes(tx, pt3, new byte[][] {
            c3.encode(m3.getClassAttribute(T3, "HostObjectIdentifier").getAttribute().getDataType(), HValue.ofText("1-2-4")),
            c3.encode(m3.getClassAttribute(T3, "SystemType").getAttribute().getDataType(), HValue.ofInt(1)),
            c3.encode(m3.getClassAttribute(T3, "SystemIsOn").getAttribute().getDataType(), HValue.ofInt(1))}, null);
        three.reserve("V3-xpdr-3a");
        long o = three.s.registerObject(m3a, "V3-xpdr-3a");
        three.s.updateAttributes(o, h3a, new byte[][] {
            c3.encode(m3.getClassAttribute(M3A, "IFFSystemIdentifier").getAttribute().getDataType(), HValue.ofText("V3-xpdr")),
            c3.encode(m3.getClassAttribute(M3A, "IsOn").getAttribute().getDataType(), HValue.ofInt(1)),
            c3.encode(m3.getClassAttribute(M3A, "Code").getAttribute().getDataType(), HValue.ofInt(07600))}, null);
        boolean ok = false;
        long end = System.currentTimeMillis() + 5000;
        byte[] buf = new byte[512];
        while (!ok && System.currentTimeMillis() < end) {
            java.net.DatagramPacket dp = new java.net.DatagramPacket(buf, buf.length);
            try {
                sim.receive(dp);
            } catch (java.net.SocketTimeoutException e) {
                continue;
            }
            if ((buf[2] & 0xFF) != 28 || dp.getLength() < 60) continue;
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(buf, 0, dp.getLength());
            int site = b.getShort(12) & 0xFFFF, app = b.getShort(14) & 0xFFFF, ent = b.getShort(16) & 0xFFFF;
            int status = buf[44] & 0xFF;
            int p3 = b.getShort(48 + 4) & 0xFFFF;
            ok = site == 1 && app == 2 && ent == 4 && (status & (1 << 3)) != 0 && (p3 & 0xFFF) == 07600 && (p3 & 0x2000) != 0;
        }
        check("DIS gets an IFF PDU of 1-2-4 with Mode 3/A 7600 on (folded from the v3 IFFData)", ok);
        gw.stop();
        three.s.close();
        sim.close();
    }

    private static boolean waitLong(Fed f, HlaCodec c, InformationExchangeModel m, long instance, int attr, String name, long want) {
        for (int i = 0; i < 60; i++) {
            Map<Integer, byte[]> mine = f.byInstance.get(instance);
            byte[] b = mine == null ? null : mine.get(attr);
            if (b != null) {
                long got = c.decode(m.getClassAttribute(T2, name).getAttribute().getDataType(), b).asLong();
                if (name.equals("Mode5CAltitude")) got = (short) got;
                if (got == want) return true;
            }
            sleep(50);
        }
        return false;
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

        byte[] waitValue(long instance, int attr, long millis) {
            long end = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < end) {
                Map<Integer, byte[]> mine = byInstance.get(instance);
                byte[] v = mine == null ? null : mine.get(attr);
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
