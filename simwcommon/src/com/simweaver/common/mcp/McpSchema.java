package com.simweaver.common.mcp;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The small part of JSON Schema the MCP tools use (0.14.0): a builder for tool input and output
 * schemas, and a checker for tool arguments.
 *
 * <p>Supported keywords: {@code type} ({@code object}, {@code string}, {@code integer},
 * {@code number}, {@code boolean}, {@code array}), {@code description}, {@code properties},
 * {@code required}, {@code enum}, {@code minimum}, {@code maximum}, {@code maxLength},
 * {@code items}, {@code default}. Not a general validator: schemas are only ever built here.
 *
 * <p>The checker is lenient where small models are sloppy: an integer may come as {@code 25.0} or
 * {@code "25"}, a number or boolean as a string; unknown properties are ignored.
 */
public final class McpSchema {

    private final Map<String, Object> node = new LinkedHashMap<>();

    private McpSchema(String type, String description) {
        if (type != null) {
            node.put("type", type);
        }
        if (description != null) {
            node.put("description", description);
        }
    }

    // ------------------------------------------------------------------ builders

    /** @return an object schema without properties yet */
    public static McpSchema object() {
        return new McpSchema("object", null);
    }

    /** @return an object schema with a description */
    public static McpSchema object(String description) {
        return new McpSchema("object", description);
    }

    public static McpSchema string(String description) {
        return new McpSchema("string", description);
    }

    public static McpSchema integer(String description) {
        return new McpSchema("integer", description);
    }

    public static McpSchema number(String description) {
        return new McpSchema("number", description);
    }

    public static McpSchema bool(String description) {
        return new McpSchema("boolean", description);
    }

    /** @return a schema without a type: any value (output schemas) */
    public static McpSchema any(String description) {
        return new McpSchema(null, description);
    }

    /** @return a string schema limited to these values */
    public static McpSchema enumOf(String description, String... values) {
        McpSchema s = new McpSchema("string", description);
        s.node.put("enum", Arrays.asList(values));
        return s;
    }

    /** @return an array schema with this item schema */
    public static McpSchema array(String description, McpSchema items) {
        McpSchema s = new McpSchema("array", description);
        s.node.put("items", items.toMap());
        return s;
    }

    /** Adds a property (object schemas). */
    public McpSchema prop(String name, McpSchema schema) {
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) node.computeIfAbsent("properties", k -> new LinkedHashMap<String, Object>());
        props.put(name, schema.toMap());
        return this;
    }

    /** Names required properties (object schemas). */
    public McpSchema required(String... names) {
        @SuppressWarnings("unchecked")
        List<Object> req = (List<Object>) node.computeIfAbsent("required", k -> new ArrayList<Object>());
        req.addAll(Arrays.asList(names));
        return this;
    }

    public McpSchema min(double v) {
        node.put("minimum", number(v));
        return this;
    }

    public McpSchema max(double v) {
        node.put("maximum", number(v));
        return this;
    }

    public McpSchema maxLength(int n) {
        node.put("maxLength", n);
        return this;
    }

    public McpSchema def(Object value) {
        node.put("default", value);
        return this;
    }

    private static Object number(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? (Object) (long) v : (Object) v;
    }

    /** @return the schema as JSON-ready maps and lists (a copy) */
    public Map<String, Object> toMap() {
        return deepCopy(node);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map) {
                v = deepCopy((Map<String, Object>) v);
            } else if (v instanceof List) {
                v = new ArrayList<>((List<Object>) v);
            }
            out.put(e.getKey(), v);
        }
        return out;
    }

    // ------------------------------------------------------------------ checking

    /**
     * Checks arguments against a schema made by this class.
     *
     * @return the problems, each a sentence naming the argument; empty when fine
     */
    public static List<String> check(Map<String, Object> schema, Object value) {
        List<String> problems = new ArrayList<>();
        check(schema, value, "", problems);
        return problems;
    }

    @SuppressWarnings("unchecked")
    private static void check(Map<String, Object> schema, Object value, String path, List<String> problems) {
        if (schema == null) {
            return;
        }
        String type = (String) schema.get("type");
        String where = path.isEmpty() ? "the arguments" : "'" + path + "'";
        if (value == null) {
            if (path.isEmpty() && "object".equals(type)) {
                value = Collections.emptyMap();
            } else {
                return;
            }
        }
        if (type == null) {
            return;
        }
        switch (type) {
            case "object":
                if (!(value instanceof Map)) {
                    problems.add(where + " must be an object");
                    return;
                }
                Map<String, Object> obj = (Map<String, Object>) value;
                Object req = schema.get("required");
                if (req instanceof List) {
                    for (Object r : (List<Object>) req) {
                        Object v = obj.get(String.valueOf(r));
                        if (v == null || (v instanceof String && ((String) v).trim().isEmpty())) {
                            problems.add("'" + join(path, String.valueOf(r)) + "' is required");
                        }
                    }
                }
                Object props = schema.get("properties");
                if (props instanceof Map) {
                    for (Map.Entry<String, Object> p : ((Map<String, Object>) props).entrySet()) {
                        if (obj.containsKey(p.getKey())) {
                            check((Map<String, Object>) p.getValue(), obj.get(p.getKey()), join(path, p.getKey()), problems);
                        }
                    }
                }
                return;
            case "array":
                if (value instanceof String) {
                    return;                                         // lenient: "a,b" (McpArgs.strings splits it)
                }
                if (!(value instanceof List)) {
                    problems.add(where + " must be an array");
                    return;
                }
                Object items = schema.get("items");
                List<Object> list = (List<Object>) value;
                for (int i = 0; i < list.size(); i++) {
                    check((Map<String, Object>) items, list.get(i), path + "[" + i + "]", problems);
                }
                return;
            case "string":
                if (!(value instanceof String)) {
                    if (value instanceof Number || value instanceof Boolean) {
                        value = String.valueOf(value);             // lenient: 3 for "3"
                    } else {
                        problems.add(where + " must be a string");
                        return;
                    }
                }
                String s = (String) value;
                Object ml = schema.get("maxLength");
                if (ml instanceof Number && s.length() > ((Number) ml).intValue()) {
                    problems.add(where + " is longer than " + ml + " characters");
                }
                Object en = schema.get("enum");
                if (en instanceof List && !((List<Object>) en).contains(s)) {
                    problems.add(where + " must be one of " + en + ", not '" + s + "'");
                }
                return;
            case "integer":
            case "number": {
                Double d = toNumber(value);
                if (d == null || ("integer".equals(type) && d != Math.rint(d))) {
                    problems.add(where + " must be " + ("integer".equals(type) ? "an integer" : "a number"));
                    return;
                }
                Object mn = schema.get("minimum"), mx = schema.get("maximum");
                if (mn instanceof Number && d < ((Number) mn).doubleValue()) {
                    problems.add(where + " must be at least " + mn);
                }
                if (mx instanceof Number && d > ((Number) mx).doubleValue()) {
                    problems.add(where + " must be at most " + mx);
                }
                return;
            }
            case "boolean":
                if (toBoolean(value) == null) {
                    problems.add(where + " must be true or false");
                }
                return;
            default:
                return;
        }
    }

    private static String join(String path, String name) {
        return path.isEmpty() ? name : path + "." + name;
    }

    /** @return the value as a number (numbers, numeric strings), or null */
    static Double toNumber(Object v) {
        if (v instanceof BigDecimal) {
            return ((BigDecimal) v).doubleValue();
        }
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
        }
        if (v instanceof String) {
            try {
                double d = Double.parseDouble(((String) v).trim());
                return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** @return the value as a boolean (booleans, "true"/"false"/"yes"/"no"/"1"/"0"), or null */
    static Boolean toBoolean(Object v) {
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof String || v instanceof Number) {
            switch (String.valueOf(v).trim().toLowerCase(java.util.Locale.ROOT)) {
                case "true": case "yes": case "1":
                    return Boolean.TRUE;
                case "false": case "no": case "0":
                    return Boolean.FALSE;
                default:
                    return null;
            }
        }
        return null;
    }
}
