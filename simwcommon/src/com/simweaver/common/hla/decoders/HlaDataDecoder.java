package com.simweaver.common.hla.decoders;

import com.simweaver.common.hla.decoders.rpr.RprFormatters;
import com.simweaver.common.iem.BasicDataType;
import com.simweaver.common.iem.ClassAttribute;
import com.simweaver.common.iem.ClassParameter;
import com.simweaver.common.iem.DataType;
import com.simweaver.common.iem.Enumerator;
import com.simweaver.common.iem.EnumeratedDataType;
import com.simweaver.common.iem.FixedRecordDataType;
import com.simweaver.common.iem.FomFamily;
import com.simweaver.common.iem.HlaClass;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.iem.InteractionClass;
import com.simweaver.common.iem.MemberOnClass;
import com.simweaver.common.iem.ObjectClass;
import com.simweaver.common.iem.RecordField;
import com.simweaver.common.iem.SimpleDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decodes HLA attribute and parameter values using an {@link InformationExchangeModel}.
 * Independent of the HLA API version and of any RTI: the encoding of a value is defined by
 * the FOM, and IEEE 1516-2010 and 1516-2025 encode the same datatype identically. Handles
 * (which are what the RTI adds) are mapped to IEM ids by the version specific bindings in
 * {@code ...decoders.evolved} and {@code ...decoders.hla4}.
 * <p>
 * Immutable and thread safe once built; build one per model and share it.
 * <p>
 * The public {@code decode} methods never throw for bad data: they return the part that could
 * be decoded, with {@link DecodedValue#getError()} set on the top node and an
 * {@link ValueKind#ERROR} node where decoding stopped.
 *
 * <pre>
 * HlaDataDecoder decoder = HlaDataDecoder.create(iem);          // RPR formatters when the FOM is RPR/NETN
 * ClassAttribute spatial = iem.getClassAttribute("HLAobjectRoot.BaseEntity.PhysicalEntity", "Spatial");
 * DecodedValue v = decoder.decodeAttribute(spatial, bytes, receivedMillis);
 * v.getText();   // "DRM_FPW 59.329312 18.068581 120.4 m hdg 274.1 ..."
 * </pre>
 */
public final class HlaDataDecoder {

    /** Value of a received time when it is unknown. */
    public static final long NO_TIME = Long.MIN_VALUE;

    private final InformationExchangeModel model;
    private final DecoderOptions options;
    private final TypeLayouts layouts;
    private final ValueFormatter[][] formatters;
    private final boolean[] booleanEnum;
    private final Map<String, CountExpression> countRules;

    /**
     * Decoder with default options (no formatters).
     *
     * @param model the model
     */
    public HlaDataDecoder(InformationExchangeModel model) {
        this(model, DecoderOptions.defaults());
    }

    /**
     * @param model   the model
     * @param options options (formatters, limits, overrides)
     */
    public HlaDataDecoder(InformationExchangeModel model, DecoderOptions options) {
        if (model == null || options == null) {
            throw new NullPointerException(model == null ? "model" : "options");
        }
        this.model = model;
        this.options = options;
        Map<String, EncodingHandler> handlers = DefaultEncodings.all();
        handlers.putAll(options.getEncodingHandlers());
        this.layouts = new TypeLayouts(model, handlers, options.getBasicInterpretations());

        List<DataType> types = model.getDataTypes();
        this.booleanEnum = new boolean[types.size()];
        for (DataType t : types) {
            if (t instanceof EnumeratedDataType) {
                booleanEnum[t.getIndex()] = isBooleanLike((EnumeratedDataType) t);
            }
        }

        this.formatters = new ValueFormatter[types.size()][];
        if (options.isFormattersEnabled()) {
            List<List<ValueFormatter>> tmp = new ArrayList<List<ValueFormatter>>(types.size());
            for (int i = 0; i < types.size(); i++) {
                tmp.add(null);
            }
            for (ValueFormatter f : options.getFormatters()) {
                for (String n : f.getDataTypeNames()) {
                    DataType t = model.getDataType(n);
                    if (t != null && f.accepts(t)) {
                        List<ValueFormatter> l = tmp.get(t.getIndex());
                        if (l == null) {
                            l = new ArrayList<ValueFormatter>(2);
                            tmp.set(t.getIndex(), l);
                        }
                        if (!l.contains(f)) {
                            l.add(f);
                        }
                    }
                }
            }
            for (int i = 0; i < types.size(); i++) {
                List<ValueFormatter> l = tmp.get(i);
                formatters[i] = l == null ? null : l.toArray(new ValueFormatter[0]);
            }
        }

        this.countRules = new HashMap<String, CountExpression>();
        for (Map.Entry<String, String> e : options.getCountRules().entrySet()) {
            countRules.put(e.getKey(), CountExpression.parse(e.getValue()));
        }
    }

    /**
     * The usual way to build a decoder: default options, plus the RPR-FOM formatters and
     * count rules when the model contains RPR v2, RPR v3 or NETN modules.
     *
     * @param model the model
     * @return the decoder
     */
    public static HlaDataDecoder create(InformationExchangeModel model) {
        return new HlaDataDecoder(model, defaultOptionsFor(model).build());
    }

    /**
     * @param model the model
     * @return default options for this model (RPR formatters when RPR/NETN is present), to adjust
     */
    public static DecoderOptions.Builder defaultOptionsFor(InformationExchangeModel model) {
        DecoderOptions.Builder b = DecoderOptions.builder();
        if (model.hasFomFamily(FomFamily.RPR_FOM_V2) || model.hasFomFamily(FomFamily.RPR_FOM_V3)
                || model.hasFomFamily(FomFamily.NETN)) {
            RprFormatters.install(b);
        }
        return b;
    }

    /** @return the model */
    public InformationExchangeModel getModel() {
        return model;
    }

    /** @return the options */
    public DecoderOptions getOptions() {
        return options;
    }

    /** @return boundaries, sizes and handlers of all datatypes */
    public TypeLayouts getLayouts() {
        return layouts;
    }

    /**
     * Formatters bound to a datatype (after their shape checks).
     *
     * @param type datatype
     * @return formatters in order; empty when none
     */
    public List<ValueFormatter> getFormatters(DataType type) {
        ValueFormatter[] f = formatters[type.getIndex()];
        List<ValueFormatter> out = new ArrayList<ValueFormatter>();
        if (f != null) {
            for (ValueFormatter x : f) {
                out.add(x);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ public decode API

    /**
     * Decodes a whole buffer as one value of a datatype.
     *
     * @param type datatype of this model
     * @param data encoded value (kept by reference, do not modify)
     * @return the value; never null
     */
    public DecodedValue decode(DataType type, byte[] data) {
        return decode(type, data, 0, data.length);
    }

    /**
     * Decodes a slice as one value of a datatype. Alignment is relative to {@code offset}.
     *
     * @param type   datatype of this model
     * @param data   buffer (kept by reference, do not modify)
     * @param offset first octet of the value
     * @param length octets of the value
     * @return the value; never null
     */
    public DecodedValue decode(DataType type, byte[] data, int offset, int length) {
        return decodeTop(type, null, null, data, offset, length, NO_TIME);
    }

    /**
     * Decodes an attribute value.
     *
     * @param attribute      the attribute as seen on the object's known class
     * @param data           encoded value (kept by reference, do not modify)
     * @param receivedMillis receive time (epoch ms UTC) for time dependent formatters, or {@link #NO_TIME}
     * @return the value, named after the attribute; never null
     */
    public DecodedValue decodeAttribute(ClassAttribute attribute, byte[] data, long receivedMillis) {
        return decodeTop(attribute.getDataType(), attribute, attribute.getName(), data, 0, data.length, receivedMillis);
    }

    /**
     * Decodes an attribute value by IEM ids.
     *
     * @param objectClassId  id of the object's known class
     * @param attributeId    attribute id
     * @param data           encoded value (kept by reference, do not modify)
     * @param receivedMillis receive time, or {@link #NO_TIME}
     * @return the value; an error node when the ids do not match the model
     */
    public DecodedValue decodeAttribute(int objectClassId, int attributeId, byte[] data, long receivedMillis) {
        ClassAttribute a = model.getClassAttribute(objectClassId, attributeId);
        if (a == null) {
            return unknownMember("attribute id " + attributeId + " on object class id " + objectClassId, data);
        }
        return decodeAttribute(a, data, receivedMillis);
    }

    /**
     * Decodes a parameter value.
     *
     * @param parameter      the parameter as seen on the interaction class
     * @param data           encoded value (kept by reference, do not modify)
     * @param receivedMillis receive time, or {@link #NO_TIME}
     * @return the value, named after the parameter; never null
     */
    public DecodedValue decodeParameter(ClassParameter parameter, byte[] data, long receivedMillis) {
        return decodeTop(parameter.getDataType(), parameter, parameter.getName(), data, 0, data.length, receivedMillis);
    }

    /**
     * Decodes a parameter value by IEM ids.
     *
     * @param interactionClassId interaction class id
     * @param parameterId        parameter id
     * @param data               encoded value (kept by reference, do not modify)
     * @param receivedMillis     receive time, or {@link #NO_TIME}
     * @return the value; an error node when the ids do not match the model
     */
    public DecodedValue decodeParameter(int interactionClassId, int parameterId, byte[] data, long receivedMillis) {
        ClassParameter p = model.getClassParameter(interactionClassId, parameterId);
        if (p == null) {
            return unknownMember("parameter id " + parameterId + " on interaction class id " + interactionClassId, data);
        }
        return decodeParameter(p, data, receivedMillis);
    }

    /**
     * Decodes all values of a captured update or interaction (see the bindings' {@code capture}
     * methods, which run on the RTI callback thread; this one is meant for a worker thread).
     *
     * @param raw captured values
     * @return decoded members, in capture order
     */
    public DecodedMembers decode(RawValues raw) {
        List<DecodedMember> out = new ArrayList<DecodedMember>(raw.size());
        HlaClass<?> cls;
        if (raw.getKind() == RawValues.Kind.ATTRIBUTES) {
            ObjectClass oc = model.getObjectClass(raw.getClassId());
            cls = oc;
            for (int i = 0; i < raw.size(); i++) {
                ClassAttribute a = oc == null ? null : oc.getAttribute(raw.getMemberId(i));
                if (a == null) {
                    out.add(new DecodedMember(null, raw.getLabel(i), unknownMember(raw.getLabel(i), raw.getValue(i))));
                } else {
                    out.add(new DecodedMember(a, a.getName(), decodeAttribute(a, raw.getValue(i), raw.getReceivedMillis())));
                }
            }
        } else {
            InteractionClass ic = model.getInteractionClass(raw.getClassId());
            cls = ic;
            for (int i = 0; i < raw.size(); i++) {
                ClassParameter p = ic == null ? null : ic.getParameter(raw.getMemberId(i));
                if (p == null) {
                    out.add(new DecodedMember(null, raw.getLabel(i), unknownMember(raw.getLabel(i), raw.getValue(i))));
                } else {
                    out.add(new DecodedMember(p, p.getName(), decodeParameter(p, raw.getValue(i), raw.getReceivedMillis())));
                }
            }
        }
        return new DecodedMembers(raw.getKind(), cls, raw.getReceivedMillis(), out);
    }

    // ------------------------------------------------------------------ internals

    private DecodedValue unknownMember(String label, byte[] data) {
        return DecodedValue.builder(ValueKind.BYTES).name(label)
            .text(DisplayText.hex(data, 0, data.length, options.getMaxHexOctets()))
            .raw(data, 0, data.length)
            .warning("not in the information exchange model; shown as raw octets").build();
    }

    private DecodedValue decodeTop(DataType type, MemberOnClass<?, ?> member, String name, byte[] data,
                                   int offset, int length, long receivedMillis) {
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IndexOutOfBoundsException("offset " + offset + ", length " + length + ", buffer " + data.length);
        }
        if (type == null) {
            String dt = member == null ? null : member.getDataTypeName();
            return DecodedValue.builder(ValueKind.BYTES).name(name).dataTypeName(dt)
                .text(DisplayText.hex(data, offset, length, options.getMaxHexOctets()))
                .raw(data, offset, length)
                .warning("no datatype" + (dt == null ? "" : " (" + dt + ")") + "; shown as raw octets").build();
        }
        DecodeContext ctx = new DecodeContext(this, data, offset, length, member, receivedMillis);
        DecodedValue v;
        try {
            v = ctx.decode(type, name);
        } catch (HlaDecodeException e) {
            DecodedValue p = e.getPartial();
            if (p == null) {
                p = DecodedValue.builder(ValueKind.ERROR).name(name).dataType(type).text("")
                    .raw(data, offset, length).build();
            }
            return p.toBuilder().error(e.getMessage()).build();
        } catch (RuntimeException e) {
            // a bug in a handler must not take down the caller's thread
            return DecodedValue.builder(ValueKind.ERROR).name(name).dataType(type).text("")
                .raw(data, offset, length).error("internal decoder error: " + e).build();
        }
        int left = ctx.getRemaining();
        if (left > 0) {
            switch (options.getTrailingOctets()) {
                case WARN:
                    v = v.toBuilder().warning(left + " trailing octets not decoded").build();
                    break;
                case ERROR:
                    v = v.toBuilder().error(left + " trailing octets not decoded").build();
                    break;
                default:
                    break;
            }
        }
        return v;
    }

    /**
     * NEW (Junktion codec): the element count of a lengthless array field from its count rule
     * ({@link DecoderOptions.Builder#countRule}), evaluated against the numeric fields decoded
     * before it in the same record.
     *
     * @param record        the record
     * @param field         the lengthless array field
     * @param earlierFields numeric values of the fields before it, by field name
     * @return the count, or -1 when the field has no count rule or the rule cannot be evaluated
     */
    public long evaluateCountRule(FixedRecordDataType record, RecordField field, Map<String, Long> earlierFields) {
        CountExpression rule = getCountRule(record, field);
        if (rule == null) {
            return -1;
        }
        try {
            long c = rule.evaluate(earlierFields);
            return c < 0 || c > Integer.MAX_VALUE ? -1 : c;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    CountExpression getCountRule(FixedRecordDataType record, RecordField field) {
        if (countRules.isEmpty()) {
            return null;
        }
        return countRules.get(record.getName() + "." + field.getName());
    }

    /** Called through {@link DecodeContext#decode(DataType, String)}. */
    DecodedValue decodeNode(DecodeContext ctx, DataType type, String name) {
        if (type == null) {
            throw ctx.error("datatype of " + name + " not resolved");
        }
        if (ctx.depth + 1 >= options.getMaxDepth()) {
            throw ctx.error("nesting deeper than " + options.getMaxDepth() + " at " + type.getName());
        }
        ctx.depth++;
        try {
            DecodedValue v;
            switch (type.getCategory()) {
                case BASIC:
                    v = decodeBasic(ctx, (BasicDataType) type, type, name);
                    break;
                case SIMPLE:
                    v = decodeSimple(ctx, (SimpleDataType) type, name);
                    break;
                case ENUMERATED:
                    v = decodeEnum(ctx, (EnumeratedDataType) type, name);
                    break;
                case ARRAY:
                case FIXED_RECORD:
                case VARIANT_RECORD: {
                    EncodingHandler h = layouts.getHandler(type);
                    if (h == null) {
                        throw ctx.error("no handler for encoding '" + type.getEncoding() + "' of " + type.getName());
                    }
                    v = h.decode(type, name, ctx);
                    String fb = layouts.getFallbackEncoding(type);
                    if (fb != null) {
                        v = v.toBuilder().warning("unknown encoding " + fb + ", decoded with the standard encoding").build();
                    }
                    break;
                }
                default:
                    throw ctx.error("cannot decode " + type.getName() + ": datatype table not supported");
            }
            ValueFormatter[] fs = formatters[type.getIndex()];
            if (fs != null) {
                for (ValueFormatter f : fs) {
                    try {
                        DecodedValue r = f.format(v, ctx);
                        if (r != null) {
                            v = r;
                        }
                    } catch (RuntimeException e) {
                        v = v.toBuilder().warning("formatter " + f.getClass().getSimpleName() + " failed: " + e).build();
                    }
                }
            }
            return v;
        } finally {
            ctx.depth--;
        }
    }

    private DecodedValue decodeBasic(DecodeContext ctx, BasicDataType basic, DataType asType, String name) {
        int size = basic.getSizeOctets();
        ctx.align(layouts.getOctetBoundary(basic));
        int start = ctx.getPosition();
        boolean big = basic.getEndianness() != com.simweaver.common.iem.Endianness.LITTLE;
        BasicInterpretation in = layouts.getInterpretation(basic);
        DecodedValue.Builder b = DecodedValue.builder(ValueKind.INTEGER).name(name).dataType(asType);
        switch (in) {
            case FLOAT:
                if (size == 4) {
                    float f = Float.intBitsToFloat((int) ctx.readUnsigned(4, big));
                    b.kind(ValueKind.FLOAT).doubleValue(f).text(DisplayText.floating(f, true));
                } else {
                    double d = Double.longBitsToDouble(ctx.readUnsigned(8, big));
                    b.kind(ValueKind.FLOAT).doubleValue(d).text(DisplayText.floating(d, false));
                }
                break;
            case UNSIGNED: {
                long v = ctx.readUnsigned(size, big);
                b.longValue(v).unsigned(size == 8).text(DisplayText.integer(v, size == 8));
                break;
            }
            case SIGNED: {
                long v = ctx.readSigned(size, big);
                b.longValue(v).text(Long.toString(v));
                break;
            }
            default:
                ctx.skip(size);
                b.kind(ValueKind.BYTES).text(DisplayText.hex(ctx.getBuffer(), start, size, options.getMaxHexOctets()));
                break;
        }
        return b.raw(ctx.getBuffer(), start, ctx.getPosition() - start).build();
    }

    private DecodedValue decodeSimple(DecodeContext ctx, SimpleDataType simple, String name) {
        BasicDataType rep = simple.getRepresentation();
        if (rep == null) {
            throw ctx.error("representation '" + simple.getRepresentationName() + "' of " + simple.getName() + " not resolved");
        }
        DecodedValue v = decodeBasic(ctx, rep, simple, name);
        DecodedValue.Builder b = v.toBuilder();
        String units = simple.getUnits();
        if (units != null && !units.trim().isEmpty() && !"NA".equalsIgnoreCase(units.trim())) {
            b.units(units.trim());
        }
        if (layouts.isCharType(simple) && v.getKind() == ValueKind.INTEGER) {
            char c = (char) v.getLong();
            b.kind(ValueKind.TEXT).stringValue(String.valueOf(c)).text(DisplayText.escape(String.valueOf(c), 8));
        }
        return b.build();
    }

    private DecodedValue decodeEnum(DecodeContext ctx, EnumeratedDataType type, String name) {
        BasicDataType rep = type.getRepresentation();
        if (rep == null) {
            throw ctx.error("representation '" + type.getRepresentationName() + "' of " + type.getName() + " not resolved");
        }
        DecodedValue raw = decodeBasic(ctx, rep, type, name);
        long value = raw.getLong();
        Enumerator e = type.getEnumeratorByValue(value);
        DecodedValue.Builder b = raw.toBuilder().kind(ValueKind.ENUM);
        String num = DisplayText.integer(value, raw.isUnsigned());
        if (e == null) {
            b.text(num).warning("value " + num + " is not an enumerator of " + type.getName());
            return b.build();
        }
        b.enumName(e.getName());
        if (booleanEnum[type.getIndex()]) {
            boolean t = isTrueName(e.getName());
            return b.kind(ValueKind.BOOLEAN).booleanValue(t).text(t ? "true" : "false").build();
        }
        switch (options.getEnumText()) {
            case VALUE:
                b.text(num);
                break;
            case NAME_AND_VALUE:
                b.text(e.getName() + " (" + num + ")");
                break;
            default:
                b.text(e.getName());
                break;
        }
        return b.build();
    }

    private static boolean isBooleanLike(EnumeratedDataType t) {
        if (t.getEnumerators().size() != 2) {
            return false;
        }
        boolean hasTrue = false;
        boolean hasFalse = false;
        for (Enumerator e : t.getEnumerators()) {
            String n = strip(e.getName());
            if (n.equals("true") && e.getValue() == 1) {
                hasTrue = true;
            } else if (n.equals("false") && e.getValue() == 0) {
                hasFalse = true;
            }
        }
        return hasTrue && hasFalse;
    }

    private static boolean isTrueName(String n) {
        return strip(n).equals("true");
    }

    private static String strip(String n) {
        String s = n.toLowerCase(Locale.ROOT);
        if (s.startsWith("hla") || s.startsWith("rpr")) {
            s = s.substring(3);
        }
        return s;
    }
}
