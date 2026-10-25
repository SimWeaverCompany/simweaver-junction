package com.simweaver.common.hla.decoders.rpr;

import com.simweaver.common.hla.decoders.DecodedValue;
import com.simweaver.common.hla.decoders.FormatContext;
import com.simweaver.common.hla.decoders.Reference;
import com.simweaver.common.hla.decoders.ValueFormatter;
import com.simweaver.common.iem.ArrayDataType;
import com.simweaver.common.iem.DataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Tags {@code RTIobjectId} (an HLA object instance name; v2 null-terminated ASCII, v3 Unicode
 * variable array) and {@code RTIobjectIdArray} values as {@link Reference#OBJECT_NAME}
 * references. Empty names are not references.
 */
final class ObjectNameFormatter implements ValueFormatter {

    private static final String ID = "RTIobjectId";
    private static final String ARRAY = "RTIobjectIdArray";

    @Override
    public Collection<String> getDataTypeNames() {
        return Arrays.asList(ID, ARRAY);
    }

    @Override
    public boolean accepts(DataType type) {
        if (ID.equals(type.getName())) {
            return Shapes.isCharArray(type);
        }
        if (ARRAY.equals(type.getName()) && type instanceof ArrayDataType) {
            DataType e = ((ArrayDataType) type).getElementType();
            return e != null && ID.equals(e.getName()) && Shapes.isCharArray(e);
        }
        return false;
    }

    @Override
    public DecodedValue format(DecodedValue value, FormatContext ctx) {
        if (value.hasError()) {
            return value;
        }
        if (ID.equals(value.getDataTypeName())) {
            if (!IdentifierFormatter.isText(value)) {
                return value;
            }
            String name = IdentifierFormatter.stripNul(value.getString());
            return name.isEmpty() ? value : value.toBuilder().reference(Reference.of(Reference.OBJECT_NAME, name)).build();
        }
        List<String> keys = new ArrayList<String>(value.getChildren().size());
        for (DecodedValue c : value.getChildren()) {
            if (IdentifierFormatter.isText(c)) {
                keys.add(IdentifierFormatter.stripNul(c.getString()));
            }
        }
        Reference r = new Reference(Reference.OBJECT_NAME, keys);
        return r.isEmpty() ? value : value.toBuilder().reference(r).build();
    }
}
