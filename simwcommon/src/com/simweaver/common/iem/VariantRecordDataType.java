package com.simweaver.common.iem;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Variant record datatype (OMT variantRecordData). Alternatives contributed by several modules
 * (HLA 4 extended merging, used by NETN) are merged into one list, in merge order.
 */
public final class VariantRecordDataType extends DataType {

    String discriminantName;
    String discriminantTypeName;
    EnumeratedDataType discriminantType;
    List<VariantAlternative> alternatives = Collections.emptyList();
    Map<Long, VariantAlternative> byValue = Collections.emptyMap();
    Map<String, VariantAlternative> byEnumeratorName = Collections.emptyMap();
    VariantAlternative otherAlternative;

    VariantRecordDataType(String name) {
        super(name);
    }

    @Override
    public DataTypeCategory getCategory() {
        return DataTypeCategory.VARIANT_RECORD;
    }

    /** Name of the discriminant. */
    public String getDiscriminantName() {
        return discriminantName;
    }

    /** Discriminant datatype (always an enumeration in a valid FOM), or null if unresolved. */
    public EnumeratedDataType getDiscriminantType() {
        return discriminantType;
    }

    public String getDiscriminantTypeName() {
        return discriminantTypeName;
    }

    /** All alternatives, in merge order. */
    public List<VariantAlternative> getAlternatives() {
        return alternatives;
    }

    /** The {@code HLAother} alternative, or null. */
    public VariantAlternative getOtherAlternative() {
        return otherAlternative;
    }

    /**
     * The alternative selected by a decoded discriminant value: the alternative listing an enumerator
     * with that value, else the {@code HLAother} alternative, else null.
     */
    public VariantAlternative getAlternativeForValue(long discriminantValue) {
        VariantAlternative a = byValue.get(discriminantValue);
        return a != null ? a : otherAlternative;
    }

    /** The alternative selected by an enumerator (name), else the HLAother alternative, else null. */
    public VariantAlternative getAlternativeForEnumerator(String enumeratorName) {
        VariantAlternative a = enumeratorName == null ? null : byEnumeratorName.get(enumeratorName.trim());
        return a != null ? a : otherAlternative;
    }
}
