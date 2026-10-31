package com.simweaver.common.iem;

/**
 * The kind of a model element. Every {@link IemElement} has exactly one kind.
 * <p>
 * Ids are allocated from one global id space, in the order of the constants below, so the
 * elements of one kind occupy a contiguous id range.
 */
public enum ElementKind {
    DATA_TYPE,
    OBJECT_CLASS,
    ATTRIBUTE,
    INTERACTION_CLASS,
    PARAMETER,
    DIMENSION,
    TRANSPORTATION,
    UPDATE_RATE,
    SYNCHRONIZATION_POINT
}
