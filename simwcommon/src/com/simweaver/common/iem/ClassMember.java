package com.simweaver.common.iem;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Common part of {@link Attribute} and {@link Parameter}: the declaration of a class member.
 * <p>
 * A declaration belongs to exactly one class (the declaring class) and has one id, which is shared
 * by every subclass that inherits it - exactly like an HLA attribute or parameter handle.
 */
public abstract class ClassMember extends IemElement {

    String qualifiedName;
    String dataTypeName;
    DataType dataType;
    String semantics;
    List<FomModule> modules = Collections.emptyList();
    Map<String, String> otherProperties = Collections.emptyMap();

    ClassMember(String name) {
        super(name);
    }

    /** The class that declares this member. */
    public abstract HlaClass<?> getDeclaringClass();

    /** Id of the class that declares this member. */
    public final int getDeclaringClassId() {
        return getDeclaringClass().getId();
    }

    /**
     * Fully qualified name: declaring class qualified name + '.' + member name,
     * e.g. {@code HLAobjectRoot.BaseEntity.EntityType}.
     */
    public final String getQualifiedName() {
        return qualifiedName;
    }

    /** Datatype name as written in the FOM ({@code "NA"} or null when there is none). */
    public final String getDataTypeName() {
        return dataTypeName;
    }

    /** Resolved datatype, or null when the FOM says NA or the reference could not be resolved. */
    public final DataType getDataType() {
        return dataType;
    }

    /** Id of the resolved datatype, or {@link IemElement#NO_ID}. */
    public final int getDataTypeId() {
        return dataType == null ? NO_ID : dataType.getId();
    }

    /** Semantics (description) from the first module that provided one, or null. */
    public final String getSemantics() {
        return semantics;
    }

    /** Modules that declare this member (more than one when several modules declare it identically). */
    public final List<FomModule> getModules() {
        return modules;
    }

    /** First module that declares this member. */
    public final FomModule getModule() {
        return modules.get(0);
    }

    /** Unrecognised simple elements of the declaration (element name to text). */
    public final Map<String, String> getOtherProperties() {
        return otherProperties;
    }

    @Override
    public String toString() {
        return getKind() + " " + qualifiedName + " [id=" + getId() + ", type=" + dataTypeName + "]";
    }
}
