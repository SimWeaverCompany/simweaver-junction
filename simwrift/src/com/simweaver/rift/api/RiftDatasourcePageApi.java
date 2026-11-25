package com.simweaver.rift.api;

import com.simweaver.common.rest.Jsons;
import com.simweaver.common.rest.XrResultPair;
import com.simweaver.rift.db.RiftDatabase;
import com.simweaver.rift.db.RiftInstance;
import com.simweaver.rift.db.RiftInteraction;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * REST endpoints of the datasource page (contract sections 5, 6 and 8), on top of
 * {@link RiftDatabase} and {@link RiftJson}:
 *
 * <pre>
 *   GET /api/datasources/{dsid}/classes
 *   GET /api/datasources/{dsid}/objects?class=&amp;subclasses=&amp;q=&amp;related=&amp;ids=&amp;sort=&amp;offset=&amp;limit=
 *   GET /api/datasources/{dsid}/objects/{instanceid}?view=full|list
 *   GET /api/datasources/{dsid}/interactions?before=&amp;limit=&amp;class=&amp;subclasses=&amp;object=
 *   GET /api/datasources/{dsid}/interactions/{interactionid}
 *   GET /api/datasources/{dsid}/map?class=&amp;subclasses=&amp;q=&amp;related=&amp;ids=&amp;bbox=w,s,e,n&amp;limit=&amp;events=&amp;beams=&amp;lasers=
 *                                                    (RiftMapObjects; events: RiftMapEvents; beams: RiftMapBeams;
 *                                                    lasers: RiftMapLasers)
 * </pre>
 *
 * {@link #handle} answers these and returns null for every other path, so it plugs into an
 * existing API handler in one line ({@code r = pageApi.handle(...); if (r != null) return r;}).
 * It does not depend on the {@code ApiCallHandler} signatures. The datasource itself
 * ({@code GET/DELETE /api/datasources/{dsid}}) stays with the caller.
 *
 * <p>Errors use the envelope {@code {"success":false,"error":{"code","message"}}} with a real
 * status: 400 {@code BAD_REQUEST}, 404 {@code DATASOURCE_UNKNOWN} / {@code OBJECT_UNKNOWN} /
 * {@code INTERACTION_UNKNOWN}, 405 {@code METHOD_NOT_ALLOWED}.
 *
 * <p>Stateless and thread safe: every call reads the database's lock-free state. Each reply
 * carries the database {@code seq} read before the data (contract 7.4).
 */
public final class RiftDatasourcePageApi {

    public static final String DEFAULT_PREFIX = "/api/datasources/";
    public static final int OBJECTS_DEFAULT_LIMIT = 200;
    public static final int OBJECTS_MAX_LIMIT = 500;
    public static final int INTERACTIONS_DEFAULT_LIMIT = 200;
    public static final int INTERACTIONS_MAX_LIMIT = 1000;

    private final Function<String, RiftDatabase> databases;
    private final RiftListProfiles profiles;
    private final String prefix;
    private volatile boolean mapEnabled = true;

    /**
     * @param databases datasource id (as in the URL) to its database; null (or an exception) =
     *                  unknown. E.g. {@code id -> { DataSource ds = manager.find(id); return ds == null ? null : ds.getDatabase(); }}
     * @param profiles  the same list profiles the RiftEventPublisher uses, so REST and SSE rows match
     */
    public RiftDatasourcePageApi(Function<String, RiftDatabase> databases, RiftListProfiles profiles) {
        this(databases, profiles, DEFAULT_PREFIX);
    }

    /** @param prefix path prefix before the datasource id, e.g. "/api/datasources/" */
    public RiftDatasourcePageApi(Function<String, RiftDatabase> databases, RiftListProfiles profiles, String prefix) {
        if (databases == null) throw new IllegalArgumentException("databases must not be null");
        if (prefix == null || !prefix.startsWith("/") || !prefix.endsWith("/")) {
            throw new IllegalArgumentException("prefix must start and end with '/': " + prefix);
        }
        this.databases = databases;
        this.profiles = profiles != null ? profiles : RiftListProfiles.none();
        this.prefix = prefix;
    }

    /** NEW (map): false answers .../map with 404 MAP_DISABLED (server setting map.enabled). */
    public void setMapEnabled(boolean enabled) {
        this.mapEnabled = enabled;
    }

    /**
     * Answers a datasource page endpoint, or returns null when the path is not one of them.
     *
     * @param requestUri the request URI as XrRequestHandler passes it (path, optionally "?query")
     * @param query      the decoded query parameters (lists comma separated)
     */
    public XrResultPair handle(String method, String requestUri, Map<String, String> query) {
        List<String> seg = segments(requestUri);
        if (seg == null || seg.size() < 2 || seg.size() > 3) return null;
        String sub = seg.get(1);
        boolean objects = "objects".equals(sub), interactions = "interactions".equals(sub);
        boolean map = "map".equals(sub) && seg.size() == 2;
        if (!objects && !interactions && !map && !("classes".equals(sub) && seg.size() == 2)) return null;

        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            return error(405, "METHOD_NOT_ALLOWED", method + " is not supported here; use GET");
        }
        RiftDatabase db = database(seg.get(0));
        if (db == null) return error(404, "DATASOURCE_UNKNOWN", "No datasource " + seg.get(0));

        Map<String, String> q = query != null ? query : Collections.emptyMap();
        RiftJson json = new RiftJson(profiles, db.resolver());
        try {
            if (seg.size() == 2) {
                if (map) return map(db, json, q);
                if (objects) return objects(db, json, q);
                if (interactions) return interactions(db, json, q);
                return classes(db);
            }
            return objects ? object(db, json, seg.get(2), q) : interaction(db, json, seg.get(2));
        } catch (BadRequest e) {
            return error(400, "BAD_REQUEST", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ GET …/classes (contract 5)

    private XrResultPair classes(RiftDatabase db) {
        long seq = db.getSeq();
        List<Object> list = new ArrayList<>();
        int total = 0;
        for (Map.Entry<String, Integer> e : db.getClassCounts().entrySet()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("classnameq", e.getKey());
            c.put("count", e.getValue());
            list.add(c);
            total += e.getValue();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", seq);
        m.put("total", total);
        m.put("classes", list);
        return ok(m);
    }

    // ------------------------------------------------------------------ GET …/objects (contract 6.1)

    private XrResultPair objects(RiftDatabase db, RiftJson json, Map<String, String> q) {
        long seq = db.getSeq();
        int offset = intParam(q, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = intParam(q, "limit", OBJECTS_DEFAULT_LIMIT, 1, OBJECTS_MAX_LIMIT);
        String sort = q.getOrDefault("sort", "label");
        Comparator<Row> order = order(sort);

        List<RiftInstance> hits = matching(db, json, q);

        List<Row> rows = new ArrayList<>(hits.size());
        for (RiftInstance o : hits) rows.add(new Row(o));
        rows.sort(order);

        List<Object> page = new ArrayList<>();
        for (int i = offset; i < rows.size() && page.size() < limit; i++) page.add(json.listView(rows.get(i).o));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", seq);
        m.put("total", rows.size());
        m.put("offset", offset);
        m.put("limit", limit);
        m.put("objects", page);
        return ok(m);
    }

    /** The objects of the list filter (ids, or class + subclasses + q + related). */
    private List<RiftInstance> matching(RiftDatabase db, RiftJson json, Map<String, String> q) {
        List<RiftInstance> hits;
        List<String> ids = list(q.get("ids"));
        if (!ids.isEmpty()) {
            hits = new ArrayList<>();
            Set<RiftInstance> seen = new LinkedHashSet<>();
            for (String id : ids) {
                RiftInstance o = db.getInstance(id);
                if (o != null && seen.add(o)) hits.add(o);       // removed ones (still retained) included
            }
        } else {
            List<String> classes = list(q.get("class"));
            boolean subclasses = boolParam(q, "subclasses", true);
            String text = q.getOrDefault("q", "").trim().toLowerCase(Locale.ROOT);
            hits = db.getInstances(o -> !o.isRemoved() && classMatch(o.getClassName(), classes, subclasses)
                    && (text.isEmpty() || textMatch(o, text, json)));
            if (boolParam(q, "related", false)) hits = withReferrers(db, hits);
        }
        return hits;
    }

    // ------------------------------------------------------------------ GET …/map (NEW)

    private XrResultPair map(RiftDatabase db, RiftJson json, Map<String, String> q) {
        if (!mapEnabled) return error(404, "MAP_DISABLED", "The map is turned off on this server (map.enabled=false)");
        long seq = db.getSeq();
        int limit = intParam(q, "limit", RiftMapObjects.DEFAULT_LIMIT, 1, RiftMapObjects.MAX_LIMIT);
        double[] bbox = null;
        String b = q.get("bbox");
        if (b != null && !b.trim().isEmpty()) {
            String[] p = b.split(",");
            if (p.length != 4) throw new BadRequest("bbox must be west,south,east,north in degrees");
            bbox = new double[4];
            try {
                for (int i = 0; i < 4; i++) bbox[i] = Double.parseDouble(p[i].trim());
            } catch (NumberFormatException e) {
                throw new BadRequest("bbox must be west,south,east,north in degrees");
            }
        }
        List<RiftInstance> hits = new ArrayList<>();
        for (RiftInstance o : matching(db, json, q)) if (!o.isRemoved()) hits.add(o);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", seq);
        long now = System.currentTimeMillis();
        m.putAll(RiftMapObjects.build(db, hits, bbox, limit, now));
        String ev = q.get("events");
        if (ev != null && !ev.trim().isEmpty()) {                     // fire / detonation flashes
            m.putAll(RiftMapEvents.build(db, longParam(q, "events", 0), bbox, now));
        }
        boolean beams = boolParam(q, "beams", false), lasers = boolParam(q, "lasers", false);
        if (beams || lasers) {
            java.util.Set<String> shown = new java.util.HashSet<>();      // the symbols drawn
            Object objs = m.get("objects");
            if (objs instanceof List) {
                for (Object o : (List<?>) objs) {
                    if (o instanceof Map) shown.add(String.valueOf(((Map<?, ?>) o).get("id")));
                }
            }
            if (beams) m.putAll(RiftMapBeams.build(db, shown, now));     // radar and jammer beams
            if (lasers) m.putAll(RiftMapLasers.build(db, shown, now));   // laser designators
        }
        return ok(m);
    }

    /** related=true: the matches plus the active objects that reference any of them. */
    private static List<RiftInstance> withReferrers(RiftDatabase db, List<RiftInstance> hits) {
        Set<RiftInstance> all = new LinkedHashSet<>(hits);
        for (RiftInstance o : hits) {
            for (RiftRelation r : db.getRelations(o, Integer.MAX_VALUE).incoming) {
                if (r.other instanceof RiftInstance && !r.other.removed()) all.add((RiftInstance) r.other);
            }
        }
        return new ArrayList<>(all);
    }

    static boolean classMatch(String classNameQ, List<String> classes, boolean subclasses) {
        if (classes.isEmpty()) return true;
        for (String c : classes) {
            if (classNameQ.equals(c) || (subclasses && classNameQ.startsWith(c) && classNameQ.length() > c.length()
                    && classNameQ.charAt(c.length()) == '.')) {
                return true;
            }
        }
        return false;
    }

    /** Case-insensitive search in label, instance name and the list-view attribute texts (and entity type names). */
    static boolean textMatch(RiftInstance o, String lower, RiftJson json) {
        if (contains(RiftJson.labelOf(o), lower) || contains(o.getInstanceName(), lower)) return true;
        Map<String, ? extends RiftMemberView> attrs = o.attributes();
        for (String name : json.listAttributes(o)) {
            RiftMemberView a = attrs.get(name);
            if (a != null && a.value() != null && contains(a.value().text(), lower)) return true;
            Map<String, Object> et = a == null || a.value() == null ? null : RiftJson.entityType(a.value(), false);
            if (et != null) {                                    // 0.11.1: the entity type in words, and its country
                for (String k : new String[] {"text", "context", "country", "countryname"}) {
                    if (et.get(k) instanceof String && contains((String) et.get(k), lower)) return true;
                }
            }
        }
        return false;
    }

    private static boolean contains(String s, String lower) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(lower);
    }

    /** Sort keys computed once per row. */
    private static final class Row {
        final RiftInstance o;
        final String label;

        Row(RiftInstance o) {
            this.o = o;
            String l = RiftJson.labelOf(o);
            this.label = l == null ? "" : l.toLowerCase(Locale.ROOT);
        }
    }

    private static Comparator<Row> order(String sort) {
        Comparator<Row> byId = Comparator.comparingInt(r -> r.o.getDbId());
        switch (sort) {
            case "label":
                return Comparator.<Row, String>comparing(r -> r.label).thenComparing(byId);
            case "class":
                return Comparator.<Row, String>comparing(r -> RiftJson.simpleName(r.o.getClassName()))
                        .thenComparing(r -> r.label).thenComparing(byId);
            case "lastupdated":                                         // newest first
                return Comparator.<Row>comparingLong(r -> -r.o.lastUpdated().toEpochMilli()).thenComparing(byId.reversed());
            case "discovered":                                          // newest first
                return Comparator.<Row>comparingLong(r -> -r.o.discovered().toEpochMilli()).thenComparing(byId.reversed());
            default:
                throw new BadRequest("sort must be label, class, lastupdated or discovered");
        }
    }

    // ------------------------------------------------------------------ GET …/objects/{iid} (contract 6.2)

    private XrResultPair object(RiftDatabase db, RiftJson json, String iid, Map<String, String> q) {
        long seq = db.getSeq();
        String view = q.getOrDefault("view", "full");
        if (!"full".equals(view) && !"list".equals(view)) throw new BadRequest("view must be full or list");
        RiftInstance o = db.getInstance(iid);
        if (o == null) return error(404, "OBJECT_UNKNOWN", "No object " + iid);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", seq);
        m.put("object", "list".equals(view) ? json.listView(o)
                : json.fullView(o, json.related(db.getRelations(o, RiftDatabase.DEFAULT_INCOMING_CAP))));
        return ok(m);
    }

    // ------------------------------------------------------------------ GET …/interactions (contract 8.2)

    private XrResultPair interactions(RiftDatabase db, RiftJson json, Map<String, String> q) {
        long seq = db.getSeq();
        int limit = intParam(q, "limit", INTERACTIONS_DEFAULT_LIMIT, 1, INTERACTIONS_MAX_LIMIT);
        long before = longParam(q, "before", 0);
        List<String> classes = list(q.get("class"));
        boolean subclasses = boolParam(q, "subclasses", true);
        Predicate<RiftInteraction> filter = classes.isEmpty() ? null
                : ix -> classMatch(ix.classNameQ(), classes, subclasses);
        String objectId = q.get("object");
        List<RiftInteraction> found;
        if (objectId != null && !objectId.isEmpty()) {
            RiftInstance o = db.getInstance(objectId);
            Predicate<RiftInteraction> involving = db.involving(o);
            Predicate<RiftInteraction> f = filter == null ? involving : filter.and(involving);
            found = o == null ? Collections.emptyList() : db.getInteractions(before, limit + 1, f);
        } else {
            found = db.getInteractions(before, limit + 1, filter);
        }
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < found.size() && i < limit; i++) out.add(json.interaction(found.get(i), false));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", seq);
        m.put("more", found.size() > limit);
        m.put("interactions", out);
        return ok(m);
    }

    private XrResultPair interaction(RiftDatabase db, RiftJson json, String interactionId) {
        long seq = db.getSeq();
        long s = parseInteractionId(interactionId);
        RiftInteraction ix = s > 0 ? db.getInteraction(s) : null;
        if (ix == null) return error(404, "INTERACTION_UNKNOWN", "Interaction " + interactionId + " is not in the ring buffer");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", seq);
        m.put("interaction", json.interaction(ix, true));
        return ok(m);
    }

    /** "i48230" (or "48230") -> 48230; 0 if not an interaction id. */
    static long parseInteractionId(String id) {
        if (id == null || id.isEmpty()) return 0;
        String digits = id.charAt(0) == 'i' ? id.substring(1) : id;
        if (digits.isEmpty() || digits.length() > 18) return 0;
        for (int i = 0; i < digits.length(); i++) if (!Character.isDigit(digits.charAt(i))) return 0;
        return Long.parseLong(digits);
    }

    // ------------------------------------------------------------------ helpers

    private RiftDatabase database(String dsid) {
        try {
            return databases.apply(dsid);
        } catch (RuntimeException e) {          // e.g. UUID.fromString on a malformed id
            return null;
        }
    }

    /** Path segments after the prefix (percent-decoded), or null if the path is not under it. */
    List<String> segments(String requestUri) {
        if (requestUri == null) return null;
        String path = requestUri;
        int scheme = path.indexOf("://");
        if (scheme > 0 && scheme < 10) {                         // absolute URI: drop scheme://authority
            int slash = path.indexOf('/', scheme + 3);
            path = slash < 0 ? "/" : path.substring(slash);
        }
        int cut = path.length();
        int qm = path.indexOf('?'), hash = path.indexOf('#');
        if (qm >= 0) cut = qm;
        if (hash >= 0 && hash < cut) cut = hash;
        path = path.substring(0, cut);
        if (!path.startsWith(prefix)) return null;
        String rest = path.substring(prefix.length());
        if (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);
        if (rest.isEmpty()) return null;
        List<String> out = new ArrayList<>(3);
        for (String s : rest.split("/", -1)) {
            String d = percentDecode(s);
            if (d == null || d.isEmpty()) return null;
            out.add(d);
        }
        return out;
    }

    /** Path-segment decoding: %XX as UTF-8, '+' kept. Null if malformed. */
    static String percentDecode(String s) {
        if (s.indexOf('%') < 0) return s;
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) return null;
                int hi = Character.digit(s.charAt(i + 1), 16), lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) return null;
                out.write((hi << 4) | lo);
                i += 2;
            } else {
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(b, 0, b.length);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static List<String> list(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static int intParam(Map<String, String> q, String name, int def, int min, int max) {
        String v = q.get(name);
        if (v == null || v.trim().isEmpty()) return def;
        try {
            long n = Long.parseLong(v.trim());
            return (int) Math.max(min, Math.min(max, n));
        } catch (NumberFormatException e) {
            throw new BadRequest(name + " must be an integer, got '" + v + "'");
        }
    }

    private static long longParam(Map<String, String> q, String name, long def) {
        String v = q.get(name);
        if (v == null || v.trim().isEmpty()) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new BadRequest(name + " must be an integer, got '" + v + "'");
        }
    }

    private static boolean boolParam(Map<String, String> q, String name, boolean def) {
        String v = q.get(name);
        if (v == null || v.trim().isEmpty()) return def;
        switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "true": case "1": case "yes": return true;
            case "false": case "0": case "no": return false;
            default: throw new BadRequest(name + " must be true or false, got '" + v + "'");
        }
    }

    private static XrResultPair ok(Map<String, Object> body) {
        return new XrResultPair(Jsons.toJson(body), 200);
    }

    /** The error envelope {"success":false,"error":{"code","message"}} with this status. */
    public static XrResultPair error(int status, String code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", Boolean.FALSE);
        m.put("error", err);
        return new XrResultPair(Jsons.toJson(m), status);
    }

    private static final class BadRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BadRequest(String message) {
            super(message, null, false, false);
        }
    }
}
