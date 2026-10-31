package com.simweaver.junction.router;

import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.decoders.BasicInterpretation;
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
import com.simweaver.junction.config.ValueRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The router's value rules ({@link ValueRule}) compiled for one endpoint's model: which
 * datatypes they replace values of, and which datatypes hold such a value somewhere (records,
 * arrays, variant alternatives), so the routes know which members to look into.
 *
 * <p>Values are replaced in the <em>source</em> model, before a value is passed on or
 * transcoded, so a rule written for the RPR v2 datatype also works when the destination is RPR
 * v3 (the replaced value is then transcoded as usual). Each value is looked at once: with
 * {@code A -> B} and {@code B -> C}, an A becomes B, not C.
 *
 * <p>Immutable after {@link #compile}; {@link #apply} is thread safe.
 */
public final class ValueMap {

    /** No rules. */
    public static final ValueMap NONE = new ValueMap(Collections.<DataType, Target>emptyMap(), new boolean[0], Collections.<String>emptyList());

    /** The rules for one datatype of the model. */
    private static final class Target {
        final DataType type;
        final boolean record;
        final List<String> fieldNames;
        final List<DataType> fieldTypes;
        final Map<String, long[]> replace = new HashMap<String, long[]>();     // "1.2.225..." -> B

        Target(DataType type, boolean record, List<String> fieldNames, List<DataType> fieldTypes) {
            this.type = type;
            this.record = record;
            this.fieldNames = fieldNames;
            this.fieldTypes = fieldTypes;
        }
    }

    private final Map<DataType, Target> targets;
    private final boolean[] contains;              // by datatype index: holds a target somewhere
    private final List<String> description;

    private ValueMap(Map<DataType, Target> targets, boolean[] contains, List<String> description) {
        this.targets = targets;
        this.contains = contains;
        this.description = description;
    }

    /**
     * @param rules      the router's rules
     * @param tools      the endpoint's model tools
     * @param endpointId for messages
     * @return the rules that apply in this model; {@link #NONE} when none does
     * @throws IllegalArgumentException when a rule does not fit its datatype in this model
     *                                  (field count, a field that is not an integer, a value too large)
     */
    public static ValueMap compile(List<ValueRule> rules, ModelTools tools, String endpointId) {
        if (rules == null || rules.isEmpty()) {
            return NONE;
        }
        Map<DataType, Target> targets = new IdentityHashMap<DataType, Target>();
        List<String> description = new ArrayList<String>();
        for (ValueRule r : rules) {
            DataType t = find(tools, r.getDataType());
            if (t == null) {
                continue;                                  // not in this model: nothing to replace here
            }
            Target tg = targets.get(t);
            if (tg == null) {
                tg = target(t, tools, endpointId);
                targets.put(t, tg);
            }
            if (tg.fieldTypes.size() != r.size()) {
                throw new IllegalArgumentException(r.getKey() + ": " + t.getName() + " in the model of endpoint " + endpointId + " has "
                        + tg.fieldTypes.size() + (tg.record ? " fields " + tg.fieldNames : " value") + ", the rule " + r.size());
            }
            long[] a = r.getFrom(), b = r.getTo();
            for (int i = 0; i < a.length; i++) {
                checkRange(r, tg, i, a[i], endpointId);
                checkRange(r, tg, i, b[i], endpointId);
            }
            tg.replace.put(ValueRule.text(a), b);
            description.add(t.getName() + ": " + ValueRule.text(a) + " -> " + ValueRule.text(b));
        }
        if (targets.isEmpty()) {
            return NONE;
        }
        // which datatypes hold a target somewhere: least fixed point over the datatype graph
        List<DataType> types = tools.model().getDataTypes();
        int n = 0;
        for (DataType t : types) {
            n = Math.max(n, t.getIndex() + 1);
        }
        boolean[] contains = new boolean[n];
        for (DataType t : targets.keySet()) {
            if (t.getIndex() >= 0 && t.getIndex() < n) {
                contains[t.getIndex()] = true;
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (DataType t : types) {
                int i = t.getIndex();
                if (i < 0 || i >= n || contains[i]) {
                    continue;
                }
                for (DataType c : children(t)) {
                    if (c != null && c.getIndex() >= 0 && c.getIndex() < n && contains[c.getIndex()]) {
                        contains[i] = true;
                        changed = true;
                        break;
                    }
                }
            }
        }
        return new ValueMap(targets, contains, Collections.unmodifiableList(description));
    }

    /** @return true when no rule applies in this model */
    public boolean isEmpty() {
        return targets.isEmpty();
    }

    /** @return the rules that apply here, as "EntityTypeStruct: A -> B" */
    public List<String> describe() {
        return description;
    }

    /** @return true when values of {@code t} may hold a value a rule replaces */
    public boolean touches(DataType t) {
        return t != null && t.getIndex() >= 0 && t.getIndex() < contains.length && contains[t.getIndex()];
    }

    /**
     * Replaces the matching values in a value decoded with this model's codec.
     *
     * @param v     the value
     * @param stats counts the replacements (may be null)
     * @return the value with replacements, or {@code v} itself when nothing matched
     */
    public HValue apply(HValue v, final RouterStats stats) {
        if (v == null || targets.isEmpty()) {
            return v;
        }
        return v.transform(node -> {
            DataType t = node.getType();
            Target tg = t == null ? null : targets.get(t);
            if (tg == null) {
                return node;
            }
            long[] b = tg.replace.get(key(node, tg));
            if (b == null) {
                return node;
            }
            if (stats != null) {
                stats.valuesReplaced.incrementAndGet();
            }
            return build(tg, b);
        });
    }

    // ------------------------------------------------------------------ internals

    private static DataType find(ModelTools tools, String name) {
        DataType t = tools.model().getDataType(name);
        if (t != null) {
            return t;
        }
        for (DataType d : tools.model().getDataTypes()) {
            if (d.getName() != null && d.getName().equalsIgnoreCase(name)) {
                return d;
            }
        }
        return null;
    }

    private static Target target(DataType t, ModelTools tools, String endpointId) {
        if (t instanceof FixedRecordDataType) {
            List<String> names = new ArrayList<String>();
            List<DataType> ftypes = new ArrayList<DataType>();
            for (RecordField f : ((FixedRecordDataType) t).getFields()) {
                if (bits(f.getDataType(), tools) <= 0) {
                    throw new IllegalArgumentException("value rules for " + t.getName() + " (endpoint " + endpointId + "): field "
                            + f.getName() + " is not an integer or enumeration");
                }
                names.add(f.getName());
                ftypes.add(f.getDataType());
            }
            return new Target(t, true, Collections.unmodifiableList(names), Collections.unmodifiableList(ftypes));
        }
        if (bits(t, tools) <= 0) {
            throw new IllegalArgumentException("value rules for " + t.getName() + " (endpoint " + endpointId
                    + "): only integers, enumerations and records of them can have value rules");
        }
        return new Target(t, false, Collections.singletonList(t.getName()), Collections.singletonList(t));
    }

    /** Size in bits of an integer-like datatype; 0 when it is not one. */
    private static int bits(DataType t, ModelTools tools) {
        BasicDataType b = null;
        if (t instanceof BasicDataType) {
            b = (BasicDataType) t;
        } else if (t instanceof SimpleDataType) {
            b = ((SimpleDataType) t).getRepresentation();
        } else if (t instanceof EnumeratedDataType) {
            b = ((EnumeratedDataType) t).getRepresentation();
        }
        if (b == null) {
            return 0;
        }
        BasicInterpretation in = tools.codec().getLayouts().getInterpretation(b);
        if (in != BasicInterpretation.SIGNED && in != BasicInterpretation.UNSIGNED) {
            return 0;
        }
        return b.getSizeBits();
    }

    private static void checkRange(ValueRule r, Target tg, int i, long v, String endpointId) {
        int bits = 0;
        DataType ft = tg.fieldTypes.get(i);
        BasicDataType b = ft instanceof BasicDataType ? (BasicDataType) ft : ft instanceof SimpleDataType ? ((SimpleDataType) ft).getRepresentation()
                : ft instanceof EnumeratedDataType ? ((EnumeratedDataType) ft).getRepresentation() : null;
        if (b != null) {
            bits = b.getSizeBits();
        }
        if (bits <= 0 || bits >= 64) {
            return;
        }
        long max = (1L << bits) - 1, min = -(1L << (bits - 1));
        if (v < min || v > max) {
            throw new IllegalArgumentException(r.getKey() + ": " + v + " does not fit " + tg.type.getName() + "." + tg.fieldNames.get(i)
                    + " (" + bits + " bits) in the model of endpoint " + endpointId);
        }
    }

    private static String key(HValue node, Target tg) {
        if (!tg.record) {
            return node.isNumeric() ? String.valueOf(node.asLong()) : "";
        }
        List<HValue> vs = node.getValues();
        if (vs == null || vs.size() != tg.fieldTypes.size()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < vs.size(); i++) {
            HValue f = vs.get(i);
            if (f == null || !f.isNumeric()) {
                return "";
            }
            if (i > 0) {
                sb.append('.');
            }
            sb.append(f.asLong());
        }
        return sb.toString();
    }

    private static HValue build(Target tg, long[] b) {
        if (!tg.record) {
            return number(tg.type, b[0]);
        }
        Map<String, HValue> fields = new LinkedHashMap<String, HValue>();
        for (int i = 0; i < b.length; i++) {
            fields.put(tg.fieldNames.get(i), number(tg.fieldTypes.get(i), b[i]));
        }
        return HValue.ofRecord(fields).withType(tg.type);
    }

    private static HValue number(DataType t, long v) {
        if (t instanceof EnumeratedDataType) {
            Enumerator en = ((EnumeratedDataType) t).getEnumeratorByValue(v);
            return HValue.ofEnum(v, en == null ? null : en.getName()).withType(t);
        }
        return HValue.ofInt(v).withType(t);
    }

    private static List<DataType> children(DataType t) {
        if (t instanceof ArrayDataType) {
            return Collections.singletonList(((ArrayDataType) t).getElementType());
        }
        if (t instanceof FixedRecordDataType) {
            List<DataType> l = new ArrayList<DataType>();
            for (RecordField f : ((FixedRecordDataType) t).getFields()) {
                l.add(f.getDataType());
            }
            return l;
        }
        if (t instanceof VariantRecordDataType) {
            List<DataType> l = new ArrayList<DataType>();
            for (VariantAlternative a : ((VariantRecordDataType) t).getAlternatives()) {
                l.add(a.getDataType());
            }
            return l;
        }
        return Collections.emptyList();
    }
}
