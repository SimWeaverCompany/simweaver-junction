package com.simweaver.rift.mcp;

import com.simweaver.rift.api.RiftJson;
import com.simweaver.rift.api.RiftObjectView;
import com.simweaver.rift.api.RiftQueries;
import com.simweaver.rift.api.RiftRefResolver;
import com.simweaver.rift.api.ValueNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 0.14.0: decoded values in the compact form the MCP tools answer with: a value per field instead
 * of the REST API's nodes. Enumerations by name, entity types in words, references with the
 * label of the object they point to, records as maps, arrays as lists (capped).
 */
final class McpValues {

    static final int MAX_DEPTH = 6;
    static final int MAX_ITEMS = 50;
    static final int MAX_TEXT = 400;

    private final RiftRefResolver resolver;

    McpValues(RiftRefResolver resolver) {
        this.resolver = resolver != null ? resolver : RiftRefResolver.NONE;
    }

    /** The compact value: a string, number, boolean, map or list (never a ValueNode). */
    Object compact(ValueNode v) {
        return compact(v, 0);
    }

    private Object compact(ValueNode v, int depth) {
        if (v == null) {
            return null;
        }
        String kind = v.kind();
        if (v.error() != null) {
            return "(not decoded: " + v.error() + ")";
        }
        if (v.refKind() != null) {
            return reference(v);
        }
        Map<String, Object> et = RiftQueries.entityType(v);
        if (et != null) {
            return entityTypeText(et, v);
        }
        switch (kind == null ? "" : kind) {
            case "ENUM":
                return v.enumName() != null ? v.enumName() : v.text();
            case "INTEGER":
            case "BOOLEAN":
                return v.scalar() != null ? v.scalar() : v.text();
            case "FLOAT": {
                Object s = v.scalar();
                if (s instanceof Number) {
                    double d = ((Number) s).doubleValue();
                    Double r = McpGeo.round(d, Math.abs(d) >= 1000 ? 2 : 6);
                    return r == null ? String.valueOf(d) : r;
                }
                return v.text();
            }
            case "TEXT":
                return cut(v.scalar() instanceof String ? (String) v.scalar() : v.text());
            case "BYTES":
                return cut(v.text());
            case "EMPTY":
            case "PADDING":
                return null;
            default:
                break;
        }
        if (depth >= MAX_DEPTH) {
            return cut(v.text());
        }
        if ("ARRAY".equals(kind)) {
            List<Object> out = new ArrayList<>();
            int n = 0;
            for (ValueNode i : v.items()) {
                if (i != null && "PADDING".equals(i.kind())) {
                    continue;
                }
                if (n++ >= MAX_ITEMS) {
                    break;
                }
                out.add(compact(i, depth + 1));
            }
            int count = Math.max(v.count(), v.items().size());
            if (count > out.size()) {
                out.add("… " + (count - out.size()) + " more");
            }
            return out;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (ValueNode.Named f : v.fields()) {
            if (f == null || f.node == null || "PADDING".equals(f.node.kind())) {
                continue;
            }
            m.put(f.name, compact(f.node, depth + 1));
        }
        if (!v.derived().isEmpty()) {
            Map<String, Object> d = new LinkedHashMap<>();
            for (ValueNode.Named f : v.derived()) {
                if (f != null && f.node != null) {
                    d.put(f.name, compact(f.node, depth + 1));
                }
            }
            m.put("derived", d);
        }
        if (m.isEmpty()) {
            return cut(v.text());
        }
        return m;
    }

    /** One line: the text, with a reference's label or an entity type's name. */
    String shortText(ValueNode v) {
        if (v == null) {
            return null;
        }
        if (v.refKind() != null) {
            Object r = reference(v);
            return r instanceof String ? (String) r : String.valueOf(r);
        }
        Map<String, Object> et = RiftQueries.entityType(v);
        if (et != null) {
            return entityTypeText(et, v);
        }
        if ("ENUM".equals(v.kind()) && v.enumName() != null) {
            return v.enumName();
        }
        return cut(v.text());
    }

    /** "key (label)" for each key; one string for one key, else a list. */
    private Object reference(ValueNode v) {
        List<String> keys = v.refKeys();
        List<Object> out = new ArrayList<>();
        for (String k : keys) {
            RiftObjectView t = resolver.resolve(v.refKind(), k);
            if (t == null || t.removed()) {
                out.add(k);
            } else {
                String label = RiftJson.labelOf(t);
                out.add(label == null || label.equals(k) ? k + " (" + t.externalId() + ")" : k + " (" + label + ", " + t.externalId() + ")");
            }
        }
        if (out.isEmpty()) {
            return cut(v.text());
        }
        return out.size() == 1 ? out.get(0) : out;
    }

    /** "Main battle tank M1A2 Abrams (USA) [1.1.225.1.1.3.0]" */
    private static String entityTypeText(Map<String, Object> et, ValueNode v) {
        StringBuilder b = new StringBuilder(String.valueOf(et.get("text")));
        Object country = et.get("country");
        if (country != null) {
            b.append(" (").append(country).append(')');
        }
        String code = entityTypeCode(v);
        if (code != null) {
            b.append(" [").append(code).append(']');
        }
        return b.toString();
    }

    private static final List<String> ET_FIELDS = Arrays.asList("EntityKind", "Domain", "CountryCode", "Category", "Subcategory", "Specific", "Extra");

    /** "1.2.225.1.3.0.0" for an entity type record, else null. */
    static String entityTypeCode(ValueNode v) {
        if (v == null || !"RECORD".equals(v.kind())) {
            return null;
        }
        long[] x = new long[ET_FIELDS.size()];
        int found = 0;
        for (ValueNode.Named f : v.fields()) {
            int i = f == null ? -1 : ET_FIELDS.indexOf(f.name);
            if (i < 0 || f.node == null || !(f.node.scalar() instanceof Number)) {
                continue;
            }
            x[i] = ((Number) f.node.scalar()).longValue();
            found |= 1 << i;
        }
        if (found != (1 << ET_FIELDS.size()) - 1) {
            return null;
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < x.length; i++) {
            if (i > 0) {
                b.append('.');
            }
            b.append(x[i]);
        }
        return b.toString();
    }

    static String cut(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT) + "…";
    }

    // ------------------------------------------------------------------ maps from the REST API

    /** Removes these keys at every depth (e.g. "history": 59 numbers per item). */
    @SuppressWarnings("unchecked")
    static Object dropKeys(Object o, String... keys) {
        Set<String> drop = new HashSet<>(Arrays.asList(keys));
        return drop(o, drop);
    }

    @SuppressWarnings("unchecked")
    private static Object drop(Object o, Set<String> keys) {
        if (o instanceof Map) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                if (!keys.contains(e.getKey())) {
                    m.put(e.getKey(), drop(e.getValue(), keys));
                }
            }
            return m;
        }
        if (o instanceof List) {
            List<Object> l = new ArrayList<>();
            for (Object x : (List<Object>) o) {
                l.add(drop(x, keys));
            }
            return l;
        }
        return o;
    }

    /** A map with only these keys (those present), in this order. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> pick(Object o, String... keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            for (String k : keys) {
                if (m.containsKey(k)) {
                    out.put(k, m.get(k));
                }
            }
        }
        return out;
    }

    /** Removes the entries whose value is 0, 0.0, false, null, "" or empty (for counters). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> nonZero(Object o) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (o instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                Object v = e.getValue();
                if (v == null || Boolean.FALSE.equals(v) || "".equals(v)
                        || (v instanceof Number && ((Number) v).doubleValue() == 0)
                        || (v instanceof List && ((List<?>) v).isEmpty())
                        || (v instanceof Map && ((Map<?, ?>) v).isEmpty())) {
                    continue;
                }
                out.put(e.getKey(), v);
            }
        }
        return out;
    }
}
