package com.simweaver.common.iem;

import java.util.Collections;
import java.util.List;

/**
 * A merged OMT datatype. Concrete subclasses: {@link BasicDataType}, {@link SimpleDataType},
 * {@link EnumeratedDataType}, {@link ArrayDataType}, {@link FixedRecordDataType},
 * {@link VariantRecordDataType} and {@link OtherDataType}.
 * <p>
 * All references between datatypes are resolved to objects when the model is built, so a decoder
 * can walk the type graph directly. Datatype graphs may be recursive (for example a record holding
 * a variable array of itself); walkers must guard against cycles.
 */
public abstract class DataType extends IemElement {

    String semantics;
    String encoding;
    boolean predefined;
    List<FomModule> modules = Collections.emptyList();

    DataType(String name) {
        super(name);
    }

    @Override
    public final ElementKind getKind() {
        return ElementKind.DATA_TYPE;
    }

    /** The OMT table this datatype comes from. */
    public abstract DataTypeCategory getCategory();

    /** Semantics from the first module that provided any, or null. */
    public final String getSemantics() {
        return semantics;
    }

    /**
     * The {@code <encoding>} text: an encoding name for arrays and records (e.g. {@code HLAfixedRecord}),
     * a free-text description for basic data; null for simple and enumerated types.
     */
    public final String getEncoding() {
        return encoding;
    }

    /** {@link #getEncoding()} classified; {@link KnownEncoding#CUSTOM} when unknown or absent. */
    public final KnownEncoding getEncodingKind() {
        return KnownEncoding.of(encoding);
    }

    /** True for datatypes from the standard MIM (built-in or supplied). */
    public final boolean isPredefined() {
        return predefined;
    }

    /** Modules defining (or extending) this datatype, in merge order. */
    public final List<FomModule> getModules() {
        return modules;
    }

    /** First module defining this datatype. */
    public final FomModule getModule() {
        return modules.get(0);
    }

    @Override
    public String toString() {
        return getCategory() + " " + getName() + " [id=" + getId() + "]";
    }
}
