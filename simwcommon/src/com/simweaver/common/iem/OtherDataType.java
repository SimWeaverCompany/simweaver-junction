package com.simweaver.common.iem;

import java.util.Collections;
import java.util.Map;

/**
 * A datatype from an OMT datatype table this package does not model (for example a table added
 * in a newer OMT revision). Its simple properties are kept verbatim so that references to it
 * resolve and a decoder can decide what to do.
 */
public final class OtherDataType extends DataType {

    String tableElement;
    String entryElement;
    Map<String, String> properties = Collections.emptyMap();

    OtherDataType(String name) {
        super(name);
    }

    @Override
    public DataTypeCategory getCategory() {
        return DataTypeCategory.OTHER;
    }

    /** XML element name of the table, e.g. {@code referenceDataTypes}. */
    public String getTableElement() {
        return tableElement;
    }

    /** XML element name of the entry. */
    public String getEntryElement() {
        return entryElement;
    }

    /** Simple child elements of the entry (name to text), name excluded. */
    public Map<String, String> getProperties() {
        return properties;
    }
}
