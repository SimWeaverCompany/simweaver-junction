package com.simweaver.common.rest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small dependency-free JSON parser for Java 8.
 * JSON objects become Map<String,Object>, arrays become List<Object>,
 * strings become String, numbers become Long/Double/BigDecimal,
 * and JSON booleans/null become Boolean/null.
 */
public final class JsonParser {
    /**
     * Maximum nesting of objects/arrays. Without a limit, input such as 100 000 '['
     * overflows the stack (StackOverflowError) on the request thread.
     */
    public static final int MAX_DEPTH = 512;

    private final String json;
    private int pos;
    private int depth;

    private JsonParser(String json) {
        if (json == null) throw new IllegalArgumentException("JSON input must not be null");
        this.json = json;
    }

    /** Parse any JSON value into Java maps, lists, strings, numbers, booleans, or null. */
    public static Object parse(String json) {
        JsonParser parser = new JsonParser(json);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.pos != parser.json.length()) {
            throw parser.error("Unexpected content after JSON value");
        }
        return value;
    }

    /** Parse a JSON object, requiring the top-level value to be an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object value = parse(json);
        if (!(value instanceof Map)) throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String, Object>) value;
    }

    /** Parse a JSON array, requiring the top-level value to be an array. */
    @SuppressWarnings("unchecked")
    public static List<Object> parseArray(String json) {
        Object value = parse(json);
        if (!(value instanceof List)) throw new IllegalArgumentException("Expected a JSON array");
        return (List<Object>) value;
    }

    private Object readValue() {
        skipWhitespace();
        if (pos >= json.length()) throw error("Expected a JSON value");
        char c = json.charAt(pos);
        if (c == '{') return readObject();
        if (c == '[') return readArray();
        if (c == '"') return readString();
        if (c == 't') { readLiteral("true"); return Boolean.TRUE; }
        if (c == 'f') { readLiteral("false"); return Boolean.FALSE; }
        if (c == 'n') { readLiteral("null"); return null; }
        if (c == '-' || (c >= '0' && c <= '9')) return readNumber();
        throw error("Unexpected character while reading JSON value");
    }

    private Map<String, Object> readObject() {
        enter();
        pos++; // {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        skipWhitespace();
        if (consume('}')) { depth--; return result; }
        while (true) {
            skipWhitespace();
            if (pos >= json.length() || json.charAt(pos) != '"') throw error("Expected a quoted object key");
            String key = readString();
            skipWhitespace();
            if (!consume(':')) throw error("Expected ':' after object key");
            Object value = readValue();
            result.put(key, value);
            skipWhitespace();
            if (consume('}')) { depth--; return result; }
            if (!consume(',')) throw error("Expected ',' or '}' in object");
        }
    }

    private List<Object> readArray() {
        enter();
        pos++; // [
        List<Object> result = new ArrayList<Object>();
        skipWhitespace();
        if (consume(']')) { depth--; return result; }
        while (true) {
            result.add(readValue());
            skipWhitespace();
            if (consume(']')) { depth--; return result; }
            if (!consume(',')) throw error("Expected ',' or ']' in array");
        }
    }

    private String readString() {
        if (!consume('"')) throw error("Expected string");
        StringBuilder out = new StringBuilder();
        while (pos < json.length()) {
            char c = json.charAt(pos++);
            if (c == '"') return out.toString();
            if (c < 0x20) throw error("Unescaped control character in string");
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (pos >= json.length()) throw error("Incomplete escape sequence");
            char escaped = json.charAt(pos++);
            switch (escaped) {
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case '/': out.append('/'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u': out.append(readUnicodeEscape()); break;
                default: throw error("Invalid escape sequence");
            }
        }
        throw error("Unterminated string");
    }

    private char readUnicodeEscape() {
        if (pos + 4 > json.length()) throw error("Incomplete Unicode escape");
        int value = 0;
        for (int i = 0; i < 4; i++) {
            char c = json.charAt(pos++);
            int digit = Character.digit(c, 16);
            if (digit < 0) throw error("Invalid Unicode escape");
            value = (value << 4) | digit;
        }
        return (char) value;
    }

    private Number readNumber() {
        int start = pos;
        if (consume('-') && pos >= json.length()) throw error("Incomplete number");
        if (consume('0')) {
            if (pos < json.length() && isDigit(json.charAt(pos))) throw error("Leading zero in number");
        } else {
            if (pos >= json.length() || json.charAt(pos) < '1' || json.charAt(pos) > '9') throw error("Invalid number");
            while (pos < json.length() && isDigit(json.charAt(pos))) pos++;
        }
        boolean decimal = false;
        if (consume('.')) {
            decimal = true;
            if (pos >= json.length() || !isDigit(json.charAt(pos))) throw error("Expected digit after decimal point");
            while (pos < json.length() && isDigit(json.charAt(pos))) pos++;
        }
        if (pos < json.length() && (json.charAt(pos) == 'e' || json.charAt(pos) == 'E')) {
            decimal = true;
            pos++;
            if (pos < json.length() && (json.charAt(pos) == '+' || json.charAt(pos) == '-')) pos++;
            if (pos >= json.length() || !isDigit(json.charAt(pos))) throw error("Expected exponent digits");
            while (pos < json.length() && isDigit(json.charAt(pos))) pos++;
        }
        String number = json.substring(start, pos);
        try {
            if (!decimal) {
                try { return Long.valueOf(number); }
                catch (NumberFormatException ignored) { return new BigDecimal(number); }
            }
            double d = Double.parseDouble(number);
            if (Double.isInfinite(d) || Double.isNaN(d)) return new BigDecimal(number);
            return Double.valueOf(d);
        } catch (NumberFormatException e) {
            throw error("Invalid number");
        }
    }

    private void readLiteral(String literal) {
        if (!json.regionMatches(pos, literal, 0, literal.length())) throw error("Invalid literal");
        pos += literal.length();
    }

    private void skipWhitespace() {
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++;
            else break;
        }
    }

    private boolean consume(char c) {
        if (pos < json.length() && json.charAt(pos) == c) { pos++; return true; }
        return false;
    }

    private void enter() {
        if (++depth > MAX_DEPTH) throw error("JSON nested deeper than " + MAX_DEPTH);
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at position " + pos);
    }
}
