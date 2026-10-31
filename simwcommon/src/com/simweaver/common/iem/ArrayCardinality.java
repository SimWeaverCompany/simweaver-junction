package com.simweaver.common.iem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parsed OMT array cardinality. Supports {@code Dynamic}, a fixed count ({@code 8}), a bounded range
 * ({@code [1..2147483647]}) and multi-dimensional forms separated by commas ({@code 3,4}).
 */
public final class ArrayCardinality {

    /** Value of {@link Range#getMax()} when there is no upper bound. */
    public static final int UNBOUNDED = -1;

    /** One dimension of the cardinality. */
    public static final class Range {
        private final int min;
        private final int max;

        Range(int min, int max) {
            this.min = min;
            this.max = max;
        }

        /** Minimum number of elements. */
        public int getMin() {
            return min;
        }

        /** Maximum number of elements, or {@link #UNBOUNDED}. */
        public int getMax() {
            return max;
        }

        /** True if min == max. */
        public boolean isFixed() {
            return max != UNBOUNDED && min == max;
        }

        @Override
        public String toString() {
            return isFixed() ? Integer.toString(min) : "[" + min + ".." + (max == UNBOUNDED ? "*" : max) + "]";
        }
    }

    private final String text;
    private final List<Range> dimensions;

    private ArrayCardinality(String text, List<Range> dimensions) {
        this.text = text;
        this.dimensions = Collections.unmodifiableList(dimensions);
    }

    /**
     * Parses cardinality text.
     *
     * @throws IllegalArgumentException with a short reason when the text is not valid
     */
    static ArrayCardinality parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("empty cardinality");
        }
        List<Range> dims = new ArrayList<Range>();
        for (String part : splitTopLevel(text.trim())) {
            String p = part.trim();
            if (p.equalsIgnoreCase("Dynamic")) {
                dims.add(new Range(0, UNBOUNDED));
            } else if (p.startsWith("[") && p.endsWith("]")) {
                String inner = p.substring(1, p.length() - 1);
                int dd = inner.indexOf("..");
                if (dd < 0) {
                    throw new IllegalArgumentException("range '" + p + "' lacks '..'");
                }
                int lo = parseInt(inner.substring(0, dd).trim());
                String hiText = inner.substring(dd + 2).trim();
                int hi = hiText.equals("*") || hiText.equalsIgnoreCase("Dynamic") ? UNBOUNDED : parseInt(hiText);
                if (hi != UNBOUNDED && hi < lo) {
                    throw new IllegalArgumentException("range '" + p + "' has upper bound below lower bound");
                }
                dims.add(new Range(lo, hi));
            } else {
                int n = parseInt(p);
                dims.add(new Range(n, n));
            }
        }
        return new ArrayCardinality(text.trim(), dims);
    }

    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<String>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    private static int parseInt(String s) {
        try {
            long v = Long.parseLong(s);
            if (v < 0 || v > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("count '" + s + "' out of range");
            }
            return (int) v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + s + "' is not a number, range or 'Dynamic'");
        }
    }

    /** The text as written in the FOM. */
    public String getText() {
        return text;
    }

    /** One entry per array dimension (usually exactly one). */
    public List<Range> getDimensions() {
        return dimensions;
    }

    /** True if any dimension is not a fixed count. */
    public boolean isDynamic() {
        for (Range r : dimensions) {
            if (!r.isFixed()) {
                return true;
            }
        }
        return false;
    }

    /** Total number of elements when every dimension is fixed, otherwise -1. */
    public long getFixedElementCount() {
        long n = 1;
        for (Range r : dimensions) {
            if (!r.isFixed()) {
                return -1;
            }
            n *= r.getMin();
        }
        return n;
    }

    /** Canonical form used to compare definitions from different modules. */
    String canonical() {
        StringBuilder sb = new StringBuilder();
        for (Range r : dimensions) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(r.getMin()).append("..").append(r.getMax());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return text;
    }
}
