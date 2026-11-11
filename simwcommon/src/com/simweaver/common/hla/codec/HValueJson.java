package com.simweaver.common.hla.codec;

import com.simweaver.common.iem.ArrayDataType;
import com.simweaver.common.iem.BasicDataType;
import com.simweaver.common.iem.DataType;
import com.simweaver.common.iem.EnumeratedDataType;
import com.simweaver.common.iem.Enumerator;
import com.simweaver.common.iem.FixedRecordDataType;
import com.simweaver.common.iem.RecordField;
import com.simweaver.common.iem.SimpleDataType;
import com.simweaver.common.iem.VariantAlternative;
import com.simweaver.common.iem.VariantRecordDataType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HLA values as JSON and back, without loss: what the Kafka endpoint writes for attribute and
 * parameter values (0.13.0).
 *
 * <table>
 *   <caption>The mapping</caption>
 *   <tr><td>integers</td><td>numbers (64-bit values exactly)</td></tr>
 *   <tr><td>enumerations</td><td>the enumerator's name; a number when the value has no name</td></tr>
 *   <tr><td>floats</td><td>numbers (binary32 as its shortest decimal); {@code "NaN"}, {@code "Infinity"},
 *       {@code "-Infinity"} as strings</td></tr>
 *   <tr><td>character arrays</td><td>strings (NULs kept, as JSON escapes)</td></tr>
 *   <tr><td>octet arrays and opaque data</td><td>strings with one character per octet (ISO-8859-1):
 *       text stays readable ({@code "GRIPEN 11"}), other octets are JSON escapes (backslash u00XX)</td></tr>
 *   <tr><td>other arrays</td><td>arrays</td></tr>
 *   <tr><td>fixed records</td><td>objects, field name to value (padding left out)</td></tr>
 *   <tr><td>variant records</td><td>objects: the discriminant's name to its enumerator, the
 *       alternative's name to its value</td></tr>
 * </table>
 *
 * Reading is driven by the datatype: fields missing in the JSON get their default value, unknown
 * names are left out ({@link Issues}), numbers given as strings and enumerators given as numbers
 * are taken.
 */
public final class HValueJson {

    private HValueJson() {
    }

    /** Collects what reading had to leave out or guess. */
    public interface Issues {
        void issue(String path, String message);
    }

    private static final Issues QUIET = (p, m) -> { };

    // ------------------------------------------------------------------ to JSON

    /**
     * @param v a value (decoded, so it knows its datatype)
     * @return a JSON-ready tree of Map / List / String / Long / Float / Double
     */
    public static Object toJson(HValue v) {
        if (v == null) {
            return null;
        }
        switch (v.getKind()) {
            case INT: {
                String e = v.getEnumerator();
                return e != null ? e : (Object) v.asLong();
            }
            case FLOAT: {
                double d = v.asDouble();
                if (Double.isNaN(d)) {
                    return "NaN";
                }
                if (Double.isInfinite(d)) {
                    return d > 0 ? "Infinity" : "-Infinity";
                }
                BasicDataType b = basicOf(v.getType());
                return b != null && b.getSizeOctets() == 4 ? (Object) (float) d : (Object) d;
            }
            case TEXT:
            case BYTES:
                return v.asText();
            case ARRAY: {
                List<Object> l = new ArrayList<Object>(v.size());
                for (HValue e : v.getValues()) {
                    l.add(toJson(e));
                }
                return l;
            }
            case RECORD: {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                List<String> names = v.getFieldNames();
                List<HValue> values = v.getValues();
                for (int i = 0; i < names.size(); i++) {
                    if (values.get(i).getKind() != HValue.Kind.PADDING) {
                        m.put(names.get(i), toJson(values.get(i)));
                    }
                }
                return m;
            }
            case VARIANT: {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                String disc = "Discriminant";
                if (v.getType() instanceof VariantRecordDataType) {
                    disc = ((VariantRecordDataType) v.getType()).getDiscriminantName();
                }
                m.put(disc, v.getEnumerator() != null ? v.getEnumerator() : (Object) v.asLong());
                if (v.getAlternativeName() != null && v.getAlternative() != null) {
                    m.put(v.getAlternativeName(), toJson(v.getAlternative()));
                }
                return m;
            }
            default:
                return null;
        }
    }

    /** @return the basic representation behind a basic or simple datatype, or null */
    static BasicDataType basicOf(DataType t) {
        if (t instanceof BasicDataType) {
            return (BasicDataType) t;
        }
        if (t instanceof SimpleDataType) {
            return ((SimpleDataType) t).getRepresentation();
        }
        if (t instanceof EnumeratedDataType) {
            return ((EnumeratedDataType) t).getRepresentation();
        }
        return null;
    }

    // ------------------------------------------------------------------ from JSON

    /**
     * @param codec  the model's codec (default values, layouts)
     * @param type   the datatype to read
     * @param json   the parsed JSON (Map / List / String / Number / Boolean / null)
     * @param issues receives what was left out, or null
     * @return the value, ready for {@link HlaCodec#encode}
     */
    public static HValue fromJson(HlaCodec codec, DataType type, Object json, Issues issues) {
        return read(codec, type, json, issues == null ? QUIET : issues, type == null ? "" : type.getName(), 0);
    }

    private static HValue read(HlaCodec codec, DataType t, Object json, Issues issues, String path, int depth) {
        if (t == null || depth > 64) {
            issues.issue(path, "no datatype");
            return null;
        }
        if (json == null) {
            return codec.defaultValue(t);
        }
        switch (t.getCategory()) {
            case ENUMERATED:
                return enumerated((EnumeratedDataType) t, json, issues, path, codec);
            case BASIC:
            case SIMPLE:
                return basic(codec, t, json, issues, path);
            case ARRAY:
                return array(codec, (ArrayDataType) t, json, issues, path, depth);
            case FIXED_RECORD:
                return record(codec, (FixedRecordDataType) t, json, issues, path, depth);
            case VARIANT_RECORD:
                return variant(codec, (VariantRecordDataType) t, json, issues, path, depth);
            default:
                issues.issue(path, "datatype " + t.getName() + " cannot be read from JSON");
                return codec.defaultValue(t);
        }
    }

    private static HValue enumerated(EnumeratedDataType t, Object json, Issues issues, String path, HlaCodec codec) {
        if (json instanceof String) {
            Enumerator e = t.getEnumerator((String) json);
            if (e != null) {
                return HValue.ofEnum(e.getValue(), e.getName()).withType(t);
            }
            Long n = number((String) json);
            if (n != null) {
                return byValue(t, n);
            }
            issues.issue(path, "no enumerator " + json + " in " + t.getName());
            return codec.defaultValue(t);
        }
        if (json instanceof Number) {
            return byValue(t, ((Number) json).longValue());
        }
        issues.issue(path, "an enumerator name or number expected");
        return codec.defaultValue(t);
    }

    private static HValue byValue(EnumeratedDataType t, long v) {
        Enumerator e = t.getEnumeratorByValue(v);
        return HValue.ofEnum(v, e == null ? null : e.getName()).withType(t);
    }

    private static Long number(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static HValue basic(HlaCodec codec, DataType t, Object json, Issues issues, String path) {
        HValue template = codec.defaultValue(t);
        switch (template.getKind()) {
            case FLOAT: {
                Double d = toDouble(json);
                if (d == null) {
                    issues.issue(path, "a number expected");
                    return template;
                }
                return HValue.ofFloat(d).withType(t);
            }
            case BYTES:
                if (json instanceof String) {
                    return HValue.ofBytes(((String) json).getBytes(StandardCharsets.ISO_8859_1)).withType(t);
                }
                issues.issue(path, "a string expected");
                return template;
            default: {
                if (json instanceof Boolean) {
                    return HValue.ofInt((Boolean) json ? 1 : 0).withType(t);
                }
                Long n = toLong(json);
                if (n == null) {
                    issues.issue(path, "an integer expected");
                    return template;
                }
                return HValue.ofInt(n).withType(t);
            }
        }
    }

    private static Double toDouble(Object json) {
        if (json instanceof Number) {
            return ((Number) json).doubleValue();
        }
        if (json instanceof String) {
            String s = ((String) json).trim();
            switch (s) {
                case "NaN":
                    return Double.NaN;
                case "Infinity":
                case "+Infinity":
                    return Double.POSITIVE_INFINITY;
                case "-Infinity":
                    return Double.NEGATIVE_INFINITY;
                default:
                    try {
                        return Double.parseDouble(s);
                    } catch (NumberFormatException e) {
                        return null;
                    }
            }
        }
        return null;
    }

    private static Long toLong(Object json) {
        if (json instanceof Long || json instanceof Integer || json instanceof Short || json instanceof Byte) {
            return ((Number) json).longValue();
        }
        if (json instanceof BigDecimal || json instanceof BigInteger) {
            // unsigned 64-bit values above Long.MAX_VALUE keep their bits
            BigInteger b = json instanceof BigDecimal ? ((BigDecimal) json).toBigInteger() : (BigInteger) json;
            return b.longValue();
        }
        if (json instanceof Number) {
            return (long) ((Number) json).doubleValue();
        }
        if (json instanceof String) {
            String s = ((String) json).trim();
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                try {
                    return new BigInteger(s).longValue();
                } catch (NumberFormatException e2) {
                    return null;
                }
            }
        }
        return null;
    }

    private static HValue array(HlaCodec codec, ArrayDataType t, Object json, Issues issues, String path, int depth) {
        HValue template = codec.defaultValue(t);
        if (template.getKind() == HValue.Kind.TEXT) {
            if (json instanceof String) {
                return HValue.ofText((String) json).withType(t);
            }
            issues.issue(path, "a string expected");
            return template;
        }
        if (template.getKind() == HValue.Kind.BYTES) {
            if (json instanceof String) {
                return HValue.ofBytes(((String) json).getBytes(StandardCharsets.ISO_8859_1)).withType(t);
            }
            if (json instanceof List) {                             // octets as numbers are taken too
                List<?> l = (List<?>) json;
                byte[] b = new byte[l.size()];
                for (int i = 0; i < b.length; i++) {
                    Long n = toLong(l.get(i));
                    b[i] = n == null ? 0 : (byte) (long) n;
                }
                return HValue.ofBytes(b).withType(t);
            }
            issues.issue(path, "a string expected");
            return template;
        }
        if (!(json instanceof List)) {
            issues.issue(path, "an array expected");
            return template;
        }
        List<?> l = (List<?>) json;
        List<HValue> out = new ArrayList<HValue>(l.size());
        for (int i = 0; i < l.size(); i++) {
            out.add(read(codec, t.getElementType(), l.get(i), issues, path + "[" + i + "]", depth + 1));
        }
        return HValue.ofArray(out).withType(t);
    }

    private static HValue record(HlaCodec codec, FixedRecordDataType t, Object json, Issues issues, String path, int depth) {
        if (!(json instanceof Map)) {
            issues.issue(path, "an object expected");
            return codec.defaultValue(t);
        }
        Map<?, ?> m = (Map<?, ?>) json;
        HValue template = codec.defaultValue(t);
        Map<String, HValue> fields = new LinkedHashMap<String, HValue>();
        for (RecordField f : t.getFields()) {
            HValue def = template.field(f.getName());
            if (def != null && def.getKind() == HValue.Kind.PADDING) {
                fields.put(f.getName(), def);
            } else if (m.containsKey(f.getName())) {
                fields.put(f.getName(), read(codec, f.getDataType(), m.get(f.getName()), issues, path + "." + f.getName(), depth + 1));
            } else {
                fields.put(f.getName(), def != null ? def : codec.defaultValue(f.getDataType()));
            }
        }
        for (Object k : m.keySet()) {
            if (t.getField(String.valueOf(k)) == null) {
                issues.issue(path + "." + k, "no such field in " + t.getName());
            }
        }
        return HValue.ofRecord(fields).withType(t);
    }

    private static HValue variant(HlaCodec codec, VariantRecordDataType t, Object json, Issues issues, String path, int depth) {
        if (!(json instanceof Map)) {
            issues.issue(path, "an object expected");
            return codec.defaultValue(t);
        }
        Map<?, ?> m = (Map<?, ?>) json;
        EnumeratedDataType dt = t.getDiscriminantType();
        Object d = m.get(t.getDiscriminantName());
        if (d == null || dt == null) {
            issues.issue(path, "no " + t.getDiscriminantName());
            return codec.defaultValue(t);
        }
        HValue disc = enumerated(dt, d, issues, path + "." + t.getDiscriminantName(), codec);
        VariantAlternative alt = t.getAlternativeForValue(disc.asLong());
        if (alt == null || alt.isEmpty() || alt.getDataType() == null) {
            return HValue.ofVariant(disc.asLong(), disc.getEnumerator(), alt == null ? null : alt.getName(), null).withType(t);
        }
        Object av = m.get(alt.getName());
        HValue value = av == null ? codec.defaultValue(alt.getDataType())
                : read(codec, alt.getDataType(), av, issues, path + "." + alt.getName(), depth + 1);
        return HValue.ofVariant(disc.asLong(), disc.getEnumerator(), alt.getName(), value).withType(t);
    }
}
