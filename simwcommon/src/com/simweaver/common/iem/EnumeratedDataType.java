package com.simweaver.common.iem;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Enumerated datatype (OMT enumeratedData). Enumerators contributed by several modules (HLA 4
 * extended merging, used by NETN) are merged into one list, in merge order.
 */
public final class EnumeratedDataType extends DataType {

    String representationName;
    BasicDataType representation;
    List<Enumerator> enumerators = Collections.emptyList();
    Map<String, Enumerator> byName = Collections.emptyMap();
    Map<Long, Enumerator> byValue = Collections.emptyMap();

    EnumeratedDataType(String name) {
        super(name);
    }

    @Override
    public DataTypeCategory getCategory() {
        return DataTypeCategory.ENUMERATED;
    }

    /** The basic representation the values are encoded as. */
    public BasicDataType getRepresentation() {
        return representation;
    }

    public String getRepresentationName() {
        return representationName;
    }

    /** All enumerators in merge order. */
    public List<Enumerator> getEnumerators() {
        return enumerators;
    }

    /** Enumerator by name, or null. */
    public Enumerator getEnumerator(String enumeratorName) {
        return enumeratorName == null ? null : byName.get(enumeratorName.trim());
    }

    /** Enumerator having this value (any of its values), or null. */
    public Enumerator getEnumeratorByValue(long value) {
        return byValue.get(value);
    }
}
