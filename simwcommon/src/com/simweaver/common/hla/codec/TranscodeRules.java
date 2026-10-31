package com.simweaver.common.hla.codec;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Renames and value maps that {@link HlaCodec#encode(com.simweaver.common.iem.DataType, HValue,
 * TranscodeRules, HlaCodec.Issues)} applies when the value comes from another model. Without
 * a rule the encoder matches record fields and enumerators by name, and enumerators by value
 * when the name is unknown in the target.
 *
 * <p>Build once, then share: immutable after {@link Builder#build()}, thread safe.
 */
public final class TranscodeRules {

    /** No rules. */
    public static final TranscodeRules NONE = builder().build();

    private final Map<String, String> fieldSources;          // "TargetRecord.TargetField" -> source field name
    private final Set<String> defaulted;                      // "TargetRecord.TargetField": default without a warning
    private final Map<String, Map<Long, Long>> enumMaps;      // "SourceEnum>TargetEnum" -> value map
    private final Map<String, java.util.function.UnaryOperator<HValue>> adapters;  // "SourceType>TargetType"

    private TranscodeRules(Builder b) {
        this.fieldSources = Collections.unmodifiableMap(new HashMap<String, String>(b.fieldSources));
        this.defaulted = Collections.unmodifiableSet(new HashSet<String>(b.defaulted));
        Map<String, Map<Long, Long>> m = new HashMap<String, Map<Long, Long>>();
        for (Map.Entry<String, Map<Long, Long>> e : b.enumMaps.entrySet()) {
            m.put(e.getKey(), Collections.unmodifiableMap(new HashMap<Long, Long>(e.getValue())));
        }
        this.enumMaps = Collections.unmodifiableMap(m);
        this.adapters = Collections.unmodifiableMap(new HashMap<String, java.util.function.UnaryOperator<HValue>>(b.adapters));
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @param targetRecord target record datatype name
     * @param targetField  target field name
     * @return the source field name to use, or null (= same name)
     */
    public String sourceField(String targetRecord, String targetField) {
        return fieldSources.isEmpty() ? null : fieldSources.get(targetRecord + "." + targetField);
    }

    /** @return true when the field is known to have no source: write the default silently */
    public boolean isDefaulted(String targetRecord, String targetField) {
        return !defaulted.isEmpty() && defaulted.contains(targetRecord + "." + targetField);
    }

    /**
     * @param sourceEnum  source enumerated datatype name
     * @param targetEnum  target enumerated datatype name
     * @param sourceValue the value
     * @return the mapped value, or null when there is no map or the value is not in it
     */
    public Long enumValue(String sourceEnum, String targetEnum, long sourceValue) {
        if (enumMaps.isEmpty()) {
            return null;
        }
        Map<Long, Long> m = enumMaps.get(sourceEnum + ">" + targetEnum);
        return m == null ? null : m.get(sourceValue);
    }

    /**
     * Reshapes a value of one datatype for another where renames are not enough (a record that
     * became a variant, say).
     *
     * @param sourceType the value's datatype name
     * @param targetType the datatype written
     * @param value      the value
     * @return the value to write (encoded by field and enumerator names as usual), or null when
     *         there is no adapter for the pair
     */
    public HValue adapt(String sourceType, String targetType, HValue value) {
        if (adapters.isEmpty()) {
            return null;
        }
        java.util.function.UnaryOperator<HValue> a = adapters.get(sourceType + ">" + targetType);
        return a == null ? null : a.apply(value);
    }

    /** @return true when an adapter is set for the pair (see {@link #adapt}) */
    public boolean hasAdapter(String sourceType, String targetType) {
        return !adapters.isEmpty() && adapters.containsKey(sourceType + ">" + targetType);
    }

    /**
     * True when a value of {@code sourceType} written as {@code targetType} must be converted
     * even if both encode the same way: an enumerator value map or an adapter is set for the pair.
     */
    public boolean changesValues(String sourceType, String targetType) {
        String k = sourceType + ">" + targetType;
        return enumMaps.containsKey(k) || adapters.containsKey(k);
    }

    /** @return true when no rule is set */
    public boolean isEmpty() {
        return fieldSources.isEmpty() && defaulted.isEmpty() && enumMaps.isEmpty() && adapters.isEmpty();
    }

    /** Collects rules. */
    public static final class Builder {
        private final Map<String, String> fieldSources = new HashMap<String, String>();
        private final Set<String> defaulted = new HashSet<String>();
        private final Map<String, Map<Long, Long>> enumMaps = new HashMap<String, Map<Long, Long>>();
        private final Map<String, java.util.function.UnaryOperator<HValue>> adapters
                = new HashMap<String, java.util.function.UnaryOperator<HValue>>();

        private Builder() {
        }

        /** Target field {@code targetRecord.targetField} takes the source field {@code sourceField}. */
        public Builder field(String targetRecord, String targetField, String sourceField) {
            fieldSources.put(targetRecord + "." + targetField, sourceField);
            return this;
        }

        /** Target field without a source: write its default value without reporting it. */
        public Builder defaulted(String targetRecord, String targetField) {
            defaulted.add(targetRecord + "." + targetField);
            return this;
        }

        /** Maps one enumerator value from {@code sourceEnum} to {@code targetEnum}. */
        public Builder enumValue(String sourceEnum, String targetEnum, long from, long to) {
            String k = sourceEnum + ">" + targetEnum;
            Map<Long, Long> m = enumMaps.get(k);
            if (m == null) {
                m = new HashMap<Long, Long>();
                enumMaps.put(k, m);
            }
            m.put(from, to);
            return this;
        }

        /**
         * A value of {@code sourceType} written as {@code targetType} is first passed through
         * {@code adapter} (see {@link TranscodeRules#adapt}). The adapter must be thread safe.
         */
        public Builder adapter(String sourceType, String targetType, java.util.function.UnaryOperator<HValue> adapter) {
            adapters.put(sourceType + ">" + targetType, adapter);
            return this;
        }

        /** @return the rules */
        public TranscodeRules build() {
            return new TranscodeRules(this);
        }
    }
}
