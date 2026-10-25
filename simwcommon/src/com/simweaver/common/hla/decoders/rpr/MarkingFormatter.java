package com.simweaver.common.hla.decoders.rpr;

import com.simweaver.common.hla.decoders.DecodedValue;
import com.simweaver.common.hla.decoders.DisplayText;
import com.simweaver.common.hla.decoders.FormatContext;
import com.simweaver.common.hla.decoders.ValueFormatter;
import com.simweaver.common.hla.decoders.ValueKind;
import com.simweaver.common.iem.ArrayDataType;
import com.simweaver.common.iem.DataType;
import com.simweaver.common.iem.FixedRecordDataType;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;

/**
 * {@code MarkingStruct} (RPR v2 {@code MarkingEncodingEnum8}, v3
 * {@code EntityMarkingCharacterSetEnum8} + 11 octets) as plain text.
 * <p>
 * Character sets ASCII (1) and Other/Unused (0) are read as ISO-8859-1 up to the first NUL,
 * trailing spaces removed; control characters are escaped ({@code \x07}). Army marking (CCTT)
 * and digit chevron (2, 3) use other symbol sets and are shown as {@code [set] hex}.
 * The result is a {@link ValueKind#TEXT} node; {@link DecodedValue#getString()} gives the
 * marking, and the original fields stay available as children.
 */
final class MarkingFormatter implements ValueFormatter {

    @Override
    public Collection<String> getDataTypeNames() {
        return Collections.singletonList("MarkingStruct");
    }

    @Override
    public boolean accepts(DataType type) {
        FixedRecordDataType r = Shapes.record(type, 2);
        if (r == null || !Shapes.isInteger(Shapes.field(r, 0), 1)) {
            return false;
        }
        DataType data = Shapes.field(r, 1);
        if (!(data instanceof ArrayDataType)) {
            return false;
        }
        ArrayDataType a = (ArrayDataType) data;
        return !a.isDynamic() && a.getCardinality().getFixedElementCount() == 11
            && Shapes.isInteger(a.getElementType(), 1);
    }

    @Override
    public DecodedValue format(DecodedValue value, FormatContext ctx) {
        if (value.hasError() || value.getChildren().size() < 2) {
            return value;
        }
        DecodedValue set = value.getChildren().get(0);
        DecodedValue data = value.getChildren().get(1);
        byte[] bytes = octets(data);
        if (bytes == null) {
            return value;
        }
        long cs = set.getLong();
        if (cs == 0 || cs == 1) {
            int end = 0;
            while (end < bytes.length && bytes[end] != 0) {
                end++;
            }
            String s = new String(bytes, 0, end, StandardCharsets.ISO_8859_1);
            int t = s.length();
            while (t > 0 && s.charAt(t - 1) == ' ') {
                t--;
            }
            s = s.substring(0, t);
            return value.toBuilder().kind(ValueKind.TEXT).stringValue(s)
                .text(DisplayText.escape(s, ctx.getOptions().getMaxTextLength())).build();
        }
        String setName = set.getEnumName() != null ? set.getEnumName() : set.getText();
        return value.toBuilder().text("[" + setName + "] " + DisplayText.hex(bytes, 0, bytes.length, bytes.length)).build();
    }

    /** The 11 octets: from a BYTES node directly, or from an array of numbers. */
    private static byte[] octets(DecodedValue data) {
        if (data.getKind() == ValueKind.BYTES) {
            return data.getRawBytes();
        }
        if (data.getKind() == ValueKind.ARRAY) {
            byte[] b = new byte[data.getChildren().size()];
            for (int i = 0; i < b.length; i++) {
                b[i] = (byte) data.getChildren().get(i).getLong();
            }
            return b;
        }
        return null;
    }
}
