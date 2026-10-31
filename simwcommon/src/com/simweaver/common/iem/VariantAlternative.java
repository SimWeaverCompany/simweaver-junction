package com.simweaver.common.iem;

import java.util.Collections;
import java.util.List;

/**
 * One alternative of a {@link VariantRecordDataType}.
 */
public final class VariantAlternative {

    final VariantRecordDataType record;
    final String name;
    final String enumeratorSpec;
    final String dataTypeName;
    final String semantics;
    final FomModule module;
    DataType dataType;
    List<Enumerator> enumerators = Collections.emptyList();
    boolean other;

    VariantAlternative(VariantRecordDataType record, String name, String enumeratorSpec, String dataTypeName,
            String semantics, FomModule module) {
        this.record = record;
        this.name = name;
        this.enumeratorSpec = enumeratorSpec;
        this.dataTypeName = dataTypeName;
        this.semantics = semantics;
        this.module = module;
    }

    public VariantRecordDataType getVariantRecord() {
        return record;
    }

    /** Alternative name, or null when the FOM says NA. */
    public String getName() {
        return name;
    }

    /** The enumerator text as written, e.g. {@code "Detach, StopAtSideOfRoad"}, {@code "[A..C]"}, {@code "HLAother"}. */
    public String getEnumeratorSpec() {
        return enumeratorSpec;
    }

    /** Resolved enumerators of the discriminant selecting this alternative (empty for a pure HLAother). */
    public List<Enumerator> getEnumerators() {
        return enumerators;
    }

    /** True if this alternative is selected by {@code HLAother} (any value not covered elsewhere). */
    public boolean isOther() {
        return other;
    }

    /** Datatype name, or null/"NA" when the alternative carries no data. */
    public String getDataTypeName() {
        return dataTypeName;
    }

    /** Resolved datatype, or null when the alternative carries no data. */
    public DataType getDataType() {
        return dataType;
    }

    /** True if the alternative carries no data (datatype NA). */
    public boolean isEmpty() {
        return dataTypeName == null || "NA".equals(dataTypeName);
    }

    public String getSemantics() {
        return semantics;
    }

    /** Module contributing this alternative. */
    public FomModule getModule() {
        return module;
    }

    @Override
    public String toString() {
        return record.getName() + "[" + enumeratorSpec + "]" + (name == null ? "" : " " + name) + ":" + dataTypeName;
    }
}
