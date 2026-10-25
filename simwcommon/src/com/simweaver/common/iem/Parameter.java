package com.simweaver.common.iem;

/**
 * Declaration of an interaction class parameter (one row of the OMT parameter table).
 * Use {@link InteractionClass#getParameter(String)} to see a parameter in the context of a
 * particular class, including whether it is inherited there.
 */
public final class Parameter extends ClassMember {

    InteractionClass declaringClass;

    Parameter(String name) {
        super(name);
    }

    @Override
    public ElementKind getKind() {
        return ElementKind.PARAMETER;
    }

    @Override
    public InteractionClass getDeclaringClass() {
        return declaringClass;
    }
}
