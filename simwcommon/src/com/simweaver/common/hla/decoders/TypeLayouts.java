package com.simweaver.common.hla.decoders;

import com.simweaver.common.iem.ArrayDataType;
import com.simweaver.common.iem.BasicDataType;
import com.simweaver.common.iem.DataType;
import com.simweaver.common.iem.DataTypeCategory;
import com.simweaver.common.iem.ElementKind;
import com.simweaver.common.iem.EnumeratedDataType;
import com.simweaver.common.iem.FixedRecordDataType;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.iem.KnownEncoding;
import com.simweaver.common.iem.SimpleDataType;
import com.simweaver.common.iem.VariantRecordDataType;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Encoding properties of every datatype of a model, computed once: octet boundary, fixed
 * size (or -1), a lower bound of the size, the {@link EncodingHandler} of array and record
 * types, and how basic representations are read. Immutable after construction; thread safe.
 * <p>
 * Octet boundaries of recursive datatypes (a record holding a variable array of itself) are
 * solved as a fixed point; fixed sizes of recursive datatypes are -1.
 */
public final class TypeLayouts {

    private static final int UNKNOWN = Integer.MIN_VALUE;
    private static final int IN_PROGRESS = Integer.MIN_VALUE + 1;

    private final InformationExchangeModel model;
    private final int firstId;
    private final int count;
    private final int[] boundary;
    private final int[] fixedSize;
    private final int[] minSize;
    private final EncodingHandler[] handlers;
    private final String[] fallbackFrom;
    private final BasicInterpretation[] interpretation;
    private final boolean[] charElement;
    private final boolean[] octetElement;

    TypeLayouts(InformationExchangeModel model, Map<String, EncodingHandler> handlerByName,
                Map<String, BasicInterpretation> basicOverrides) {
        this.model = model;
        this.firstId = model.getFirstId(ElementKind.DATA_TYPE);
        this.count = model.getElementCount(ElementKind.DATA_TYPE);
        this.boundary = new int[count];
        this.fixedSize = new int[count];
        this.minSize = new int[count];
        this.handlers = new EncodingHandler[count];
        this.fallbackFrom = new String[count];
        this.interpretation = new BasicInterpretation[count];
        this.charElement = new boolean[count];
        this.octetElement = new boolean[count];

        List<DataType> types = model.getDataTypes();
        for (DataType t : types) {
            int i = t.getIndex();
            boundary[i] = 1;
            fixedSize[i] = UNKNOWN;
            minSize[i] = UNKNOWN;
            if (t instanceof BasicDataType) {
                interpretation[i] = BasicInterpretation.of((BasicDataType) t, basicOverrides);
            }
            DataTypeCategory c = t.getCategory();
            if (c == DataTypeCategory.ARRAY || c == DataTypeCategory.FIXED_RECORD || c == DataTypeCategory.VARIANT_RECORD) {
                String enc = t.getEncoding() == null ? "" : t.getEncoding().trim();
                EncodingHandler h = handlerByName.get(enc);
                if (h == null) {
                    String std = fallbackEncoding(t);
                    h = handlerByName.get(std);
                    fallbackFrom[i] = enc.isEmpty() ? "(none)" : enc;
                }
                handlers[i] = h;
            }
        }
        for (DataType t : types) {
            int i = t.getIndex();
            charElement[i] = isCharType(t);
            octetElement[i] = !charElement[i] && isOctetType(t);
        }
        // octet boundaries: monotone fixed point (values only grow, bounded by the largest basic size)
        boolean changed = true;
        int rounds = 0;
        while (changed) {
            changed = false;
            rounds++;
            for (DataType t : types) {
                int b = Math.max(1, computeBoundary(t));
                if (b > boundary[t.getIndex()]) {
                    boundary[t.getIndex()] = b;
                    changed = true;
                }
            }
            if (rounds > types.size() + 2) {
                break;   // cannot happen for well formed input; guards against a misbehaving handler
            }
        }
        for (DataType t : types) {
            getFixedSize(t);
            getMinSize(t);
        }
    }

    private static String fallbackEncoding(DataType t) {
        switch (t.getCategory()) {
            case ARRAY:
                ArrayDataType a = (ArrayDataType) t;
                return a.getCardinality() != null && !a.isDynamic()
                    ? KnownEncoding.HLA_FIXED_ARRAY.getOmtName() : KnownEncoding.HLA_VARIABLE_ARRAY.getOmtName();
            case FIXED_RECORD:
                return KnownEncoding.HLA_FIXED_RECORD.getOmtName();
            default:
                return KnownEncoding.HLA_VARIANT_RECORD.getOmtName();
        }
    }

    private int computeBoundary(DataType t) {
        switch (t.getCategory()) {
            case BASIC:
                return Math.max(1, ((BasicDataType) t).getSizeOctets());
            case SIMPLE: {
                BasicDataType r = ((SimpleDataType) t).getRepresentation();
                return r == null ? 1 : boundary[r.getIndex()];
            }
            case ENUMERATED: {
                BasicDataType r = ((EnumeratedDataType) t).getRepresentation();
                return r == null ? 1 : boundary[r.getIndex()];
            }
            case ARRAY:
            case FIXED_RECORD:
            case VARIANT_RECORD: {
                EncodingHandler h = handlers[t.getIndex()];
                return h == null ? 1 : h.octetBoundary(t, this);
            }
            default:
                return 1;
        }
    }

    /** @return the model these layouts belong to */
    public InformationExchangeModel getModel() {
        return model;
    }

    /**
     * @param t datatype of this model
     * @return octet boundary (1, 2, 4, 8 ...)
     */
    public int getOctetBoundary(DataType t) {
        return t == null ? 1 : boundary[t.getIndex()];
    }

    /**
     * @param t datatype of this model
     * @return encoded size in octets when every value has the same size, else -1
     */
    public int getFixedSize(DataType t) {
        if (t == null) {
            return 0;
        }
        int i = t.getIndex();
        int v = fixedSize[i];
        if (v == IN_PROGRESS) {
            return -1;   // recursive: not fixed
        }
        if (v != UNKNOWN) {
            return v;
        }
        fixedSize[i] = IN_PROGRESS;
        int r = computeFixedSize(t);
        fixedSize[i] = r;
        return r;
    }

    private int computeFixedSize(DataType t) {
        switch (t.getCategory()) {
            case BASIC:
                return ((BasicDataType) t).getSizeOctets();
            case SIMPLE: {
                BasicDataType r = ((SimpleDataType) t).getRepresentation();
                return r == null ? -1 : getFixedSize(r);
            }
            case ENUMERATED: {
                BasicDataType r = ((EnumeratedDataType) t).getRepresentation();
                return r == null ? -1 : getFixedSize(r);
            }
            case ARRAY:
            case FIXED_RECORD:
            case VARIANT_RECORD: {
                EncodingHandler h = handlers[t.getIndex()];
                return h == null ? -1 : h.fixedSize(t, this);
            }
            default:
                return -1;
        }
    }

    /**
     * @param t datatype of this model
     * @return lower bound of the encoded size in octets
     */
    public int getMinSize(DataType t) {
        if (t == null) {
            return 0;
        }
        int i = t.getIndex();
        int v = minSize[i];
        if (v == IN_PROGRESS) {
            return 0;
        }
        if (v != UNKNOWN) {
            return v;
        }
        minSize[i] = IN_PROGRESS;
        int r;
        int fixed = getFixedSize(t);
        if (fixed >= 0) {
            r = fixed;
        } else {
            switch (t.getCategory()) {
                case ARRAY:
                case FIXED_RECORD:
                case VARIANT_RECORD: {
                    EncodingHandler h = handlers[i];
                    r = h == null ? 0 : Math.max(0, h.minSize(t, this));
                    break;
                }
                default:
                    r = 0;
            }
        }
        minSize[i] = r;
        return r;
    }

    /**
     * @param t array or record datatype
     * @return its handler, or null for basic, simple, enumerated and unknown-table types
     */
    public EncodingHandler getHandler(DataType t) {
        return t == null ? null : handlers[t.getIndex()];
    }

    /**
     * @param t datatype
     * @return the unknown encoding name when the datatype is decoded with a standard fallback
     *         (HLAfixedArray/HLAvariableArray/HLAfixedRecord/HLAvariantRecord), else null
     */
    public String getFallbackEncoding(DataType t) {
        return t == null ? null : fallbackFrom[t.getIndex()];
    }

    /**
     * @param t basic datatype
     * @return how it is read
     */
    public BasicInterpretation getInterpretation(BasicDataType t) {
        return interpretation[t.getIndex()];
    }

    /**
     * @param t datatype
     * @return true for character element types (HLAASCIIchar, HLAunicodeChar, any simple
     *         type named *char with a 1 or 2 octet representation)
     */
    public boolean isCharType(DataType t) {
        if (t == null) {
            return false;
        }
        if (t.getIndex() < count && t.getIndex() >= 0 && charElement[t.getIndex()]) {
            return true;
        }
        if (!(t instanceof SimpleDataType)) {
            return false;
        }
        BasicDataType r = ((SimpleDataType) t).getRepresentation();
        String n = t.getName().toLowerCase(Locale.ROOT);
        return r != null && (r.getSizeOctets() == 1 || r.getSizeOctets() == 2) && n.endsWith("char");
    }

    /**
     * @param t datatype
     * @return true for opaque octet element types (HLAoctet, HLAbyte, RPR Octet ...): an array
     *         of them is shown as hex rather than one node per octet
     */
    public boolean isOctetType(DataType t) {
        if (t == null) {
            return false;
        }
        if (t.getIndex() >= 0 && t.getIndex() < count && octetElement[t.getIndex()]) {
            return true;
        }
        BasicDataType r;
        if (t instanceof BasicDataType) {
            r = (BasicDataType) t;
        } else if (t instanceof SimpleDataType) {
            r = ((SimpleDataType) t).getRepresentation();
        } else {
            return false;
        }
        if (r == null || r.getSizeOctets() != 1) {
            return false;
        }
        String n = t.getName().toLowerCase(Locale.ROOT);
        return n.contains("octet") || n.contains("byte");
    }

    /**
     * Size of one array element including the padding before the next one.
     *
     * @param element element datatype
     * @return stride, or -1 when the element size is not fixed
     */
    public int getStride(DataType element) {
        int s = getFixedSize(element);
        if (s < 0) {
            return -1;
        }
        int b = getOctetBoundary(element);
        return (s + b - 1) / b * b;
    }

    /**
     * Fixed size of {@code n} consecutive elements (padding between, none after the last).
     *
     * @param element element datatype
     * @param n       element count
     * @return octets, or -1
     */
    public int getFixedArraySize(DataType element, long n) {
        if (n == 0) {
            return 0;
        }
        int s = getFixedSize(element);
        int stride = getStride(element);
        if (s < 0) {
            return -1;
        }
        long total = (n - 1) * (long) stride + s;
        return total > Integer.MAX_VALUE ? -1 : (int) total;
    }

    /**
     * Fixed size of a record laid out from an aligned start.
     *
     * @param record record datatype
     * @return octets, or -1
     */
    public int getFixedRecordSize(FixedRecordDataType record) {
        int pos = 0;
        for (int f = 0; f < record.getFields().size(); f++) {
            DataType ft = record.getFields().get(f).getDataType();
            int s = getFixedSize(ft);
            if (s < 0 || ft == null) {
                return -1;
            }
            pos = align(pos, getOctetBoundary(ft)) + s;
        }
        return pos;
    }

    /**
     * Fixed size of a plain variant record: only when every alternative has the same size
     * once aligned after the discriminant.
     *
     * @param v variant datatype
     * @return octets, or -1
     */
    public int getFixedVariantSize(VariantRecordDataType v) {
        DataType d = v.getDiscriminantType();
        int ds = getFixedSize(d);
        if (d == null || ds < 0 || v.getAlternatives().isEmpty() || v.getOtherAlternative() == null
                && hasUncoveredEnumerators(v)) {
            return -1;
        }
        int size = -1;
        for (int i = 0; i < v.getAlternatives().size(); i++) {
            DataType at = v.getAlternatives().get(i).getDataType();
            int s = at == null ? ds : align(ds, getOctetBoundary(at)) + getFixedSize(at);
            if (at != null && getFixedSize(at) < 0) {
                return -1;
            }
            if (size >= 0 && s != size) {
                return -1;
            }
            size = s;
        }
        return size;
    }

    private static boolean hasUncoveredEnumerators(VariantRecordDataType v) {
        EnumeratedDataType d = v.getDiscriminantType();
        for (int i = 0; i < d.getEnumerators().size(); i++) {
            if (v.getAlternativeForValue(d.getEnumerators().get(i).getValue()) == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param pos      offset
     * @param boundary octet boundary
     * @return pos rounded up to the boundary
     */
    public static int align(int pos, int boundary) {
        int r = pos % boundary;
        return r == 0 ? pos : pos + boundary - r;
    }
}
