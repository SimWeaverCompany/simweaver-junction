package com.simweaver.junction.config;

import com.simweaver.common.json.Json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One value rule of a router ({@code router.valuemap.<datatype>[.<label>] = A -> B}): every
 * value of the datatype that equals A, wherever it occurs in a routed attribute or parameter, is
 * replaced by B. One way: B is left alone unless another rule says otherwise. Immutable.
 *
 * <p>A and B are written in dot notation, one number per field of the datatype in FOM order
 * (an RPR {@code EntityTypeStruct}: kind.domain.country.category.subcategory.specific.extra), or
 * a single number for an integer or enumerated datatype. Several pairs can share a key,
 * separated by {@code ;}.
 */
public final class ValueRule implements Json.Writable {

    /** Prefix of the rule keys. */
    public static final String PREFIX = "router.valuemap.";

    /** Short names for datatypes (lower case) -> the datatype name in the FOM. */
    private static final Map<String, String> ALIASES;

    static {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("entitytype", "EntityTypeStruct");
        ALIASES = Collections.unmodifiableMap(m);
    }

    private final String dataType;
    private final String key;
    private final long[] from;
    private final long[] to;

    ValueRule(String dataType, String key, long[] from, long[] to) {
        this.dataType = dataType;
        this.key = key;
        this.from = from.clone();
        this.to = to.clone();
    }

    /** @return the datatype name in the FOM (aliases resolved) */
    public String getDataType() {
        return dataType;
    }

    /** @return the setting the rule comes from */
    public String getKey() {
        return key;
    }

    /** @return the value to replace, one number per field */
    public long[] getFrom() {
        return from.clone();
    }

    /** @return the replacement, one number per field */
    public long[] getTo() {
        return to.clone();
    }

    /** @return number of fields */
    public int size() {
        return from.length;
    }

    /** @return "1.2.225.1.3.0.0" for a value */
    public static String text(long[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(v[i]);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return dataType + ": " + text(from) + " -> " + text(to);
    }

    @Override
    public Object toJson() {
        return Json.object().put("datatype", dataType).put("from", text(from)).put("to", text(to)).put("key", key);
    }

    /** The datatype name a key names: an alias, or the name as written. */
    static String dataTypeOf(String name) {
        String a = ALIASES.get(name.toLowerCase(Locale.ROOT));
        return a != null ? a : name;
    }

    /**
     * Reads all {@code router.valuemap.*} settings.
     *
     * @param keys   the keys with the prefix, in a stable order
     * @param values setting values by key
     * @param errors receives what is wrong (the gateway does not start then)
     * @return the rules, in key order
     */
    static List<ValueRule> parseAll(List<String> keys, Map<String, String> values, List<String> errors) {
        List<ValueRule> out = new ArrayList<ValueRule>();
        Map<String, ValueRule> byFrom = new LinkedHashMap<String, ValueRule>();      // "type|A" -> rule
        Map<String, ValueRule> firstOfType = new LinkedHashMap<String, ValueRule>();
        for (String key : keys) {
            String rest = key.substring(PREFIX.length());
            int dot = rest.indexOf('.');
            String typeName = (dot < 0 ? rest : rest.substring(0, dot)).trim();
            if (typeName.isEmpty()) {
                errors.add(key + ": the key must name a datatype, e.g. " + PREFIX + "EntityType.1");
                continue;
            }
            String type = dataTypeOf(typeName);
            String text = values.get(key);
            if (text == null || text.trim().isEmpty()) {
                errors.add(key + ": no rule (A -> B)");
                continue;
            }
            for (String pair : text.split(";")) {
                if (pair.trim().isEmpty()) {
                    continue;
                }
                ValueRule r = parsePair(type, key, pair.trim(), errors);
                if (r == null) {
                    continue;
                }
                String id = type + "|" + text(r.from);
                ValueRule before = byFrom.get(id);
                if (before != null) {
                    if (!Arrays.equals(before.to, r.to)) {
                        errors.add(key + ": " + type + " " + text(r.from) + " already becomes " + text(before.to)
                                + " (" + before.key + ")");
                    }
                    continue;
                }
                if (Arrays.equals(r.from, r.to)) {
                    continue;                                       // A -> A: nothing to do
                }
                ValueRule first = firstOfType.get(type);
                if (first != null && first.size() != r.size()) {
                    errors.add(key + ": '" + pair.trim() + "': " + type + " values have " + first.size() + " fields in " + first.key);
                    continue;
                }
                firstOfType.putIfAbsent(type, r);
                byFrom.put(id, r);
                out.add(r);
            }
        }
        return out;
    }

    private static ValueRule parsePair(String type, String key, String pair, List<String> errors) {
        int at = pair.indexOf("->");
        int len = 2;
        if (at < 0) {
            at = pair.indexOf('>');
            len = 1;
        }
        if (at <= 0 || at + len >= pair.length()) {
            errors.add(key + ": '" + pair + "' is not <value> -> <new value>");
            return null;
        }
        long[] a = number(pair.substring(0, at).trim());
        long[] b = number(pair.substring(at + len).trim());
        if (a == null || b == null) {
            errors.add(key + ": '" + pair + "': values are numbers in dot notation, e.g. 1.2.225.1.3.0.0");
            return null;
        }
        if (a.length != b.length) {
            errors.add(key + ": '" + pair + "': both values need the same number of fields (" + a.length + " and " + b.length + ")");
            return null;
        }
        return new ValueRule(type, key, a, b);
    }

    private static long[] number(String s) {
        if (!s.matches("-?\\d{1,19}(\\.-?\\d{1,19})*")) {
            return null;
        }
        String[] p = s.split("\\.");
        long[] v = new long[p.length];
        try {
            for (int i = 0; i < p.length; i++) {
                v[i] = Long.parseLong(p[i]);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return v;
    }
}
