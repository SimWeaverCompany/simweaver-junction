package com.simweaver.common.iem;

/**
 * A named update rate (OMT update rate table).
 */
public final class UpdateRate extends IemElement {

    String rateText;
    double rate = Double.NaN;
    String semantics;

    UpdateRate(String name) {
        super(name);
    }

    @Override
    public ElementKind getKind() {
        return ElementKind.UPDATE_RATE;
    }

    /** Rate as written, or null. */
    public String getRateText() {
        return rateText;
    }

    /** Rate in Hz, or NaN when absent or not numeric. */
    public double getRate() {
        return rate;
    }

    public String getSemantics() {
        return semantics;
    }
}
