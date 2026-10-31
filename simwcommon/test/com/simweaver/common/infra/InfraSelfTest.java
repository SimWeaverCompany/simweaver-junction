package com.simweaver.common.infra;

import com.simweaver.common.rest.Jsons;
import com.simweaver.common.rest.XrResultPair;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The Infra collector without a network or an RTI: the MOM datatypes, a DIS tap (bundled and
 * padded PDUs, versions, exercises, senders, site/application numbers, Live Entity ids, broken
 * datagrams, C-DIS), the activity meter, forgetting, the JSON and the REST API's errors.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.common.infra.InfraSelfTest</pre>
 */
public final class InfraSelfTest {

    private static int checks;
    private static int failures;

    private InfraSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        codec();
        meter();
        disTap();
        api();
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ MOM datatypes

    private static void codec() {
        byte[] s = unicode("Fed-1");
        check("HLAunicodeString", "Fed-1".equals(MomCodec.unicodeString(s)));
        check("HLAunicodeString too short", MomCodec.unicodeString(new byte[] {0, 0, 0, 9, 0, 65}) == null);
        check("HLAcount 32", MomCodec.count(new byte[] {0, 0, 1, 0}) == 256L);
        check("HLAcount 64", MomCodec.count(new byte[] {0, 0, 0, 0, 0, 0, 1, 1}) == 257L);
        check("HLAboolean", MomCodec.bool(new byte[] {0, 0, 0, 1}) && !MomCodec.bool(new byte[] {0, 0, 0, 0}));
        byte[] h = MomCodec.encodeHandle(new byte[] {1, 2, 3});
        check("HLAhandle round trip", Arrays.equals(MomCodec.handle(h), new byte[] {1, 2, 3}));
        // HLAhandleList of a 3-octet and a 4-octet handle: the second starts at a 4-octet boundary
        byte[] list = {0, 0, 0, 2, 0, 0, 0, 3, 9, 8, 7, 0, 0, 0, 0, 4, 1, 2, 3, 4};
        List<byte[]> l = MomCodec.handleList(list);
        check("HLAhandleList with padding", l.size() == 2 && Arrays.equals(l.get(0), new byte[] {9, 8, 7})
                && Arrays.equals(l.get(1), new byte[] {1, 2, 3, 4}));
        check("HLAhandleList cut short", MomCodec.handleList(Arrays.copyOf(list, 12)).size() == 1);
        byte[] sub = {0, 0, 0, 2, 0, 0, 0, 4, 0, 0, 0, 5, 0, 0, 0, 1, 0, 0, 0, 4, 0, 0, 0, 6, 0, 0, 0, 0};
        List<MomCodec.InteractionSub> subs = MomCodec.interactionSubList(sub);
        check("HLAinteractionSubList", subs.size() == 2 && subs.get(0).active && !subs.get(1).active
                && subs.get(1).handle[3] == 6);
        byte[] names = concat(new byte[] {0, 0, 0, 2}, pad4(unicode("A.xml")), unicode("B"));
        check("HLAmoduleDesignatorList", MomCodec.stringList(names).equals(Arrays.asList("A.xml", "B")));
        byte[] t = MomCodec.encodeHandle(longBytes(Double.doubleToLongBits(12.5)));
        check("float time", "12.5".equals(MomCodec.time(t, "HLAfloat64Time")));
        check("integer time", "4".equals(MomCodec.time(MomCodec.encodeHandle(longBytes(4)), "HLAinteger64Time")));
        check("whole float time", "3".equals(MomCodec.time(MomCodec.encodeHandle(longBytes(Double.doubleToLongBits(3.0))), null)));
        check("hex round trip", Arrays.equals(MomCodec.unhex(MomCodec.hex(new byte[] {0, 15, -1})), new byte[] {0, 15, -1}));
        check("bad hex", MomCodec.unhex("0g") == null && MomCodec.unhex("abc") == null);
    }

    // ------------------------------------------------------------------ meter

    private static void meter() {
        ActivityMeter m = new ActivityMeter();
        long t0 = 1_000_000_000L;                 // a whole second
        for (int s = 0; s < 6; s++) {
            m.add(t0 + s * 1000L + 10, 10, 1000);
        }
        check("rate over five whole seconds", Math.abs(m.rate(t0 + 6000) - 10) < 1e-9);
        check("octet rate", Math.abs(m.octetRate(t0 + 6000) - 1000) < 1e-9);
        int[] h = m.history(t0 + 6000);
        check("history length 59, newest last", h.length == 59 && h[58] == 10 && h[53] == 10 && h[52] == 0);
        check("totals", m.total() == 60 && m.totalOctets() == 6000);
        check("quiet after a minute", m.rate(t0 + 120_000) == 0 && m.history(t0 + 120_000)[58] == 0);
    }

    // ------------------------------------------------------------------ DIS tap

    private static void disTap() throws Exception {
        InfraRegistry reg = new InfraRegistry();
        DisTap tap = reg.openDis(InfraSource.rift("dis test", "1234"), false, 3000, "UDP port 3000 on all interfaces");
        tap.setNote("listens to every exercise");
        InetAddress a = InetAddress.getByName("192.0.2.10");
        InetAddress b = InetAddress.getByName("192.0.2.11");
        long now = System.currentTimeMillis();
        // one datagram with two DIS 7 entity states of exercise 1, the second padded to 8 octets
        byte[] es1 = pdu(7, 1, 1, 144, 7, 42, 1);
        byte[] es2 = pdu(7, 1, 1, 144, 7, 42, 2);
        byte[] fire = pdu(7, 1, 2, 96, 7, 43, 1);
        byte[] bundle = concat(es1, es2, fire);
        tap.datagram(a, 5000, bundle, 0, bundle.length, now);
        byte[] odd = pdu(7, 1, 22, 37, 7, 42, 9);       // a comment of 37 octets, then padding to 40, then a fire
        byte[] padded = concat(odd, new byte[3], fire);
        tap.datagram(a, 5000, padded, 0, padded.length, now);
        // DIS 6, exercise 3, from another sender; a Live Entity TSPI with 8-bit site and application
        tap.datagram(b, 6000, pdu(6, 3, 1, 144, 9, 1, 5), 0, 144, now);
        byte[] tspi = pdu(7, 1, 46, 40, 0, 0, 0);
        tspi[12] = 5;
        tspi[13] = 6;
        tspi[14] = 0;
        tspi[15] = 33;
        tap.datagram(b, 6000, tspi, 0, tspi.length, now);
        // broken: claims more octets than it has
        byte[] broken = pdu(7, 1, 1, 144, 1, 1, 1);
        tap.datagram(b, 6000, broken, 0, 60, now);

        Map<String, Object> o = parse(reg.overview().toString());
        List<?> dis7 = (List<?>) o.get("dis7");
        List<?> dis6 = (List<?>) o.get("dis6");
        check("one DIS 7 and one DIS 6 exercise", dis7.size() == 1 && dis6.size() == 1);
        Map<?, ?> ex1 = (Map<?, ?>) dis7.get(0);
        check("exercise 1, port, where, note", num(ex1.get("exercise")) == 1 && num(ex1.get("port")) == 3000
                && "UDP port 3000 on all interfaces".equals(ex1.get("where")) && "listens to every exercise".equals(ex1.get("note")));
        check("exercise 1: two senders, PDUs counted (bundle 3 + 2 + TSPI)", num(ex1.get("senders")) == 2 && num(ex1.get("pdus")) == 6);
        check("entities: two ES + one TSPI", num(ex1.get("entities")) == 3);
        check("active", "active".equals(ex1.get("status")));
        check("source", "rift".equals(((Map<?, ?>) ex1.get("source")).get("kind"))
                && "datasource.html?id=1234".equals(((Map<?, ?>) ex1.get("source")).get("link")));
        check("key family prefix", String.valueOf(ex1.get("key")).startsWith("dis7-"));
        check("DIS 6 exercise 3, version 6", num(((Map<?, ?>) dis6.get(0)).get("exercise")) == 3
                && num(((List<?>) ((Map<?, ?>) dis6.get(0)).get("versions")).get(0)) == 6);

        Map<?, ?> d = (Map<?, ?>) ((List<?>) parse(reg.details(Collections.singletonList(String.valueOf(ex1.get("key")))).toString())
                .get("items")).get(0);
        check("malformed datagram counted", num(d.get("malformed")) == 1 && num(d.get("datagrams")) == 5);
        List<?> senders = (List<?>) d.get("senderlist");
        Map<?, ?> sa = null;
        Map<?, ?> sb = null;
        for (Object x : senders) {
            Map<?, ?> s = (Map<?, ?>) x;
            if ("192.0.2.10".equals(s.get("address"))) {
                sa = s;
            } else {
                sb = s;
            }
        }
        check("sender a: port, not local", sa != null && num(sa.get("port")) == 5000 && Boolean.FALSE.equals(sa.get("local")));
        check("sender a: site/app 7:42 and 7:43", sa != null && siteApps(sa).equals(Arrays.asList("7:42", "7:43")));
        check("sender a: two entities, ES most frequent", sa != null && num(sa.get("entities")) == 2
                && "Entity State".equals(((Map<?, ?>) ((List<?>) sa.get("pdutypes")).get(0)).get("name")));
        check("sender b: Live Entity site/app 5:6", sb != null && siteApps(sb).equals(Collections.singletonList("5:6")));
        check("PDU type names", "Comment".equals(DisPduTypes.name(22)) && "TSPI".equals(DisPduTypes.name(46))
                && "PDU type 200".equals(DisPduTypes.name(200)));

        // forgetting
        tap.prune(now + 1000, 600_000);
        check("not forgotten early", ((List<?>) parse(reg.overview().toString()).get("dis7")).size() == 1);
        tap.prune(now + 700_000, 600_000);
        check("forgotten after the setting", ((List<?>) parse(reg.overview().toString()).get("dis7")).isEmpty());

        // C-DIS: decoded PDUs, the datagram's octets counted as traffic
        DisTap c = reg.openDis(InfraSource.junction("gw", "cdis"), true, 3100, "UDP port 3100");
        c.decoded(a, 7000, 30, Arrays.asList(pdu(7, 2, 1, 144, 3, 4, 1), pdu(7, 2, 2, 96, 3, 4, 1)), now);
        c.datagram(a, 7000, es1, 0, es1.length, now);      // a C-DIS tap ignores raw DIS
        List<?> cdis = (List<?>) parse(reg.overview().toString()).get("cdis");
        check("C-DIS exercise 2 from the decoded PDUs", cdis.size() == 1 && num(((Map<?, ?>) cdis.get(0)).get("exercise")) == 2
                && num(((Map<?, ?>) cdis.get(0)).get("pdus")) == 2 && num(((Map<?, ?>) cdis.get(0)).get("octets")) == 30);
        check("junction source", "junction:gw/cdis".equals(((Map<?, ?>) ((Map<?, ?>) cdis.get(0)).get("source")).get("key")));
        String key = String.valueOf(((Map<?, ?>) cdis.get(0)).get("key"));
        c.close();
        check("closed tap gone", ((List<?>) parse(reg.overview().toString()).get("cdis")).isEmpty());
        Map<?, ?> missing = (Map<?, ?>) ((List<?>) parse(reg.details(Collections.singletonList(key)).toString()).get("items")).get(0);
        check("a missing item is flagged", Boolean.TRUE.equals(missing.get("missing")));
        // the same identity gives the same key again (a breakout tab finds it after a restart)
        DisTap c2 = reg.openDis(InfraSource.junction("gw", "cdis"), true, 3100, "UDP port 3100");
        c2.decoded(a, 7000, 30, Collections.singletonList(pdu(7, 2, 1, 144, 3, 4, 1)), now);
        check("stable key", key.equals(((Map<?, ?>) ((List<?>) parse(reg.overview().toString()).get("cdis")).get(0)).get("key")));

        // switched off: taps ignore everything
        InfraRegistry off = new InfraRegistry();
        com.simweaver.common.config.SimwProperties p = new com.simweaver.common.config.SimwProperties();
        p.set("infra.enabled", "false");
        off.configure(p);
        DisTap t = off.openDis(InfraSource.rift("x", null), false, 3000, "");
        t.datagram(a, 1, es1, 0, es1.length, now);
        check("disabled: nothing listed", ((List<?>) parse(off.overview().toString()).get("dis7")).isEmpty()
                && Boolean.FALSE.equals(parse(off.overview().toString()).get("enabled")));
    }

    // ------------------------------------------------------------------ REST

    private static void api() {
        InfraRegistry reg = new InfraRegistry();
        InfraApi api = new InfraApi(reg);
        XrResultPair r = api.handleCallToJson("GET", "/api/infra", "/api", null, null);
        check("GET /infra 200", status(r) == 200 && body(r).contains("\"hla4\""));
        check("POST /infra 405", status(api.handleCallToJson("POST", "/api/infra", "/api", null, "{}")) == 405);
        check("items without keys 400", status(api.handleCallToJson("GET", "/api/infra/items", "/api", Collections.<String, String>emptyMap(), null)) == 400);
        check("unknown item 404", status(api.handleCallToJson("GET", "/api/infra/items/dis7-000000000000", "/api", null, null)) == 404);
        check("refresh unknown 404", status(api.handleCallToJson("POST", "/api/infra/items/hla3-000000000000/refresh", "/api", null, null)) == 404);
        check("declarations without federate 400", status(api.handleCallToJson("POST", "/api/infra/items/hla3-000000000000/declarations",
                "/api", null, "{}")) == 400);
        check("declarations bad body 400", status(api.handleCallToJson("POST", "/api/infra/items/hla3-000000000000/declarations",
                "/api", null, "[1]")) == 400);
        check("declarations unknown item 404", status(api.handleCallToJson("POST", "/api/infra/items/hla3-000000000000/declarations",
                "/api", null, "{\"federate\":\"0000002a\"}")) == 404);
        check("other path 404", status(api.handleCallToJson("GET", "/api/infra/nothing", "/api", null, null)) == 404);
        Map<String, String> q = new java.util.HashMap<String, String>();
        q.put("keys", "a,b");
        XrResultPair m = api.handleCallToJson("GET", "/api/infra/items?keys=a,b", "/api", q, null);
        check("items: both flagged missing", status(m) == 200 && body(m).split("\"missing\":true", -1).length == 3);
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> siteApps(Map<?, ?> sender) {
        List<String> out = new ArrayList<String>();
        for (Object x : (List<?>) sender.get("siteapps")) {
            Map<?, ?> m = (Map<?, ?>) x;
            out.add(num(m.get("site")) + ":" + num(m.get("app")));
        }
        return out;
    }

    /** A PDU of the given length with the header and the first entity id filled in. */
    static byte[] pdu(int version, int exercise, int type, int length, int site, int app, int entity) {
        byte[] b = new byte[length];
        b[0] = (byte) version;
        b[1] = (byte) exercise;
        b[2] = (byte) type;
        b[3] = 1;
        b[8] = (byte) (length >> 8);
        b[9] = (byte) length;
        if (length >= 18) {
            b[12] = (byte) (site >> 8);
            b[13] = (byte) site;
            b[14] = (byte) (app >> 8);
            b[15] = (byte) app;
            b[16] = (byte) (entity >> 8);
            b[17] = (byte) entity;
        }
        return b;
    }

    private static byte[] unicode(String s) {
        byte[] c = s.getBytes(StandardCharsets.UTF_16BE);
        return concat(new byte[] {0, 0, 0, (byte) s.length()}, c);
    }

    private static byte[] pad4(byte[] b) {
        return Arrays.copyOf(b, (b.length + 3) & ~3);
    }

    private static byte[] longBytes(long v) {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) {
            b[i] = (byte) v;
            v >>>= 8;
        }
        return b;
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    private static Map<String, Object> parse(String json) {
        return Jsons.fromJsonObject(json);
    }

    private static long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : -1;
    }

    private static int status(XrResultPair r) {
        return r.getValue();
    }

    private static String body(XrResultPair r) {
        return r.getText();
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }
}
