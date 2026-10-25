package com.simweaver.common.iem;

/**
 * The OMT datatype table a datatype is defined in.
 */
public enum DataTypeCategory {

    /** basicDataRepresentations / basicData. */
    BASIC("basicDataRepresentations", "basicData"),

    /** simpleDataTypes / simpleData. */
    SIMPLE("simpleDataTypes", "simpleData"),

    /** enumeratedDataTypes / enumeratedData. */
    ENUMERATED("enumeratedDataTypes", "enumeratedData"),

    /** arrayDataTypes / arrayData. */
    ARRAY("arrayDataTypes", "arrayData"),

    /** fixedRecordDataTypes / fixedRecordData. */
    FIXED_RECORD("fixedRecordDataTypes", "fixedRecordData"),

    /** variantRecordDataTypes / variantRecordData. */
    VARIANT_RECORD("variantRecordDataTypes", "variantRecordData"),

    /**
     * A datatype table not known to this implementation (for example a table added by a later
     * revision of the OMT). Its entries are kept as {@link OtherDataType} so references resolve.
     */
    OTHER(null, null);

    private final String tableElement;
    private final String entryElement;

    DataTypeCategory(String tableElement, String entryElement) {
        this.tableElement = tableElement;
        this.entryElement = entryElement;
    }

    /** XML element of the table (e.g. {@code arrayDataTypes}); null for {@link #OTHER}. */
    public String getTableElement() {
        return tableElement;
    }

    /** XML element of one entry (e.g. {@code arrayData}); null for {@link #OTHER}. */
    public String getEntryElement() {
        return entryElement;
    }

    static DataTypeCategory fromTableElement(String element) {
        for (DataTypeCategory c : values()) {
            if (c.tableElement != null && c.tableElement.equals(element)) {
                return c;
            }
        }
        return OTHER;
    }
}
