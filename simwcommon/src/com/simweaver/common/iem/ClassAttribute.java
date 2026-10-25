package com.simweaver.common.iem;

/**
 * An attribute as available on a particular object class (declared there or inherited).
 * See {@link MemberOnClass} for the meaning of class / declaring class / parent / child classes.
 */
public final class ClassAttribute extends MemberOnClass<ObjectClass, Attribute> {

    ClassAttribute(ObjectClass objectClass, Attribute attribute) {
        super(objectClass, attribute);
    }

    /** The attribute declaration (same as {@link #getDeclaration()}). */
    public Attribute getAttribute() {
        return getDeclaration();
    }

    /** The object class the attribute is viewed on. */
    public ObjectClass getObjectClass() {
        return getHlaClass();
    }

    /** The object class declaring the attribute. */
    public ObjectClass getDeclaringClass() {
        return getDeclaration().getDeclaringClass();
    }
}
