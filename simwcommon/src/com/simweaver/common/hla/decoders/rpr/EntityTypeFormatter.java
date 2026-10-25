package com.simweaver.common.hla.decoders.rpr;

import com.simweaver.common.hla.decoders.DecodedValue;
import com.simweaver.common.hla.decoders.FormatContext;
import com.simweaver.common.hla.decoders.ValueFormatter;
import com.simweaver.common.iem.DataType;
import com.simweaver.common.iem.FixedRecordDataType;

import java.util.Collection;
import java.util.Collections;

/**
 * {@code EntityTypeStruct} as {@code Kind.Domain.Country.Category.Subcategory.Specific.Extra},
 * e.g. {@code 1.3.203.3.3.3.0} (SISO-REF-010 notation), unsigned decimal. With
 * {@link RprFormatters.EntityTypeText#STRUCT} the generic record display is kept.
 */
final class EntityTypeFormatter implements ValueFormatter {

    private final RprFormatters.EntityTypeText mode;

    EntityTypeFormatter(RprFormatters.EntityTypeText mode) {
        this.mode = mode;
    }

    @Override
    public Collection<String> getDataTypeNames() {
        return Collections.singletonList("EntityTypeStruct");
    }

    @Override
    public boolean accepts(DataType type) {
        FixedRecordDataType r = Shapes.record(type, 7);
        if (r == null) {
            return false;
        }
        for (int i = 0; i < 7; i++) {
            if (!Shapes.isInteger(Shapes.field(r, i), i == 2 ? 2 : 1)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public DecodedValue format(DecodedValue value, FormatContext ctx) {
        if (mode != RprFormatters.EntityTypeText.DOTTED || value.hasError() || value.getChildren().size() != 7) {
            return value;
        }
        StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < 7; i++) {
            if (i > 0) {
                sb.append('.');
            }
            long v = value.getChildren().get(i).getLong();
            sb.append(v & (i == 2 ? 0xffffL : 0xffL));
        }
        return value.toBuilder().text(sb.toString()).build();
    }
}
