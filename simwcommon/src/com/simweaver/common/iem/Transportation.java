package com.simweaver.common.iem;

/**
 * A transportation type. {@code HLAreliable} and {@code HLAbestEffort} always exist.
 */
public final class Transportation extends IemElement {

    /** Name of the predefined reliable transportation. */
    public static final String HLA_RELIABLE = "HLAreliable";

    /** Name of the predefined best-effort transportation. */
    public static final String HLA_BEST_EFFORT = "HLAbestEffort";

    Boolean reliable;
    String semantics;
    boolean predefined;

    Transportation(String name) {
        super(name);
    }

    @Override
    public ElementKind getKind() {
        return ElementKind.TRANSPORTATION;
    }

    /** True/false from the FOM's "reliable" column (Yes/No); null when not stated. */
    public Boolean getReliable() {
        return reliable;
    }

    /** True if reliable is stated as Yes. */
    public boolean isReliable() {
        return Boolean.TRUE.equals(reliable);
    }

    public String getSemantics() {
        return semantics;
    }

    /** True for HLAreliable and HLAbestEffort. */
    public boolean isPredefined() {
        return predefined;
    }
}
