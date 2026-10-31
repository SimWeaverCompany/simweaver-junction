package com.simweaver.common.iem;

/**
 * A parameter as available on a particular interaction class (declared there or inherited).
 * See {@link MemberOnClass} for the meaning of class / declaring class / parent / child classes.
 */
public final class ClassParameter extends MemberOnClass<InteractionClass, Parameter> {

    ClassParameter(InteractionClass interactionClass, Parameter parameter) {
        super(interactionClass, parameter);
    }

    /** The parameter declaration (same as {@link #getDeclaration()}). */
    public Parameter getParameter() {
        return getDeclaration();
    }

    /** The interaction class the parameter is viewed on. */
    public InteractionClass getInteractionClass() {
        return getHlaClass();
    }

    /** The interaction class declaring the parameter. */
    public InteractionClass getDeclaringClass() {
        return getDeclaration().getDeclaringClass();
    }
}
