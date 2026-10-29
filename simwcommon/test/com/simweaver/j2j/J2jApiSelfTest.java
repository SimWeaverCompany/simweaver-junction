package com.simweaver.j2j;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.rest.JsonParser;
import com.simweaver.common.rest.XrResultPair;
import com.simweaver.j2j.api.J2jApi;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The J2J REST API (design §13) on two servers in one JVM: off, switched on by a reload, the calls
 * of the J2J page, their errors, and reloads with a bad and a changed member list.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.j2j.J2jApiSelfTest</pre>
 */
public final class J2jApiSelfTest {

    private static int checks;
    private static int failures;

    private J2jApiSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        J2jSelfTest.captureEvents();
        int pa = J2jSelfTest.freePort();
        int pb = J2jSelfTest.freePort();
        int pc = J2jSelfTest.freePort();
        String keys = J2jSelfTest.key("k1", J2jSelfTest.KEY1);
        Path da = J2jSelfTest.files(null, null, keys);
        Path db = J2jSelfTest.files(null, null, keys);
        String members = "network.name=API test\nmember.a.name=Alpha\nmember.a.addresses=127.0.0.1:" + pa + "\nmember.a.site=North\n"
                + "member.b.name=Bravo\nmember.b.addresses=127.0.0.1:" + pb + "\nmember.b.site=South\n";

        J2jService a = new J2jService(props("a", pa), da.resolve("network.settings"), da.resolve("network.key"), "SimWeaver test");
        J2jService b = new J2jService(props("b", pb), db.resolve("network.settings"), db.resolve("network.key"), "SimWeaver test");
        J2jApi api = new J2jApi(a);
        try {
            a.start();

            // ---- off
            Map<String, Object> s = obj(call(api, "GET", "/api/j2j", 200));
            check("off: state", "off".equals(s.get("state")) && s.get("how") != null && s.get("network") == null);
            check("off: the members are J2J_OFF", code(call(api, "GET", "/api/j2j/members", 409)).equals("J2J_OFF"));
            check("off: topology is J2J_OFF", code(call(api, "GET", "/api/j2j/topology", 409)).equals("J2J_OFF"));
            Map<String, Object> k = obj(call(api, "POST", "/api/j2j/key/new", 200));
            String line = String.valueOf(k.get("line"));
            check("a new key: 32 random octets, not installed", Base64.getDecoder().decode(String.valueOf(k.get("key"))).length == 32
                    && line.equals(k.get("keyid") + "=" + k.get("key")) && String.valueOf(k.get("fingerprint")).length() == 8
                    && !String.valueOf(k.get("key")).equals(Base64.getEncoder().encodeToString(J2jSelfTest.KEY1)));
            check("GET /j2j/key/new is 405", code(call(api, "GET", "/api/j2j/key/new", 405)).equals("METHOD_NOT_ALLOWED"));
            check("POST /j2j is 405", code(call(api, "POST", "/api/j2j", 405)).equals("METHOD_NOT_ALLOWED"));

            // ---- switched on by a reload
            write(da, "network.id=api-net\n" + members);
            write(db, "network.id=api-net\n" + members);
            b.start();
            Map<String, Object> r = obj(call(api, "POST", "/api/j2j/members/reload", 200));
            check("reload: on, running", "running".equals(r.get("state")) && Boolean.TRUE.equals(r.get("success")));
            check("b becomes direct", J2jSelfTest.await(() -> "direct".equals(member(api, "b").get("state")), 10000));
            check("the admin plane has b's status", J2jSelfTest.await(() -> a.statuses().containsKey("b"), 15000));

            s = obj(call(api, "GET", "/api/j2j", 200));
            Map<String, Object> net = map(s.get("network"));
            Map<String, Object> counts = map(s.get("counts"));
            Map<String, Object> listen = map(s.get("listen"));
            check("status: running, self, network", "running".equals(s.get("state")) && "a".equals(map(s.get("self")).get("id"))
                    && "Alpha".equals(map(s.get("self")).get("name")) && "api-net".equals(net.get("id")) && "API test".equals(net.get("name")));
            check("status: key id and fingerprint, never the key", "k1".equals(net.get("keyid")) && String.valueOf(net.get("keyfingerprint")).length() == 8
                    && !call(api, "GET", "/api/j2j", 200).contains(Base64.getEncoder().encodeToString(J2jSelfTest.KEY1)));
            check("status: both member lists agree", num(net.get("known")) == 2 && num(net.get("agree")) == 2 && num(net.get("members")) == 2);
            check("status: counts", num(counts.get("direct")) == 1 && num(counts.get("routed")) == 0 && num(counts.get("notreachable")) == 0);
            check("status: listens on the port", num(listen.get("tcp")) == pa);

            List<Object> ms = list(obj(call(api, "GET", "/api/j2j/members", 200)).get("members"));
            Map<String, Object> ma = map(ms.get(0));
            Map<String, Object> mb = map(ms.get(1));
            check("members: this server first", ms.size() == 2 && "a".equals(ma.get("id")) && "self".equals(ma.get("state"))
                    && Boolean.TRUE.equals(ma.get("self")) && "North".equals(ma.get("site")));
            List<Object> msb = list(obj(call(new J2jApi(b), "GET", "/api/j2j/members", 200)).get("members"));
            check("members on b: b first, then the file's order", msb.size() == 2 && "b".equals(map(msb.get(0)).get("id"))
                    && "a".equals(map(msb.get(1)).get("id")));
            check("members: b direct, with address, path and hops", "direct".equals(mb.get("state")) && ("127.0.0.1:" + pb).equals(mb.get("address"))
                    && list(mb.get("path")).size() == 2 && num(mb.get("hops")) == 1 && "out".equals(mb.get("direction")));
            check("members: b's channel, ping and member list", "tcp".equals(map(list(mb.get("channels")).get(0)).get("kind"))
                    && Boolean.TRUE.equals(mb.get("encrypted")) && "same".equals(mb.get("memberlist"))
                    && J2jSelfTest.await(() -> member(api, "b").get("rttms") instanceof Number, 5000));

            Map<String, Object> one = map(obj(call(api, "GET", "/api/j2j/members/b", 200)).get("member"));
            Map<String, Object> cls = map(one.get("classes"));
            check("one member: traffic per class, attempts, its status", cls.containsKey("admin") && cls.containsKey("reliable")
                    && cls.containsKey("besteffort") && one.get("attempts") instanceof List && one.get("node") != null
                    && "Bravo".equals(map(one.get("node")).get("name")));
            check("unknown member: 404", code(call(api, "GET", "/api/j2j/members/zz", 404)).equals("UNKNOWN_MEMBER"));

            Map<String, Object> p = obj(call(api, "POST", "/api/j2j/members/b/ping", 200));
            check("ping b", num(p.get("rttms")) >= 0 && list(p.get("path")).size() == 1 && "b".equals(map(list(p.get("path")).get(0)).get("id")));
            check("ping this server: 409", code(call(api, "POST", "/api/j2j/members/a/ping", 409)).equals("UNREACHABLE"));
            check("GET ping: 405", code(call(api, "GET", "/api/j2j/members/b/ping", 405)).equals("METHOD_NOT_ALLOWED"));

            Map<String, Object> t = obj(call(api, "GET", "/api/j2j/topology", 200));
            check("topology: sites, nodes", list(t.get("sites")).size() == 2 && list(t.get("nodes")).size() == 2);
            check("topology: the link a-b, up, ours", J2jSelfTest.await(() -> {
                List<Object> ls = list(obj(call(api, "GET", "/api/j2j/topology", 200)).get("links"));
                return ls.size() == 1 && "a".equals(map(ls.get(0)).get("a")) && "b".equals(map(ls.get(0)).get("b"))
                        && "up".equals(map(ls.get(0)).get("state")) && Boolean.TRUE.equals(map(ls.get(0)).get("ours"));
            }, 5000));
            check("sessions: none yet", list(obj(call(api, "GET", "/api/j2j/sessions", 200)).get("sessions")).isEmpty());

            List<Object> ev = list(obj(call(api, "GET", "/api/j2j/events?limit=50", 200, Collections.singletonMap("limit", "50"))).get("events"));
            boolean linkUp = false;
            for (Object o : ev) {
                linkUp |= "j2j.link.up".equals(map(o).get("event"));
            }
            check("events: j2j.link.up, with time and level", linkUp && map(ev.get(0)).get("at") != null && map(ev.get(0)).get("level") != null);
            check("events: limit", list(obj(call(api, "GET", "/api/j2j/events?limit=1", 200, Collections.singletonMap("limit", "1"))).get("events")).size() == 1);
            check("events: a bad limit is 400", code(call(api, "GET", "/api/j2j/events?limit=x", 400, Collections.singletonMap("limit", "x"))).equals("BAD_REQUEST"));
            check("an unknown call: 404", code(call(api, "GET", "/api/j2j/nothing", 404)).equals("NOT_FOUND"));

            // ---- a member list with a problem is not applied while J2J runs
            write(da, "network.id=api-net\nmember.b.addresses=127.0.0.1:" + pb + "\n");
            String bad = call(api, "POST", "/api/j2j/members/reload", 422);
            check("reload of a bad list: 422, the running list stays", code(bad).equals("MEMBERS_INVALID")
                    && !list(obj(bad).get("problems")).isEmpty() && "direct".equals(member(api, "b").get("state")));

            // ---- a changed list: c added
            write(da, "network.id=api-net\n" + members + "member.c.addresses=127.0.0.1:" + pc + "\n");
            r = obj(call(api, "POST", "/api/j2j/members/reload", 200));
            check("reload: c added", list(r.get("added")).size() == 1 && "c".equals(list(r.get("added")).get(0)) && list(r.get("removed")).isEmpty());
            check("after the reload: b direct again, c not reachable", J2jSelfTest.await(() -> "direct".equals(member(api, "b").get("state")), 10000)
                    && J2jSelfTest.await(() -> {
                        Object st = member(api, "c").get("state");
                        return "waiting".equals(st) || "connecting".equals(st);
                    }, 5000));
            check("ping c: 409 UNREACHABLE", code(call(api, "POST", "/api/j2j/members/c/ping", 409)).equals("UNREACHABLE"));
            check("status: the member lists differ now", J2jSelfTest.await(() -> {
                Map<String, Object> n2 = map(obj(call(api, "GET", "/api/j2j", 200)).get("network"));
                return num(n2.get("agree")) == 1 && num(n2.get("known")) == 2;
            }, 5000) && "different".equals(member(api, "b").get("memberlist")));

            // ---- off again
            write(da, "");
            r = obj(call(api, "POST", "/api/j2j/members/reload", 200));
            check("reload of an empty list: off", "off".equals(r.get("state")) && "off".equals(obj(call(api, "GET", "/api/j2j", 200)).get("state")));
        } finally {
            a.stop();
            b.stop();
        }
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    static SimwProperties props(String id, int port) {
        return new SimwProperties().set("j2j.node.id", id).set("j2j.port", String.valueOf(port)).set("j2j.deadseconds", "3")
                .set("j2j.udp", "false").set("j2j.fallback.port", "0");
    }

    static void write(Path dir, String text) throws Exception {
        Files.write(dir.resolve("network.settings"), text.getBytes(StandardCharsets.UTF_8));
    }

    static String call(J2jApi api, String method, String uri, int status) {
        return call(api, method, uri, status, new HashMap<String, String>());
    }

    static String call(J2jApi api, String method, String uri, int status, Map<String, String> query) {
        XrResultPair r = api.handleCallToJson(method, uri, "/api", query, null);
        if (r.getValue() != status) {
            System.out.println("  " + method + " " + uri + ": HTTP " + r.getValue() + " (expected " + status + "): " + r.getText());
        }
        return r.getValue() == status ? r.getText() : "{}";
    }

    static Map<String, Object> member(J2jApi api, String id) {
        return map(obj(api.handleCallToJson("GET", "/api/j2j/members/" + id, "/api", new HashMap<String, String>(), null).getText()).get("member"));
    }

    static Map<String, Object> obj(String json) {
        return JsonParser.parseObject(json);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Collections.<String, Object>emptyMap();
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : Collections.emptyList();
    }

    static double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : Double.NaN;
    }

    static String code(String json) {
        return String.valueOf(map(obj(json).get("error")).get("code"));
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
