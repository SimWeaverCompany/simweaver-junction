package com.simweaver.common.hla.codec;

import com.simweaver.common.iem.ArrayCardinality;
import com.simweaver.common.iem.ArrayDataType;
import com.simweaver.common.iem.DataType;
import com.simweaver.common.iem.Enumerator;
import com.simweaver.common.iem.FixedRecordDataType;
import com.simweaver.common.iem.RecordField;
import com.simweaver.common.iem.VariantAlternative;
import com.simweaver.common.iem.VariantRecordDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Test values that hold object instance names (RTIobjectId) in every place a datatype can hold
 * one: the reference itself, each record field that contains one, two elements of a dynamic
 * array (each element of a short fixed one), and in a variant record an alternative that contains
 * one (for arrays of variants: a different such alternative per element, round robin).
 * Everything else is the codec's default value.
 */
public final class RefSamples {

    private final HlaCodec codec;
    private final TypeAnalysis ta;
    private final Supplier<String> names;
    private int turn;

    /** @param names gives the next instance name to put in */
    public RefSamples(HlaCodec codec, TypeAnalysis ta, Supplier<String> names) {
        this.codec = codec;
        this.ta = ta;
        this.names = names;
    }

    public HValue sample(DataType t) {
        return sample(t, 0);
    }

    /** @return the kinds of datatypes passed through on the way to the references (ARRAY, RECORD, VARIANT) */
    public static List<String> path(TypeAnalysis ta, DataType t) {
        List<String> out = new ArrayList<>();
        walk(ta, t, out, 0);
        return out;
    }

    private static void walk(TypeAnalysis ta, DataType t, List<String> out, int depth) {
        if (t == null || depth > 12 || ta.isReference(t) || !ta.containsReference(t)) {
            return;
        }
        switch (t.getCategory()) {
            case ARRAY:
                out.add("ARRAY");
                walk(ta, ((ArrayDataType) t).getElementType(), out, depth + 1);
                break;
            case FIXED_RECORD:
                out.add("RECORD");
                for (RecordField f : ((FixedRecordDataType) t).getFields()) {
                    walk(ta, f.getDataType(), out, depth + 1);
                }
                break;
            case VARIANT_RECORD:
                out.add("VARIANT");
                for (VariantAlternative a : ((VariantRecordDataType) t).getAlternatives()) {
                    walk(ta, a.getDataType(), out, depth + 1);
                }
                break;
            default:
                break;
        }
    }

    private HValue sample(DataType t, int depth) {
        if (ta.isReference(t)) {
            return HValue.ofText(names.get()).withType(t);
        }
        if (!ta.containsReference(t) || depth > 12) {
            return codec.defaultValue(t);
        }
        switch (t.getCategory()) {
            case ARRAY: {
                ArrayDataType a = (ArrayDataType) t;
                int n = 2;
                ArrayCardinality c = a.getCardinality();
                if (c != null && !c.getDimensions().isEmpty() && c.getDimensions().get(0).isFixed()) {
                    n = Math.min(4, c.getDimensions().get(0).getMin());
                }
                List<HValue> el = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    el.add(sample(a.getElementType(), depth + 1));
                }
                return HValue.ofArray(el).withType(t);
            }
            case FIXED_RECORD: {
                HValue r = codec.defaultValue(t);
                for (RecordField f : ((FixedRecordDataType) t).getFields()) {
                    if (ta.containsReference(f.getDataType())) {
                        r = r.withField(f.getName(), sample(f.getDataType(), depth + 1));
                    }
                }
                return r;
            }
            case VARIANT_RECORD: {
                VariantRecordDataType v = (VariantRecordDataType) t;
                List<VariantAlternative> withRef = new ArrayList<>();
                for (VariantAlternative alt : v.getAlternatives()) {
                    if (!alt.isEmpty() && !alt.getEnumerators().isEmpty() && ta.containsReference(alt.getDataType())) {
                        withRef.add(alt);
                    }
                }
                if (withRef.isEmpty()) {
                    return codec.defaultValue(t);
                }
                VariantAlternative alt = withRef.get(turn++ % withRef.size());
                Enumerator e = alt.getEnumerators().get(0);
                return HValue.ofVariant(e.getValue(), e.getName(), alt.getName(), sample(alt.getDataType(), depth + 1)).withType(t);
            }
            default:
                return codec.defaultValue(t);
        }
    }
}
