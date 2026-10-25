package com.simweaver.common.iem;

/**
 * Simple datatype (OMT simpleData): a basic representation with units, resolution and accuracy.
 */
public final class SimpleDataType extends DataType {

    String representationName;
    BasicDataType representation;
    String units;
    String resolution;
    String accuracy;

    SimpleDataType(String name) {
        super(name);
    }

    @Override
    public DataTypeCategory getCategory() {
        return DataTypeCategory.SIMPLE;
    }

    /** The basic representation this type is encoded as. */
    public BasicDataType getRepresentation() {
        return representation;
    }

    public String getRepresentationName() {
        return representationName;
    }

    /** Units text, e.g. "m/s" or "NA"; may be null. */
    public String getUnits() {
        return units;
    }

    public String getResolution() {
        return resolution;
    }

    public String getAccuracy() {
        return accuracy;
    }
}
