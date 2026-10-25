package com.simweaver.common.hla.codec;

import com.simweaver.common.hla.decoders.BasicInterpretation;
import com.simweaver.common.hla.decoders.TypeLayouts;
import com.simweaver.common.iem.ArrayDataType;
import com.simweaver.common.iem.BasicDataType;
import com.simweaver.common.iem.DataType;
import com.simweaver.common.iem.Endianness;
import com.simweaver.common.iem.EnumeratedDataType;
import com.simweaver.common.iem.Enumerator;
import com.simweaver.common.iem.FixedRecordDataType;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.iem.RecordField;
import com.simweaver.common.iem.SimpleDataType;
import com.simweaver.common.iem.VariantAlternative;
import com.simweaver.common.iem.VariantRecordDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * Structural facts about the datatypes of one model, computed once:
 *
 * <ul>
 *   <li><b>Signatures</b> ({@link #signature(DataType)}): a text that is equal for two
 *       datatypes - of the same or of different models - exactly when they encode the same
 *       values to the same octets. It covers sizes, endianness, integer vs floating point,
 *       array encodings and fixed counts, record field order and the variant alternatives per
 *       discriminant value. Names of datatypes, fields and enumerators do not count. Equal
 *       signatures mean the octets can be passed on unchanged.</li>
 *   <li><b>Object references</b> ({@link #containsReference(DataType)}): datatypes that hold
 *       an HLA object instance name somewhere (by default the RPR {@code RTIobjectId}), also
 *       nested in records, arrays and variant alternatives. Values of such datatypes are
 *       rewritten when instance names differ between federations
 *       ({@link #rewriteReferences}).</li>
 * </ul>
 *
 * Thread safe after construction.
 */
public final class TypeAnalysis {

    /** The datatype names treated as object instance names unless told otherwise. */
    public static final Set<String> DEFAULT_REFERENCE_TYPES = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("RTIobjectId")));

    private final InformationExchangeModel model;
    private final TypeLayouts layouts;
    private final Set<String> referenceTypes;
    private final String[] signatures;
    private final boolean[] reference;
    private final boolean[] containsReference;

    /**
     * @param codec          the model's codec (for its layouts)
     * @param referenceTypes datatype names that are object instance names; null = the default
     */
    public TypeAnalysis(HlaCodec codec, Collection<String> referenceTypes) {
        this.model = codec.getModel();
        this.layouts = codec.getLayouts();
        this.referenceTypes = Collections.unmodifiableSet(new HashSet<String>(
                referenceTypes == null ? DEFAULT_REFERENCE_TYPES : referenceTypes));
        List<DataType> types = model.getDataTypes();
        int n = 0;
        for (DataType t : types) {
            n = Math.max(n, t.getIndex() + 1);
        }
        this.signatures = new String[n];
        this.reference = new boolean[n];
        this.containsReference = new boolean[n];
        for (DataType t : types) {
            reference[t.getIndex()] = this.referenceTypes.contains(t.getName());
            containsReference[t.getIndex()] = reference[t.getIndex()];
        }
        // contains-reference: least fixed point over the datatype graph (recursive types)
        boolean changed = true;
        while (changed) {
            changed = false;
            for (DataType t : types) {
                int i = t.getIndex();
                if (!containsReference[i] && anyChildContains(t)) {
                    containsReference[i] = true;
                    changed = true;
                }
            }
        }
        for (DataType t : types) {
            signature(t);
        }
    }

    /** @param codec the model's codec; references are the default {@link #DEFAULT_REFERENCE_TYPES} */
    public TypeAnalysis(HlaCodec codec) {
        this(codec, null);
    }

    /** @return the model */
    public InformationExchangeModel getModel() {
        return model;
    }

    /** @return the datatype names treated as object instance names */
    public Set<String> getReferenceTypes() {
        return referenceTypes;
    }

    /** @return true when values of {@code t} are object instance names themselves */
    public boolean isReference(DataType t) {
        return t != null && t.getIndex() >= 0 && t.getIndex() < reference.length && reference[t.getIndex()]
                && t.getName() != null && referenceTypes.contains(t.getName());
    }

    /** @return true when values of {@code t} hold an object instance name somewhere */
    public boolean containsReference(DataType t) {
        return t != null && t.getIndex() >= 0 && t.getIndex() < containsReference.length && containsReference[t.getIndex()];
    }

    private boolean anyChildContains(DataType t) {
        for (DataType c : children(t)) {
            if (c != null && containsReference[c.getIndex()]) {
                return true;
            }
        }
        return false;
    }

    private static List<DataType> children(DataType t) {
        switch (t.getCategory()) {
            case ARRAY:
                return Collections.singletonList(((ArrayDataType) t).getElementType());
            case FIXED_RECORD: {
                List<DataType> l = new ArrayList<DataType>();
                for (RecordField f : ((FixedRecordDataType) t).getFields()) {
                    l.add(f.getDataType());
                }
                return l;
            }
            case VARIANT_RECORD: {
                List<DataType> l = new ArrayList<DataType>();
                for (VariantAlternative a : ((VariantRecordDataType) t).getAlternatives()) {
                    l.add(a.getDataType());
                }
                return l;
            }
            default:
                return Collections.emptyList();
        }
    }

    // ------------------------------------------------------------------ references in values

    /**
     * Replaces every object instance name in a value decoded with this model's codec.
     *
     * @param v      the value
     * @param rename old name to new name; returns the name itself (or null) to keep it
     * @return the value with names replaced (the same instance when nothing changed)
     */
    public HValue rewriteReferences(HValue v, final UnaryOperator<String> rename) {
        if (v == null) {
            return null;
        }
        return v.transform(node -> {
            if (node.getKind() == HValue.Kind.TEXT && isReference(node.getType())) {
                String old = node.asTrimmedText();
                String n = rename.apply(old);
                if (n != null && !n.equals(old)) {
                    return HValue.ofText(n).withType(node.getType());
                }
            }
            return node;
        });
    }

    /**
     * @param v a value decoded with this model's codec
     * @return the object instance names in it, in order (empty names left out)
     */
    public List<String> references(HValue v) {
        final List<String> out = new ArrayList<String>(2);
        if (v != null) {
            v.transform(node -> {
                if (node.getKind() == HValue.Kind.TEXT && isReference(node.getType())) {
                    String s = node.asTrimmedText();
                    if (!s.isEmpty()) {
                        out.add(s);
                    }
                }
                return node;
            });
        }
        return out;
    }

    // ------------------------------------------------------------------ signatures

    /**
     * @param t a datatype of this model
     * @return its encoding signature (see the class comment); "?" for a null or unresolved datatype
     */
    public String signature(DataType t) {
        if (t == null) {
            return "?";
        }
        String s = t.getIndex() < signatures.length ? signatures[t.getIndex()] : null;
        if (s != null) {
            return s;
        }
        Sig r = sig(t, new ArrayList<DataType>());
        return r.text;
    }

    /** Result of a signature walk: text and the shallowest stack depth a back reference pointed to. */
    private static final class Sig {
        final String text;
        final int minTarget;

        Sig(String text, int minTarget) {
            this.text = text;
            this.minTarget = minTarget;
        }
    }

    private Sig sig(DataType t, List<DataType> stack) {
        if (t == null) {
            return new Sig("?", Integer.MAX_VALUE);
        }
        String cached = t.getIndex() < signatures.length ? signatures[t.getIndex()] : null;
        if (cached != null) {
            return new Sig(cached, Integer.MAX_VALUE);
        }
        int onStack = stack.indexOf(t);
        if (onStack >= 0) {
            // back reference, relative so that the subtree's text does not depend on its position
            return new Sig("#" + (stack.size() - onStack), onStack);
        }
        int depth = stack.size();
        stack.add(t);
        int min = Integer.MAX_VALUE;
        StringBuilder sb = new StringBuilder();
        switch (t.getCategory()) {
            case BASIC:
                sb.append(basic((BasicDataType) t));
                break;
            case SIMPLE: {
                BasicDataType r = ((SimpleDataType) t).getRepresentation();
                sb.append(r == null ? "?" : basic(r));
                break;
            }
            case ENUMERATED: {
                BasicDataType r = ((EnumeratedDataType) t).getRepresentation();
                sb.append(r == null ? "?" : basic(r));
                break;
            }
            case ARRAY: {
                ArrayDataType a = (ArrayDataType) t;
                HlaCodec.ArrayEncoding enc = HlaCodec.arrayEncoding(a);
                sb.append('A').append(enc.name().charAt(0)).append(enc.name().length());
                if (enc == HlaCodec.ArrayEncoding.FIXED) {
                    sb.append(a.getCardinality() == null ? 0 : a.getCardinality().getFixedElementCount());
                }
                if (enc != HlaCodec.ArrayEncoding.PAD32 && enc != HlaCodec.ArrayEncoding.PAD64) {
                    Sig e = sig(a.getElementType(), stack);
                    min = Math.min(min, e.minTarget);
                    sb.append('(').append(e.text).append(')');
                }
                break;
            }
            case FIXED_RECORD: {
                sb.append("R{");
                boolean first = true;
                for (RecordField f : ((FixedRecordDataType) t).getFields()) {
                    Sig e = sig(f.getDataType(), stack);
                    min = Math.min(min, e.minTarget);
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    sb.append(e.text);
                }
                sb.append('}');
                break;
            }
            case VARIANT_RECORD: {
                VariantRecordDataType v = (VariantRecordDataType) t;
                sb.append(HlaCodec.isExtendable(v) ? "VX" : "VP");
                EnumeratedDataType d = v.getDiscriminantType();
                sb.append('(').append(d == null || d.getRepresentation() == null ? "?" : basic(d.getRepresentation())).append(')');
                // alternatives by discriminant value, in value order
                TreeMap<Long, String> byValue = new TreeMap<Long, String>();
                Map<VariantAlternative, String> altSig = new HashMap<VariantAlternative, String>();
                for (VariantAlternative a : v.getAlternatives()) {
                    String s;
                    if (a.isEmpty() || a.getDataType() == null) {
                        s = "-";
                    } else {
                        Sig e = sig(a.getDataType(), stack);
                        min = Math.min(min, e.minTarget);
                        s = e.text;
                    }
                    altSig.put(a, s);
                    for (Enumerator en : a.getEnumerators()) {
                        for (Long val : en.getValues()) {
                            byValue.put(val, s);
                        }
                    }
                }
                sb.append('{');
                for (Map.Entry<Long, String> e : byValue.entrySet()) {
                    sb.append(e.getKey()).append(':').append(e.getValue()).append(';');
                }
                VariantAlternative other = v.getOtherAlternative();
                if (other != null) {
                    sb.append("*:").append(altSig.get(other) == null ? "-" : altSig.get(other));
                }
                sb.append('}');
                break;
            }
            default:
                sb.append("O:").append(t.getName());
        }
        stack.remove(stack.size() - 1);
        String text = sb.toString();
        if (min >= depth && t.getIndex() < signatures.length) {
            signatures[t.getIndex()] = text;      // self-contained: safe to cache
        }
        return new Sig(text, min);
    }

    private String basic(BasicDataType b) {
        BasicInterpretation in = layouts.getInterpretation(b);
        char kind = in == BasicInterpretation.FLOAT ? 'F' : in == BasicInterpretation.OPAQUE ? 'O' : 'I';
        return "" + kind + b.getSizeBits() + (b.getEndianness() == Endianness.LITTLE ? 'l' : 'b');
    }
}
