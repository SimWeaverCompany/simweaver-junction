package com.simweaver.common.hla.decoders.rpr;

import com.simweaver.common.hla.decoders.DecodedValue;
import com.simweaver.common.hla.decoders.ValueKind;
import com.simweaver.common.iem.ArrayDataType;
import com.simweaver.common.iem.BasicDataType;
import com.simweaver.common.iem.DataType;
import com.simweaver.common.iem.DataTypeCategory;
import com.simweaver.common.iem.EnumeratedDataType;
import com.simweaver.common.iem.FixedRecordDataType;
import com.simweaver.common.iem.RecordField;
import com.simweaver.common.iem.SimpleDataType;

import java.util.Locale;

/**
 * Shape checks and small helpers shared by the RPR formatters.
 */
final class Shapes {

    private Shapes() {
    }

    static BasicDataType basicOf(DataType t) {
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

    /** Integer-like (basic, simple or enumerated) with a representation of at most {@code maxOctets}. */
    static boolean isInteger(DataType t, int maxOctets) {
        BasicDataType b = basicOf(t);
        if (b == null || b.getSizeOctets() > maxOctets) {
            return false;
        }
        String n = b.getName().toLowerCase(Locale.ROOT);
        return !n.contains("float") && !b.isFloatingPointHint();
    }

    /** 64-bit or 32-bit floating point (basic or simple). */
    static boolean isFloat(DataType t, int octets) {
        BasicDataType b = basicOf(t);
        if (b == null || t instanceof EnumeratedDataType || b.getSizeOctets() != octets) {
            return false;
        }
        return b.getName().toLowerCase(Locale.ROOT).contains("float") || b.isFloatingPointHint();
    }

    static FixedRecordDataType record(DataType t, int fieldCount) {
        if (!(t instanceof FixedRecordDataType)) {
            return null;
        }
        FixedRecordDataType r = (FixedRecordDataType) t;
        return r.getFields().size() == fieldCount ? r : null;
    }

    static DataType field(FixedRecordDataType r, int i) {
        RecordField f = r.getFields().get(i);
        return f.getDataType();
    }

    static boolean isCharArray(DataType t) {
        if (!(t instanceof ArrayDataType)) {
            return false;
        }
        DataType e = ((ArrayDataType) t).getElementType();
        return e != null && e.getCategory() == DataTypeCategory.SIMPLE
            && e.getName().toLowerCase(Locale.ROOT).endsWith("char");
    }

    /** Three float64 fields (X, Y, Z of a location). */
    static boolean isVector64(DataType t) {
        FixedRecordDataType r = record(t, 3);
        return r != null && isFloat(field(r, 0), 8) && isFloat(field(r, 1), 8) && isFloat(field(r, 2), 8);
    }

    /** Three float32 fields (velocity, acceleration, angular velocity, orientation). */
    static boolean isVector32(DataType t) {
        FixedRecordDataType r = record(t, 3);
        return r != null && isFloat(field(r, 0), 4) && isFloat(field(r, 1), 4) && isFloat(field(r, 2), 4);
    }

    /** Numeric value of the i-th child, NaN when absent or not numeric. */
    static double num(DecodedValue v, int i) {
        if (v == null || i >= v.getChildren().size()) {
            return Double.NaN;
        }
        DecodedValue c = v.getChildren().get(i);
        return c.isNumeric() ? c.getDouble() : Double.NaN;
    }

    static boolean isStructural(DecodedValue v, ValueKind kind) {
        return v != null && v.getKind() == kind && !v.hasError();
    }

    static DecodedValue derived(String name, double value, int decimals, String units) {
        return DecodedValue.builder(ValueKind.FLOAT).name(name).doubleValue(value)
            .text(Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%." + decimals + "f", value))
            .units(units).derived(true).build();
    }

    static DecodedValue derivedVector(String name, String[] names, double[] values, int decimals, String units) {
        DecodedValue.Builder b = DecodedValue.builder(ValueKind.RECORD).name(name).derived(true).units(units);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            DecodedValue c = derived(names[i], values[i], decimals, units);
            b.child(c);
            if (i > 0) {
                text.append(", ");
            }
            text.append(c.getText());
        }
        return b.text(text.toString()).build();
    }
}
