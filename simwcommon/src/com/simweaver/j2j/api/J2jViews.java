package com.simweaver.j2j.api;

import com.simweaver.common.json.Json;
import com.simweaver.common.logging.Events;
import com.simweaver.j2j.J2jNode;
import com.simweaver.j2j.J2jService;
import com.simweaver.j2j.admin.AdminPlane;
import com.simweaver.j2j.config.J2jConfig;
import com.simweaver.j2j.config.J2jMember;
import com.simweaver.j2j.crypto.J2jCrypto;
import com.simweaver.j2j.link.Link;
import com.simweaver.j2j.link.LinkManager;
import com.simweaver.j2j.route.NetMsgs;
import com.simweaver.j2j.route.Router;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.LogRecord;

/** The JSON of the J2J REST API (design §13). */
public final class J2jViews {

    /** Who uses a member: this server's gateways in sessions with it (filled by the session layer). */
    public interface Usage {
        /** @return {gateway id, config id, instance, session, remote gateways} per local gateway sharing a session with {@code member} */
        List<Map<String, Object>> gatewaysWith(String member);

        /** @return the sessions {@code member} takes part in */
        List<String> sessionsOf(String member);

        /** @return every session as JSON-ready maps (design §13.7) */
        List<Map<String, Object>> sessions();
    }

    static final Usage NO_USAGE = new Usage() {
        @Override
        public List<Map<String, Object>> gatewaysWith(String member) {
            return new ArrayList<Map<String, Object>>();
        }

        @Override
        public List<String> sessionsOf(String member) {
            return new ArrayList<String>();
        }

        @Override
        public List<Map<String, Object>> sessions() {
            return new ArrayList<Map<String, Object>>();
        }
    };

    private final J2jService service;
    private volatile Usage usage = NO_USAGE;
    private final Map<String, long[]> rateBase = new HashMap<String, long[]>();     // member -> {at, bytesIn, bytesOut}
    private final Map<String, double[]> rates = new HashMap<String, double[]>();    // member -> {in kbps, out kbps}

    public J2jViews(J2jService service) {
        this.service = service;
    }

    public void setUsage(Usage u) {
        this.usage = u == null ? NO_USAGE : u;
    }

    static String iso(long ms) {
        return ms <= 0 ? null : Instant.ofEpochMilli(ms).toString();
    }

    // ---- GET /j2j ---------------------------------------------------------------------------

    public Json.JsonObject status() {
        J2jNode n = service.node();
        Json.JsonObject o = Json.object();
        String state = n == null ? "off" : n.state();
        o.put("state", state);
        J2jConfig cfg = n == null ? null : n.config();
        o.put("problems", cfg == null ? new ArrayList<String>() : cfg.problems());
        if ("off".equals(state)) {
            o.put("how", "List the members in etc/j2j/network.settings, put the network key in etc/j2j/network.key and set "
                    + "j2j.node.id in etc/server.root.settings (install guide 2.8)");
        }
        if (cfg != null && cfg.isConfigured()) {
            J2jMember self = cfg.self();
            o.put("self", Json.object().put("id", cfg.selfId()).put("name", self == null ? cfg.selfId() : self.name()));
            J2jConfig.Key k = cfg.preferredKey();
            int agree = 0;
            int known = 0;
            if (n.isRunning()) {
                for (LinkManager.PeerView pv : n.links().peerViews()) {
                    String h = pv.peerMemberHash;
                    AdminPlane.NodeStatus ns = service.statuses().get(pv.id);
                    if (h == null && ns != null) {
                        h = ns.memberHash;
                    }
                    if (h != null) {
                        known++;
                        agree += h.equals(cfg.memberHash()) ? 1 : 0;
                    }
                }
            }
            o.put("network", Json.object().put("id", cfg.networkId()).put("name", cfg.networkName())
                    .put("keyid", k == null ? null : k.id).put("keyfingerprint", k == null ? null : k.fingerprint())
                    .put("keys", cfg.keys().size()).put("memberhash", cfg.memberHash()).put("members", cfg.members().size())
                    .put("known", known + 1).put("agree", agree + 1));
            o.put("listen", Json.object().put("tcp", n.isRunning() && n.links().localPort() > 0 ? n.links().localPort() : null)
                    .put("udp", cfg.udpEnabled() ? cfg.port() : null)
                    .put("fallback", n.isRunning() && n.links().fallbackPort() > 0 ? n.links().fallbackPort() : null)
                    .put("fallbackproxy", cfg.fallbackProxy().isEmpty() ? null : cfg.fallbackProxy())
                    .put("problems", n.isRunning() ? listenProblems(n.links()) : new ArrayList<String>()));
            int direct = 0, routed = 0, down = 0;
            for (Json.JsonObject m : memberObjects(false)) {
                String s = String.valueOf(get(m, "state"));
                if ("direct".equals(s)) {
                    direct++;
                } else if ("routed".equals(s)) {
                    routed++;
                } else if (!"self".equals(s)) {
                    down++;
                }
            }
            o.put("counts", Json.object().put("direct", direct).put("routed", routed).put("notreachable", down)
                    .put("sessions", usage.sessions().size()));
        }
        o.put("now", iso(System.currentTimeMillis()));
        return o;
    }

    private static List<String> listenProblems(LinkManager l) {
        List<String> p = new ArrayList<String>();
        if (l.listenError() != null) {
            p.add(l.listenError());
        }
        if (l.fallbackError() != null) {
            p.add(l.fallbackError());
        }
        return p;
    }

    private static Object get(Json.JsonObject o, String key) {
        return o.get(key);
    }

    // ---- GET /j2j/members -------------------------------------------------------------------

    public Json.JsonObject members() {
        return Json.object().put("members", memberObjects(false));
    }

    public Json.JsonObject member(String id) {
        for (Json.JsonObject m : memberObjects(true)) {
            if (id.equals(get(m, "id"))) {
                return Json.object().put("member", m);
            }
        }
        return null;
    }

    synchronized List<Json.JsonObject> memberObjects(boolean detail) {
        List<Json.JsonObject> out = new ArrayList<Json.JsonObject>();
        J2jNode n = service.node();
        if (n == null || !n.config().isConfigured()) {
            return out;
        }
        J2jConfig cfg = n.config();
        boolean running = n.isRunning();
        Map<String, Router.Route> routes = running ? n.router().routes() : new HashMap<String, Router.Route>();
        long now = System.currentTimeMillis();
        List<J2jMember> ordered = new ArrayList<J2jMember>(cfg.members().values());
        J2jMember selfMember = cfg.member(cfg.selfId());
        if (selfMember != null) {
            ordered.remove(selfMember);
            ordered.add(0, selfMember);                       // this server first, then as the file lists them
        }
        for (J2jMember m : ordered) {
            Json.JsonObject o = Json.object();
            boolean self = m.id().equals(cfg.selfId());
            o.put("id", m.id()).put("name", m.name()).put("site", m.site()).put("self", self);
            List<String> addrs = new ArrayList<String>();
            for (J2jMember.Address a : m.addresses()) {
                addrs.add(a.toString());
            }
            o.put("addresses", addrs).put("fallback", m.fallback() == null ? null : m.fallback().toString()).put("connect", m.connect());
            LinkManager.PeerView pv = running && !self ? n.links().peerView(m.id()) : null;
            Link link = pv == null ? null : pv.link;
            Router.Route route = routes.get(m.id());
            AdminPlane.NodeStatus ns = service.statuses().get(m.id());
            String state;
            String message = null;
            if (self) {
                state = "self";
            } else if (!running) {
                state = "notstarted";
            } else if (link != null) {
                state = "direct";
            } else if (route != null) {
                state = "routed";
            } else if (pv.refused != null) {
                state = "refused";
                message = pv.refused;
            } else if (pv.dialing) {
                state = "connecting";
            } else if (pv.dialable) {
                state = "waiting";
                message = pv.lastError;
            } else {
                state = pv.lastSeen > 0 ? "unreachable" : "notdialled";
                message = m.addresses().isEmpty() ? "no address: waits for it to dial in" : "connect=false";
            }
            o.put("state", state).put("statemessage", message);
            if (pv != null && "waiting".equals(state)) {
                o.put("retryseconds", Math.round(pv.retryInMs / 1000.0));
            }
            o.put("since", link != null ? iso(link.upSince()) : null);
            o.put("address", link != null ? link.address() : null);
            o.put("direction", link != null ? (link.dialedBySelf() ? "out" : "in") : null);
            o.put("via", route != null && link == null ? route.nextHop : null);
            List<String> path = new ArrayList<String>();
            path.add(cfg.selfId());
            if (route != null) {
                path.addAll(route.path);
            } else if (self) {
                path.clear();
            }
            Integer hops = route != null ? Integer.valueOf(route.path.size()) : link != null ? Integer.valueOf(1) : null;
            o.put("path", path).put("hops", hops);
            List<Json.JsonObject> ch = new ArrayList<Json.JsonObject>();
            if (link != null) {
                ch.add(Json.object().put("kind", link.kind()).put("address", link.address()).put("state", "up").put("since", iso(link.upSince())));
                if (!"none".equals(link.udpState())) {
                    ch.add(Json.object().put("kind", "udp").put("address", link.udpAddress()).put("state", link.udpState()));
                }
            }
            o.put("channels", ch).put("encrypted", link != null || route != null ? Boolean.TRUE : null);
            Double rtt = null;
            if (link != null && link.rttMs() >= 0) {
                rtt = round1(link.rttMs());
            } else if (route != null && running) {
                rtt = pathRtt(n.router(), cfg.selfId(), route);
            }
            o.put("rttms", rtt);
            o.put("lossratio", link != null && link.udpLoss() >= 0 ? round3(link.udpLoss()) : null);
            Long offset = null;
            if (link != null) {
                offset = link.clockOffsetMs();
            } else if (ns != null && ns.sentAt > 0 && rtt != null) {
                offset = ns.sentAt - (ns.receivedAt - Math.round(rtt / 2));
            }
            o.put("clockoffsetms", self ? null : offset);
            String software = pv != null && pv.peerSoftware != null ? pv.peerSoftware : ns == null ? null : ns.software;
            o.put("version", self ? n.software() : software);
            String hash = pv != null && pv.peerMemberHash != null ? pv.peerMemberHash : ns == null ? null : ns.memberHash;
            o.put("memberlist", self ? "same" : hash == null ? "unknown" : hash.equals(cfg.memberHash()) ? "same" : "different");
            long seen = Math.max(pv == null ? 0 : pv.lastSeen, ns == null ? 0 : ns.receivedAt);
            o.put("lastseen", self ? iso(now) : iso(seen));
            double[] r = link == null ? null : rate(m.id(), link, now);
            o.put("rate", Json.object().put("inkbps", r == null ? null : round1(r[0])).put("outkbps", r == null ? null : round1(r[1])));
            List<String> transit = new ArrayList<String>();
            for (Router.Route x : routes.values()) {
                if (x.nextHop.equals(m.id()) && !x.destination.equals(m.id())) {
                    transit.add(x.destination);
                }
            }
            o.put("users", Json.object().put("gateways", usage.gatewaysWith(m.id())).put("sessions", usage.sessionsOf(m.id()))
                    .put("transitfor", transit));
            if (detail) {
                List<Json.JsonObject> att = new ArrayList<Json.JsonObject>();
                if (pv != null) {
                    for (String[] a : pv.attempts) {
                        att.add(Json.object().put("address", a[0]).put("kind", a[1]).put("at", iso(Long.parseLong(a[2])))
                                .put("result", a[3]).put("ok", Boolean.parseBoolean(a[4])));
                    }
                }
                o.put("attempts", att);
                if (link != null) {
                    long[] c = link.counters();
                    int[] q = link.queued();
                    o.put("classes", Json.object()
                            .put("admin", Json.object().put("outmsgs", c[4]).put("inmsgs", c[7]).put("queued", q[0]))
                            .put("reliable", Json.object().put("outmsgs", c[5]).put("inmsgs", c[8]).put("queued", q[1])
                                    .put("unacked", n.router().pending(m.id())[0]))
                            .put("besteffort", Json.object().put("outmsgs", c[6]).put("inmsgs", c[9]).put("queued", q[2])
                                    .put("dropped", c[10] + c[12]).put("superseded", c[11]).put("udpout", c[14]).put("udpin", c[15])));
                    List<Double> hist = new ArrayList<Double>();
                    for (double h : link.rttHistory()) {
                        hist.add(round1(h));
                    }
                    o.put("rtthistory", hist);
                } else {
                    o.put("classes", null).put("rtthistory", new ArrayList<Double>());
                }
                o.put("node", ns == null ? null : Json.object().put("name", ns.name).put("version", ns.software)
                        .put("started", iso(ns.started)).put("cpu", ns.cpuPercent < 0 ? null : ns.cpuPercent)
                        .put("heapused", ns.heapUsed < 0 ? null : ns.heapUsed).put("heapmax", ns.heapMax < 0 ? null : ns.heapMax)
                        .put("gateways", ns.gateways).put("sessions", ns.sessions).put("received", iso(ns.receivedAt)));
                o.put("lasterror", pv == null ? null : pv.lastError).put("lasterrortime", pv == null ? null : iso(pv.lastErrorAt));
                o.put("pending", running && !self ? n.router().pending(m.id())[0] : null);
            }
            out.add(o);
        }
        return out;
    }

    private double[] rate(String id, Link link, long now) {
        long[] c = link.counters();
        long[] base = rateBase.get(id);
        double[] r = rates.get(id);
        if (base == null || now - base[0] >= 2000 || base[3] != System.identityHashCode(link)) {
            if (base != null && base[3] == System.identityHashCode(link) && now > base[0]) {
                double secs = (now - base[0]) / 1000.0;
                r = new double[] {(c[3] - base[1]) * 8 / 1000.0 / secs, (c[2] - base[2]) * 8 / 1000.0 / secs};
                rates.put(id, r);
            }
            rateBase.put(id, new long[] {now, c[3], c[2], System.identityHashCode(link)});
        }
        return r;
    }

    /** RTT of a route: the sum of the advertised link RTTs along it. */
    private static Double pathRtt(Router router, String self, Router.Route route) {
        Map<String, NetMsgs.Lsa> db = router.lsdb();
        double sum = 0;
        String u = self;
        for (String v : route.path) {
            NetMsgs.Lsa l = db.get(u);
            long[] e = l == null ? null : l.neighbours.get(v);
            if (e == null) {
                return null;
            }
            sum += e[1] / 1000.0;
            u = v;
        }
        return round1(sum);
    }

    static Double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    static Double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    // ---- GET /j2j/topology ------------------------------------------------------------------

    public Json.JsonObject topology() {
        J2jNode n = service.node();
        Json.JsonObject o = Json.object();
        List<Json.JsonObject> nodes = new ArrayList<Json.JsonObject>();
        List<Json.JsonObject> links = new ArrayList<Json.JsonObject>();
        Set<String> sites = new LinkedHashSet<String>();
        if (n != null && n.config().isConfigured()) {
            for (Json.JsonObject m : memberObjects(false)) {
                Object site = get(m, "site");
                if (site != null) {
                    sites.add(site.toString());
                }
                nodes.add(Json.object().put("id", get(m, "id")).put("name", get(m, "name")).put("site", site)
                        .put("state", get(m, "state")).put("via", get(m, "via")).put("rttms", get(m, "rttms")));
            }
            if (n.isRunning()) {
                String self = n.config().selfId();
                Map<String, NetMsgs.Lsa> db = n.router().lsdb();
                Set<String> done = new LinkedHashSet<String>();
                for (NetMsgs.Lsa l : db.values()) {
                    for (Map.Entry<String, long[]> e : l.neighbours.entrySet()) {
                        String a = l.origin;
                        String b = e.getKey();
                        String key = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
                        NetMsgs.Lsa other = db.get(b);
                        boolean twoWay = other != null && other.neighbours.containsKey(a);
                        if (!twoWay || !done.add(key)) {
                            continue;
                        }
                        boolean ours = a.equals(self) || b.equals(self);
                        Link lk = ours ? n.links().link(a.equals(self) ? b : a) : null;
                        List<String> ch = new ArrayList<String>();
                        if (lk != null) {
                            ch.add(lk.kind());
                            if ("up".equals(lk.udpState())) {
                                ch.add("udp");
                            }
                        }
                        links.add(Json.object().put("a", key.split("\\|")[0]).put("b", key.split("\\|")[1]).put("state", "up")
                                .put("channels", ch).put("rttms", round1(e.getValue()[1] / 1000.0)).put("cost", e.getValue()[0]).put("ours", ours));
                    }
                }
                for (LinkManager.PeerView pv : n.links().peerViews()) {
                    String key = self.compareTo(pv.id) < 0 ? self + "|" + pv.id : pv.id + "|" + self;
                    if (done.contains(key) || pv.link != null || !pv.dialable && pv.refused == null) {
                        continue;
                    }
                    links.add(Json.object().put("a", key.split("\\|")[0]).put("b", key.split("\\|")[1])
                            .put("state", pv.refused != null ? "refused" : "waiting").put("reason", pv.refused != null ? pv.refused : pv.lastError)
                            .put("ours", true));
                }
            }
        }
        return o.put("sites", new ArrayList<String>(sites)).put("nodes", nodes).put("links", links);
    }

    // ---- sessions, events, key --------------------------------------------------------------

    public Json.JsonObject sessions() {
        return Json.object().put("sessions", usage.sessions());
    }

    public Json.JsonObject events(int limit) {
        List<Json.JsonObject> l = new ArrayList<Json.JsonObject>();
        for (LogRecord r : service.events(limit)) {
            l.add(event(r));
        }
        return Json.object().put("events", l);
    }

    static Json.JsonObject event(LogRecord r) {
        Events.Tag t = Events.tagOf(r);
        Json.JsonObject f = Json.object();
        if (t != null) {
            for (Map.Entry<String, String> e : t.fields().entrySet()) {
                f.put(e.getKey(), e.getValue());
            }
        }
        return Json.object().put("at", iso(r.getMillis())).put("level", r.getLevel().getName())
                .put("event", t == null ? null : t.event()).put("text", r.getMessage()).put("fields", f);
    }

    public Json.JsonObject newKey() {
        byte[] k = J2jCrypto.random(32);
        J2jNode n = service.node();
        Set<String> used = new LinkedHashSet<String>();
        if (n != null) {
            for (J2jConfig.Key x : n.config().keys()) {
                used.add(x.id);
            }
        }
        int i = 1;
        while (used.contains("k" + i)) {
            i++;
        }
        String b64 = Base64.getEncoder().encodeToString(k);
        return Json.object().put("success", true).put("key", b64).put("keyid", "k" + i)
                .put("fingerprint", J2jCrypto.fingerprint(k, 4)).put("line", "k" + i + "=" + b64);
    }
}
