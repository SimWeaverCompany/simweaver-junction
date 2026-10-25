package com.simweaver.common.hla.decoders.rpr;

import com.simweaver.common.geo.KinematicState;

/**
 * Attached to a decoded {@code SpatialVariantStruct} value: the kinematic state as received,
 * stamped with the receive time, ready for dead reckoning. Immutable.
 */
public final class SpatialState {

    private final KinematicState state;
    private final boolean relative;

    SpatialState(KinematicState state, boolean relative) {
        this.state = state;
        this.relative = relative;
    }

    /** @return the state as received (position ECEF for world spatial values) */
    public KinematicState getState() {
        return state;
    }

    /**
     * @return true for RPR v3 {@code RelativeSpatial}: position and orientation are in the
     *         host entity's body frame, so no geodetic values apply on their own
     */
    public boolean isRelative() {
        return relative;
    }

    @Override
    public String toString() {
        return (relative ? "relative " : "") + state;
    }
}
