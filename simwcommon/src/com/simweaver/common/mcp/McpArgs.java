package com.simweaver.common.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The arguments of one tool call (0.14.0), already checked against the tool's input schema
 * ({@link McpSchema#check}); the getters convert as leniently as the check accepts.
 */
public final class McpArgs {

    private final Map<String, Object> values;

    public McpArgs(Map<String, Object> values) {
        this.values = values == null ? Collections.<String, Object>emptyMap() : values;
    }

    /** @return true when the argument was given (and is not null or an empty string) */
    public boolean has(String name) {
        Object v = values.get(name);
        return v != null && !(v instanceof String && ((String) v).trim().isEmpty());
    }

    /** @return the argument as a trimmed string, or {@code def} */
    public String str(String name, String def) {
        Object v = values.get(name);
        if (v == null) {
            return def;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    public int integer(String name, int def) {
        Double d = McpSchema.toNumber(values.get(name));
        return d == null ? def : (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(d)));
    }

    /** @return the argument clamped to [min, max], or {@code def} */
    public int integer(String name, int def, int min, int max) {
        return Math.max(min, Math.min(max, integer(name, def)));
    }

    public double number(String name, double def) {
        Double d = McpSchema.toNumber(values.get(name));
        return d == null ? def : d;
    }

    /** @return the argument as a number, or null when absent */
    public Double numberOrNull(String name) {
        return McpSchema.toNumber(values.get(name));
    }

    public boolean bool(String name, boolean def) {
        Boolean b = McpSchema.toBoolean(values.get(name));
        return b == null ? def : b;
    }

    /** @return an object argument as {@link McpArgs}, or null when absent */
    @SuppressWarnings("unchecked")
    public McpArgs object(String name) {
        Object v = values.get(name);
        return v instanceof Map ? new McpArgs((Map<String, Object>) v) : null;
    }

    /** @return an array argument's elements as strings (empty when absent); "a,b" is taken as [a, b] */
    public List<String> strings(String name) {
        Object v = values.get(name);
        List<String> out = new ArrayList<>();
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (o != null && !String.valueOf(o).trim().isEmpty()) {
                    out.add(String.valueOf(o).trim());
                }
            }
        } else if (v instanceof String) {
            for (String s : ((String) v).split(",")) {
                if (!s.trim().isEmpty()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    /** @return the raw values (for the audit summary) */
    public Map<String, Object> raw() {
        return values;
    }
}
