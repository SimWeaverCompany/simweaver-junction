package com.simweaver.j2j;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.j2j.config.J2jConfig;
import com.simweaver.j2j.link.Link;
import com.simweaver.j2j.route.NetMsgs;
import com.simweaver.j2j.route.Router;
import com.simweaver.j2j.wire.Envelope;
import com.simweaver.j2j.wire.J2jWire;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * J2J phase 2 on the loopback: link state and routes in a partial mesh, forwarding, reliable
 * delivery across hops and through a reroute (in order, once), best effort, the path echo, a
 * partition and its healing, a receiver restart (stream reset), and re-keying under load.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.j2j.J2jNetworkSelfTest</pre>
 */
public final class J2jNetworkSelfTest {

    private static int checks;
    private static int failures;

    private J2jNetworkSelfTest() {
    }

    static final class Sink implements Router.Receiver {
        final List<String> got = new CopyOnWriteArrayList<String>();

        @Override
        public void receive(String sourceId, Envelope env) {
            got.add(sourceId + ":" + env.stream + ":" + new String(env.payload, StandardCharsets.UTF_8));
        }

        long count(String prefix) {
            return got.stream().filter(s -> s.startsWith(prefix)).count();
        }
    }

    static final int TYPE = 0x0101;

    public static void main(String[] args) throws Exception {
        J2jSelfTest.captureEvents();
        mesh();
        rekey();
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    static J2jConfig cfg(Path d, String self, int port, String rekeyMinutes) {
        SimwProperties s = new SimwProperties().set("j2j.node.id", self).set("j2j.port", String.valueOf(port)).set("j2j.deadseconds", "3");
        if (rekeyMinutes != null) {
            s.set("j2j.rekeyminutes", rekeyMinutes);
        }
        return J2jConfig.load(s, d.resolve("network.settings"), d.resolve("network.key"));
    }

    /** allowed links: a-b, b-c, c-d, a-c (not a-d, not b-d) */
    static final Set<String> EDGES = new HashSet<String>(Arrays.asList("a-b", "b-c", "c-d", "a-c"));

    static boolean edge(String x, String y) {
        return EDGES.contains(x.compareTo(y) < 0 ? x + "-" + y : y + "-" + x);
    }

    static J2jNode node(Path d, String id, int port, Sink sink) {
        J2jNode n = J2jNode.start(cfg(d, id, port, null), "SimWeaver test");
        n.links().setLinkFilter(peer -> edge(id, peer));
        n.router().register(0x0100, 0x01FF, sink);
        return n;
    }

    private static void mesh() throws Exception {
        String[] ids = {"a", "b", "c", "d"};
        int[] ports = new int[4];
        StringBuilder m = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            ports[i] = J2jSelfTest.freePort();
            m.append("member.").append(ids[i]).append(".addresses=127.0.0.1:").append(ports[i]).append('\n');
        }
        Path d = J2jSelfTest.files("mesh-net", m.toString(), J2jSelfTest.key("k1", J2jSelfTest.KEY1));
        Sink sa = new Sink(), sb = new Sink(), sc = new Sink(), sd = new Sink();
        J2jNode a = node(d, "a", ports[0], sa);
        J2jNode b = node(d, "b", ports[1], sb);
        J2jNode c = node(d, "c", ports[2], sc);
        J2jNode dd = node(d, "d", ports[3], sd);
        List<J2jNode> all = new ArrayList<J2jNode>(Arrays.asList(a, b, c, dd));
        try {
            check("partial mesh: a has links to b and c only", J2jSelfTest.await(() -> a.links().links().size() == 2
                    && a.links().link("d") == null && dd.links().links().size() == 1, 8000));
            check("link state: every server knows every LSA", J2jSelfTest.await(() -> a.router().lsdb().size() == 4
                    && dd.router().lsdb().size() == 4, 8000));
            check("route a -> d via c (2 hops)", J2jSelfTest.await(() -> route(a, "d") != null && route(a, "d").path.equals(Arrays.asList("c", "d")), 8000));
            check("route d -> b via c", J2jSelfTest.await(() -> route(dd, "b") != null && route(dd, "b").path.equals(Arrays.asList("c", "b")), 8000));
            check("direct route a -> b", J2jSelfTest.await(() -> route(a, "b") != null && route(a, "b").path.equals(Arrays.asList("b")), 5000));
            // the way back (acknowledgements) settles too: a link's first cost may still be the "no RTT yet" one
            check("routes back to a: c direct, d via c", J2jSelfTest.await(() -> route(c, "a") != null && route(c, "a").path.equals(Arrays.asList("a"))
                    && route(dd, "a") != null && route(dd, "a").path.equals(Arrays.asList("c", "a")), 8000));
            long bForwarded = b.router().forwarded.get();
            long cForwarded = c.router().forwarded.get();

            for (int i = 1; i <= 200; i++) {
                check0(a.router().send("d", J2jWire.CLASS_RELIABLE, TYPE, 7, ("m" + i).getBytes(StandardCharsets.UTF_8), null));
            }
            check("200 reliable a -> d over 2 hops: all, once, in order", J2jSelfTest.await(() -> sd.count("a:7:") >= 200, 8000)
                    && inOrder(sd, "a:7:", 1, 200));
            check("forwarded by c, not by b", c.router().forwarded.get() - cForwarded >= 200 && b.router().forwarded.get() == bForwarded);
            check("acknowledged: nothing pending", J2jSelfTest.await(() -> a.router().pending("d")[0] == 0, 5000));
            check("best effort a -> d arrives", a.router().send("d", J2jWire.CLASS_BEST_EFFORT, TYPE, 0, "be".getBytes(StandardCharsets.UTF_8), "obj".getBytes(StandardCharsets.UTF_8))
                    && J2jSelfTest.await(() -> sd.got.contains("a:0:be"), 3000));
            Double rtt = a.router().echo("d", 3000).get(4, TimeUnit.SECONDS);
            check("echo a -> d over the route: " + rtt + " ms", rtt != null && rtt >= 0 && rtt < 500);
            check("echo to an unknown server fails at once", a.router().echo("zz", 1000).isCompletedExceptionally() || failsSoon(a.router().echo("zz", 1000)));
            check("send to a non-member refused", !a.router().send("zz", J2jWire.CLASS_RELIABLE, TYPE, 7, new byte[1], null));

            // reroute under load: cut a-c while a stream runs; a -> d goes a-b-c-d
            Thread sender = new Thread(() -> {
                for (int i = 201; i <= 1200; i++) {
                    while (!a.router().send("d", J2jWire.CLASS_RELIABLE, TYPE, 7, ("m" + i).getBytes(StandardCharsets.UTF_8), null)) {
                        sleep(5);
                    }
                    if (i % 50 == 0) {
                        sleep(20);
                    }
                }
            });
            sender.start();
            sleep(150);
            EDGES.remove("a-c");
            a.links().setLinkFilter(peer -> edge("a", peer));
            c.links().setLinkFilter(peer -> edge("c", peer));
            sender.join();
            check("reroute: a -> d now via b, c", J2jSelfTest.await(() -> route(a, "d") != null && route(a, "d").path.equals(Arrays.asList("b", "c", "d")), 8000));
            check("reroute: 1200 messages, once, in order", J2jSelfTest.await(() -> sd.count("a:7:") >= 1200, 15000)
                    && inOrder(sd, "a:7:", 1, 1200));

            // partition: c stops; d unreachable from a; messages wait and arrive when c is back
            int cport = ports[2];
            c.stop();
            all.remove(c);
            check("partition: d unreachable from a", J2jSelfTest.await(() -> route(a, "d") == null, 15000));
            for (int i = 1201; i <= 1250; i++) {
                check0(a.router().send("d", J2jWire.CLASS_RELIABLE, TYPE, 7, ("m" + i).getBytes(StandardCharsets.UTF_8), null));
            }
            check("partition: 50 messages wait at a", a.router().pending("d")[0] >= 50);
            Sink sc2 = new Sink();
            J2jNode c2 = node(d, "c", cport, sc2);
            all.add(c2);
            a.links().dialNow();
            b.links().dialNow();
            dd.links().dialNow();
            check("healed: route a -> d again", J2jSelfTest.await(() -> route(a, "d") != null, 15000));
            check("healed: the 50 waiting messages arrive, in order", J2jSelfTest.await(() -> sd.count("a:7:") >= 1250, 15000)
                    && inOrder(sd, "a:7:", 1, 1250));

            // receiver restart: d comes back empty; the stream starts a new epoch and goes on
            int dport = ports[3];
            dd.stop();
            all.remove(dd);
            J2jSelfTest.await(() -> route(a, "d") == null, 15000);
            Sink sd2 = new Sink();
            J2jNode d2 = node(d, "d", dport, sd2);
            all.add(d2);
            c2.links().dialNow();
            check("d back", J2jSelfTest.await(() -> route(a, "d") != null, 15000));
            for (int i = 1251; i <= 1300; i++) {
                check0(a.router().send("d", J2jWire.CLASS_RELIABLE, TYPE, 7, ("m" + i).getBytes(StandardCharsets.UTF_8), null));
            }
            // what the old d got but had not acknowledged comes again (at least once across a receiver restart)
            check("restarted receiver: the new messages arrive, in order, after any unacknowledged older ones",
                    J2jSelfTest.await(() -> sd2.got.contains("a:7:m1300"), 15000) && endsInOrder(sd2, "a:7:", 1251, 1300));
        } finally {
            for (J2jNode n : all) {
                n.stop();
            }
        }
    }

    private static void rekey() throws Exception {
        int pa = J2jSelfTest.freePort(), pb = J2jSelfTest.freePort();
        Path d = J2jSelfTest.files("rekey-net", "member.a.addresses=127.0.0.1:" + pa + "\nmember.b.addresses=127.0.0.1:" + pb + "\n",
                J2jSelfTest.key("k1", J2jSelfTest.KEY1));
        J2jNode a = J2jNode.start(cfg(d, "a", pa, "0.05"), "SimWeaver test");         // 3 s
        J2jNode b = J2jNode.start(cfg(d, "b", pb, "0.05"), "SimWeaver test");
        Sink sb = new Sink();
        b.router().register(0x0100, 0x01FF, sb);
        long downsBefore = J2jSelfTest.events("j2j.link.down");
        try {
            check("rekey pair linked", J2jSelfTest.await(() -> a.links().link("b") != null && a.router().route("b") != null, 8000));
            Link first = a.links().link("b");
            Set<Link> seen = new HashSet<Link>();
            for (int i = 1; i <= 800; i++) {
                while (!a.router().send("b", J2jWire.CLASS_RELIABLE, TYPE, 3, ("k" + i).getBytes(StandardCharsets.UTF_8), null)) {
                    sleep(5);
                }
                Link l = a.links().link("b");
                if (l != null) {
                    seen.add(l);
                }
                sleep(10);
            }
            check("re-keyed while sending: new links replaced the first (" + seen.size() + " links)", seen.size() >= 2 && a.links().link("b") != first);
            check("re-keying: no link-down event " + J2jSelfTest.eventTexts("j2j.link.down"), J2jSelfTest.events("j2j.link.down") == downsBefore);
            check("re-keying: 800 messages, once, in order", J2jSelfTest.await(() -> sb.count("a:3:") >= 800, 10000) && inOrder(sb, "a:3:", 1, 800));
        } finally {
            a.stop();
            b.stop();
        }
    }

    static Router.Route route(J2jNode n, String dst) {
        return n.router().route(dst);
    }

    static boolean inOrder(Sink s, String prefix, int from, int to) {
        List<String> l = new ArrayList<String>();
        for (String g : s.got) {
            if (g.startsWith(prefix)) {
                l.add(g.substring(prefix.length()));
            }
        }
        if (l.size() != to - from + 1) {
            System.out.println("  got " + l.size() + " of " + (to - from + 1) + (l.isEmpty() ? "" : ", first " + l.get(0) + ", last " + l.get(l.size() - 1)));
            return false;
        }
        for (int i = 0; i < l.size(); i++) {
            if (!l.get(i).matches("[a-z]" + (from + i))) {
                System.out.println("  at " + i + ": " + l.get(i));
                return false;
            }
        }
        return true;
    }

    /** The messages {@code from}..{@code to} are the last ones, in order; before them only older numbers, ascending. */
    static boolean endsInOrder(Sink s, String prefix, int from, int to) {
        List<Integer> l = new ArrayList<Integer>();
        for (String g : s.got) {
            if (g.startsWith(prefix)) {
                l.add(Integer.parseInt(g.substring(prefix.length() + 1)));
            }
        }
        int n = to - from + 1;
        if (l.size() < n) {
            return false;
        }
        for (int i = 0; i < l.size(); i++) {
            int expect = i - (l.size() - n);
            if (expect >= 0 ? l.get(i) != from + expect : (l.get(i) >= from || (i > 0 && l.get(i) <= l.get(i - 1)))) {
                System.out.println("  at " + i + ": " + l.get(i));
                return false;
            }
        }
        return true;
    }

    static boolean failsSoon(java.util.concurrent.CompletableFuture<Double> f) {
        try {
            f.get(2, TimeUnit.SECONDS);
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void check0(boolean ok) {
        if (!ok) {
            failures++;
            checks++;
            System.out.println("FAIL: a send was refused");
        }
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    static void unused() {
        NetMsgs.sequence(1, 1);
    }
}
