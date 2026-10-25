package com.simweaver.rift.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the JSON trees of the datasource page contract (sections 3 and 8) as plain
 * Map / List / String / Number / Boolean values, ready for com.simweaver.common.rest's Jsons.toJson and
 * XrSseHub.publishPatch.
 *
 * Rules applied (contract 3.3):
 * - integers outside +/-(2^53 - 1) are sent as strings (browsers round them);
 * - NaN / Infinity are sent as the strings "NaN", "Infinity", "-Infinity";
 * - shallow nodes (list view, SSE, interaction rows) carry no fields/items/derived;
 * - full ARRAY nodes carry at most ITEMS_FULL items; "count" keeps the real size;
 * - PADDING nodes are left out.
 *
 * Stateless apart from its configuration; thread safe.
 */
public final class RiftJson {

    public static final int ITEMS_FULL = 1000;
    private static final long MAX_SAFE_INTEGER = 9007199254740991L;

    private final RiftListProfiles profiles;
    private final RiftRefResolver resolver;

    public RiftJson(RiftListProfiles profiles, RiftRefResolver resolver) {
        this.profiles = profiles != null ? profiles : RiftListProfiles.none();
        this.resolver = resolver != null ? resolver : RiftRefResolver.NONE;
    }

    public RiftListProfiles profiles() {
        return profiles;
    }

    // ------------------------------------------------------------------ objects (3.1 / 3.2)

    /** List view: header plus the list-profile attributes as shallow nodes. */
    public Map<String, Object> listView(RiftObjectView o) {
        Map<String, Object> m = header(o, "list");
        List<Object> attrs = new ArrayList<>();
        Map<String, ? extends RiftMemberView> all = o.attributes();
        for (String name : listAttributes(o)) {
            RiftMemberView a = all.get(name);
            if (a != null) attrs.add(member(a, false));
        }
        m.put("attributes", attrs);
        return m;
    }

    /**
     * Full view: header plus all received attributes as full nodes. The "related" block
     * (contract 4.2) is added by the caller when available (pass null to leave it out).
     */
    public Map<String, Object> fullView(RiftObjectView o, Map<String, Object> related) {
        return fullView(o, related, System.currentTimeMillis());
    }

    /**
     * Full view at a given time (for the computed attributes). Order: the list-view attributes
     * first (as in the row), then the others in FOM order; a computed attribute directly after
     * the attribute its name ends with ("DRSpatial" after "Spatial"), else at the end.
     */
    public Map<String, Object> fullView(RiftObjectView o, Map<String, Object> related, long nowMillis) {
        Map<String, Object> m = header(o, "full");
        m.put("attributes", fullAttributes(o, nowMillis));
        if (related != null) m.put("related", related);
        return m;
    }

    private List<Object> fullAttributes(RiftObjectView o, long nowMillis) {
        Map<String, ? extends RiftMemberView> all = o.attributes();
        List<RiftMemberView> ordered = new ArrayList<>(all.size() + 1);
        java.util.Set<String> placed = new java.util.HashSet<>();
        for (String name : listAttributes(o)) {
            RiftMemberView a = all.get(name);
            if (a != null && placed.add(name)) ordered.add(a);
        }
        for (RiftMemberView a : all.values()) if (placed.add(a.name())) ordered.add(a);
        List<? extends RiftMemberView> computed = o.hasComputedAttributes()
                ? o.computedAttributes(nowMillis) : java.util.Collections.<RiftMemberView>emptyList();
        for (RiftMemberView c : computed) {
            int at = ordered.size();
            for (int i = 0; i < ordered.size(); i++) {
                String base = ordered.get(i).name();
                if (c.name().endsWith(base) && !c.name().equals(base)) { at = i + 1; break; }
            }
            ordered.add(at, c);
        }
        List<Object> out = new ArrayList<>(ordered.size());
        for (RiftMemberView a : ordered) out.add(member(a, true));
        return out;
    }

    /**
     * The "related" block of the full view (contract 4.2): outgoing references with their target's
     * list view (null while not discovered), incoming ones with the source's list view, and the
     * real incoming count.
     */
    public Map<String, Object> related(RiftRelations r) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> out = new ArrayList<>();
        for (RiftRelation rel : r.outgoing) {
            Map<String, Object> e = relation(rel);
            e.put("target", rel.other == null || rel.other.removed() ? null : listView(rel.other));
            out.add(e);
        }
        List<Object> in = new ArrayList<>();
        for (RiftRelation rel : r.incoming) {
            Map<String, Object> e = relation(rel);
            e.put("source", listView(rel.other));
            in.add(e);
        }
        m.put("outgoing", out);
        m.put("incoming", in);
        m.put("incomingtotal", r.incomingTotal);
        return m;
    }

    private static Map<String, Object> relation(RiftRelation rel) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("attribute", rel.attribute);
        e.put("path", rel.path);
        e.put("kind", rel.kind);
        e.put("key", rel.key);
        return e;
    }

    /** Names of the attributes the list view of this object shows. */
    public List<String> listAttributes(RiftObjectView o) {
        return profiles.select(o.classNameQ(), o.attributes().keySet());
    }

    private Map<String, Object> header(RiftObjectView o, String view) {
        Map<String, Object> m = new LinkedHashMap<>();
        String cq = o.classNameQ();
        m.put("instanceid", o.externalId());
        m.put("instancename", o.instanceName());
        m.put("label", labelOf(o));
        m.put("classname", simpleName(cq));
        m.put("classnameq", cq);
        m.put("view", view);
        m.put("state", o.removed() ? "removed" : "active");
        if (o.removed() && o.removedAt() != null) m.put("removed", iso(o.removedAt()));
        m.put("discovered", iso(o.discovered()));
        m.put("lastupdated", iso(o.lastUpdated()));
        m.put("rev", o.rev());
        m.put("updatecount", o.updateCount());
        if (o.producer() != null) m.put("producer", o.producer());
        return m;
    }

    /** Attribute whose text is the display label (RPR BaseEntity.Marking). */
    public static final String LABEL_ATTRIBUTE = "Marking";

    /** Display label: the view's label, else the RPR Marking text, else the instance name. */
    public static String labelOf(RiftObjectView o) {
        String l = o.label();
        if (l != null && !l.trim().isEmpty()) return l;
        RiftMemberView marking = o.attributes().get(LABEL_ATTRIBUTE);
        if (marking != null && marking.value() != null) {
            String t = marking.value().text();
            if (t != null && !t.trim().isEmpty()) return t.trim();
        }
        return o.instanceName();
    }

    /** Short reference used in chips: {instanceid, label, classname}. */
    public static Map<String, Object> target(RiftObjectView o) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("instanceid", o.externalId());
        t.put("label", labelOf(o));
        t.put("classname", simpleName(o.classNameQ()));
        return t;
    }

    // ------------------------------------------------------------------ members and nodes (3.3 / 3.4)

    /** An attribute or parameter: name, datatype, lastupdated, raw, value node. */
    public Map<String, Object> member(RiftMemberView a, boolean full) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", a.name());
        m.put("datatype", a.datatype());
        if (a.lastUpdated() != null) m.put("lastupdated", iso(a.lastUpdated()));
        m.put("rawsize", a.rawSize());
        if (a.rawHex() != null) m.put("valueRaw", a.rawHex());
        if (a.rawTruncated()) m.put("rawtruncated", Boolean.TRUE);
        m.put("value", node(a.value(), full));
        return m;
    }

    /** One value node; full = with fields/items/derived. */
    public Map<String, Object> node(ValueNode v, boolean full) {
        Map<String, Object> n = new LinkedHashMap<>();
        if (v == null) {
            n.put("kind", "EMPTY");
            n.put("text", "");
            return n;
        }
        String kind = v.kind();
        n.put("kind", kind);
        n.put("text", v.text() != null ? v.text() : "");
        Object s = safeScalar(v.scalar());
        if (s != null) n.put("value", s);
        if ("ENUM".equals(kind)) n.put("enum", v.enumName());
        if (v.units() != null) n.put("units", v.units());
        if (full && v.type() != null) n.put("type", v.type());
        if (v.refKind() != null) n.put("ref", ref(v.refKind(), v.refKeys()));
        if (v.error() != null) n.put("error", v.error());
        if (full && !v.warnings().isEmpty()) n.put("warnings", new ArrayList<>(v.warnings()));

        if (full) {
            List<Object> fields = named(v.fields());
            if (!fields.isEmpty()) n.put("fields", fields);
            if ("ARRAY".equals(kind)) {
                List<ValueNode> items = v.items();
                int count = Math.max(v.count(), items.size());
                List<Object> out = new ArrayList<>();
                for (int i = 0; i < items.size() && i < ITEMS_FULL; i++) {
                    if (!isPadding(items.get(i))) out.add(node(items.get(i), true));
                }
                n.put("items", out);
                n.put("count", count);
                if (count > out.size() || v.truncated()) n.put("truncated", Boolean.TRUE);
            } else if (v.truncated()) {
                n.put("truncated", Boolean.TRUE);
            }
            List<Object> derived = named(v.derived());
            if (!derived.isEmpty()) n.put("derived", derived);
        } else if (v.truncated()) {
            n.put("truncated", Boolean.TRUE);
        }
        return n;
    }

    private List<Object> named(List<ValueNode.Named> list) {
        List<Object> out = new ArrayList<>();
        for (ValueNode.Named f : list) {
            if (f == null || isPadding(f.node)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name);
            m.putAll(node(f.node, true));
            out.add(m);
        }
        return out;
    }

    private static boolean isPadding(ValueNode v) {
        return v != null && "PADDING".equals(v.kind());
    }

    /** {"kind", "keys": [...], "targets": [... aligned with keys, null when unknown]} */
    public Map<String, Object> ref(String kind, List<String> keys) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", kind);
        List<Object> k = new ArrayList<>(keys);
        List<Object> targets = new ArrayList<>(keys.size());
        for (String key : keys) {
            RiftObjectView t = resolver.resolve(kind, key);
            targets.add(t == null || t.removed() ? null : target(t));
        }
        r.put("keys", k);
        r.put("targets", targets);
        return r;
    }

    // ------------------------------------------------------------------ interactions (8.1)

    /** Interaction with shallow parameter nodes (log rows, SSE) or full ones (detail GET). */
    public Map<String, Object> interaction(RiftInteractionView ix, boolean full) {
        Map<String, Object> m = new LinkedHashMap<>();
        String cq = ix.classNameQ();
        m.put("seq", ix.seq());
        m.put("interactionid", "i" + ix.seq());
        m.put("received", iso(ix.received()));
        m.put("classname", simpleName(cq));
        m.put("classnameq", cq);
        if (ix.sentOrder() != null) m.put("sentorder", ix.sentOrder());
        if (ix.logicalTime() != null) m.put("logicaltime", ix.logicalTime());
        if (ix.producer() != null) m.put("producer", ix.producer());
        if (ix.summary() != null) m.put("summary", ix.summary());
        if (ix.correlationKey() != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("key", ix.correlationKey());
            c.put("label", ix.correlationLabel() != null ? ix.correlationLabel() : ix.correlationKey());
            m.put("correlation", c);
        }
        List<Object> params = new ArrayList<>();
        for (RiftMemberView p : ix.parameters()) params.add(member(p, full));
        m.put("parameters", params);
        return m;
    }

    // ------------------------------------------------------------------ helpers

    /** "2026-10-19T11:43:12.345Z" (milliseconds; Instant omits a zero fraction, which is fine). */
    public static String iso(Instant t) {
        return t == null ? null : t.truncatedTo(ChronoUnit.MILLIS).toString();
    }

    public static String simpleName(String classNameQ) {
        if (classNameQ == null) return null;
        int dot = classNameQ.lastIndexOf('.');
        return dot < 0 ? classNameQ : classNameQ.substring(dot + 1);
    }

    /** JSON-safe scalar: big integers and non-finite doubles become strings. */
    static Object safeScalar(Object s) {
        if (s instanceof Double || s instanceof Float) {
            double d = ((Number) s).doubleValue();
            if (Double.isNaN(d)) return "NaN";
            if (Double.isInfinite(d)) return d > 0 ? "Infinity" : "-Infinity";
            return d;
        }
        if (s instanceof Long || s instanceof Integer || s instanceof Short || s instanceof Byte) {
            long l = ((Number) s).longValue();
            return Math.abs(l) <= MAX_SAFE_INTEGER ? (Object) l : (Object) Long.toString(l);
        }
        if (s instanceof java.math.BigInteger || s instanceof java.math.BigDecimal) {
            return s.toString();
        }
        return s;   // String, Boolean or null
    }

    /** Count helper for class-count patches. */
    static Map<String, Object> single(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    static List<String> list(Collection<String> c) {
        return new ArrayList<>(c);
    }
}
