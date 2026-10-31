package com.simweaver.common.iem;

import java.util.Collections;
import java.util.List;

/**
 * A DDM dimension (OMT dimension table).
 */
public final class Dimension extends IemElement {

    String dataTypeName;
    DataType dataType;
    String upperBoundText;
    long upperBound = -1;
    String normalization;
    String value;
    List<FomModule> modules = Collections.emptyList();

    Dimension(String name) {
        super(name);
    }

    @Override
    public ElementKind getKind() {
        return ElementKind.DIMENSION;
    }

    public String getDataTypeName() {
        return dataTypeName;
    }

    /** Resolved datatype, or null. */
    public DataType getDataType() {
        return dataType;
    }

    /** Upper bound as written, or null. */
    public String getUpperBoundText() {
        return upperBoundText;
    }

    /** Upper bound, or -1 when absent or not a number. */
    public long getUpperBound() {
        return upperBound;
    }

    /** Normalization function text, or null. */
    public String getNormalization() {
        return normalization;
    }

    /** Default range ("value when unspecified", e.g. "Excluded" or "[0..5)"), or null. */
    public String getValue() {
        return value;
    }

    public List<FomModule> getModules() {
        return modules;
    }
}
