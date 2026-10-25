package com.simweaver.common.hla.decoders;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable settings of an {@link HlaDataDecoder}. Build with {@link #builder()}.
 *
 * <h2>Safety limits</h2>
 * Wire data is untrusted: an array count of 2^31 must not allocate 2^31 nodes. The decoder
 * checks every count against the octets that remain and against {@link #getMaxElements()},
 * and nesting against {@link #getMaxDepth()}.
 */
public final class DecoderOptions {

    /** What to do with octets left over after the top-level value. */
    public enum TrailingOctets {
        /** Ignore them. */
        IGNORE,
        /** Add a warning to the value (default). */
        WARN,
        /** Treat as a decoding error. */
        ERROR
    }

    /** How enumerators are shown in {@link DecodedValue#getText()}. */
    public enum EnumText {
        /** {@code Friendly} (default). */
        NAME,
        /** {@code Friendly (1)}. */
        NAME_AND_VALUE,
        /** {@code 1}. */
        VALUE
    }

    /**
     * Layout of the variant encodings whose exact definition is not available in this
     * project ({@code HLAextendableVariantRecord} of IEEE 1516.2-2025 and RPR's
     * {@code RPRextendedVariantRecord}). See {@link Builder#variantLayout(String, VariantLayout)}.
     */
    public enum VariantLayout {
        /**
         * Try {@link #LENGTH_PREFIXED}; if the length does not match the decoded alternative,
         * fall back to {@link #PLAIN}. The layout used is reported as a warning.
         */
        AUTO,
        /**
         * Discriminant, then (aligned to 4) an HLAinteger32BE count of the octets that follow
         * for the alternative, then the alternative. Unknown discriminants are skipped by length.
         */
        LENGTH_PREFIXED,
        /** Same as HLAvariantRecord. */
        PLAIN
    }

    private static final DecoderOptions DEFAULTS = builder().build();

    private final int maxElements;
    private final int maxDepth;
    private final int maxTextLength;
    private final int maxListedElements;
    private final int maxHexOctets;
    private final int rawCap;
    private final TrailingOctets trailingOctets;
    private final EnumText enumText;
    private final Map<String, VariantLayout> variantLayouts;
    private final Map<String, BasicInterpretation> basicInterpretations;
    private final Map<String, EncodingHandler> encodingHandlers;
    private final Map<String, String> countRules;
    private final List<ValueFormatter> formatters;
    private final boolean formattersEnabled;
    private final Clock clock;

    /** Source of the current time for formatters ("now" values); replaceable in tests. */
    public interface Clock {
        /** @return current time, epoch milliseconds UTC */
        long currentTimeMillis();
    }

    private DecoderOptions(Builder b) {
        this.maxElements = b.maxElements;
        this.maxDepth = b.maxDepth;
        this.maxTextLength = b.maxTextLength;
        this.maxListedElements = b.maxListedElements;
        this.maxHexOctets = b.maxHexOctets;
        this.rawCap = b.rawCap;
        this.trailingOctets = b.trailingOctets;
        this.enumText = b.enumText;
        this.variantLayouts = Collections.unmodifiableMap(new LinkedHashMap<String, VariantLayout>(b.variantLayouts));
        this.basicInterpretations = Collections.unmodifiableMap(new LinkedHashMap<String, BasicInterpretation>(b.basicInterpretations));
        this.encodingHandlers = Collections.unmodifiableMap(new LinkedHashMap<String, EncodingHandler>(b.encodingHandlers));
        this.countRules = Collections.unmodifiableMap(new LinkedHashMap<String, String>(b.countRules));
        this.formatters = Collections.unmodifiableList(new ArrayList<ValueFormatter>(b.formatters));
        this.formattersEnabled = b.formattersEnabled;
        this.clock = b.clock;
    }

    /** @return the default options (no formatters) */
    public static DecoderOptions defaults() {
        return DEFAULTS;
    }

    /** @return a builder with default values */
    public static Builder builder() {
        return new Builder();
    }

    /** @return a builder initialised from these options */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.maxElements = maxElements;
        b.maxDepth = maxDepth;
        b.maxTextLength = maxTextLength;
        b.maxListedElements = maxListedElements;
        b.maxHexOctets = maxHexOctets;
        b.rawCap = rawCap;
        b.trailingOctets = trailingOctets;
        b.enumText = enumText;
        b.variantLayouts.putAll(variantLayouts);
        b.basicInterpretations.putAll(basicInterpretations);
        b.encodingHandlers.putAll(encodingHandlers);
        b.countRules.putAll(countRules);
        b.formatters.addAll(formatters);
        b.formattersEnabled = formattersEnabled;
        b.clock = clock;
        return b;
    }

    /** @return maximum number of elements of one array that become child nodes */
    public int getMaxElements() {
        return maxElements;
    }

    /** @return maximum nesting depth */
    public int getMaxDepth() {
        return maxDepth;
    }

    /** @return maximum characters of a display text before it is cut with an ellipsis */
    public int getMaxTextLength() {
        return maxTextLength;
    }

    /** @return number of array elements listed in an array's display text */
    public int getMaxListedElements() {
        return maxListedElements;
    }

    /** @return number of octets shown as hex in the display text of opaque data */
    public int getMaxHexOctets() {
        return maxHexOctets;
    }

    /** @return cap for raw hex in serialized output ({@code valueRaw}); contract default 256 */
    public int getRawCap() {
        return rawCap;
    }

    /** @return trailing octet policy */
    public TrailingOctets getTrailingOctets() {
        return trailingOctets;
    }

    /** @return enumerator display */
    public EnumText getEnumText() {
        return enumText;
    }

    /**
     * @param encodingName {@code HLAextendableVariantRecord} or {@code RPRextendedVariantRecord}
     * @return the configured layout, {@link VariantLayout#AUTO} by default
     */
    public VariantLayout getVariantLayout(String encodingName) {
        VariantLayout l = variantLayouts.get(encodingName);
        return l == null ? VariantLayout.AUTO : l;
    }

    /** @return basic representation interpretation overrides, by basic datatype name */
    public Map<String, BasicInterpretation> getBasicInterpretations() {
        return basicInterpretations;
    }

    /** @return encoding handler overrides and additions, by encoding name */
    public Map<String, EncodingHandler> getEncodingHandlers() {
        return encodingHandlers;
    }

    /**
     * @return element count rules for length-less arrays: {@code "Record.Field"} to an
     *         expression over the record's earlier fields
     */
    public Map<String, String> getCountRules() {
        return countRules;
    }

    /** @return value formatters, applied in order */
    public List<ValueFormatter> getFormatters() {
        return formatters;
    }

    /** @return false when formatters are switched off (structural decoding only) */
    public boolean isFormattersEnabled() {
        return formattersEnabled;
    }

    /** @return the clock */
    public Clock getClock() {
        return clock;
    }

    /** Builder; not thread safe. */
    public static final class Builder {
        private int maxElements = 100_000;
        private int maxDepth = 64;
        private int maxTextLength = 1024;
        private int maxListedElements = 16;
        private int maxHexOctets = 32;
        private int rawCap = 256;
        private TrailingOctets trailingOctets = TrailingOctets.WARN;
        private EnumText enumText = EnumText.NAME;
        private final Map<String, VariantLayout> variantLayouts = new LinkedHashMap<String, VariantLayout>();
        private final Map<String, BasicInterpretation> basicInterpretations = new LinkedHashMap<String, BasicInterpretation>();
        private final Map<String, EncodingHandler> encodingHandlers = new LinkedHashMap<String, EncodingHandler>();
        private final Map<String, String> countRules = new LinkedHashMap<String, String>();
        private final List<ValueFormatter> formatters = new ArrayList<ValueFormatter>();
        private boolean formattersEnabled = true;
        private Clock clock = new Clock() {
            @Override
            public long currentTimeMillis() {
                return System.currentTimeMillis();
            }
        };

        private Builder() {
        }

        /** @param v maximum array elements as nodes (>= 1) @return this */
        public Builder maxElements(int v) {
            this.maxElements = positive(v, "maxElements");
            return this;
        }

        /** @param v maximum nesting depth (>= 1) @return this */
        public Builder maxDepth(int v) {
            this.maxDepth = positive(v, "maxDepth");
            return this;
        }

        /** @param v maximum display text length (>= 8) @return this */
        public Builder maxTextLength(int v) {
            this.maxTextLength = Math.max(8, v);
            return this;
        }

        /** @param v array elements listed in display text (>= 1) @return this */
        public Builder maxListedElements(int v) {
            this.maxListedElements = positive(v, "maxListedElements");
            return this;
        }

        /** @param v octets of opaque data shown as hex in display text (>= 1) @return this */
        public Builder maxHexOctets(int v) {
            this.maxHexOctets = positive(v, "maxHexOctets");
            return this;
        }

        /** @param v raw hex cap for serialized output, negative = no cap @return this */
        public Builder rawCap(int v) {
            this.rawCap = v;
            return this;
        }

        /** @param v trailing octet policy @return this */
        public Builder trailingOctets(TrailingOctets v) {
            this.trailingOctets = v;
            return this;
        }

        /** @param v enumerator display @return this */
        public Builder enumText(EnumText v) {
            this.enumText = v;
            return this;
        }

        /**
         * @param encodingName {@code HLAextendableVariantRecord} or {@code RPRextendedVariantRecord}
         * @param layout       layout to use
         * @return this
         */
        public Builder variantLayout(String encodingName, VariantLayout layout) {
            variantLayouts.put(encodingName, layout);
            return this;
        }

        /**
         * Tells the decoder how to read a basic representation the FOM does not describe
         * precisely enough (the OMT basic table has size and endianness only).
         *
         * @param basicTypeName  basic datatype name
         * @param interpretation interpretation
         * @return this
         */
        public Builder basicInterpretation(String basicTypeName, BasicInterpretation interpretation) {
            basicInterpretations.put(basicTypeName, interpretation);
            return this;
        }

        /**
         * Adds or replaces the handler of an array / record encoding.
         *
         * @param encodingName encoding name as written in the FOM
         * @param handler      handler
         * @return this
         */
        public Builder encodingHandler(String encodingName, EncodingHandler handler) {
            encodingHandlers.put(encodingName, handler);
            return this;
        }

        /**
         * Element count of a length-less array field, as an expression over earlier fields of
         * the same record, e.g. {@code countRule("SphericalHarmonicAntennaStruct.Coefficients",
         * "Order*Order+2*Order+1")}. Without a rule a length-less array takes the rest of the
         * enclosing data.
         *
         * @param recordDotField record datatype name + '.' + field name
         * @param expression     integer expression: field names, integer literals, + - * / ( )
         * @return this
         * @throws IllegalArgumentException if the expression does not parse
         */
        public Builder countRule(String recordDotField, String expression) {
            CountExpression.parse(expression);
            countRules.put(recordDotField, expression);
            return this;
        }

        /** @param f formatter to append @return this */
        public Builder formatter(ValueFormatter f) {
            formatters.add(f);
            return this;
        }

        /** @param f formatters to append @return this */
        public Builder formatters(List<? extends ValueFormatter> f) {
            formatters.addAll(f);
            return this;
        }

        /** @return this, with all formatters removed */
        public Builder clearFormatters() {
            formatters.clear();
            return this;
        }

        /** @param v false to decode structurally only @return this */
        public Builder formattersEnabled(boolean v) {
            this.formattersEnabled = v;
            return this;
        }

        /** @param v clock (not null) @return this */
        public Builder clock(Clock v) {
            if (v == null) {
                throw new NullPointerException("clock");
            }
            this.clock = v;
            return this;
        }

        /** @return the options */
        public DecoderOptions build() {
            return new DecoderOptions(this);
        }

        private static int positive(int v, String what) {
            if (v < 1) {
                throw new IllegalArgumentException(what + " must be >= 1: " + v);
            }
            return v;
        }
    }
}
