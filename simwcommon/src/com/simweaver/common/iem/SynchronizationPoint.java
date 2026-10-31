package com.simweaver.common.iem;

/**
 * A synchronization point from the OMT synchronization table. {@link #getName()} is the label.
 */
public final class SynchronizationPoint extends IemElement {

    String dataTypeName;
    DataType dataType;
    String capability;
    String semantics;

    SynchronizationPoint(String label) {
        super(label);
    }

    @Override
    public ElementKind getKind() {
        return ElementKind.SYNCHRONIZATION_POINT;
    }

    /** The label (same as {@link #getName()}). */
    public String getLabel() {
        return getName();
    }

    /** Datatype name of the user supplied tag, "NA" or null. */
    public String getDataTypeName() {
        return dataTypeName;
    }

    /** Resolved tag datatype, or null. */
    public DataType getDataType() {
        return dataType;
    }

    /** Capability text (Register, Achieve, RegisterAchieve, NoSynch, NA), or null. */
    public String getCapability() {
        return capability;
    }

    public String getSemantics() {
        return semantics;
    }
}
