package com.simweaver.common.hla.decoders.rpr;

import com.simweaver.common.geo.DeadReckoner;
import com.simweaver.common.geo.Ellipsoid;
import com.simweaver.common.geo.KinematicState;
import com.simweaver.common.geo.LocalKinematics;
import com.simweaver.common.hla.decoders.DecodedValue;
import com.simweaver.common.hla.decoders.ValueKind;

import java.util.Locale;

/**
 * Access to decoded RPR spatial values: the state as received ("latest") and dead reckoned to
 * any time ("now"). The "now" value is computed on demand, when it is read, because a value
 * computed at decode time is stale a moment later.
 *
 * <pre>
 * DecodedValue spatial = decoder.decodeAttribute(spatialAttr, bytes, receivedMillis);
 * LocalKinematics latest = RprSpatial.latest(spatial);
 * LocalKinematics now    = RprSpatial.now(spatial, System.currentTimeMillis(), 30.0);
 * DecodedValue nowNode   = RprSpatial.nowValue(spatial, System.currentTimeMillis(), 30.0);
 * </pre>
 */
public final class RprSpatial {

    /** Suggested cap for dead reckoning a silent entity, seconds. */
    public static final double DEFAULT_MAX_EXTRAPOLATION_SECONDS = 30.0;

    private RprSpatial() {
    }

    /**
     * @param spatial a decoded {@code SpatialVariantStruct} (or any value containing one)
     * @return the attached state, or null when the value holds no decoded spatial
     */
    public static SpatialState stateOf(DecodedValue spatial) {
        if (spatial == null) {
            return null;
        }
        SpatialState s = spatial.getAttachment(SpatialState.class);
        if (s != null) {
            return s;
        }
        for (DecodedValue c : spatial.getChildren()) {
            s = stateOf(c);
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    /**
     * @param spatial decoded spatial value
     * @return the received state in local terms, or null (no spatial, or relative spatial)
     */
    public static LocalKinematics latest(DecodedValue spatial) {
        SpatialState s = stateOf(spatial);
        return s == null || s.isRelative() ? null : LocalKinematics.of(s.getState(), Ellipsoid.WGS84);
    }

    /**
     * Dead reckons to a time. A state without receive time is returned as received.
     *
     * @param spatial    decoded spatial value
     * @param nowMillis  target time, epoch ms UTC
     * @param maxSeconds cap for |now - received|
     * @return the extrapolated state in local terms, or null (no spatial, or relative spatial)
     */
    public static LocalKinematics now(DecodedValue spatial, long nowMillis, double maxSeconds) {
        SpatialState s = stateOf(spatial);
        if (s == null || s.isRelative()) {
            return null;
        }
        KinematicState k = s.getState();
        if (k.hasTime()) {
            k = DeadReckoner.extrapolateTo(k, nowMillis, maxSeconds);
        }
        return LocalKinematics.of(k, Ellipsoid.WGS84);
    }

    /**
     * A derived node {@code Now} with the same derived children as the spatial value
     * (latitude ... angular velocity) plus {@code Age} (seconds since received), extrapolated
     * to {@code nowMillis}.
     *
     * @param spatial    decoded spatial value
     * @param nowMillis  target time
     * @param maxSeconds cap for the extrapolation
     * @return the node, or null when not applicable
     */
    public static DecodedValue nowValue(DecodedValue spatial, long nowMillis, double maxSeconds) {
        SpatialState s = stateOf(spatial);
        LocalKinematics lk = now(spatial, nowMillis, maxSeconds);
        if (lk == null) {
            return null;
        }
        DecodedValue.Builder b = DecodedValue.builder(ValueKind.RECORD).name("Now").derived(true)
            .text(lk.getState().getAlgorithm().getRprName() + " " + lk.summary())
            .attachment(new SpatialState(lk.getState(), false));
        if (s.getState().hasTime()) {
            double age = (nowMillis - s.getState().getTimeMillis()) / 1000.0;
            b.child(Shapes.derived("Age", age, 3, "s"));
            double used = (lk.getState().getTimeMillis() - s.getState().getTimeMillis()) / 1000.0;
            if (Math.abs(used - age) > 0.0005) {
                b.warning(String.format(Locale.ROOT, "extrapolation capped at %.1f s", used));
            }
        }
        for (DecodedValue d : SpatialFormatter.derivedChildren(lk)) {
            b.child(d);
        }
        return b.build();
    }
}
