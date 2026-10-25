package com.simweaver.junction;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.dis.DisEntityId;
import com.simweaver.common.dis.DisEntityState;
import com.simweaver.common.dis.DisEntityType;
import com.simweaver.common.dis.DisReader;
import com.simweaver.common.dis.DisWriter;
import com.simweaver.common.dis.cdis.CdisCodec;
import com.simweaver.common.dis.cdis.CdisSettings;
import com.simweaver.common.geo.Ellipsoid;
import com.simweaver.common.geo.Vec3;
import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.codec.HlaCodec;
import com.simweaver.common.hla.session.ConnectSettings;
import com.simweaver.common.hla.session.RtiLibrary;
import com.simweaver.common.hla.session.RtiSession;
import com.simweaver.common.hla.session.RtiSessionListener;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.json.Json;
import com.simweaver.junction.config.ConfigLoader;
import com.simweaver.junction.config.GatewayConfig;
import com.simweaver.junction.endpoint.dis.DisEndpoint;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * C-DIS in Junction (0.9.18): a gateway between an IEEE 1516-2010 federation (RPR v2, test RTI)
 * and a C-DIS network (full and partial updates), and the PDU relay between DIS 7, DIS 6 and
 * C-DIS endpoints. The "simulators" are UDP sockets of the test on the loopback interface.
 *
 * <pre>java -cp out/classes:out/test:&lt;HLA API jars&gt; com.simweaver.junction.CdisBridgeSelfTest &lt;fom root&gt; &lt;fake rti folder&gt;</pre>
 */
public final class CdisBridgeSelfTest {

    private static final String AIRCRAFT = "HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft";
    private static final String GROUND = "HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.GroundVehicle";
    private static int checks;
    private static int failures;

    private CdisBridgeSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File fomRoot = new File(args.length > 0 ? args[0] : "simwrift/rtdir/etc/fom").getAbsoluteFile();
        File rtiDir = new File(args.length > 1 ? args[1] : "fakerti/build").getAbsoluteFile();
        config(fomRoot);
        hlaToCdis(fomRoot, rtiDir, false);
        hlaToCdis(fomRoot, rtiDir, true);
        relay(fomRoot);
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ configuration

    private static void config(File fomRoot) throws Exception {
        String rpr2 = new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath();
        File dir = Files.createTempDirectory("junction-cdis-config").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "C-DIS");
        p.set("junction.endpoints", "a;b");
        p.set("junction.endpoint.a.standard", "cdis");
        p.set("junction.endpoint.a.hla.foms", rpr2);
        p.set("junction.endpoint.b.hla.foms", rpr2);
        p.set("junction.endpoint.a.cdis.updatemode", "partial");
        p.set("junction.endpoint.a.cdis.fullupdatemultiplier", "3");
        p.set("junction.endpoint.a.cdis.heartbeat.entitystate", "2.5");
        p.set("junction.endpoint.b.standard", "dis7");
        p.set("junction.router.kind", "pdu");
        p.set("junction.router.pdu.types", "1;2;26");
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "c.junction.settings"), p);
        check("a C-DIS endpoint and a PDU relay: valid " + gc.getErrors(), gc.isValid());
        CdisSettings s = gc.getEndpoints().get("a").getCdisSettings();
        check("C-DIS settings read", s.getUpdateMode() == CdisSettings.UpdateMode.PARTIAL && s.getFullUpdateMultiplier() == 3
                && s.heartbeat(CdisCodec.ENTITY_STATE) == 2500 && s.heartbeat(CdisCodec.TRANSMITTER) == 2000);
        check("PDU types of the relay", gc.getRouter().getPduTypes().equals(Arrays.asList(1, 2, 26)));
        String json = Json.toJson(gc);
        check("shown in the configuration", json.contains("\"standard\":\"cdis\"") && json.contains("\"updatemode\":\"partial\"")
                && json.contains("\"kind\":\"pdu\""));

        p.set("junction.endpoint.a.cdis.updatemode", "sometimes");
        p.set("junction.endpoint.b.standard", "ieee1516-2010");
        GatewayConfig bad = new ConfigLoader(junctions, dir).gateway(new File(junctions, "c.junction.settings"), p);
        String errors = bad.getErrors().toString();
        check("a wrong update mode and an HLA endpoint in a PDU relay are errors: " + errors,
                !bad.isValid() && errors.contains("updatemode") && errors.contains("PDU relay"));
    }

    // ------------------------------------------------------------------ HLA <-> C-DIS

    private static void hlaToCdis(File fomRoot, File rtiDir, boolean partial) throws Exception {
        String mode = partial ? "partial" : "full";
        String rpr2 = new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath();
        File jar3 = new File(rtiDir, "fakerti-1516e.jar");
        int gatewayPort = freePort();
        DatagramSocket sim = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        sim.setSoTimeout(200);
        File dir = Files.createTempDirectory("junction-cdis-test").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        String federation = partial ? "CdisPartial" : "CdisFull";
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "HLA - C-DIS");
        p.set("junction.endpoints", "hla;cdis");
        p.set("junction.endpoint.hla.standard", "ieee1516-2010");
        p.set("junction.endpoint.hla.hla.foms", rpr2);
        p.set("junction.endpoint.hla.hla.federation", federation);
        p.set("junction.endpoint.hla.hla.rtijar", jar3.getPath());
        p.set("junction.endpoint.cdis.standard", "cdis");
        p.set("junction.endpoint.cdis.hla.foms", rpr2);
        p.set("junction.endpoint.cdis.dis.interface", "127.0.0.1");
        p.set("junction.endpoint.cdis.dis.port", String.valueOf(gatewayPort));
        p.set("junction.endpoint.cdis.dis.exid", "3");
        p.set("junction.endpoint.cdis.dis.sendaddress", "127.0.0.1:" + sim.getLocalPort());
        p.set("junction.endpoint.cdis.dis.heartbeatsecs", "1");
        p.set("junction.endpoint.cdis.cdis.updatemode", mode);
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "cdis.junction.settings"), p);
        check(mode + ": config valid " + gc.getErrors(), gc.isValid());

        RtiLibrary lib = RtiLibrary.isolated(Collections.singletonList(jar3));
        InformationExchangeModel m = InformationExchangeModel.fromHlaEvolved(Collections.singletonList(rpr2));
        HlaCodec codec = new HlaCodec(m);
        Gateway gw = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
        gw.start();
        check(mode + ": gateway running: " + gw.getState(), gw.getState() == Gateway.State.RUNNING);

        // HLA -> C-DIS
        Fed blue = new Fed(lib, federation, "BlueSim", rpr2);
        int ac = blue.s.objectClass(AIRCRAFT);
        String[] names = {"EntityIdentifier", "EntityType", "Spatial", "Marking", "ForceIdentifier"};
        int[] h = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            h[i] = blue.s.attribute(ac, names[i]);
        }
        blue.s.publishObjectClass(ac, h);
        blue.reserve("Viper-1");
        long viper = blue.s.registerObject(ac, "Viper-1");
        Vec3 where = Ellipsoid.WGS84.toEcef(59.33, 18.07, 3000);
        blue.s.updateAttributes(viper, h, new byte[][] {
            encode(codec, m, AIRCRAFT, "EntityIdentifier", entityId(5, 6, 7)),
            encode(codec, m, AIRCRAFT, "EntityType", entityType(1, 2, 225, 1, 3, 0, 0)),
            encode(codec, m, AIRCRAFT, "Spatial", spatial(codec, m, where.getX(), where.getY(), where.getZ())),
            encode(codec, m, AIRCRAFT, "Marking", marking("VIPER1")),
            encode(codec, m, AIRCRAFT, "ForceIdentifier", HValue.ofInt(1))}, null);

        CdisCodec decoder = new CdisCodec(CdisSettings.DEFAULTS);
        List<byte[]> heard = new ArrayList<byte[]>();
        DisEntityState es = entityState(sim, decoder, new DisEntityId(5, 6, 7), 4000, heard);
        check(mode + ": the C-DIS network gets the aircraft as 5-6-7", es != null);
        if (es != null) {
            double d = Math.sqrt(Math.pow(es.location[0] - where.getX(), 2) + Math.pow(es.location[1] - where.getY(), 2)
                    + Math.pow(es.location[2] - where.getZ(), 2));
            check(mode + ": location within C-DIS precision (" + String.format("%.4f", d) + " m)", d < 0.02);
            check(mode + ": type, marking, force", es.type.kind == 1 && es.type.domain == 2 && es.type.country == 225
                    && new String(es.marking, 0, 6, "US-ASCII").equals("VIPER1") && es.forceId == 1);
        }
        check(mode + ": heartbeats", entityState(sim, decoder, new DisEntityId(5, 6, 7), 2500, heard) != null);
        if (partial) {
            int first = heard.get(0).length;
            int last = heard.get(heard.size() - 1).length;
            check("partial: heartbeats are partial updates (" + first + " then " + last + " octets)", last < first);
            CdisCodec late = new CdisCodec(CdisSettings.DEFAULTS);
            byte[] p2 = heard.get(heard.size() - 1);
            check("partial: a decoder that missed the full update waits for one",
                    late.decode(p2, 0, p2.length, System.currentTimeMillis()).isEmpty() && late.stats().getWaitingForFull() == 1);
        } else {
            check("full: every update the same size", heard.get(0).length == heard.get(heard.size() - 1).length);
        }

        // C-DIS -> HLA
        int gv = blue.s.objectClass(GROUND);
        int gSpatial = blue.s.attribute(gv, "Spatial");
        int gId = blue.s.attribute(gv, "EntityIdentifier");
        blue.s.subscribeObjectClass(gv, new int[] {gSpatial, gId});
        CdisCodec sender = new CdisCodec(CdisSettings.DEFAULTS.withUpdateMode(
                partial ? CdisSettings.UpdateMode.PARTIAL : CdisSettings.UpdateMode.FULL));
        byte[] tank = tankPdu(3, where.getX() + 100, where.getY(), where.getZ());
        DatagramSocket out = new DatagramSocket();
        String[] disc = null;
        for (int i = 0; i < 20 && disc == null; i++) {
            byte[] c = sender.encode(tank, 0, tank.length, System.currentTimeMillis());
            out.send(new DatagramPacket(c, c.length, InetAddress.getLoopbackAddress(), gatewayPort));
            disc = blue.take("discovered", 250);
        }
        check(mode + ": a C-DIS tank is discovered in HLA", disc != null && Integer.parseInt(disc[2]) == gv);
        byte[] sp = disc == null ? null : blue.waitValue(Long.parseLong(disc[1]), gSpatial, 3000);
        if (sp != null) {
            HValue v = codec.decode(m.getClassAttribute(GROUND, "Spatial").getDataType(), sp);
            HValue loc = v.getAlternative().field("WorldLocation");
            double x = loc.field("X").asDouble();
            check(mode + ": with its location (x " + String.format("%.3f", x) + ")", Math.abs(x - (where.getX() + 100)) < 0.02);
        } else {
            check(mode + ": with its location", false);
        }
        DisEndpoint ep = (DisEndpoint) gw.endpoint("cdis");
        Map<String, Object> diag = ep.diagnostics();
        @SuppressWarnings("unchecked")
        Map<String, Object> cd = (Map<String, Object>) diag.get("cdis");
        check(mode + ": diagnostics: C-DIS, encoded and decoded " + cd, "C-DIS".equals(diag.get("protocol"))
                && ((Number) cd.get("encoded")).longValue() > 0 && ((Number) cd.get("decoded")).longValue() > 0
                && ((Number) cd.get("encoderatio")).doubleValue() < 0.8);
        check(mode + ": in the gateway status", Json.toJson(gw).contains("\"protocol\":\"C-DIS\""));
        out.close();
        blue.s.close();
        gw.stop();
        sim.close();
    }

    // ------------------------------------------------------------------ DIS 7 / DIS 6 <-> C-DIS relay

    private static void relay(File fomRoot) throws Exception {
        DatagramSocket simDis = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        DatagramSocket simCdis = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        DatagramSocket simDis6 = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        for (DatagramSocket s : new DatagramSocket[] {simDis, simCdis, simDis6}) {
            s.setSoTimeout(200);
        }
        int disPort = freePort();
        int cdisPort = freePort();
        int dis6Port = freePort();
        File dir = Files.createTempDirectory("junction-cdis-relay").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        SimwProperties p = new SimwProperties();
        p.set("junction.name", "DIS 7 - C-DIS relay");
        p.set("junction.endpoints", "dis;cdis;old");
        String rpr2 = new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath();
        endpoint(p, "dis", "dis7", disPort, simDis.getLocalPort(), 3, rpr2);
        endpoint(p, "cdis", "cdis", cdisPort, simCdis.getLocalPort(), 3, rpr2);
        endpoint(p, "old", "dis6", dis6Port, simDis6.getLocalPort(), 3, rpr2);
        p.set("junction.router.kind", "pdu");
        GatewayConfig gc = new ConfigLoader(junctions, dir).gateway(new File(junctions, "relay.junction.settings"), p);
        check("relay: config valid " + gc.getErrors(), gc.isValid());
        Gateway gw = new Gateway(gc, Collections.<String, String>emptyMap(), dir, null);
        gw.start();
        check("relay: running", gw.getState() == Gateway.State.RUNNING);
        DatagramSocket out = new DatagramSocket();
        InetAddress lo = InetAddress.getLoopbackAddress();

        // DIS 7 -> C-DIS: PDUs at C-DIS precision come out the same
        CdisCodec decoder = new CdisCodec(CdisSettings.DEFAULTS);
        byte[][] pdus = {staticEntity(), comment("relayed"), receiver(), signal()};
        String[] what = {"Entity State", "Comment", "Receiver", "Signal"};
        for (int i = 0; i < pdus.length; i++) {
            out.send(new DatagramPacket(pdus[i], pdus[i].length, lo, disPort));
            byte[] got = cdisPdu(simCdis, decoder, pdus[i][2] & 0xFF, 3000);
            check("relay DIS 7 → C-DIS: " + what[i] + " the same", got != null && Arrays.equals(got, pdus[i]));
        }
        byte[] fromDis6 = dis6Copy(pdus[0]);
        byte[] at6 = disPdu(simDis6, 1, 3000);
        check("relay DIS 7 → DIS 6: version 6, PDU status cleared", at6 != null && at6[0] == 6 && at6[10] == 0
                && Arrays.equals(at6, fromDis6));

        // C-DIS -> DIS 7
        CdisCodec encoder = new CdisCodec(CdisSettings.DEFAULTS);
        drain(simDis);
        for (int i = 0; i < pdus.length; i++) {
            byte[] c = encoder.encode(pdus[i], 0, pdus[i].length, 0);
            out.send(new DatagramPacket(c, c.length, lo, cdisPort));
            byte[] got = disPdu(simDis, pdus[i][2] & 0xFF, 3000);
            check("relay C-DIS → DIS 7: " + what[i] + " the same", got != null && Arrays.equals(got, pdus[i]));
        }

        // exercise: the receiving endpoint's own
        byte[] other = comment("exercise 9");
        other[1] = 9;
        out.send(new DatagramPacket(other, other.length, lo, disPort));
        check("relay: another exercise is not relayed", cdisPdu(simCdis, decoder, 22, 800) == null);

        // a PDU type C-DIS does not have
        byte[] esu = new DisWriter(80).header(7, 3, 67, 1, 0, 0).entityId(1, 2, 3).zeros(60).finish();
        out.send(new DatagramPacket(esu, esu.length, lo, disPort));
        byte[] at6esu = disPdu(simDis6, 67, 3000);
        check("relay: a type C-DIS does not have still goes to DIS 6", at6esu != null);
        DisEndpoint c = (DisEndpoint) gw.endpoint("cdis");
        @SuppressWarnings("unchecked")
        Map<String, Object> cd = (Map<String, Object>) c.diagnostics().get("cdis");
        check("relay: but not to C-DIS (counted) " + cd, ((Number) cd.get("notconvertible")).longValue() >= 1);
        String status = Json.toJson(gw);
        check("relay: routes in the status", status.contains("\"relayed\"") && status.contains("dis -> cdis")
                && status.contains("cdis -> dis"));
        out.close();
        gw.stop();
        simDis.close();
        simCdis.close();
        simDis6.close();
    }

    private static void endpoint(SimwProperties p, String id, String standard, int port, int sendTo, int exercise, String fom) {
        p.set("junction.endpoint." + id + ".standard", standard);
        p.set("junction.endpoint." + id + ".hla.foms", fom);
        p.set("junction.endpoint." + id + ".dis.interface", "127.0.0.1");
        p.set("junction.endpoint." + id + ".dis.port", String.valueOf(port));
        p.set("junction.endpoint." + id + ".dis.exid", String.valueOf(exercise));
        p.set("junction.endpoint." + id + ".dis.sendaddress", "127.0.0.1:" + sendTo);
    }

    // ------------------------------------------------------------------ PDUs

    private static final double LAT = ((1L << 30) - 1) / (Math.PI / 2);
    private static final double LON = ((1L << 31) - 1) / Math.PI;

    /** A static ground vehicle at C-DIS precision. */
    private static byte[] staticEntity() {
        Vec3 e = Ellipsoid.WGS84.toEcefRadians(700_000_000L / LAT, 200_000_000L / LON, 4_321 / 100.0);
        DisWriter w = new DisWriter(144).header(7, 3, 1, 1, ((99L << 6) << 1) | 1, 0);
        w.entityId(1, 2, 3).u8(2).u8(0).entityType(new DisEntityType(1, 1, 225, 1, 1, 3, 0)).zeros(8).zeros(12);
        w.f64(e.getX()).f64(e.getY()).f64(e.getZ()).zeros(12).u32(0).u8(1).zeros(15).zeros(24);
        w.u8(1).text("TANK", 11).u32(0);
        return w.finish();
    }

    private static byte[] comment(String text) {
        byte[] t = text.getBytes();
        int octets = (t.length * 8 + 63) / 64 * 8;
        return new DisWriter(64).header(7, 3, 22, 5, 0, 0).entityId(1, 2, 0).entityId(0xFFFF, 0xFFFF, 0xFFFF)
                .u32(0).u32(1).u32(50000).u32(t.length * 8L).bytes(Arrays.copyOf(t, octets)).finish();
    }

    private static byte[] receiver() {
        return new DisWriter(36).header(7, 3, 27, 4, 0, 0).entityId(1, 2, 3).u16(1).u16(1).zeros(2).f32(-80)
                .entityId(1, 2, 4).u16(2).finish();
    }

    private static byte[] signal() {
        byte[] audio = new byte[64];
        for (int i = 0; i < audio.length; i++) {
            audio[i] = (byte) (i * 3);
        }
        return new DisWriter(96).header(7, 3, 26, 4, 0, 0).entityId(1, 2, 3).u16(1).u16(4).u16(0).u32(8000).u16(512).u16(32)
                .bytes(audio).finish();
    }

    private static byte[] dis6Copy(byte[] pdu) {
        byte[] c = pdu.clone();
        c[0] = 6;
        c[10] = 0;
        return c;
    }

    private static byte[] tankPdu(int exercise, double x, double y, double z) {
        DisWriter w = new DisWriter(144).header(7, exercise, 1, 1, 0, 0);
        w.entityId(new DisEntityId(1, 2, 3)).u8(2).u8(0);
        w.entityType(new DisEntityType(1, 1, 225, 1, 1, 3, 0)).entityType(new DisEntityType(1, 1, 225, 1, 1, 3, 0));
        w.f32(0).f32(0).f32(0).f64(x).f64(y).f64(z).f32(0).f32(0).f32(0);
        w.i32(0).u8(1).zeros(15).zeros(24);
        w.u8(1).text("TANK", 11).i32(0);
        return w.finish();
    }

    // ------------------------------------------------------------------ the simulators

    private static DisEntityState entityState(DatagramSocket s, CdisCodec decoder, DisEntityId id, long millis, List<byte[]> heard)
            throws Exception {
        long end = System.currentTimeMillis() + millis;
        byte[] buf = new byte[2048];
        while (System.currentTimeMillis() < end) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                s.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            }
            byte[] c = Arrays.copyOf(buf, p.getLength());
            for (byte[] pdu : decoder.decode(c, 0, c.length, System.currentTimeMillis())) {
                if ((pdu[2] & 0xFF) == 1) {
                    DisReader r = new DisReader(pdu, 0, pdu.length);
                    r.seek(12);
                    DisEntityState es = DisEntityState.read(r);
                    if (es.id.equals(id)) {
                        heard.add(c);
                        return es;
                    }
                }
            }
        }
        return null;
    }

    private static byte[] cdisPdu(DatagramSocket s, CdisCodec decoder, int type, long millis) throws Exception {
        long end = System.currentTimeMillis() + millis;
        byte[] buf = new byte[2048];
        while (System.currentTimeMillis() < end) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                s.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            }
            for (byte[] pdu : decoder.decode(buf, 0, p.getLength(), 0)) {
                if ((pdu[2] & 0xFF) == type) {
                    return pdu;
                }
            }
        }
        return null;
    }

    private static byte[] disPdu(DatagramSocket s, int type, long millis) throws Exception {
        long end = System.currentTimeMillis() + millis;
        byte[] buf = new byte[2048];
        while (System.currentTimeMillis() < end) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                s.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            }
            if (p.getLength() >= 12 && (buf[2] & 0xFF) == type) {
                return Arrays.copyOf(buf, p.getLength());
            }
        }
        return null;
    }

    private static void drain(DatagramSocket s) throws Exception {
        byte[] buf = new byte[2048];
        try {
            while (true) {
                s.receive(new DatagramPacket(buf, buf.length));
            }
        } catch (SocketTimeoutException done) {
            // empty
        }
    }

    private static int freePort() throws Exception {
        try (DatagramSocket s = new DatagramSocket(0)) {
            return s.getLocalPort();
        }
    }

    // ------------------------------------------------------------------ values

    private static byte[] encode(HlaCodec c, InformationExchangeModel m, String cls, String attr, HValue v) {
        return c.encode(m.getClassAttribute(cls, attr).getDataType(), v);
    }

    private static HValue entityId(int site, int app, int entity) {
        Map<String, HValue> f = new LinkedHashMap<String, HValue>();
        f.put("SiteID", HValue.ofInt(site));
        f.put("ApplicationID", HValue.ofInt(app));
        Map<String, HValue> e = new LinkedHashMap<String, HValue>();
        e.put("FederateIdentifier", HValue.ofRecord(f));
        e.put("EntityNumber", HValue.ofInt(entity));
        return HValue.ofRecord(e);
    }

    private static HValue entityType(int kind, int domain, int country, int category, int sub, int specific, int extra) {
        Map<String, HValue> t = new LinkedHashMap<String, HValue>();
        t.put("EntityKind", HValue.ofInt(kind));
        t.put("Domain", HValue.ofInt(domain));
        t.put("CountryCode", HValue.ofInt(country));
        t.put("Category", HValue.ofInt(category));
        t.put("Subcategory", HValue.ofInt(sub));
        t.put("Specific", HValue.ofInt(specific));
        t.put("Extra", HValue.ofInt(extra));
        return HValue.ofRecord(t);
    }

    private static HValue spatial(HlaCodec codec, InformationExchangeModel m, double x, double y, double z) {
        HValue def = codec.defaultValue(m.getDataType("SpatialVariantStruct"));
        Map<String, HValue> loc = new LinkedHashMap<String, HValue>();
        loc.put("X", HValue.ofFloat(x));
        loc.put("Y", HValue.ofFloat(y));
        loc.put("Z", HValue.ofFloat(z));
        HValue fp = codec.defaultValue(m.getDataType("SpatialFPStruct")).withField("WorldLocation", HValue.ofRecord(loc));
        return HValue.ofVariant(2, "DRM_FPW", "SpatialFPW", fp).withType(def.getType());
    }

    private static HValue marking(String text) {
        Map<String, HValue> mk = new LinkedHashMap<String, HValue>();
        mk.put("MarkingEncodingType", HValue.ofEnum(1, "ASCII"));
        mk.put("MarkingData", HValue.ofText(text));
        return HValue.ofRecord(mk);
    }

    // ------------------------------------------------------------------ a test federate

    private static final class Fed implements RtiSessionListener {
        final RtiSession s;
        final BlockingQueue<String[]> q = new LinkedBlockingQueue<String[]>();
        final Map<Long, Map<Integer, byte[]>> byInstance = new ConcurrentHashMap<Long, Map<Integer, byte[]>>();

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
            List<String[]> skipped = new ArrayList<String[]>();
            try {
                while (System.currentTimeMillis() < end) {
                    String[] e = q.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
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

        byte[] waitValue(long instance, int attr, long millis) {
            long end = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < end) {
                Map<Integer, byte[]> mine = byInstance.get(instance);
                byte[] v = mine == null ? null : mine.get(attr);
                if (v != null) {
                    return v;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
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
                mine.put(attributes[i], vals[i]);
            }
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

    private static void check(String what, boolean ok) {
        checks++;
        if (ok) {
            System.out.println("  ok   " + what);
        } else {
            failures++;
            System.out.println("  FAIL " + what);
        }
    }
}
