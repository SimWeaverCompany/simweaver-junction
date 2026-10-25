package com.simweaver.common.hla.codec;

import com.simweaver.common.hla.decoders.BasicInterpretation;
import com.simweaver.common.hla.decoders.HlaDataDecoder;
import com.simweaver.common.hla.decoders.TypeLayouts;
import com.simweaver.common.iem.ArrayDataType;
import com.simweaver.common.iem.BasicDataType;
import com.simweaver.common.iem.DataType;
import com.simweaver.common.iem.Endianness;
import com.simweaver.common.iem.EnumeratedDataType;
import com.simweaver.common.iem.Enumerator;
import com.simweaver.common.iem.FixedRecordDataType;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.iem.KnownEncoding;
import com.simweaver.common.iem.RecordField;
import com.simweaver.common.iem.SimpleDataType;
import com.simweaver.common.iem.VariantAlternative;
import com.simweaver.common.iem.VariantRecordDataType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decodes HLA encoded values to {@link HValue} and encodes them again, for one
 * {@link InformationExchangeModel}. The encoder may be given a value decoded with
 * <em>another</em> model: it then follows the target datatype and matches by name (record
 * fields, enumerators, variant alternatives), which is how Junktion converts between FOM
 * versions (RPR v2 and v3) without code per class.
 *
 * <p>Encodings: {@code HLAfixedArray}, {@code HLAvariableArray}, {@code HLAfixedRecord},
 * {@code HLAvariantRecord}, {@code HLAextendableVariantRecord}, {@code HLAlengthlessVarArray},
 * {@code RPRlengthlessArray}, {@code RPRnullTerminatedArray}, {@code RPRextendedVariantRecord},
 * {@code RPRpaddingTo32Array}, {@code RPRpaddingTo64Array}. Unknown array/record encodings
 * fall back to the standard ones, as in the decoders. Alignment follows IEEE 1516.2 clause
 * 4.13: every element on its octet boundary, counted from the start of the value, no padding
 * after the last element. Octet boundaries and sizes come from the decoders' {@link TypeLayouts},
 * the RPR count rules of lengthless arrays from {@link HlaDataDecoder#evaluateCountRule}.
 *
 * <p>Extendable variant records ({@code HLAextendableVariantRecord}, {@code RPRextendedVariantRecord})
 * are read in the length-prefixed layout when the data allows it, else plain (as the decoders'
 * AUTO). They are written in the layout they were read with when the datatype is the same,
 * else length-prefixed.
 *
 * <p>Thread safe after construction.
 */
public final class HlaCodec {

    /** Receives what the encoder had to adjust (overflow, missing fields ...). */
    public interface Issues {
        /** @param message what was adjusted */
        void warn(String message);
    }

    private static final Issues IGNORE = new Issues() {
        @Override
        public void warn(String message) {
        }
    };

    private static final int MAX_ELEMENTS = 1 << 24;
    private static final int MAX_DEPTH = 64;

    private final InformationExchangeModel model;
    private final HlaDataDecoder decoder;
    private final TypeLayouts layouts;

    /** @param model the model whose datatypes are decoded and encoded */
    public HlaCodec(InformationExchangeModel model) {
        this(HlaDataDecoder.create(model));
    }

    /**
     * Shares the layouts and count rules of an existing decoder.
     *
     * @param decoder the decoder
     */
    public HlaCodec(HlaDataDecoder decoder) {
        this.decoder = decoder;
        this.model = decoder.getModel();
        this.layouts = decoder.getLayouts();
    }

    /** @return the model */
    public InformationExchangeModel getModel() {
        return model;
    }

    /** @return the layouts (octet boundaries, sizes) */
    public TypeLayouts getLayouts() {
        return layouts;
    }

    // ================================================================== decode

    /**
     * @param type datatype of this codec's model
     * @param data encoded value
     * @return the value
     * @throws HlaCodecException when the data does not fit the datatype
     */
    public HValue decode(DataType type, byte[] data) {
        return decode(type, data, 0, data == null ? 0 : data.length);
    }

    /**
     * @param type   datatype
     * @param data   buffer
     * @param offset start of the value (alignment is counted from here)
     * @param length octets of the value
     * @return the value
     * @throws HlaCodecException when the data does not fit the datatype
     */
    public HValue decode(DataType type, byte[] data, int offset, int length) {
        if (type == null) {
            throw new HlaCodecException("no datatype");
        }
        Reader r = new Reader(data == null ? new byte[0] : data, offset, offset + length);
        return read(type, r, 0);
    }

    private HValue read(DataType t, Reader r, int depth) {
        if (depth > MAX_DEPTH) {
            throw new HlaCodecException(t.getName() + ": nested deeper than " + MAX_DEPTH);
        }
        switch (t.getCategory()) {
            case BASIC:
                return readBasic((BasicDataType) t, r).withType(t);
            case SIMPLE: {
                BasicDataType rep = ((SimpleDataType) t).getRepresentation();
                if (rep == null) {
                    throw new HlaCodecException("representation of " + t.getName() + " not resolved");
                }
                return readBasic(rep, r).withType(t);
            }
            case ENUMERATED: {
                EnumeratedDataType e = (EnumeratedDataType) t;
                if (e.getRepresentation() == null) {
                    throw new HlaCodecException("representation of " + t.getName() + " not resolved");
                }
                long v = readBasic(e.getRepresentation(), r).asLong();
                Enumerator en = e.getEnumeratorByValue(v);
                return HValue.ofEnum(v, en == null ? null : en.getName()).withType(t);
            }
            case ARRAY:
                return readArray((ArrayDataType) t, r, depth).withType(t);
            case FIXED_RECORD:
                return readRecord((FixedRecordDataType) t, r, depth).withType(t);
            case VARIANT_RECORD:
                return readVariant((VariantRecordDataType) t, r, depth).withType(t);
            default:
                throw new HlaCodecException("datatype " + t.getName() + " (" + t.getCategory() + ") cannot be decoded");
        }
    }

    private HValue readBasic(BasicDataType b, Reader r) {
        int n = b.getSizeOctets();
        r.align(layouts.getOctetBoundary(b));
        BasicInterpretation in = layouts.getInterpretation(b);
        boolean big = b.getEndianness() != Endianness.LITTLE;
        if (in == BasicInterpretation.OPAQUE || n > 8 || n <= 0) {
            return HValue.ofBytes(r.bytes(Math.max(0, n)));
        }
        long raw = r.number(n, big);
        switch (in) {
            case FLOAT:
                return HValue.ofFloat(n == 4 ? Float.intBitsToFloat((int) raw) : Double.longBitsToDouble(raw));
            case SIGNED:
                if (n < 8) {
                    int shift = 64 - 8 * n;
                    raw = (raw << shift) >> shift;
                }
                return HValue.ofInt(raw);
            default:
                return HValue.ofInt(raw);
        }
    }

    private HValue readArray(ArrayDataType a, Reader r, int depth) {
        DataType elem = a.getElementType();
        if (elem == null) {
            throw new HlaCodecException("element datatype of " + a.getName() + " not resolved");
        }
        switch (arrayEncoding(a)) {
            case FIXED: {
                long n = a.getCardinality() == null ? 0 : a.getCardinality().getFixedElementCount();
                if (n < 0) {
                    n = a.getCardinality().getDimensions().isEmpty() ? 0 : a.getCardinality().getDimensions().get(0).getMin();
                }
                r.align(layouts.getOctetBoundary(a));
                return readElements(a, elem, (int) n, r, depth);
            }
            case VARIABLE: {
                r.align(layouts.getOctetBoundary(a));
                int n = (int) r.number(4, true);
                if (n < 0 || n > MAX_ELEMENTS) {
                    throw new HlaCodecException(a.getName() + ": element count " + n);
                }
                return readElements(a, elem, n, r, depth);
            }
            case LENGTHLESS: {
                int hint = r.takeHint();
                int eb = layouts.getOctetBoundary(elem);
                if (hint < 0 && r.remaining() - r.paddingTo(eb) <= 0) {
                    return emptyArray(elem);
                }
                if (hint == 0) {
                    return emptyArray(elem);
                }
                r.align(eb);
                if (hint > 0) {
                    return readElements(a, elem, hint, r, depth);
                }
                int size = layouts.getFixedSize(elem);
                if (size > 0) {
                    int stride = layouts.getStride(elem);
                    int rem = r.remaining();
                    int n = rem < size ? 0 : 1 + (rem - size) / stride;
                    return readElements(a, elem, n, r, depth);
                }
                List<HValue> list = new ArrayList<HValue>();
                while (r.remaining() - r.paddingTo(eb) > 0) {
                    int p = r.pos;
                    list.add(read(elem, r, depth + 1));
                    if (r.pos == p || list.size() > MAX_ELEMENTS) {
                        break;
                    }
                }
                return wrapElements(elem, list);
            }
            case NULL_TERMINATED: {
                r.align(layouts.getOctetBoundary(a));
                List<HValue> list = new ArrayList<HValue>();
                while (r.remaining() - r.paddingTo(layouts.getOctetBoundary(elem)) > 0) {
                    int save = r.pos;
                    r.align(layouts.getOctetBoundary(elem));
                    int start = r.pos;
                    HValue e = read(elem, r, depth + 1);
                    if (r.allZero(start, r.pos)) {
                        return wrapElements(elem, list);
                    }
                    if (r.pos == save) {
                        break;
                    }
                    list.add(e);
                    if (list.size() > MAX_ELEMENTS) {
                        throw new HlaCodecException(a.getName() + ": more than " + MAX_ELEMENTS + " elements");
                    }
                }
                return wrapElements(elem, list);   // not terminated: tolerated
            }
            case PAD32:
            case PAD64: {
                int to = arrayEncoding(a) == ArrayEncoding.PAD32 ? 4 : 8;
                r.skip(Math.min(r.paddingTo(to), r.remaining()));
                return HValue.padding();
            }
            default:
                throw new IllegalStateException();
        }
    }

    private HValue emptyArray(DataType elem) {
        return wrapElements(elem, new ArrayList<HValue>(0));
    }

    private HValue readElements(ArrayDataType a, DataType elem, int n, Reader r, int depth) {
        if (n > MAX_ELEMENTS) {
            throw new HlaCodecException(a.getName() + ": " + n + " elements");
        }
        int size = layouts.getFixedSize(elem);
        if (layouts.isCharType(elem) && (size == 1 || size == 2)) {
            boolean big = bigEndian(elem);
            r.align(layouts.getOctetBoundary(elem));
            r.need((long) n * size);
            char[] c = new char[n];
            for (int i = 0; i < n; i++) {
                c[i] = (char) r.number(size, big);
            }
            return HValue.ofText(new String(c));
        }
        if (layouts.isOctetType(elem) && size == 1) {
            return HValue.ofBytes(r.bytes(n));
        }
        if (size > 0 && (long) n * size > r.remaining() + 8L) {
            throw new HlaCodecException(a.getName() + ": " + n + " elements do not fit in " + r.remaining() + " octets");
        }
        List<HValue> list = new ArrayList<HValue>(Math.min(n, 4096));
        for (int i = 0; i < n; i++) {
            list.add(read(elem, r, depth + 1));
        }
        return HValue.ofArray(list);
    }

    private HValue wrapElements(DataType elem, List<HValue> list) {
        int size = layouts.getFixedSize(elem);
        if (layouts.isCharType(elem) && (size == 1 || size == 2)) {
            char[] c = new char[list.size()];
            for (int i = 0; i < c.length; i++) {
                c[i] = (char) list.get(i).asLong();
            }
            return HValue.ofText(new String(c));
        }
        if (layouts.isOctetType(elem) && size == 1) {
            byte[] b = new byte[list.size()];
            for (int i = 0; i < b.length; i++) {
                b[i] = (byte) list.get(i).asLong();
            }
            return HValue.ofBytes(b);
        }
        return HValue.ofArray(list);
    }

    private HValue readRecord(FixedRecordDataType rec, Reader r, int depth) {
        r.align(layouts.getOctetBoundary(rec));
        Map<String, HValue> fields = new LinkedHashMap<String, HValue>();
        Map<String, Long> numbers = null;
        for (RecordField f : rec.getFields()) {
            DataType ft = f.getDataType();
            if (ft == null) {
                throw new HlaCodecException("datatype '" + f.getDataTypeName() + "' of " + rec.getName() + "." + f.getName() + " not resolved");
            }
            if (ft instanceof ArrayDataType && arrayEncoding((ArrayDataType) ft) == ArrayEncoding.LENGTHLESS) {
                if (numbers == null) {
                    numbers = new HashMap<String, Long>();
                    for (Map.Entry<String, HValue> e : fields.entrySet()) {
                        if (e.getValue().getKind() == HValue.Kind.INT) {
                            numbers.put(e.getKey(), e.getValue().asLong());
                        }
                    }
                }
                long c = decoder.evaluateCountRule(rec, f, numbers);
                r.setHint(c < 0 ? -1 : (int) c);
            }
            HValue v = read(ft, r, depth + 1);
            r.takeHint();
            fields.put(f.getName(), v);
            if (numbers != null && v.getKind() == HValue.Kind.INT) {
                numbers.put(f.getName(), v.asLong());
            }
        }
        return HValue.ofRecord(fields);
    }

    private HValue readVariant(VariantRecordDataType v, Reader r, int depth) {
        EnumeratedDataType dt = v.getDiscriminantType();
        if (dt == null) {
            throw new HlaCodecException("discriminant datatype of " + v.getName() + " not resolved");
        }
        boolean extendable = isExtendable(v);
        int save = r.pos;
        if (extendable) {
            try {
                return readVariantPrefixed(v, dt, r, depth).withLayout(HValue.Layout.LENGTH_PREFIXED);
            } catch (HlaCodecException e) {
                r.pos = save;
                r.takeHint();
            }
        }
        r.align(layouts.getOctetBoundary(v));
        HValue d = read(dt, r, depth + 1);
        VariantAlternative alt = v.getAlternativeForValue(d.asLong());
        if (alt == null) {
            throw new HlaCodecException(v.getName() + ": no alternative for discriminant " + d.asLong());
        }
        HValue av = null;
        if (!alt.isEmpty()) {
            if (alt.getDataType() == null) {
                throw new HlaCodecException(v.getName() + ": datatype of alternative " + alt.getName() + " not resolved");
            }
            av = read(alt.getDataType(), r, depth + 1);
        }
        HValue res = HValue.ofVariant(d.asLong(), d.getEnumerator(), alt.getName(), av);
        return extendable ? res.withLayout(HValue.Layout.PLAIN) : res;
    }

    private HValue readVariantPrefixed(VariantRecordDataType v, EnumeratedDataType dt, Reader r, int depth) {
        r.align(Math.max(4, layouts.getOctetBoundary(v)));
        HValue d = read(dt, r, depth + 1);
        r.align(4);
        int length = (int) r.number(4, true);
        if (length < 0 || length > r.remaining()) {
            throw new HlaCodecException("bad alternative length " + length);
        }
        int altStart = r.pos;
        int altEnd = altStart + length;
        VariantAlternative alt = v.getAlternativeForValue(d.asLong());
        if (alt == null) {
            byte[] raw = r.bytes(length);
            return HValue.ofVariant(d.asLong(), d.getEnumerator(), null, HValue.ofBytes(raw));
        }
        HValue av = null;
        if (!alt.isEmpty() && alt.getDataType() != null) {
            int oldLimit = r.limit;
            r.limit = altEnd;
            try {
                av = read(alt.getDataType(), r, depth + 1);
            } finally {
                r.limit = oldLimit;
            }
            int left = altEnd - r.pos;
            if (left < 0 || left >= Math.max(1, layouts.getOctetBoundary(alt.getDataType()))) {
                throw new HlaCodecException("alternative length mismatch");
            }
        } else if (length != 0) {
            throw new HlaCodecException("non-empty data for an empty alternative");
        }
        r.pos = altEnd;
        return HValue.ofVariant(d.asLong(), d.getEnumerator(), alt.getName(), av);
    }

    // ================================================================== encode

    /**
     * Encodes a value of this model's datatype {@code type}. The value may come from another
     * model; fields, enumerators and alternatives are then matched by name.
     *
     * @param type  target datatype (of this codec's model)
     * @param value the value; null writes the datatype's default value
     * @return the encoded octets
     */
    public byte[] encode(DataType type, HValue value) {
        return encode(type, value, null, IGNORE);
    }

    /**
     * @param type   target datatype
     * @param value  the value (null: default value)
     * @param rules  renames and value maps for values from another model, or null
     * @param issues receives what had to be adjusted, or null
     * @return the encoded octets
     */
    public byte[] encode(DataType type, HValue value, TranscodeRules rules, Issues issues) {
        if (type == null) {
            throw new HlaCodecException("no datatype");
        }
        Writer w = new Writer();
        write(type, value, w, rules == null ? TranscodeRules.NONE : rules, issues == null ? IGNORE : issues, 0);
        return w.toBytes();
    }

    /**
     * @param type datatype of this model
     * @return its default value: zero numbers, the zero (or first) enumerator, empty variable
     *         arrays, filled fixed arrays, records of defaults, the first alternative of variants
     */
    public HValue defaultValue(DataType type) {
        return defaultValue(type, 0);
    }

    private HValue defaultValue(DataType t, int depth) {
        if (t == null || depth > MAX_DEPTH) {
            return HValue.ofInt(0);
        }
        switch (t.getCategory()) {
            case BASIC:
            case SIMPLE: {
                BasicDataType b = basicOf(t);
                if (b != null && layouts.getInterpretation(b) == BasicInterpretation.FLOAT) {
                    return HValue.ofFloat(0).withType(t);
                }
                if (b != null && (layouts.getInterpretation(b) == BasicInterpretation.OPAQUE || b.getSizeOctets() > 8)) {
                    return HValue.ofBytes(new byte[Math.max(0, b.getSizeOctets())]).withType(t);
                }
                return HValue.ofInt(0).withType(t);
            }
            case ENUMERATED: {
                EnumeratedDataType e = (EnumeratedDataType) t;
                Enumerator z = e.getEnumeratorByValue(0);
                if (z == null && !e.getEnumerators().isEmpty()) {
                    z = e.getEnumerators().get(0);
                }
                return HValue.ofEnum(z == null ? 0 : z.getValue(), z == null ? null : z.getName()).withType(t);
            }
            case ARRAY: {
                ArrayDataType a = (ArrayDataType) t;
                ArrayEncoding enc = arrayEncoding(a);
                if (enc == ArrayEncoding.PAD32 || enc == ArrayEncoding.PAD64) {
                    return HValue.padding().withType(t);
                }
                int n = 0;
                if (enc == ArrayEncoding.FIXED) {
                    long c = a.getCardinality().getFixedElementCount();
                    n = (int) Math.max(0, Math.min(c, MAX_ELEMENTS));
                }
                DataType elem = a.getElementType();
                int size = layouts.getFixedSize(elem);
                if (layouts.isCharType(elem) && (size == 1 || size == 2)) {
                    return HValue.ofText(new String(new char[n])).withType(t);
                }
                if (layouts.isOctetType(elem) && size == 1) {
                    return HValue.ofBytes(new byte[n]).withType(t);
                }
                List<HValue> l = new ArrayList<HValue>(n);
                for (int i = 0; i < n; i++) {
                    l.add(defaultValue(elem, depth + 1));
                }
                return HValue.ofArray(l).withType(t);
            }
            case FIXED_RECORD: {
                Map<String, HValue> m = new LinkedHashMap<String, HValue>();
                for (RecordField f : ((FixedRecordDataType) t).getFields()) {
                    m.put(f.getName(), defaultValue(f.getDataType(), depth + 1));
                }
                return HValue.ofRecord(m).withType(t);
            }
            case VARIANT_RECORD: {
                VariantRecordDataType v = (VariantRecordDataType) t;
                for (VariantAlternative alt : v.getAlternatives()) {
                    if (!alt.getEnumerators().isEmpty()) {
                        Enumerator e = alt.getEnumerators().get(0);
                        HValue av = alt.isEmpty() ? null : defaultValue(alt.getDataType(), depth + 1);
                        return HValue.ofVariant(e.getValue(), e.getName(), alt.getName(), av).withType(t);
                    }
                }
                return HValue.ofVariant(0, null, null, null).withType(t);
            }
            default:
                return HValue.ofInt(0);
        }
    }

    private void write(DataType t, HValue v, Writer w, TranscodeRules rules, Issues issues, int depth) {
        if (depth > MAX_DEPTH) {
            throw new HlaCodecException(t.getName() + ": nested deeper than " + MAX_DEPTH);
        }
        if (v == null) {
            v = defaultValue(t, depth);
        } else if (v.getType() != null && v.getType() != t && !rules.isEmpty()) {
            HValue adapted = rules.adapt(v.getTypeName(), t.getName(), v);
            if (adapted != null) {
                v = adapted;
            }
        }
        switch (t.getCategory()) {
            case BASIC:
                writeBasic((BasicDataType) t, t, v, w, issues);
                return;
            case SIMPLE: {
                BasicDataType rep = ((SimpleDataType) t).getRepresentation();
                if (rep == null) {
                    throw new HlaCodecException("representation of " + t.getName() + " not resolved");
                }
                writeBasic(rep, t, v, w, issues);
                return;
            }
            case ENUMERATED:
                writeEnum((EnumeratedDataType) t, v, w, rules, issues);
                return;
            case ARRAY:
                writeArray((ArrayDataType) t, v, w, rules, issues, depth);
                return;
            case FIXED_RECORD:
                writeRecord((FixedRecordDataType) t, v, w, rules, issues, depth);
                return;
            case VARIANT_RECORD:
                writeVariant((VariantRecordDataType) t, v, w, rules, issues, depth);
                return;
            default:
                throw new HlaCodecException("datatype " + t.getName() + " (" + t.getCategory() + ") cannot be encoded");
        }
    }

    private void writeBasic(BasicDataType b, DataType declared, HValue v, Writer w, Issues issues) {
        int n = b.getSizeOctets();
        w.align(layouts.getOctetBoundary(b));
        BasicInterpretation in = layouts.getInterpretation(b);
        boolean big = b.getEndianness() != Endianness.LITTLE;
        if (in == BasicInterpretation.OPAQUE || n > 8 || n <= 0) {
            byte[] src = v.getKind() == HValue.Kind.BYTES ? v.asBytes() : new byte[0];
            byte[] out = Arrays.copyOf(src, Math.max(0, n));
            w.bytes(out);
            return;
        }
        if (in == BasicInterpretation.FLOAT) {
            double d = numberAsDouble(v, declared, issues);
            w.number(n == 4 ? Float.floatToRawIntBits((float) d) & 0xFFFFFFFFL : Double.doubleToRawLongBits(d), n, big);
            return;
        }
        long l = numberAsLong(v, declared, issues);
        // a value fits when its low bits carry it, signed or unsigned; otherwise the low bits are
        // kept (as the RTI encoders do) and the overflow is reported
        if (n < 8 && (l < -(1L << (8 * n - 1)) || l > (1L << (8 * n)) - 1)) {
            issues.warn(declared.getName() + ": value " + l + " does not fit " + n + " octets");
        }
        w.number(l, n, big);
    }

    private static long numberAsLong(HValue v, DataType declared, Issues issues) {
        switch (v.getKind()) {
            case INT:
            case VARIANT:
                return v.asLong();
            case FLOAT:
                return Math.round(v.asDouble());
            case BYTES: {                                   // e.g. an octet given as one byte
                byte[] b = v.asBytes();
                if (b.length <= 8) {
                    long n = 0;
                    for (byte x : b) {
                        n = (n << 8) | (x & 0xFF);
                    }
                    return n;
                }
                issues.warn(declared.getName() + ": " + b.length + " octets where a number was expected");
                return 0;
            }
            case TEXT:
                try {
                    return Long.parseLong(v.asTrimmedText().trim());
                } catch (NumberFormatException e) {
                    issues.warn(declared.getName() + ": text '" + v.asTrimmedText() + "' is not a number");
                    return 0;
                }
            default:
                issues.warn(declared.getName() + ": " + v.getKind() + " where a number was expected");
                return 0;
        }
    }

    private static double numberAsDouble(HValue v, DataType declared, Issues issues) {
        switch (v.getKind()) {
            case INT:
            case FLOAT:
            case VARIANT:
                return v.asDouble();
            case TEXT:
                try {
                    return Double.parseDouble(v.asTrimmedText().trim());
                } catch (NumberFormatException e) {
                    issues.warn(declared.getName() + ": text '" + v.asTrimmedText() + "' is not a number");
                    return 0;
                }
            default:
                issues.warn(declared.getName() + ": " + v.getKind() + " where a number was expected");
                return 0;
        }
    }

    private void writeEnum(EnumeratedDataType e, HValue v, Writer w, TranscodeRules rules, Issues issues) {
        if (e.getRepresentation() == null) {
            throw new HlaCodecException("representation of " + e.getName() + " not resolved");
        }
        long value = enumValue(e, v, rules, issues);
        writeBasic(e.getRepresentation(), e, HValue.ofInt(value), w, issues);
    }

    /** Target enumerator value: same type = as is; value map; same enumerator name; else the number. */
    private long enumValue(EnumeratedDataType target, HValue v, TranscodeRules rules, Issues issues) {
        long src = v.getKind() == HValue.Kind.FLOAT ? Math.round(v.asDouble()) : v.asLong();
        DataType st = v.getType();
        if (st == target || st == null && v.getEnumerator() == null) {
            return src;
        }
        if (st != null) {
            Long mapped = rules.enumValue(st.getName(), target.getName(), src);
            if (mapped != null) {
                return mapped;
            }
        }
        String name = v.getEnumerator();
        if (name != null) {
            Enumerator byName = target.getEnumerator(name);
            if (byName != null) {
                return byName.getValue();
            }
        }
        if (target.getEnumeratorByValue(src) == null && !target.getEnumerators().isEmpty()) {
            issues.warn(target.getName() + ": no enumerator for " + (name != null ? name + " (" + src + ")" : String.valueOf(src)));
        }
        return src;
    }

    private void writeArray(ArrayDataType a, HValue v, Writer w, TranscodeRules rules, Issues issues, int depth) {
        DataType elem = a.getElementType();
        if (elem == null) {
            throw new HlaCodecException("element datatype of " + a.getName() + " not resolved");
        }
        ArrayEncoding enc = arrayEncoding(a);
        if (enc == ArrayEncoding.PAD32 || enc == ArrayEncoding.PAD64) {
            int to = enc == ArrayEncoding.PAD32 ? 4 : 8;
            w.zeros(w.paddingTo(to));
            return;
        }
        int size = layouts.getFixedSize(elem);
        boolean chars = layouts.isCharType(elem) && (size == 1 || size == 2);
        boolean octets = !chars && layouts.isOctetType(elem) && size == 1;

        // the elements to write
        String text = null;
        byte[] bytes = null;
        List<HValue> list = null;
        int n;
        if (chars) {
            text = charsOf(v);
            n = text.length();
        } else if (octets) {
            bytes = bytesOf(v);
            n = bytes.length;
        } else {
            list = elementsOf(v);
            n = list.size();
        }

        // fixed arrays: exactly the cardinality
        if (enc == ArrayEncoding.FIXED) {
            long c = a.getCardinality() == null ? n : a.getCardinality().getFixedElementCount();
            if (c < 0) {
                c = a.getCardinality().getDimensions().isEmpty() ? n : a.getCardinality().getDimensions().get(0).getMin();
            }
            int fixed = (int) Math.min(c, MAX_ELEMENTS);
            if (n != fixed) {
                if (n > fixed && !(chars && trailingNul(text, fixed))) {
                    issues.warn(a.getName() + ": " + n + " elements cut to " + fixed);
                }
                if (chars) {
                    text = fit(text, fixed);
                } else if (octets) {
                    bytes = Arrays.copyOf(bytes, fixed);
                } else {
                    List<HValue> l = new ArrayList<HValue>(fixed);
                    for (int i = 0; i < fixed; i++) {
                        l.add(i < n ? list.get(i) : defaultValue(elem, depth + 1));
                    }
                    list = l;
                }
                n = fixed;
            }
        } else if (chars && enc == ArrayEncoding.NULL_TERMINATED) {
            int z = text.indexOf('\0');      // a NUL inside would end the array early
            if (z >= 0) {
                text = text.substring(0, z);
                n = text.length();
            }
        }

        switch (enc) {
            case FIXED:
                w.align(layouts.getOctetBoundary(a));
                break;
            case VARIABLE:
                w.align(layouts.getOctetBoundary(a));
                w.number(n, 4, true);
                break;
            case LENGTHLESS:
                if (n > 0) {
                    w.align(layouts.getOctetBoundary(elem));
                }
                break;
            case NULL_TERMINATED:
                w.align(layouts.getOctetBoundary(a));
                break;
            default:
                break;
        }
        if (chars) {
            boolean big = bigEndian(elem);
            w.align(n > 0 || enc == ArrayEncoding.NULL_TERMINATED ? layouts.getOctetBoundary(elem) : 1);
            for (int i = 0; i < n; i++) {
                char ch = text.charAt(i);
                if (size == 1 && ch > 0xFF) {
                    issues.warn(a.getName() + ": character U+" + Integer.toHexString(ch) + " written as '?'");
                    ch = '?';
                }
                w.number(ch, size, big);
            }
        } else if (octets) {
            w.bytes(bytes);
        } else {
            for (int i = 0; i < n; i++) {
                write(elem, list.get(i), w, rules, issues, depth + 1);
            }
        }
        if (enc == ArrayEncoding.NULL_TERMINATED) {
            if (chars || octets) {
                w.align(layouts.getOctetBoundary(elem));
                w.zeros(Math.max(1, size));
            } else {
                // the terminator: an element whose encoding is all zero
                int fs = layouts.getFixedSize(elem);
                w.align(layouts.getOctetBoundary(elem));
                w.zeros(Math.max(1, fs));
            }
        }
    }

    private static boolean trailingNul(String s, int keep) {
        for (int i = keep; i < s.length(); i++) {
            if (s.charAt(i) != 0) {
                return false;
            }
        }
        return true;
    }

    private static String fit(String s, int n) {
        if (s.length() >= n) {
            return s.substring(0, n);
        }
        StringBuilder sb = new StringBuilder(n).append(s);
        while (sb.length() < n) {
            sb.append('\0');
        }
        return sb.toString();
    }

    private static String charsOf(HValue v) {
        switch (v.getKind()) {
            case TEXT:
                return v.asText();
            case BYTES:
                return new String(v.asBytes(), StandardCharsets.ISO_8859_1);
            case ARRAY: {
                StringBuilder sb = new StringBuilder(v.size());
                for (HValue e : v.getValues()) {
                    sb.append((char) e.asLong());
                }
                return sb.toString();
            }
            default:
                return v.asText();
        }
    }

    private static byte[] bytesOf(HValue v) {
        switch (v.getKind()) {
            case BYTES:
                return v.asBytes();
            case TEXT:
                return v.asText().getBytes(StandardCharsets.ISO_8859_1);
            case ARRAY: {
                byte[] b = new byte[v.size()];
                for (int i = 0; i < b.length; i++) {
                    b[i] = (byte) v.get(i).asLong();
                }
                return b;
            }
            default:
                return new byte[0];
        }
    }

    private static List<HValue> elementsOf(HValue v) {
        switch (v.getKind()) {
            case ARRAY:
                return v.getValues();
            case TEXT: {
                String s = v.asText();
                List<HValue> l = new ArrayList<HValue>(s.length());
                for (int i = 0; i < s.length(); i++) {
                    l.add(HValue.ofInt(s.charAt(i)));
                }
                return l;
            }
            case BYTES: {
                byte[] b = v.asBytes();
                List<HValue> l = new ArrayList<HValue>(b.length);
                for (byte x : b) {
                    l.add(HValue.ofInt(x & 0xFF));
                }
                return l;
            }
            default:
                return java.util.Collections.singletonList(v);   // a single value where an array is expected
        }
    }

    private void writeRecord(FixedRecordDataType rec, HValue v, Writer w, TranscodeRules rules, Issues issues, int depth) {
        w.align(layouts.getOctetBoundary(rec));
        boolean sameType = v.getType() == rec;
        boolean isRecord = v.getKind() == HValue.Kind.RECORD;
        if (!isRecord && v.getKind() != HValue.Kind.PADDING) {
            issues.warn(rec.getName() + ": " + v.getKind() + " where a record was expected (defaults written)");
        }
        List<RecordField> fields = rec.getFields();
        for (int i = 0; i < fields.size(); i++) {
            RecordField f = fields.get(i);
            DataType ft = f.getDataType();
            if (ft == null) {
                throw new HlaCodecException("datatype '" + f.getDataTypeName() + "' of " + rec.getName() + "." + f.getName() + " not resolved");
            }
            HValue fv = null;
            if (isRecord) {
                if (sameType && i < v.size()) {
                    fv = v.get(i);
                } else {
                    String src = rules.sourceField(rec.getName(), f.getName());
                    fv = v.field(src != null ? src : f.getName());
                    if (fv == null && src == null) {
                        fv = fieldIgnoreCase(v, f.getName());
                    }
                    if (fv == null && !rules.isDefaulted(rec.getName(), f.getName()) && !f.getName().startsWith("Padding")) {
                        issues.warn(rec.getName() + "." + f.getName() + ": no source field (default written)");
                    }
                }
            }
            write(ft, fv, w, rules, issues, depth + 1);
        }
    }

    private static HValue fieldIgnoreCase(HValue rec, String name) {
        List<String> names = rec.getFieldNames();
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(name)) {
                return rec.get(i);
            }
        }
        return null;
    }

    private void writeVariant(VariantRecordDataType v, HValue value, Writer w, TranscodeRules rules, Issues issues, int depth) {
        EnumeratedDataType dt = v.getDiscriminantType();
        if (dt == null) {
            throw new HlaCodecException("discriminant datatype of " + v.getName() + " not resolved");
        }
        if (value.getKind() != HValue.Kind.VARIANT) {
            issues.warn(v.getName() + ": " + value.getKind() + " where a variant was expected (default written)");
            value = defaultValue(v, depth);
        }
        boolean sameType = value.getType() == v;
        // the discriminant: by enumerator name (or value map) when the value comes from another type
        HValue discSrc = HValue.ofEnum(value.asLong(), value.getEnumerator());
        if (value.getType() instanceof VariantRecordDataType) {
            EnumeratedDataType sdt = ((VariantRecordDataType) value.getType()).getDiscriminantType();
            if (sdt != null) {
                discSrc = discSrc.withType(sdt);
            }
        }
        long disc = sameType ? value.asLong() : enumValue(dt, discSrc, rules, issues);
        VariantAlternative alt = v.getAlternativeForValue(disc);
        boolean extendable = isExtendable(v);
        boolean prefixed = extendable && !(sameType && value.getLayout() == HValue.Layout.PLAIN);

        if (prefixed) {
            w.align(Math.max(4, layouts.getOctetBoundary(v)));
        } else {
            w.align(layouts.getOctetBoundary(v));
        }
        writeBasic(dt.getRepresentation(), dt, HValue.ofInt(disc), w, issues);
        int lengthAt = -1;
        if (prefixed) {
            w.align(4);
            lengthAt = w.size();
            w.number(0, 4, true);
        }
        int altStart = w.size();
        if (alt == null) {
            if (prefixed && value.getAlternative() != null && value.getAlternative().getKind() == HValue.Kind.BYTES
                    && value.getAlternativeName() == null) {
                w.bytes(value.getAlternative().asBytes());      // unknown alternative kept as read
            } else if (!prefixed) {
                throw new HlaCodecException(v.getName() + ": no alternative for discriminant " + disc);
            } else {
                issues.warn(v.getName() + ": no alternative for discriminant " + disc + " (empty written)");
            }
        } else if (!alt.isEmpty()) {
            if (alt.getDataType() == null) {
                throw new HlaCodecException(v.getName() + ": datatype of alternative " + alt.getName() + " not resolved");
            }
            write(alt.getDataType(), value.getAlternative(), w, rules, issues, depth + 1);
        }
        if (prefixed) {
            w.patch32(lengthAt, w.size() - altStart);
        }
    }

    // ================================================================== helpers

    enum ArrayEncoding { FIXED, VARIABLE, LENGTHLESS, NULL_TERMINATED, PAD32, PAD64 }

    static ArrayEncoding arrayEncoding(ArrayDataType a) {
        String enc = a.getEncoding() == null ? "" : a.getEncoding().trim();
        KnownEncoding k = KnownEncoding.of(enc);
        if (k != null) {
            switch (k) {
                case HLA_FIXED_ARRAY:
                    return ArrayEncoding.FIXED;
                case HLA_VARIABLE_ARRAY:
                    return ArrayEncoding.VARIABLE;
                case HLA_LENGTHLESS_VAR_ARRAY:
                case RPR_LENGTHLESS_ARRAY:
                    return ArrayEncoding.LENGTHLESS;
                case RPR_NULL_TERMINATED_ARRAY:
                    return ArrayEncoding.NULL_TERMINATED;
                default:
                    break;
            }
        }
        String lower = enc.toLowerCase(Locale.ROOT);
        if (lower.equals("rprpaddingto32array")) {
            return ArrayEncoding.PAD32;
        }
        if (lower.equals("rprpaddingto64array")) {
            return ArrayEncoding.PAD64;
        }
        return a.getCardinality() != null && !a.isDynamic() ? ArrayEncoding.FIXED : ArrayEncoding.VARIABLE;
    }

    static boolean isExtendable(VariantRecordDataType v) {
        String enc = v.getEncoding() == null ? "" : v.getEncoding().trim();
        return enc.equals(KnownEncoding.HLA_EXTENDABLE_VARIANT_RECORD.getOmtName())
                || enc.equals(KnownEncoding.RPR_EXTENDED_VARIANT_RECORD.getOmtName());
    }

    private static BasicDataType basicOf(DataType t) {
        if (t instanceof BasicDataType) {
            return (BasicDataType) t;
        }
        if (t instanceof SimpleDataType) {
            return ((SimpleDataType) t).getRepresentation();
        }
        if (t instanceof EnumeratedDataType) {
            return ((EnumeratedDataType) t).getRepresentation();
        }
        return null;
    }

    private static boolean bigEndian(DataType t) {
        BasicDataType b = basicOf(t);
        return b == null || b.getEndianness() != Endianness.LITTLE;
    }

    /** Bounds-checked reader; alignment counted from {@code base}. */
    private static final class Reader {
        final byte[] buf;
        final int base;
        int pos;
        int limit;
        int hint = -1;

        Reader(byte[] buf, int offset, int limit) {
            if (offset < 0 || limit > buf.length || offset > limit) {
                throw new HlaCodecException("bad range " + offset + ".." + limit + " of " + buf.length);
            }
            this.buf = buf;
            this.base = offset;
            this.pos = offset;
            this.limit = limit;
        }

        int remaining() {
            return limit - pos;
        }

        int paddingTo(int b) {
            if (b <= 1) {
                return 0;
            }
            int rel = pos - base;
            return (b - rel % b) % b;
        }

        void align(int b) {
            int p = paddingTo(b);
            if (p > limit - pos) {
                throw new HlaCodecException("data ends inside alignment padding");
            }
            pos += p;
        }

        void need(long n) {
            if (n > limit - pos) {
                throw new HlaCodecException("data ends: " + n + " octets needed, " + (limit - pos) + " left");
            }
        }

        void skip(int n) {
            need(n);
            pos += n;
        }

        long number(int n, boolean big) {
            need(n);
            long v = 0;
            if (big) {
                for (int i = 0; i < n; i++) {
                    v = (v << 8) | (buf[pos + i] & 0xFF);
                }
            } else {
                for (int i = n - 1; i >= 0; i--) {
                    v = (v << 8) | (buf[pos + i] & 0xFF);
                }
            }
            pos += n;
            return v;
        }

        byte[] bytes(int n) {
            need(n);
            byte[] b = Arrays.copyOfRange(buf, pos, pos + n);
            pos += n;
            return b;
        }

        boolean allZero(int from, int to) {
            for (int i = from; i < to; i++) {
                if (buf[i] != 0) {
                    return false;
                }
            }
            return true;
        }

        void setHint(int h) {
            hint = h;
        }

        int takeHint() {
            int h = hint;
            hint = -1;
            return h;
        }
    }

    /** Growing writer; alignment counted from the start. */
    private static final class Writer {
        byte[] buf = new byte[64];
        int len;

        void ensure(int n) {
            if (len + n > buf.length) {
                buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + n));
            }
        }

        int size() {
            return len;
        }

        int paddingTo(int b) {
            return b <= 1 ? 0 : (b - len % b) % b;
        }

        void align(int b) {
            zeros(paddingTo(b));
        }

        void zeros(int n) {
            ensure(n);
            len += n;                 // the buffer is zero filled
        }

        void number(long v, int n, boolean big) {
            ensure(n);
            if (big) {
                for (int i = n - 1; i >= 0; i--) {
                    buf[len + i] = (byte) v;
                    v >>>= 8;
                }
            } else {
                for (int i = 0; i < n; i++) {
                    buf[len + i] = (byte) v;
                    v >>>= 8;
                }
            }
            len += n;
        }

        void bytes(byte[] b) {
            ensure(b.length);
            System.arraycopy(b, 0, buf, len, b.length);
            len += b.length;
        }

        void patch32(int at, int v) {
            buf[at] = (byte) (v >>> 24);
            buf[at + 1] = (byte) (v >>> 16);
            buf[at + 2] = (byte) (v >>> 8);
            buf[at + 3] = (byte) v;
        }

        byte[] toBytes() {
            return Arrays.copyOf(buf, len);
        }
    }
}
