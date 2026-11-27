package com.simweaver.junction;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.dis.DisWriter;
import com.simweaver.common.json.Json;
import com.simweaver.j2j.J2jNode;
import com.simweaver.j2j.config.J2jConfig;
import com.simweaver.j2j.crypto.J2jCrypto;
import com.simweaver.j2j.session.J2jSessions;
import com.simweaver.junction.config.ConfigLoader;
import com.simweaver.junction.config.GatewayConfig;
import com.simweaver.junction.endpoint.j2j.J2jEndpoint;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * The J2J endpoint (0.10.0): two gateways on two J2J servers (in one JVM) share a PDU session —
 * DIS 7 exercise 3 on one side, DIS 6 exercise 4 on the other, DIS 7 exercise 1 in the session.
 * Also the settings, J2J off, the gateway status and leaving.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.junction.J2jPduSelfTest [fom root]</pre>
 */
public final class J2jPduSelfTest {

    private static int checks;
    private static int failures;

    private J2jPduSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File fomRoot = new File(args.length > 0 ? args[0] : "simwrift/rtdir/fom");
        String rpr2 = new File(fomRoot.getAbsoluteFile(), "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath();
        File dir = Files.createTempDirectory("junction-j2j").toFile();
        File junctions = new File(dir, "etc/junctions");
        junctions.mkdirs();
        ConfigLoader loader = new ConfigLoader(junctions, dir);

        // ---- settings
        SimwProperties bad = new SimwProperties();
        bad.set("junction.endpoints", "dis;s");
        disEndpoint(bad, "dis", "dis7", freePort(), 1, 3, rpr2);
        bad.set("junction.endpoint.s.standard", "j2j");
        bad.set("junction.endpoint.s.j2j.session", "north pdu");
        bad.set("junction.endpoint.s.j2j.dis", "5");
        GatewayConfig gbad = loader.gateway(new File(junctions, "bad.junction.settings"), bad);
        String errs = String.join(" | ", gbad.getErrors());
        check("bad session name and DIS version are errors: " + errs, !gbad.isValid()
                && errs.contains("endpoint.j2j.session") && errs.contains("endpoint.j2j.dis"));
        check("without router.kind=pdu the J2J endpoint carries objects", Json.toJson(gbad.getEndpoints().get("s")).contains("\"j2jkind\":\"objects\""));

        int disA = freePort(), disB = freePort();
        DatagramSocket simA = sim(), simB = sim();
        GatewayConfig ga = loader.gateway(new File(junctions, "ga.junction.settings"), gateway("dis7", disA, simA.getLocalPort(), 3, rpr2));
        GatewayConfig gb = loader.gateway(new File(junctions, "gb.junction.settings"), gateway("dis6", disB, simB.getLocalPort(), 4, rpr2));
        check("a DIS endpoint and a J2J endpoint in a PDU relay: valid " + ga.getErrors() + gb.getErrors(), ga.isValid() && gb.isValid());
        String json = Json.toJson(ga.getEndpoints().get("j2j"));
        check("the J2J endpoint's settings in its JSON: " + json, json.contains("\"isj2j\":true") && json.contains("\"j2jsession\":\"north\"")
                && json.contains("\"defaulttarget\":\"north\"") && json.contains("\"j2jdis\":7") && json.contains("\"j2jkind\":\"pdu\""));

        // ---- J2J off: the gateway does not start
        J2jEndpoint.setHubs(g -> null);
        Gateway off = new Gateway(ga, Collections.<String, String>emptyMap(), dir, null);
        String why = null;
        try {
            off.start();
        } catch (GatewayException e) {
            why = e.getMessage();
        }
        check("J2J off: the gateway fails, saying why (" + why + ")", why != null && why.contains("J2J does not run"));

        // ---- two J2J servers
        int pa = freePort(), pb = freePort();
        Path d = Files.createTempDirectory("j2jnet");
        Files.write(d.resolve("network.settings"), ("network.id=pdu-net\nmember.a.addresses=127.0.0.1:" + pa
                + "\nmember.b.addresses=127.0.0.1:" + pb + "\n").getBytes(StandardCharsets.UTF_8));
        Files.write(d.resolve("network.key"), ("k1=" + Base64.getEncoder().encodeToString(J2jCrypto.sha256("pdu test".getBytes(StandardCharsets.UTF_8)))
                + "\n").getBytes(StandardCharsets.UTF_8));
        J2jNode a = J2jNode.start(cfg(d, "a", pa), "SimWeaver test");
        J2jNode b = J2jNode.start(cfg(d, "b", pb), "SimWeaver test");
        J2jSessions ha = new J2jSessions(3000), hb = new J2jSessions(3000);
        ha.attach(a);
        hb.attach(b);
        J2jEndpoint.setHubs(g -> g.startsWith("ga") ? ha : hb);
        Gateway gwA = new Gateway(ga, Collections.<String, String>emptyMap(), dir, null);
        Gateway gwB = new Gateway(gb, Collections.<String, String>emptyMap(), dir, null);
        DatagramSocket out = new DatagramSocket();
        InetAddress lo = InetAddress.getLoopbackAddress();
        try {
            check("the servers are linked", await(() -> a.links().link("b") != null, 10000));
            gwA.start();
            gwB.start();
            check("both gateways run", gwA.getState() == Gateway.State.RUNNING && gwB.getState() == Gateway.State.RUNNING);
            check("each server sees both members of north", await(() -> members(ha).size() == 2 && members(hb).size() == 2, 5000));
            check("the members are active, named by gateway", "active".equals(members(ha).get(1).get("state"))
                    && "gb".equals(members(ha).get(1).get("gateway")) && "b".equals(members(ha).get(1).get("node")));

            // DIS 7 exercise 3 at a -> session (DIS 7 exercise 1) -> DIS 6 exercise 4 at b
            byte[] es = entityState(7, 3, 1);
            byte[] comment = comment(7, 3, "over J2J");
            out.send(new DatagramPacket(es, es.length, lo, disA));
            out.send(new DatagramPacket(comment, comment.length, lo, disA));
            byte[] esB = pdu(simB, 1, 5000);
            byte[] commentB = pdu(simB, 22, 5000);
            check("Entity State a -> b: DIS 6, exercise 4, status cleared, the rest the same", esB != null && esB[0] == 6 && esB[1] == 4
                    && esB[10] == 0 && Arrays.equals(Arrays.copyOfRange(esB, 2, 10), Arrays.copyOfRange(es, 2, 10))
                    && Arrays.equals(Arrays.copyOfRange(esB, 11, esB.length), Arrays.copyOfRange(es, 11, es.length)));
            check("Comment a -> b (reliable)", commentB != null && commentB[0] == 6 && commentB[1] == 4
                    && Arrays.equals(Arrays.copyOfRange(commentB, 12, commentB.length), Arrays.copyOfRange(comment, 12, comment.length)));

            // and back: DIS 6 exercise 4 at b -> DIS 7 exercise 3 at a
            byte[] es6 = entityState(6, 4, 2);
            out.send(new DatagramPacket(es6, es6.length, lo, disB));
            byte[] esA = pdu(simA, 1, 5000);
            check("Entity State b -> a: DIS 7, exercise 3", esA != null && esA[0] == 7 && esA[1] == 3 && esA[17] == 2);

            // another exercise on a's network is not taken
            out.send(new DatagramPacket(comment(7, 9, "other"), comment(7, 9, "other").length, lo, disA));
            check("another exercise does not go into the session", pdu(simB, 22, 800) == null);

            // the gateway's status
            String status = Json.toJson(gwA);
            check("gateway status: the J2J endpoint's session and member", status.contains("\"j2j\":{\"session\":\"north\"")
                    && status.contains("\"gateway\":\"gb\"") && status.contains("\"relayed\""));

            // b's gateway stops: it leaves the session
            gwB.stop();
            check("b's gateway stopped: a sees one member", await(() -> members(ha).size() == 1, 5000));
        } finally {
            gwA.stop();
            gwB.stop();
            ha.close();
            hb.close();
            a.stop();
            b.stop();
            out.close();
            simA.close();
            simB.close();
        }
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    static SimwProperties gateway(String standard, int port, int sendTo, int exercise, String fom) {
        SimwProperties p = new SimwProperties();
        p.set("junction.endpoints", "dis;j2j");
        disEndpoint(p, "dis", standard, port, sendTo, exercise, fom);
        p.set("junction.endpoint.j2j.standard", "j2j");
        p.set("junction.endpoint.j2j.j2j.session", "north");
        p.set("junction.router.kind", "pdu");
        return p;
    }

    static void disEndpoint(SimwProperties p, String id, String standard, int port, int sendTo, int exercise, String fom) {
        p.set("junction.endpoint." + id + ".standard", standard);
        p.set("junction.endpoint." + id + ".hla.foms", fom);
        p.set("junction.endpoint." + id + ".dis.interface", "127.0.0.1");
        p.set("junction.endpoint." + id + ".dis.port", String.valueOf(port));
        p.set("junction.endpoint." + id + ".dis.exid", String.valueOf(exercise));
        p.set("junction.endpoint." + id + ".dis.sendaddress", "127.0.0.1:" + sendTo);
    }

    static J2jConfig cfg(Path d, String id, int port) {
        SimwProperties s = new SimwProperties().set("j2j.node.id", id).set("j2j.port", String.valueOf(port)).set("j2j.deadseconds", "3")
                .set("j2j.fallback.port", "0");
        return J2jConfig.load(s, d.resolve("network.settings"), d.resolve("network.key"));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> members(J2jSessions h) {
        for (Map<String, Object> s : h.sessions()) {
            if ("north".equals(s.get("name"))) {
                return (List<Map<String, Object>>) s.get("members");
            }
        }
        return Collections.emptyList();
    }

    static byte[] entityState(int version, int exercise, int entity) {
        DisWriter w = new DisWriter(144).header(version, exercise, 1, 1, 0, 0);
        w.entityId(1, 2, entity).u8(2).u8(0).zeros(8).zeros(8).zeros(12).zeros(24).zeros(12).u32(0).zeros(40).u8(1).zeros(11).u32(0);
        byte[] b = w.finish();
        if (version >= 7) {
            b[10] = 0x10;                                       // a PDU status octet (DIS 7 only)
        }
        return b;
    }

    static byte[] comment(int version, int exercise, String text) {
        byte[] t = text.getBytes(StandardCharsets.US_ASCII);
        int octets = (t.length * 8 + 63) / 64 * 8;
        return new DisWriter(64).header(version, exercise, 22, 5, 0, 0).entityId(1, 2, 0).entityId(0xFFFF, 0xFFFF, 0xFFFF)
                .u32(0).u32(1).u32(50000).u32(t.length * 8L).bytes(Arrays.copyOf(t, octets)).finish();
    }

    static DatagramSocket sim() throws Exception {
        DatagramSocket s = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        s.setSoTimeout(200);
        return s;
    }

    static byte[] pdu(DatagramSocket s, int type, long millis) throws Exception {
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
