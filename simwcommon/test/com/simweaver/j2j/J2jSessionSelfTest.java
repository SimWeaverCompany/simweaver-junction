package com.simweaver.j2j;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.j2j.config.J2jConfig;
import com.simweaver.j2j.session.J2jSessions;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * PDU sessions (design §9.6) between three servers in one JVM, a - b - c (a reaches c through b):
 * membership, model agreement, reliable PDUs in order, Entity State best effort and superseded,
 * local members, the loop filter, the J2J page's usage, a lost member and its return, leaving.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.j2j.J2jSessionSelfTest</pre>
 */
public final class J2jSessionSelfTest {

    private static int checks;
    private static int failures;

    private J2jSessionSelfTest() {
    }

    static final String DIS7 = "DIS 7, exercise 1";

    /** Collects what a member receives. */
    static final class Inbox implements J2jSessions.Delivery {
        final List<byte[]> got = new CopyOnWriteArrayList<byte[]>();

        @Override
        public void pdu(byte[] pdu) {
            got.add(pdu);
        }

        long count(int type) {
            return got.stream().filter(p -> (p[2] & 0xFF) == type).count();
        }

        /** the sequence numbers (octets 18-21) of the PDUs of a type, in arrival order */
        int[] seqs(int type) {
            return got.stream().filter(p -> (p[2] & 0xFF) == type).mapToInt(J2jSessionSelfTest::seq).toArray();
        }
    }

    /** A DIS 7 PDU: header, entity id 1.2.{entity}, then a 4-octet sequence number, padded to 40 octets. */
    static byte[] pdu(int type, int entity, int seq) {
        byte[] b = new byte[40];
        b[0] = 7;
        b[1] = 1;
        b[2] = (byte) type;
        b[3] = 1;
        b[9] = 40;
        b[13] = 1;
        b[15] = 2;
        b[16] = (byte) (entity >> 8);
        b[17] = (byte) entity;
        b[18] = (byte) (seq >> 24);
        b[19] = (byte) (seq >> 16);
        b[20] = (byte) (seq >> 8);
        b[21] = (byte) seq;
        return b;
    }

    static int seq(byte[] p) {
        return ((p[18] & 0xFF) << 24) | ((p[19] & 0xFF) << 16) | ((p[20] & 0xFF) << 8) | (p[21] & 0xFF);
    }

    static boolean edge(String x, String y) {
        String k = x.compareTo(y) < 0 ? x + "-" + y : y + "-" + x;
        return k.equals("a-b") || k.equals("b-c");
    }

    static J2jNode node(Path d, String id, int port) {
        SimwProperties s = new SimwProperties().set("j2j.node.id", id).set("j2j.port", String.valueOf(port)).set("j2j.deadseconds", "2");
        J2jNode n = J2jNode.start(J2jConfig.load(s, d.resolve("network.settings"), d.resolve("network.key")), "SimWeaver test");
        n.links().setLinkFilter(peer -> edge(id, peer));
        return n;
    }

    public static void main(String[] args) throws Exception {
        J2jSelfTest.captureEvents();
        check("session names", J2jSessions.nameProblem("north-pdu") == null && J2jSessions.nameProblem("Range_3.b") == null
                && J2jSessions.nameProblem("") != null && J2jSessions.nameProblem("a b") != null && J2jSessions.nameProblem("-x") != null);

        check("wire protocol A.3: session key and wire ids", J2jSessions.sessionKey("north-pdu") == 0xcf0edd164cc5411bL
                && J2jSessions.wireId("HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft") == 0x2a881512
                && J2jSessions.wireId("HLAobjectRoot.BaseEntity.EntityType") == 0x62b62d1c);
        int pa = J2jSelfTest.freePort(), pb = J2jSelfTest.freePort(), pc = J2jSelfTest.freePort();
        Path d = J2jSelfTest.files("session-net", "member.a.addresses=127.0.0.1:" + pa + "\nmember.b.addresses=127.0.0.1:" + pb
                + "\nmember.c.addresses=127.0.0.1:" + pc + "\n", J2jSelfTest.key("k1", J2jSelfTest.KEY1));
        J2jNode a = node(d, "a", pa), b = node(d, "b", pb);
        J2jNode[] c = {node(d, "c", pc)};
        J2jSessions ha = new J2jSessions(2000), hb = new J2jSessions(2000);
        J2jSessions[] hc = {new J2jSessions(2000)};
        ha.attach(a);
        hb.attach(b);
        hc[0].attach(c[0]);
        try {
            check("routed: a reaches c through b", J2jSelfTest.await(() -> a.router().route("c") != null
                    && a.router().route("c").path.size() == 2, 10000));

            Inbox ia = new Inbox(), ia2 = new Inbox(), ib = new Inbox(), ic = new Inbox();
            J2jSessions.Member ma = ha.join("north", "dis-a", "1", "j2j", DIS7, false, ia);
            J2jSessions.Member mb = hb.join("north", "dis-b", "1", "j2j", "DIS 6, exercise 1", false, ib);
            J2jSessions.Member mc = hc[0].join("north", "dis-c", "1", "j2j", DIS7, false, ic);
            check("a sees the session's three members", J2jSelfTest.await(() -> members(ha, "north").size() == 3, 5000));
            check("states on a: c active, b mismatch", J2jSelfTest.await(() -> "active".equals(state(ha, "c")) && "mismatch".equals(state(ha, "b")), 5000));
            check("model mismatch: an event", J2jSelfTest.events("j2j.session.modelmismatch") >= 1);
            check("join events", J2jSelfTest.events("j2j.session.joined") >= 6);
            check("usage on a: its gateway shares north with c, not with b", ha.gatewaysWith("c").size() == 1
                    && "north".equals(ha.gatewaysWith("c").get(0).get("session")) && ha.gatewaysWith("b").isEmpty()
                    && ha.sessionsOf("b").contains("north"));
            check("peers of a's member", ma.peers().size() == 2);

            // reliable: Fire PDUs, all, in order, through b
            for (int i = 1; i <= 300; i++) {
                ma.send(pdu(2, 7, i));
            }
            check("300 Fire PDUs a -> c over two hops: all, in order", J2jSelfTest.await(() -> ic.count(2) == 300, 8000) && inOrder(ic.seqs(2), 300));
            check("the DIS 6 member gets none", ib.got.isEmpty());

            // best effort: Entity State, superseded per entity
            for (int i = 1; i <= 2000; i++) {
                ma.send(pdu(1, 9, i));
            }
            Thread.sleep(300);
            ma.send(pdu(1, 9, 2001));                         // a burst may lose its last datagrams on UDP; the next state comes
            check("Entity State: the newest arrives", J2jSelfTest.await(() -> {
                int[] s = ic.seqs(1);
                return s.length > 0 && s[s.length - 1] == 2001;
            }, 5000));
            check("Entity State is best effort (" + ic.count(1) + " of 2001 arrived)", ic.count(1) <= 2001);

            // a second member on a: local delivery both ways
            J2jSessions.Member ma2 = ha.join("north", "dis-a2", "1", "j2j", DIS7, false, ia2);
            ma.send(pdu(3, 1, 1));
            check("a local member gets a's PDU at once", ia2.count(3) == 1);
            check("and c gets it too", J2jSelfTest.await(() -> ic.count(3) == 1, 5000));
            check("c sees a2 join", J2jSelfTest.await(() -> members(hc[0], "north").size() == 4, 5000));
            mc.send(pdu(4, 1, 1));
            check("c's PDU reaches both members on a", J2jSelfTest.await(() -> ia.count(4) == 1 && ia2.count(4) == 1, 5000));

            // loops: a PDU c received from the session, heard again on c's DIS network, is not sent back
            long loopsBefore = mc.loopsDropped.get();
            mc.send(ic.got.get(0).clone());
            byte[] rewritten = ic.got.get(0).clone();
            rewritten[0] = 6;
            rewritten[1] = 3;                                 // another relay's version and exercise
            mc.send(rewritten);
            check("loop filter: what came from the session is not sent back", mc.loopsDropped.get() - loopsBefore == 2);

            // the J2J page's view
            List<Map<String, Object>> ss = ha.sessions();
            check("sessions(): north, pdu, our model, four members", ss.size() == 1 && "north".equals(ss.get(0).get("name"))
                    && "pdu".equals(ss.get(0).get("kind")) && DIS7.equals(ss.get(0).get("model"))
                    && ((List<?>) ss.get(0).get("members")).size() == 4 && ss.get(0).get("since") != null);

            // c is lost, then comes back (a new start: new members)
            long lostBefore = J2jSelfTest.events("j2j.session.memberlost");
            c[0].stop();
            hc[0].close();
            check("c lost on a (not reachable for 2 s)", J2jSelfTest.await(() -> "lost".equals(state(ha, "c"))
                    && J2jSelfTest.events("j2j.session.memberlost") > lostBefore, 15000));
            long notSentBefore = ma.notSent.get() + ma.copies.get();
            ma.send(pdu(2, 7, 999));
            check("nothing is sent to a lost member", ma.notSent.get() + ma.copies.get() == notSentBefore);
            c[0] = node(d, "c", pc);
            hc[0] = new J2jSessions(2000);
            hc[0].attach(c[0]);
            Inbox ic2 = new Inbox();
            J2jSessions.Member mc2 = hc[0].join("north", "dis-c", "1", "j2j", DIS7, false, ic2);
            check("c back: one active member from c again", J2jSelfTest.await(() -> "active".equals(state(ha, "c"))
                    && members(ha, "north").stream().filter(m -> "c".equals(m.get("node"))).count() == 1, 15000));
            check("the restarted c learns a's members", J2jSelfTest.await(() -> members(hc[0], "north").size() == 4, 5000));
            ma.send(pdu(2, 7, 1000));
            check("PDUs flow to the new c", J2jSelfTest.await(() -> ic2.count(2) == 1, 5000));

            // a reload on b: detach, a new node, attach; b's member stays joined
            hb.detach();
            b.stop();
            J2jNode b2 = node(d, "b", pb);
            hb.attach(b2);
            check("after b's reload: a sees b's member again", J2jSelfTest.await(() -> "mismatch".equals(state(ha, "b")), 15000));

            // leaving
            ma2.leave();
            check("a2 left: c sees three members", J2jSelfTest.await(() -> members(hc[0], "north").size() == 3, 5000));
            mb.leave();
            check("b left: a sees two members", J2jSelfTest.await(() -> members(ha, "north").size() == 2, 5000));
            mc2.leave();
            ma.leave();
            check("all left: no session", J2jSelfTest.await(() -> ha.sessions().isEmpty() && hc[0].sessions().isEmpty(), 5000));
            b2.stop();
        } finally {
            ha.close();
            hb.close();
            hc[0].close();
            a.stop();
            b.stop();
            c[0].stop();
        }
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> members(J2jSessions h, String session) {
        for (Map<String, Object> s : h.sessions()) {
            if (session.equals(s.get("name"))) {
                return (List<Map<String, Object>>) s.get("members");
            }
        }
        return java.util.Collections.emptyList();
    }

    static String state(J2jSessions h, String node) {
        for (Map<String, Object> m : members(h, "north")) {
            if (node.equals(m.get("node"))) {
                return String.valueOf(m.get("state"));
            }
        }
        return null;
    }

    static boolean inOrder(int[] s, int n) {
        if (s.length != n) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (s[i] != i + 1) {
                return false;
            }
        }
        return true;
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
