package com.simweaver.common.iem;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Fixed record datatype (OMT fixedRecordData).
 */
public final class FixedRecordDataType extends DataType {

    List<RecordField> fields = Collections.emptyList();
    Map<String, RecordField> fieldsByName = Collections.emptyMap();

    FixedRecordDataType(String name) {
        super(name);
    }

    @Override
    public DataTypeCategory getCategory() {
        return DataTypeCategory.FIXED_RECORD;
    }

    /** Fields in encoding order. */
    public List<RecordField> getFields() {
        return fields;
    }

    /** Field by name, or null. */
    public RecordField getField(String fieldName) {
        return fieldName == null ? null : fieldsByName.get(fieldName.trim());
    }
}
