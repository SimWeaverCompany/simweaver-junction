package com.simweaver.common.hla.decoders.rpr;

import com.simweaver.common.hla.decoders.DecodedValue;
import com.simweaver.common.hla.decoders.FormatContext;
import com.simweaver.common.hla.decoders.ValueFormatter;
import com.simweaver.common.hla.decoders.ValueKind;
import com.simweaver.common.iem.ArrayDataType;
import com.simweaver.common.iem.ClassMember;
import com.simweaver.common.iem.DataType;
import com.simweaver.common.iem.FixedRecordDataType;
import com.simweaver.common.iem.MemberOnClass;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Link 16 values of the RPR-FOM v2 Link-16 BOM (see {@link Link16}):
 * <ul>
 *   <li>{@code JTIDSHeaderStruct} (one 6-octet field): text {@code STN 00417, TS type 1, SDU 1234};
 *       derived TimeSlotType, RelayTransmission, SourceTrackNumber (octal), SecureDataUnitSerialNumber.</li>
 *   <li>{@code TADILJWordStruct} (one array of 10-octet words): text
 *       {@code J3.2 Air Track (3 words)}; one derived record per J-series message with its words.</li>
 *   <li>{@code LETHeaderStruct}: text {@code STN 00417, LET id 2, packing 1, SDU 5}; derived fields.</li>
 *   <li>the {@code TADILJWordStruct} of a {@code VMFRadioSignal}: {@code VMF (2 words)}, words without labels.</li>
 *   <li>{@code NPGNumber} of {@code Link16RadioSignal} (any 16-bit integer type): {@code 7 Surveillance}.</li>
 *   <li>{@code TimeSlotID} of {@code Link16RadioSignal}: {@code epoch 1, slot A-4661}, or {@code no statement}.</li>
 *   <li>{@code TDLTransmitTimeStruct}: the NTP time as UTC, or {@code no statement}.</li>
 * </ul>
 */
final class Link16Formatter implements ValueFormatter {

    static final String HEADER = "JTIDSHeaderStruct";
    static final String LET_HEADER = "LETHeaderStruct";
    static final String MESSAGE = "TADILJWordStruct";
    static final String NPG_TYPE = "TDLuint16be";
    private static final String NPG_MEMBER = "Link16RadioSignal.NPGNumber";
    static final String SLOT_TYPE = "TDLuint32be";
    private static final String SLOT_MEMBER = "Link16RadioSignal.TimeSlotID";
    static final String TIME_TYPE = "TDLTransmitTimeStruct";

    private final Link16.BitOrder order;

    Link16Formatter(Link16.BitOrder order) {
        this.order = order == null ? Link16.BitOrder.AUTO : order;
    }

    @Override
    public Collection<String> getDataTypeNames() {
        return Arrays.asList(HEADER, LET_HEADER, MESSAGE, NPG_TYPE, SLOT_TYPE, TIME_TYPE);
    }

    @Override
    public boolean accepts(DataType type) {
        String n = type.getName();
        if (HEADER.equals(n) || LET_HEADER.equals(n)) {
            FixedRecordDataType r = Shapes.record(type, 1);
            return r != null && isOctetArray(Shapes.field(r, 0), Link16.HEADER_OCTETS);
        }
        if (MESSAGE.equals(n)) {
            FixedRecordDataType r = Shapes.record(type, 1);
            if (r == null || !(Shapes.field(r, 0) instanceof ArrayDataType)) {
                return false;
            }
            ArrayDataType words = (ArrayDataType) Shapes.field(r, 0);
            return words.isDynamic() && isOctetArray(words.getElementType(), Link16.WORD_OCTETS);
        }
        if (SLOT_TYPE.equals(n)) {
            return Shapes.isInteger(type, 4);
        }
        if (TIME_TYPE.equals(n)) {
            FixedRecordDataType r = Shapes.record(type, 2);
            return r != null && Shapes.isInteger(Shapes.field(r, 0), 4) && Shapes.isInteger(Shapes.field(r, 1), 4);
        }
        return NPG_TYPE.equals(n) && Shapes.isInteger(type, 2);
    }

    /** A fixed array of {@code count} one-octet integers. */
    private static boolean isOctetArray(DataType t, int count) {
        if (!(t instanceof ArrayDataType)) {
            return false;
        }
        ArrayDataType a = (ArrayDataType) t;
        return !a.isDynamic() && a.getCardinality().getFixedElementCount() == count
            && Shapes.isInteger(a.getElementType(), 1);
    }

    @Override
    public DecodedValue format(DecodedValue value, FormatContext ctx) {
        if (value.hasError()) {
            return value;
        }
        String n = value.getDataTypeName();
        if (HEADER.equals(n) || LET_HEADER.equals(n)) {
            byte[] octets = value.getChildren().isEmpty() ? null : octets(value.getChildren().get(0));
            if (octets == null) {
                return value;
            }
            return LET_HEADER.equals(n) ? Link16.letHeaderValue(value, octets, order) : Link16.headerValue(value, octets, order);
        }
        if (MESSAGE.equals(n)) {
            if (value.getChildren().isEmpty()) {
                return value;
            }
            DecodedValue array = value.getChildren().get(0);
            List<byte[]> words = new ArrayList<byte[]>(array.getChildren().size());
            for (DecodedValue w : array.getChildren()) {
                if (w.isDerived()) {
                    continue;
                }
                byte[] o = octets(w);
                if (o == null || o.length != Link16.WORD_OCTETS) {
                    return value;
                }
                words.add(o);
            }
            return isVmf(ctx) ? Link16.vmfValue(value, words, order) : Link16.messagesValue(value, words, order);
        }
        if (SLOT_TYPE.equals(n) && isMember(ctx, SLOT_MEMBER) && value.isNumeric()) {
            return value.toBuilder().text(Link16.timeSlotText(value.getLong())).build();
        }
        if (TIME_TYPE.equals(n) && value.getChildren().size() >= 2 && value.getChildren().get(0).isNumeric()
                && value.getChildren().get(1).isNumeric()) {
            return value.toBuilder().text(Link16.ntpText(value.getChildren().get(0).getLong(), value.getChildren().get(1).getLong())).build();
        }
        if (NPG_TYPE.equals(n) && isNpgMember(ctx) && value.isNumeric()) {
            String name = Link16.npgName((int) value.getLong());
            return name == null ? value : value.toBuilder().text(value.getLong() + " " + name).build();
        }
        return value;
    }

    /** True for the TADILJMessage of a VMFRadioSignal (its words are VMF, not J-series messages). */
    private static boolean isVmf(FormatContext ctx) {
        MemberOnClass<?, ?> m = ctx.getMember();
        if (m == null) {
            return false;
        }
        String cls = m.getDeclaration().getDeclaringClass().getQualifiedNameWithoutRoot();
        return cls.equals("VMFRadioSignal") || cls.endsWith(".VMFRadioSignal");
    }

    private static boolean isNpgMember(FormatContext ctx) {
        return isMember(ctx, NPG_MEMBER);
    }

    private static boolean isMember(FormatContext ctx, String member) {
        MemberOnClass<?, ?> m = ctx.getMember();
        if (m == null || ctx.getDepth() != 0) {
            return false;
        }
        ClassMember d = m.getDeclaration();
        String key = d.getDeclaringClass().getQualifiedNameWithoutRoot() + "." + d.getName();
        return key.equals(member) || key.endsWith("." + member);
    }

    /** The octets of a BYTES node, or of an array of small integers; null otherwise. */
    static byte[] octets(DecodedValue v) {
        if (v.getKind() == ValueKind.BYTES) {
            return v.getRawBytes();
        }
        if (v.getKind() == ValueKind.ARRAY) {
            List<DecodedValue> c = new ArrayList<DecodedValue>();
            for (DecodedValue e : v.getChildren()) {
                if (!e.isDerived()) {
                    c.add(e);
                }
            }
            byte[] b = new byte[c.size()];
            for (int i = 0; i < b.length; i++) {
                if (!c.get(i).isNumeric()) {
                    return null;
                }
                b[i] = (byte) c.get(i).getLong();
            }
            return b;
        }
        return null;
    }
}
