package com.simweaver.common.iem;

/**
 * Array datatype (OMT arrayData).
 */
public final class ArrayDataType extends DataType {

    String elementTypeName;
    DataType elementType;
    ArrayCardinality cardinality;

    ArrayDataType(String name) {
        super(name);
    }

    @Override
    public DataTypeCategory getCategory() {
        return DataTypeCategory.ARRAY;
    }

    /** Element datatype. */
    public DataType getElementType() {
        return elementType;
    }

    public String getElementTypeName() {
        return elementTypeName;
    }

    /** Parsed cardinality. */
    public ArrayCardinality getCardinality() {
        return cardinality;
    }

    /** Shortcut for {@code getCardinality().isDynamic()}. */
    public boolean isDynamic() {
        return cardinality.isDynamic();
    }
}
