package com.simweaver.common.iem;

/**
 * One field of a {@link FixedRecordDataType}.
 */
public final class RecordField {

    final FixedRecordDataType record;
    final String name;
    final int index;
    final String dataTypeName;
    final String semantics;
    DataType dataType;

    RecordField(FixedRecordDataType record, String name, int index, String dataTypeName, String semantics) {
        this.record = record;
        this.name = name;
        this.index = index;
        this.dataTypeName = dataTypeName;
        this.semantics = semantics;
    }

    public FixedRecordDataType getRecord() {
        return record;
    }

    public String getName() {
        return name;
    }

    /** 0-based position in the record (encoding order). */
    public int getIndex() {
        return index;
    }

    public String getDataTypeName() {
        return dataTypeName;
    }

    /** Resolved field datatype, or null if it could not be resolved. */
    public DataType getDataType() {
        return dataType;
    }

    public String getSemantics() {
        return semantics;
    }

    @Override
    public String toString() {
        return record.getName() + "." + name + ":" + dataTypeName;
    }
}
