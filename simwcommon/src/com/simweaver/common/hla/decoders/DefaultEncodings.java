package com.simweaver.common.hla.decoders;

import com.simweaver.common.iem.ArrayCardinality;
import com.simweaver.common.iem.ArrayDataType;
import com.simweaver.common.iem.BasicDataType;
import com.simweaver.common.iem.DataType;
import com.simweaver.common.iem.Endianness;
import com.simweaver.common.iem.EnumeratedDataType;
import com.simweaver.common.iem.FixedRecordDataType;
import com.simweaver.common.iem.KnownEncoding;
import com.simweaver.common.iem.RecordField;
import com.simweaver.common.iem.SimpleDataType;
import com.simweaver.common.iem.VariantAlternative;
import com.simweaver.common.iem.VariantRecordDataType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The built-in encoding handlers: IEEE 1516.2 standard encodings and the RPR / NETN ones.
 *
 * <h2>Rules (IEEE 1516.2-2010 clause 4.13, as implemented by the RTI ByteWrapper)</h2>
 * <ul>
 *   <li>Every element starts on its octet boundary, counted from the start of the top-level
 *       value; there is no padding after the last element of a record or array.</li>
 *   <li>Boundaries: basic = size; simple/enumerated = representation; HLAfixedArray =
 *       element; HLAvariableArray = max(4, element); HLAfixedRecord = max(fields);
 *       HLAvariantRecord = max(discriminant, alternatives).</li>
 *   <li>HLAvariableArray: HLAinteger32BE element count, then the elements.</li>
 * </ul>
 *
 * <h2>RPR and NETN encodings</h2>
 * <ul>
 *   <li>{@code RPRlengthlessArray}, {@code HLAlengthlessVarArray}: elements only. The count
 *       comes from a count rule ({@link DecoderOptions.Builder#countRule}); without one the
 *       array takes the rest of the enclosing data.</li>
 *   <li>{@code RPRnullTerminatedArray}: elements up to and including an all-zero element.</li>
 *   <li>{@code RPRpaddingTo32Array} / {@code RPRpaddingTo64Array}: 0..3 / 0..7 octets to the
 *       next 4 / 8 octet boundary.</li>
 *   <li>{@code HLAextendableVariantRecord}, {@code RPRextendedVariantRecord}: see
 *       {@link DecoderOptions.VariantLayout} (layout not verified against the standards
 *       text; AUTO detects it).</li>
 * </ul>
 */
final class DefaultEncodings {

    private DefaultEncodings() {
    }

    /** @return handlers by encoding name */
    static Map<String, EncodingHandler> all() {
        Map<String, EncodingHandler> m = new LinkedHashMap<String, EncodingHandler>();
        m.put(KnownEncoding.HLA_FIXED_ARRAY.getOmtName(), new FixedArray());
        m.put(KnownEncoding.HLA_VARIABLE_ARRAY.getOmtName(), new VariableArray());
        m.put(KnownEncoding.HLA_FIXED_RECORD.getOmtName(), new FixedRecord());
        m.put(KnownEncoding.HLA_VARIANT_RECORD.getOmtName(), new PlainVariant());
        m.put(KnownEncoding.HLA_EXTENDABLE_VARIANT_RECORD.getOmtName(),
              new ExtendableVariant(KnownEncoding.HLA_EXTENDABLE_VARIANT_RECORD.getOmtName()));
        m.put(KnownEncoding.RPR_EXTENDED_VARIANT_RECORD.getOmtName(),
              new ExtendableVariant(KnownEncoding.RPR_EXTENDED_VARIANT_RECORD.getOmtName()));
        m.put(KnownEncoding.RPR_LENGTHLESS_ARRAY.getOmtName(), new LengthlessArray());
        m.put(KnownEncoding.HLA_LENGTHLESS_VAR_ARRAY.getOmtName(), new LengthlessArray());
        m.put(KnownEncoding.RPR_NULL_TERMINATED_ARRAY.getOmtName(), new NullTerminatedArray());
        m.put(KnownEncoding.RPR_PADDING_TO_32_ARRAY.getOmtName(), new PaddingArray(4));
        m.put(KnownEncoding.RPR_PADDING_TO_64_ARRAY.getOmtName(), new PaddingArray(8));
        return m;
    }

    // ------------------------------------------------------------------ helpers

    static DataType elementOf(DataType t) {
        return ((ArrayDataType) t).getElementType();
    }

    private static DecodedValue errorNode(String name, DataType type, String msg, DecodeContext ctx, int start) {
        int end = Math.max(start, Math.min(ctx.getPosition(), ctx.getLimit()));
        return DecodedValue.builder(ValueKind.ERROR).name(name).dataType(type).text("")
            .error(msg).raw(ctx.getBuffer(), start, end - start).build();
    }

    /** Adds the failed child and returns the exception carrying the container's partial node. */
    static HlaDecodeException partial(HlaDecodeException e, DecodedValue.Builder container, String childName,
                                      DataType childType, DecodeContext ctx, int childStart, int start) {
        container.child(e.getPartial() != null ? e.getPartial() : errorNode(childName, childType, e.getMessage(), ctx, childStart));
        container.error("incomplete: " + e.getMessage());
        container.raw(ctx.getBuffer(), start, Math.max(0, Math.min(ctx.getPosition(), ctx.getLimit()) - start));
        return e.withPartial(container.build());
    }

    /**
     * Decodes {@code n} elements at the current position (alignment of each element included)
     * into an ARRAY, TEXT or BYTES node.
     */
    static DecodedValue elements(DataType arrayType, DataType elem, long n, String name, DecodeContext ctx,
                                 int start, List<String> warnings) {
        TypeLayouts layouts = ctx.getLayouts();
        DecoderOptions o = ctx.getOptions();
        if (elem == null) {
            throw ctx.error("element datatype of " + arrayType.getName() + " not resolved");
        }
        int elemSize = layouts.getFixedSize(elem);
        int elemBoundary = layouts.getOctetBoundary(elem);
        boolean packed = elemSize > 0 && elemSize == elemBoundary;
        if (n > 0 && packed && (layouts.isCharType(elem) || layouts.isOctetType(elem))) {
            long total = n * (long) elemSize;
            ctx.align(elemBoundary);
            if (total > ctx.getRemaining()) {
                throw ctx.error(arrayType.getName() + ": " + n + " elements need " + total + " octets, "
                    + ctx.getRemaining() + " left");
            }
            int dataStart = ctx.getPosition();
            ctx.skip((int) total);
            return packedValue(arrayType, elem, name, ctx, start, dataStart, (int) total, elemSize, warnings);
        }
        if (n == 0 && (layouts.isCharType(elem) || layouts.isOctetType(elem))) {
            return packedValue(arrayType, elem, name, ctx, start, ctx.getPosition(), 0, Math.max(1, elemSize), warnings);
        }
        long minTotal = n * (long) layouts.getMinSize(elem);
        if (minTotal > ctx.getRemaining()) {
            throw ctx.error(arrayType.getName() + ": " + n + " elements need at least " + minTotal + " octets, "
                + ctx.getRemaining() + " left");
        }
        if (n > o.getMaxElements()) {
            throw ctx.error(arrayType.getName() + ": " + n + " elements exceed the limit of " + o.getMaxElements());
        }
        DecodedValue.Builder b = DecodedValue.builder(ValueKind.ARRAY).name(name).dataType(arrayType);
        List<DecodedValue> kids = new ArrayList<DecodedValue>((int) Math.min(n, 1024));
        for (int i = 0; i < n; i++) {
            int childStart = ctx.getPosition();
            String en = "[" + i + "]";
            try {
                kids.add(ctx.decode(elem, en));
            } catch (HlaDecodeException e) {
                b.children(kids);
                throw partial(e, b, en, elem, ctx, childStart, start);
            }
        }
        b.children(kids);
        for (String w : warnings) {
            b.warning(w);
        }
        b.text(DisplayText.array(kids, kids.size(), o.getMaxListedElements(), o.getMaxTextLength()));
        b.raw(ctx.getBuffer(), start, ctx.getPosition() - start);
        return b.build();
    }

    private static DecodedValue packedValue(DataType arrayType, DataType elem, String name, DecodeContext ctx, int start,
                                            int dataStart, int length, int elemSize, List<String> warnings) {
        DecoderOptions o = ctx.getOptions();
        TypeLayouts layouts = ctx.getLayouts();
        byte[] buf = ctx.getBuffer();
        DecodedValue.Builder b = DecodedValue.builder(ValueKind.BYTES).name(name).dataType(arrayType)
            .raw(buf, start, ctx.getPosition() - start);
        if (layouts.isCharType(elem)) {
            String s;
            if (elemSize == 1) {
                s = new String(buf, dataStart, length, StandardCharsets.ISO_8859_1);
            } else {
                s = new String(buf, dataStart, length, bigEndian(elem) ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_16LE);
            }
            b.kind(ValueKind.TEXT).stringValue(s).text(DisplayText.escape(stripTrailingNul(s), o.getMaxTextLength()));
        } else {
            b.text(DisplayText.hex(buf, dataStart, length, o.getMaxHexOctets()));
        }
        for (String w : warnings) {
            b.warning(w);
        }
        return b.build();
    }

    static String stripTrailingNul(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == 0) {
            end--;
        }
        return end == s.length() ? s : s.substring(0, end);
    }

    static boolean bigEndian(DataType t) {
        BasicDataType r = t instanceof BasicDataType ? (BasicDataType) t
            : t instanceof SimpleDataType ? ((SimpleDataType) t).getRepresentation()
            : t instanceof EnumeratedDataType ? ((EnumeratedDataType) t).getRepresentation() : null;
        return r == null || r.getEndianness() != Endianness.LITTLE;
    }

    private static List<String> cardinalityCheck(ArrayDataType a, long n) {
        List<String> w = new ArrayList<String>(1);
        ArrayCardinality c = a.getCardinality();
        if (c != null && c.getDimensions().size() == 1) {
            ArrayCardinality.Range r = c.getDimensions().get(0);
            if (n < r.getMin() || (r.getMax() != ArrayCardinality.UNBOUNDED && n > r.getMax())) {
                w.add(n + " elements outside cardinality " + c.getText());
            }
        }
        return w;
    }

    // ------------------------------------------------------------------ arrays

    /** HLAfixedArray. */
    static final class FixedArray implements EncodingHandler {
        @Override
        public int octetBoundary(DataType type, TypeLayouts layouts) {
            return layouts.getOctetBoundary(elementOf(type));
        }

        @Override
        public int fixedSize(DataType type, TypeLayouts layouts) {
            long n = ((ArrayDataType) type).getCardinality().getFixedElementCount();
            return n < 0 ? -1 : layouts.getFixedArraySize(elementOf(type), n);
        }

        @Override
        public int minSize(DataType type, TypeLayouts layouts) {
            long n = ((ArrayDataType) type).getCardinality().getFixedElementCount();
            long m = n < 0 ? 0 : n * (long) layouts.getMinSize(elementOf(type));
            return (int) Math.min(Integer.MAX_VALUE, m);
        }

        @Override
        public DecodedValue decode(DataType type, String name, DecodeContext ctx) {
            ArrayDataType a = (ArrayDataType) type;
            long n = a.getCardinality().getFixedElementCount();
            if (n < 0) {
                // a dynamic cardinality with HLAfixedArray encoding: take the lower bound
                n = a.getCardinality().getDimensions().get(0).getMin();
            }
            ctx.align(ctx.getLayouts().getOctetBoundary(type));
            int start = ctx.getPosition();
            return elements(type, a.getElementType(), n, name, ctx, start, new ArrayList<String>(0));
        }
    }

    /** HLAvariableArray. */
    static final class VariableArray implements EncodingHandler {
        @Override
        public int octetBoundary(DataType type, TypeLayouts layouts) {
            return Math.max(4, layouts.getOctetBoundary(elementOf(type)));
        }

        @Override
        public int fixedSize(DataType type, TypeLayouts layouts) {
            ArrayCardinality c = ((ArrayDataType) type).getCardinality();
            long n = c.getFixedElementCount();
            if (n < 0) {
                return -1;
            }
            int body = layouts.getFixedArraySize(elementOf(type), n);
            if (body < 0) {
                return -1;
            }
            return n == 0 ? 4 : TypeLayouts.align(4, layouts.getOctetBoundary(elementOf(type))) + body;
        }

        @Override
        public int minSize(DataType type, TypeLayouts layouts) {
            return 4;
        }

        @Override
        public DecodedValue decode(DataType type, String name, DecodeContext ctx) {
            ArrayDataType a = (ArrayDataType) type;
            ctx.align(ctx.getLayouts().getOctetBoundary(type));
            int start = ctx.getPosition();
            int n = ctx.readInt32BE();
            if (n < 0) {
                throw ctx.error(type.getName() + ": negative element count " + n);
            }
            return elements(type, a.getElementType(), n, name, ctx, start, cardinalityCheck(a, n));
        }
    }

    /** RPRlengthlessArray and HLAlengthlessVarArray. */
    static final class LengthlessArray implements EncodingHandler {
        @Override
        public int octetBoundary(DataType type, TypeLayouts layouts) {
            return layouts.getOctetBoundary(elementOf(type));
        }

        @Override
        public int fixedSize(DataType type, TypeLayouts layouts) {
            return -1;
        }

        @Override
        public int minSize(DataType type, TypeLayouts layouts) {
            return 0;
        }

        @Override
        public DecodedValue decode(DataType type, String name, DecodeContext ctx) {
            ArrayDataType a = (ArrayDataType) type;
            DataType elem = a.getElementType();
            TypeLayouts layouts = ctx.getLayouts();
            int hint = ctx.takeCountHint();
            int eb = layouts.getOctetBoundary(elem);
            if (ctx.getRemaining() - ctx.paddingTo(eb) <= 0 && hint <= 0) {
                int p = ctx.getPosition();
                return elements(type, elem, 0, name, ctx, p, new ArrayList<String>(0));
            }
            ctx.align(eb);
            int start = ctx.getPosition();
            if (hint >= 0) {
                return elements(type, elem, hint, name, ctx, start, cardinalityCheck(a, hint));
            }
            int stride = layouts.getStride(elem);
            int size = layouts.getFixedSize(elem);
            if (size > 0) {
                // fixed size elements: the count follows from the remaining octets
                int rem = ctx.getRemaining();
                long n = rem < size ? 0 : 1 + (rem - size) / stride;
                List<String> w = cardinalityCheck(a, n);
                int used = n == 0 ? 0 : (int) ((n - 1) * stride + size);
                if (rem - used >= 1 && rem - used < stride) {
                    w.add((rem - used) + " octets left after the last element");
                }
                return elements(type, elem, n, name, ctx, start, w);
            }
            // variable size elements: decode until the data ends
            DecoderOptions o = ctx.getOptions();
            DecodedValue.Builder b = DecodedValue.builder(ValueKind.ARRAY).name(name).dataType(type);
            List<DecodedValue> kids = new ArrayList<DecodedValue>();
            while (ctx.getRemaining() - ctx.paddingTo(eb) > 0) {
                if (kids.size() >= o.getMaxElements()) {
                    throw ctx.error(type.getName() + ": more than " + o.getMaxElements() + " elements");
                }
                int childStart = ctx.getPosition();
                String en = "[" + kids.size() + "]";
                try {
                    kids.add(ctx.decode(elem, en));
                } catch (HlaDecodeException e) {
                    b.children(kids);
                    throw partial(e, b, en, elem, ctx, childStart, start);
                }
                if (ctx.getPosition() == childStart) {
                    break;   // zero-size element: avoid an endless loop
                }
            }
            for (String w : cardinalityCheck(a, kids.size())) {
                b.warning(w);
            }
            return b.children(kids)
                .text(DisplayText.array(kids, kids.size(), o.getMaxListedElements(), o.getMaxTextLength()))
                .raw(ctx.getBuffer(), start, ctx.getPosition() - start).build();
        }
    }

    /** RPRnullTerminatedArray. */
    static final class NullTerminatedArray implements EncodingHandler {
        @Override
        public int octetBoundary(DataType type, TypeLayouts layouts) {
            return layouts.getOctetBoundary(elementOf(type));
        }

        @Override
        public int fixedSize(DataType type, TypeLayouts layouts) {
            return -1;
        }

        @Override
        public int minSize(DataType type, TypeLayouts layouts) {
            return Math.max(1, layouts.getMinSize(elementOf(type)));
        }

        @Override
        public DecodedValue decode(DataType type, String name, DecodeContext ctx) {
            ArrayDataType a = (ArrayDataType) type;
            DataType elem = a.getElementType();
            TypeLayouts layouts = ctx.getLayouts();
            if (elem == null) {
                throw ctx.error("element datatype of " + type.getName() + " not resolved");
            }
            ctx.align(layouts.getOctetBoundary(type));
            int start = ctx.getPosition();
            int size = layouts.getFixedSize(elem);
            List<String> warnings = new ArrayList<String>(1);
            if (size > 0 && size == layouts.getOctetBoundary(elem)) {
                // packed elements: scan for the all-zero element
                byte[] buf = ctx.getBuffer();
                int p = start;
                int n = 0;
                boolean terminated = false;
                while (p + size <= ctx.getLimit()) {
                    boolean zero = true;
                    for (int i = 0; i < size; i++) {
                        if (buf[p + i] != 0) {
                            zero = false;
                            break;
                        }
                    }
                    if (zero) {
                        terminated = true;
                        break;
                    }
                    p += size;
                    n++;
                }
                if (!terminated) {
                    warnings.add("no terminating null element");
                }
                DecodedValue v = elements(type, elem, n, name, ctx, start, warnings);
                if (terminated) {
                    ctx.skip(size);
                }
                return v.toBuilder().raw(buf, start, ctx.getPosition() - start).build();
            }
            DecoderOptions o = ctx.getOptions();
            DecodedValue.Builder b = DecodedValue.builder(ValueKind.ARRAY).name(name).dataType(type);
            List<DecodedValue> kids = new ArrayList<DecodedValue>();
            boolean terminated = false;
            while (ctx.getRemaining() - ctx.paddingTo(layouts.getOctetBoundary(elem)) > 0) {
                if (kids.size() >= o.getMaxElements()) {
                    throw ctx.error(type.getName() + ": more than " + o.getMaxElements() + " elements");
                }
                int childStart = ctx.getPosition();
                String en = "[" + kids.size() + "]";
                DecodedValue e;
                try {
                    e = ctx.decode(elem, en);
                } catch (HlaDecodeException ex) {
                    b.children(kids);
                    throw partial(ex, b, en, elem, ctx, childStart, start);
                }
                if (allZero(e)) {
                    terminated = true;
                    break;
                }
                kids.add(e);
            }
            if (!terminated) {
                b.warning("no terminating null element");
            }
            return b.children(kids)
                .text(DisplayText.array(kids, kids.size(), o.getMaxListedElements(), o.getMaxTextLength()))
                .raw(ctx.getBuffer(), start, ctx.getPosition() - start).build();
        }

        private static boolean allZero(DecodedValue e) {
            for (int i = 0; i < e.getRawLength(); i++) {
                if (e.getRawByte(i) != 0) {
                    return false;
                }
            }
            return true;
        }
    }

    /** RPRpaddingTo32Array / RPRpaddingTo64Array. */
    static final class PaddingArray implements EncodingHandler {
        private final int to;

        PaddingArray(int to) {
            this.to = to;
        }

        @Override
        public int octetBoundary(DataType type, TypeLayouts layouts) {
            return 1;
        }

        @Override
        public int fixedSize(DataType type, TypeLayouts layouts) {
            return -1;
        }

        @Override
        public int minSize(DataType type, TypeLayouts layouts) {
            return 0;
        }

        @Override
        public DecodedValue decode(DataType type, String name, DecodeContext ctx) {
            int start = ctx.getPosition();
            int pad = ctx.paddingTo(to);
            int take = Math.min(pad, ctx.getRemaining());
            ctx.skip(take);
            DecodedValue.Builder b = DecodedValue.builder(ValueKind.PADDING).name(name).dataType(type)
                .text("").raw(ctx.getBuffer(), start, take);
            if (take < pad) {
                b.warning("data ends inside " + to * 8 + "-bit padding (" + take + " of " + pad + " octets)");
            }
            return b.build();
        }
    }

    // ------------------------------------------------------------------ records

    /** HLAfixedRecord (also the fallback for unknown record encodings). */
    static final class FixedRecord implements EncodingHandler {
        @Override
        public int octetBoundary(DataType type, TypeLayouts layouts) {
            int b = 1;
            for (RecordField f : ((FixedRecordDataType) type).getFields()) {
                b = Math.max(b, layouts.getOctetBoundary(f.getDataType()));
            }
            return b;
        }

        @Override
        public int fixedSize(DataType type, TypeLayouts layouts) {
            return layouts.getFixedRecordSize((FixedRecordDataType) type);
        }

        @Override
        public int minSize(DataType type, TypeLayouts layouts) {
            long m = 0;
            for (RecordField f : ((FixedRecordDataType) type).getFields()) {
                m += layouts.getMinSize(f.getDataType());
            }
            return (int) Math.min(Integer.MAX_VALUE, m);
        }

        @Override
        public DecodedValue decode(DataType type, String name, DecodeContext ctx) {
            FixedRecordDataType r = (FixedRecordDataType) type;
            ctx.align(ctx.getLayouts().getOctetBoundary(type));
            int start = ctx.getPosition();
            DecodedValue.Builder b = DecodedValue.builder(ValueKind.RECORD).name(name).dataType(type);
            List<DecodedValue> kids = new ArrayList<DecodedValue>(r.getFields().size());
            Map<String, Long> numbers = null;
            HlaDataDecoder decoder = ctx.getDecoder();
            for (RecordField f : r.getFields()) {
                int childStart = ctx.getPosition();
                try {
                    if (f.getDataType() == null) {
                        throw ctx.error("datatype '" + f.getDataTypeName() + "' of field " + f.getName() + " not resolved");
                    }
                    CountExpression rule = decoder.getCountRule(r, f);
                    if (rule != null) {
                        if (numbers == null) {
                            numbers = numericFields(kids);
                        }
                        long c;
                        try {
                            c = rule.evaluate(numbers);
                        } catch (RuntimeException ex) {
                            throw ctx.error("count rule '" + rule + "' for " + r.getName() + "." + f.getName()
                                + ": " + ex.getMessage());
                        }
                        if (c < 0 || c > Integer.MAX_VALUE) {
                            throw ctx.error("count rule '" + rule + "' gives " + c);
                        }
                        ctx.setCountHint((int) c);
                    }
                    DecodedValue v = ctx.decode(f.getDataType(), f.getName());
                    ctx.takeCountHint();
                    kids.add(v);
                    if (numbers != null && v.isNumeric()) {
                        numbers.put(f.getName(), v.getLong());
                    }
                } catch (HlaDecodeException e) {
                    ctx.takeCountHint();
                    b.children(kids);
                    throw partial(e, b, f.getName(), f.getDataType(), ctx, childStart, start);
                }
            }
            DecoderOptions o = ctx.getOptions();
            return b.children(kids).text(DisplayText.record(kids, o.getMaxTextLength()))
                .raw(ctx.getBuffer(), start, ctx.getPosition() - start).build();
        }

        private static Map<String, Long> numericFields(List<DecodedValue> kids) {
            Map<String, Long> m = new HashMap<String, Long>();
            for (DecodedValue k : kids) {
                if (k.isNumeric() && k.getName() != null) {
                    m.put(k.getName(), k.getLong());
                }
            }
            return m;
        }
    }

    /** HLAvariantRecord (also the fallback for unknown variant encodings). */
    static final class PlainVariant implements EncodingHandler {
        @Override
        public int octetBoundary(DataType type, TypeLayouts layouts) {
            VariantRecordDataType v = (VariantRecordDataType) type;
            int b = layouts.getOctetBoundary(v.getDiscriminantType());
            for (VariantAlternative a : v.getAlternatives()) {
                b = Math.max(b, layouts.getOctetBoundary(a.getDataType()));
            }
            return b;
        }

        @Override
        public int fixedSize(DataType type, TypeLayouts layouts) {
            return layouts.getFixedVariantSize((VariantRecordDataType) type);
        }

        @Override
        public int minSize(DataType type, TypeLayouts layouts) {
            return layouts.getMinSize(((VariantRecordDataType) type).getDiscriminantType());
        }

        @Override
        public DecodedValue decode(DataType type, String name, DecodeContext ctx) {
            ctx.align(ctx.getLayouts().getOctetBoundary(type));
            int start = ctx.getPosition();
            return decodeBody((VariantRecordDataType) type, name, ctx, start, -1);
        }

        /**
         * Decodes discriminant and alternative from the current position.
         *
         * @param lengthPrefixedEnd -1 for the plain layout; for the length-prefixed layout the
         *                          caller has read the discriminant ... no: see ExtendableVariant
         */
        static DecodedValue decodeBody(VariantRecordDataType v, String name, DecodeContext ctx, int start, int unused) {
            DecodedValue.Builder b = DecodedValue.builder(ValueKind.VARIANT).name(name).dataType(v);
            List<DecodedValue> kids = new ArrayList<DecodedValue>(2);
            EnumeratedDataType dt = v.getDiscriminantType();
            String dn = v.getDiscriminantName() == null ? "Discriminant" : v.getDiscriminantName();
            int discStart = ctx.getPosition();
            DecodedValue disc;
            try {
                if (dt == null) {
                    throw ctx.error("discriminant datatype '" + v.getDiscriminantTypeName() + "' not resolved");
                }
                disc = ctx.decode(dt, dn);
            } catch (HlaDecodeException e) {
                throw partial(e, b, dn, dt, ctx, discStart, start);
            }
            kids.add(disc);
            VariantAlternative alt = v.getAlternativeForValue(disc.getEnumValue());
            if (alt == null) {
                b.children(kids);
                b.child(DecodedValue.builder(ValueKind.ERROR).name("Alternative").text("")
                    .error("no alternative for discriminant " + disc.getText()).build());
                b.error("incomplete: no alternative for discriminant " + disc.getText());
                b.raw(ctx.getBuffer(), start, ctx.getPosition() - start);
                throw new HlaDecodeException(v.getName() + ": no alternative for discriminant " + disc.getText(), b.build());
            }
            String an = altName(alt);
            if (alt.isEmpty() || alt.getDataType() == null) {
                if (!alt.isEmpty()) {
                    throw partialMissing(v, alt, b, kids, ctx, start);
                }
                b.children(kids).text(disc.getText());
            } else {
                int altStart = ctx.getPosition();
                try {
                    kids.add(ctx.decode(alt.getDataType(), an));
                } catch (HlaDecodeException e) {
                    b.children(kids);
                    throw partial(e, b, an, alt.getDataType(), ctx, altStart, start);
                }
                b.children(kids).text(disc.getText() + " " + DisplayText.nested(kids.get(1)));
            }
            return b.raw(ctx.getBuffer(), start, ctx.getPosition() - start).build();
        }

        private static HlaDecodeException partialMissing(VariantRecordDataType v, VariantAlternative alt,
                                                         DecodedValue.Builder b, List<DecodedValue> kids,
                                                         DecodeContext ctx, int start) {
            b.children(kids);
            String msg = v.getName() + ": datatype '" + alt.getDataTypeName() + "' of alternative " + altName(alt) + " not resolved";
            b.error("incomplete: " + msg).raw(ctx.getBuffer(), start, ctx.getPosition() - start);
            return new HlaDecodeException(msg, b.build());
        }

        static String altName(VariantAlternative alt) {
            return alt.getName() != null ? alt.getName() : "Alternative";
        }
    }

    /**
     * HLAextendableVariantRecord (IEEE 1516.2-2025) and RPRextendedVariantRecord. The exact
     * layout could not be verified against the standards text; see
     * {@link DecoderOptions.VariantLayout}.
     */
    static final class ExtendableVariant implements EncodingHandler {
        private final String encodingName;
        private final PlainVariant plain = new PlainVariant();

        ExtendableVariant(String encodingName) {
            this.encodingName = encodingName;
        }

        @Override
        public int octetBoundary(DataType type, TypeLayouts layouts) {
            return Math.max(4, plain.octetBoundary(type, layouts));
        }

        @Override
        public int fixedSize(DataType type, TypeLayouts layouts) {
            return -1;
        }

        @Override
        public int minSize(DataType type, TypeLayouts layouts) {
            return plain.minSize(type, layouts);
        }

        @Override
        public DecodedValue decode(DataType type, String name, DecodeContext ctx) {
            VariantRecordDataType v = (VariantRecordDataType) type;
            DecoderOptions.VariantLayout layout = ctx.getOptions().getVariantLayout(encodingName);
            if (layout == DecoderOptions.VariantLayout.PLAIN) {
                return plainDecode(v, name, ctx);
            }
            int save = ctx.getPosition();
            if (layout == DecoderOptions.VariantLayout.LENGTH_PREFIXED) {
                return lengthPrefixed(v, name, ctx, true);
            }
            // AUTO
            DecodedValue lp = null;
            try {
                lp = lengthPrefixed(v, name, ctx, false);
            } catch (HlaDecodeException e) {
                lp = null;
            }
            if (lp != null) {
                return lp.toBuilder().warning(encodingName + " layout: length-prefixed (auto-detected)").build();
            }
            ctx.seek(save);
            DecodedValue p = plainDecode(v, name, ctx);
            return p.toBuilder().warning(encodingName + " layout: plain (auto-detected)").build();
        }

        private DecodedValue plainDecode(VariantRecordDataType v, String name, DecodeContext ctx) {
            ctx.align(ctx.getLayouts().getOctetBoundary(v));
            int start = ctx.getPosition();
            return PlainVariant.decodeBody(v, name, ctx, start, -1);
        }

        /**
         * @param strict true: decoding errors are errors; false: return null when the data is
         *               not consistent with the length-prefixed layout (AUTO probing)
         */
        private DecodedValue lengthPrefixed(VariantRecordDataType v, String name, DecodeContext ctx, boolean strict) {
            TypeLayouts layouts = ctx.getLayouts();
            ctx.align(layouts.getOctetBoundary(v));
            int start = ctx.getPosition();
            DecodedValue.Builder b = DecodedValue.builder(ValueKind.VARIANT).name(name).dataType(v);
            EnumeratedDataType dt = v.getDiscriminantType();
            if (dt == null) {
                throw ctx.error("discriminant datatype '" + v.getDiscriminantTypeName() + "' not resolved");
            }
            String dn = v.getDiscriminantName() == null ? "Discriminant" : v.getDiscriminantName();
            DecodedValue disc = ctx.decode(dt, dn);
            b.child(disc);
            int length = ctx.readInt32BE();
            if (length < 0 || length > ctx.getRemaining()) {
                if (!strict) {
                    return null;
                }
                throw ctx.error(v.getName() + ": alternative length " + length + " but " + ctx.getRemaining() + " octets left");
            }
            int altStart = ctx.getPosition();
            int altEnd = altStart + length;
            VariantAlternative alt = v.getAlternativeForValue(disc.getEnumValue());
            if (alt == null) {
                ctx.skip(length);
                b.child(DecodedValue.builder(ValueKind.BYTES).name("Unknown").text(
                        DisplayText.hex(ctx.getBuffer(), altStart, length, ctx.getOptions().getMaxHexOctets()))
                        .raw(ctx.getBuffer(), altStart, length).build())
                    .warning("unknown alternative for discriminant " + disc.getText() + ", " + length + " octets skipped")
                    .text(disc.getText() + " (unknown, " + length + " octets)");
                return b.raw(ctx.getBuffer(), start, ctx.getPosition() - start).build();
            }
            if (alt.isEmpty() || alt.getDataType() == null) {
                if (length != 0 && !strict) {
                    return null;
                }
                ctx.skip(length);
                return b.text(disc.getText()).raw(ctx.getBuffer(), start, ctx.getPosition() - start).build();
            }
            int oldLimit = ctx.setLimit(altEnd);
            DecodedValue a;
            try {
                a = ctx.decode(alt.getDataType(), PlainVariant.altName(alt));
            } catch (HlaDecodeException e) {
                ctx.setLimit(oldLimit);
                if (!strict) {
                    return null;
                }
                throw partial(e, b, PlainVariant.altName(alt), alt.getDataType(), ctx, altStart, start);
            }
            ctx.setLimit(oldLimit);
            int left = altEnd - ctx.getPosition();
            if (left < 0 || left >= Math.max(1, layouts.getOctetBoundary(alt.getDataType()))) {
                if (!strict) {
                    return null;
                }
                b.warning(left + " octets of the alternative not decoded");
            }
            ctx.seek(altEnd);
            b.child(a).text(disc.getText() + " " + DisplayText.nested(a));
            return b.raw(ctx.getBuffer(), start, ctx.getPosition() - start).build();
        }
    }
}
