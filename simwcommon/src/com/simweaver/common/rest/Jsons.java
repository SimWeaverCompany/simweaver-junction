package com.simweaver.common.rest;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class Jsons {

    /** Nesting depth at which toJson gives up (protects against cycles in object graphs). */
    public static final int MAX_DEPTH = 256;

    /**
     * Parse JSON into ordinary Java values without any external dependency.
     * Objects become Map<String,Object>, arrays become List<Object>, numbers
     * become Long, Double, or BigDecimal, and booleans/null map naturally.
     *
     * @throws IllegalArgumentException if the input is null or invalid JSON
     */
    public static Object fromJson(String json) {
        return JsonParser.parse(json);
    }

    /** Parse a JSON object and require the top-level value to be an object. */
    public static Map<String, Object> fromJsonObject(String json) {
        return JsonParser.parseObject(json);
    }

    /** Parse a JSON array and require the top-level value to be an array. */
    public static List<Object> fromJsonArray(String json) {
        return JsonParser.parseArray(json);
    }

    /**
     * Serialize a Java value to JSON text. The output is always valid JSON (RFC 8259):
     * <ul>
     *   <li>{@link XrRawJson} is inserted as is (already serialized JSON).</li>
     *   <li>Strings, Characters and enums are quoted; control characters are escaped.</li>
     *   <li>Any {@link Number}; non-finite doubles/floats (NaN, Infinity) are written as the
     *       strings "NaN", "Infinity", "-Infinity", because JSON has no such numbers.</li>
     *   <li>java.time values via toString(); java.util.Date as an ISO-8601 instant.</li>
     *   <li>Arrays, Collections, Maps, Optional.</li>
     *   <li>Other objects: their non-static, non-transient fields (reflection).</li>
     * </ul>
     *
     * @throws IllegalArgumentException if the value nests deeper than {@link #MAX_DEPTH}
     *         or contains a reference cycle
     */
    public static String toJson(Object obj) {
        StringBuilder out = new StringBuilder();
        write(out, obj, 0, new IdentityHashMap<Object, Boolean>());
        return out.toString();
    }

    private static void write(StringBuilder out, Object obj, int depth, IdentityHashMap<Object, Boolean> path) {
        if (obj == null) {
            out.append("null");
            return;
        }
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("JSON nesting deeper than " + MAX_DEPTH);
        }
        if (obj instanceof XrRawJson) {
            out.append(((XrRawJson) obj).getJson());
            return;
        }

        Class<?> clazz = obj.getClass();

        // 1. Strings, Characters, and Enums (wrapped in quotes)
        if (obj instanceof CharSequence || clazz == Character.class) {
            writeString(out, obj.toString());
            return;
        }
        if (obj instanceof Enum) {
            writeString(out, obj.toString());
            return;
        }

        // 2. Booleans and all Numbers (incl. BigDecimal, BigInteger, AtomicLong, ...)
        if (clazz == Boolean.class) {
            out.append(obj.toString());
            return;
        }
        if (obj instanceof Number) {
            writeNumber(out, (Number) obj);
            return;
        }

        // 3. Date and Time API; java.util.Date as an ISO-8601 instant (not Date.toString())
        if (obj instanceof Date) {
            writeString(out, ((Date) obj).toInstant().toString());
            return;
        }
        if (obj instanceof TemporalAccessor) {
            writeString(out, obj.toString());
            return;
        }

        // 4. Java 8+ Optional handling
        if (obj instanceof Optional) {
            Optional<?> optional = (Optional<?>) obj;
            write(out, optional.isPresent() ? optional.get() : null, depth, path);
            return;
        }

        // Containers: detect reference cycles on the current path.
        if (path.put(obj, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("Reference cycle in value of type " + clazz.getName());
        }
        try {
            if (clazz.isArray()) {                                     // 5. Arrays
                out.append('[');
                int length = Array.getLength(obj);
                for (int i = 0; i < length; i++) {
                    if (i > 0) out.append(',');
                    write(out, Array.get(obj, i), depth + 1, path);
                }
                out.append(']');
            } else if (obj instanceof Collection) {                    // 6. Collections
                out.append('[');
                Iterator<?> it = ((Collection<?>) obj).iterator();
                for (boolean first = true; it.hasNext(); first = false) {
                    if (!first) out.append(',');
                    write(out, it.next(), depth + 1, path);
                }
                out.append(']');
            } else if (obj instanceof Map) {                           // 7. Maps
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
                    if (!first) out.append(',');
                    first = false;
                    writeString(out, String.valueOf(entry.getKey()));
                    out.append(':');
                    write(out, entry.getValue(), depth + 1, path);
                }
                out.append('}');
            } else {                                                   // 8. Other objects
                writeObject(out, obj, depth, path);
            }
        } finally {
            path.remove(obj);
        }
    }

    private static void writeNumber(StringBuilder out, Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (Double.isNaN(d)) { out.append("\"NaN\""); return; }
            if (Double.isInfinite(d)) { out.append(d > 0 ? "\"Infinity\"" : "\"-Infinity\""); return; }
            out.append(n.toString());          // e.g. 1.0E10 is valid JSON
            return;
        }
        if (n instanceof BigDecimal) {
            out.append(n.toString());          // scientific notation like 1E+3 is valid JSON
            return;
        }
        if (n instanceof BigInteger || n instanceof Long || n instanceof Integer
                || n instanceof Short || n instanceof Byte) {
            out.append(n.toString());
            return;
        }
        // AtomicInteger, AtomicLong, LongAdder, DoubleAdder, custom Number types
        double d = n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            writeNumber(out, Double.valueOf(d));
            return;
        }
        String text = n.toString();
        try {
            new BigDecimal(text);              // only emit text that is a valid number
            out.append(text);
        } catch (NumberFormatException e) {
            out.append(Double.toString(d));
        }
    }

    private static void writeObject(StringBuilder out, Object obj, int depth,
                                    IdentityHashMap<Object, Boolean> path) {
        out.append('{');
        boolean first = true;
        for (Field field : getAllFields(obj.getClass())) {
            int mod = field.getModifiers();
            if (field.isSynthetic() || Modifier.isStatic(mod) || Modifier.isTransient(mod)) continue;
            Object value;
            try {
                field.setAccessible(true);
                value = field.get(obj);
            } catch (RuntimeException | IllegalAccessException e) {
                continue;                       // inaccessible (e.g. module rules): skip
            }
            if (!first) out.append(',');
            first = false;
            writeString(out, field.getName());
            out.append(':');
            write(out, value, depth + 1, path);
        }
        out.append('}');
    }

    // Recursively gathers fields from the current class up through its parent classes
    private static List<Field> getAllFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            fields.addAll(Arrays.asList(c.getDeclaredFields()));
        }
        return fields;
    }

    /** Quote and escape a string as a JSON string literal. */
    static void writeString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    // All other control characters must be escaped in JSON. U+2028/U+2029
                    // are escaped too, so the output is also safe inside <script> / JS.
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        out.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int k = hex.length(); k < 4; k++) out.append('0');
                        out.append(hex);
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }
}
