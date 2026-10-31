package com.simweaver.common.hla.decoders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Text helpers shared by encoding handlers and formatters, so every display text follows the
 * same conventions. Pure functions.
 */
public final class DisplayText {

    /** Appended when text is cut. */
    public static final String ELLIPSIS = "...";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private DisplayText() {
    }

    /**
     * Integer as text, unsigned aware.
     *
     * @param v        value
     * @param unsigned true to print as unsigned 64-bit
     * @return decimal text
     */
    public static String integer(long v, boolean unsigned) {
        return unsigned ? Long.toUnsignedString(v) : Long.toString(v);
    }

    /**
     * Floating point as text: shortest round-trip form, plain notation for magnitudes from
     * 1e-4 to 1e15, scientific otherwise. Float32 values are printed as float so that
     * 0.1f shows as {@code 0.1}, not {@code 0.10000000149011612}.
     *
     * @param v       value
     * @param float32 true when the value came from a 32-bit float
     * @return text
     */
    public static String floating(double v, boolean float32) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "Infinity" : "-Infinity";
        }
        String s = float32 ? Float.toString((float) v) : Double.toString(v);
        double a = Math.abs(v);
        if (s.indexOf('E') >= 0 && (a == 0.0 || (a >= 1e-4 && a < 1e15))) {
            s = new BigDecimal(s).toPlainString();
        }
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);
        }
        return s;
    }

    /**
     * Floating point with a fixed number of decimals.
     *
     * @param v        value
     * @param decimals decimals
     * @return text
     */
    public static String fixed(double v, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    /**
     * Makes text safe to display: control characters and DEL become {@code \xNN},
     * backslash becomes {@code \\}; cut at {@code max} characters.
     *
     * @param s   text
     * @param max maximum length of the result before the ellipsis
     * @return the safe text
     */
    public static String escape(CharSequence s, int max) {
        StringBuilder sb = new StringBuilder(Math.min(s.length(), max) + 8);
        for (int i = 0; i < s.length(); i++) {
            if (sb.length() >= max) {
                sb.append(ELLIPSIS);
                break;
            }
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f || (c >= 0x80 && c < 0xa0)) {
                sb.append("\\x").append(HEX[(c >> 4) & 15]).append(HEX[c & 15]);
            } else if (c == '\\') {
                sb.append("\\\\");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Hex of octets.
     *
     * @param buf    buffer
     * @param offset start
     * @param length octets
     * @param max    maximum octets printed; the rest is summarised as {@code ... (n octets)}
     * @return hex text
     */
    public static String hex(byte[] buf, int offset, int length, int max) {
        int n = Math.min(length, max);
        StringBuilder sb = new StringBuilder(n * 2 + 24);
        for (int i = 0; i < n; i++) {
            int b = buf[offset + i] & 0xff;
            sb.append(HEX[b >>> 4]).append(HEX[b & 15]);
        }
        if (length > n) {
            sb.append(ELLIPSIS).append(" (").append(length).append(" octets)");
        }
        return sb.toString();
    }

    /**
     * Text of a value as it appears inside a parent's text: records and variants in braces.
     *
     * @param v value
     * @return text
     */
    public static String nested(DecodedValue v) {
        ValueKind k = v.getKind();
        boolean wrap = (k == ValueKind.RECORD || k == ValueKind.VARIANT) && !v.getText().startsWith("{")
            && v.getChildren().size() > 1 && v.getText().indexOf(',') >= 0;
        return wrap ? "{" + v.getText() + "}" : v.getText();
    }

    /**
     * Record text: field texts joined with {@code ", "}; padding and derived children skipped.
     *
     * @param fields fields
     * @param max    maximum length
     * @return text
     */
    public static String record(List<DecodedValue> fields, int max) {
        StringBuilder sb = new StringBuilder();
        for (DecodedValue f : fields) {
            if (f.getKind() == ValueKind.PADDING || f.isDerived()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(nested(f));
            if (sb.length() > max) {
                return sb.substring(0, max) + ELLIPSIS;
            }
        }
        return sb.toString();
    }

    /**
     * Array text: {@code [a, b, c, ... +97]}.
     *
     * @param elements  elements
     * @param total     total element count (may exceed {@code elements.size()})
     * @param maxListed elements listed
     * @param max       maximum length
     * @return text
     */
    public static String array(List<DecodedValue> elements, int total, int maxListed, int max) {
        StringBuilder sb = new StringBuilder("[");
        int listed = 0;
        for (DecodedValue e : elements) {
            if (listed >= maxListed || sb.length() > max) {
                break;
            }
            if (listed > 0) {
                sb.append(", ");
            }
            sb.append(nested(e));
            listed++;
        }
        if (sb.length() > max) {
            sb.setLength(max);
            sb.append(ELLIPSIS);
        }
        if (total > listed) {
            sb.append(listed > 0 ? ", " : "").append(ELLIPSIS).append(" +").append(total - listed);
        }
        return sb.append(']').toString();
    }
}
