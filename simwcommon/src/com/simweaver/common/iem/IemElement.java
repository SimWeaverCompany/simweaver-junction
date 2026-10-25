package com.simweaver.common.iem;

/**
 * Base class of every element of an {@link InformationExchangeModel} that carries an integer id.
 * <p>
 * <b>Ids.</b> Ids are allocated once, when the model is built, from a single model-wide id space
 * starting at 1 ({@link #NO_ID} = 0 means "none"). No two elements share an id, whatever their kind,
 * so an id can be used like an HLA handle without also tracking the kind. The elements of one
 * {@link ElementKind} occupy a contiguous id range, and {@link #getIndex()} gives the 0-based position
 * inside that range, which is convenient for sizing per-kind lookup arrays.
 * <p>
 * Ids are deterministic: the same FOM list in the same order always yields the same ids. They are
 * not stable across different FOM lists and must never be sent between federates.
 * <p>
 * Instances are immutable once the model is built and safe to share between threads.
 */
public abstract class IemElement {

    /** Id value meaning "no element". Valid ids are &gt;= 1. */
    public static final int NO_ID = 0;

    final String name;
    int id = NO_ID;
    int index = -1;

    IemElement(String name) {
        this.name = name;
    }

    /** Model-wide unique id (&gt;= 1). */
    public final int getId() {
        return id;
    }

    /** 0-based position of this element among the elements of the same kind, in id order. */
    public final int getIndex() {
        return index;
    }

    /** Simple (unqualified) name as written in the FOM. For synchronization points: the label. */
    public final String getName() {
        return name;
    }

    /** What kind of element this is. */
    public abstract ElementKind getKind();

    @Override
    public String toString() {
        return getKind() + " " + name + " [id=" + id + "]";
    }
}
