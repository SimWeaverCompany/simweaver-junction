package com.simweaver.common.hla.decoders.rpr;

import com.simweaver.common.geo.DeadReckoningAlgorithm;
import com.simweaver.common.geo.EulerAngles;
import com.simweaver.common.geo.KinematicState;
import com.simweaver.common.geo.LocalKinematics;
import com.simweaver.common.geo.Vec3;
import com.simweaver.common.geo.Ellipsoid;
import com.simweaver.common.hla.decoders.DecodedValue;
import com.simweaver.common.hla.decoders.FormatContext;
import com.simweaver.common.hla.decoders.HlaDataDecoder;
import com.simweaver.common.hla.decoders.ValueFormatter;
import com.simweaver.common.iem.DataType;
import com.simweaver.common.iem.EnumeratedDataType;
import com.simweaver.common.iem.MemberOnClass;
import com.simweaver.common.iem.VariantRecordDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * {@code SpatialVariantStruct}: the dead reckoning variant of RPR v2 and v3.
 * <p>
 * Text: {@code DRM_FPW 59.329312 18.068581 120.4 m hdg 274.1 pitch 2.0 roll -0.5 gs 113.2 m/s crs 274.0 vs 0.5 m/s}.
 * Derived children (after discriminant and alternative, all {@code derived=true}):
 * {@code Latitude}, {@code Longitude}, {@code Altitude} (HAE), {@code Heading}, {@code Pitch},
 * {@code Roll} (degrees, local NED), {@code VelocityNED} (North/East/Down m/s),
 * {@code BodyVelocity} (Forward/Right/Down m/s), {@code GroundSpeed}, {@code Course},
 * {@code VerticalSpeed}, {@code Speed}, {@code AccelerationNED} (V algorithms) and
 * {@code AngularVelocityDeg} (rotating algorithms, deg/s about body x/y/z).
 * <p>
 * A {@link SpatialState} is attached; {@link RprSpatial#now} extrapolates it to any time.
 * For RPR v3 {@code RelativeSpatial} (host body frame) only body velocity and the raw values
 * are shown.
 */
final class SpatialFormatter implements ValueFormatter {

    @Override
    public Collection<String> getDataTypeNames() {
        return Collections.singletonList("SpatialVariantStruct");
    }

    @Override
    public boolean accepts(DataType type) {
        if (!(type instanceof VariantRecordDataType)) {
            return false;
        }
        EnumeratedDataType d = ((VariantRecordDataType) type).getDiscriminantType();
        return d != null && d.getEnumerator("DRM_FPW") != null && d.getEnumerator("Static") != null;
    }

    static boolean isRelative(FormatContext ctx) {
        MemberOnClass<?, ?> m = ctx.getMember();
        return m != null && "RelativeSpatial".equals(m.getName());
    }

    @Override
    public DecodedValue format(DecodedValue value, FormatContext ctx) {
        if (value.hasError()) {
            return value;
        }
        DecodedValue disc = value.getDiscriminant();
        DecodedValue alt = value.getAlternative();
        if (disc == null || alt == null) {
            return value;
        }
        DeadReckoningAlgorithm alg = disc.getEnumName() != null ? DeadReckoningAlgorithm.fromName(disc.getEnumName()) : null;
        if (alg == null) {
            alg = DeadReckoningAlgorithm.fromCode((int) disc.getLong());
        }
        if (alg == null) {
            alg = DeadReckoningAlgorithm.OTHER;
        }
        DecodedValue loc = alt.getChild("WorldLocation") != null ? alt.getChild("WorldLocation") : alt.getChild("Location");
        if (loc == null) {
            return value;
        }
        Vec3 pos = vec(loc);
        if (!pos.isFinite()) {
            return value.toBuilder().warning("spatial position is not finite").build();
        }
        DecodedValue frozen = alt.getChild("IsFrozen");
        DecodedValue ori = alt.getChild("Orientation");
        KinematicState.Builder sb = KinematicState.builder()
            .algorithm(alg)
            .position(pos)
            .orientation(ori == null ? EulerAngles.ZERO : EulerAngles.ofRadians(
                zeroIfNaN(Shapes.num(ori, 0)), zeroIfNaN(Shapes.num(ori, 1)), zeroIfNaN(Shapes.num(ori, 2))))
            .velocity(finiteOrZero(vec(alt.getChild("VelocityVector"))))
            .acceleration(finiteOrZero(vec(alt.getChild("AccelerationVector"))))
            .angularVelocity(finiteOrZero(vec(alt.getChild("AngularVelocity"))))
            .frozen(frozen != null && frozen.getBoolean());
        long t = ctx.getReceivedMillis();
        if (t != HlaDataDecoder.NO_TIME) {
            sb.timeMillis(t);
        }
        KinematicState state = sb.build();
        boolean relative = isRelative(ctx);
        DecodedValue.Builder b = value.toBuilder().attachment(new SpatialState(state, relative));
        if (relative || pos.norm() < LocationFormatter.MIN_WORLD_RADIUS) {
            b.text(alg.getRprName() + " relative " + pos.format(2) + " m" + (state.isFrozen() ? " FROZEN" : ""));
            if (alg.isMoving()) {
                Vec3 bv = state.getBodyVelocity();
                b.child(Shapes.derivedVector("BodyVelocity", new String[] {"Forward", "Right", "Down"},
                    new double[] {bv.getX(), bv.getY(), bv.getZ()}, 2, "m/s"));
            }
            return b.build();
        }
        LocalKinematics lk = LocalKinematics.of(state, Ellipsoid.WGS84);
        b.text(alg.getRprName() + " " + lk.summary());
        for (DecodedValue d : derivedChildren(lk)) {
            b.child(d);
        }
        return b.build();
    }

    /**
     * The derived children for a local view; shared with {@link RprSpatial#nowValue}.
     *
     * @param lk local kinematics
     * @return derived nodes
     */
    static List<DecodedValue> derivedChildren(LocalKinematics lk) {
        List<DecodedValue> out = new ArrayList<DecodedValue>(16);
        DeadReckoningAlgorithm alg = lk.getState().getAlgorithm();
        out.add(Shapes.derived("Latitude", lk.getGeodetic().getLatitude(), 7, "deg"));
        out.add(Shapes.derived("Longitude", lk.getGeodetic().getLongitude(), 7, "deg"));
        out.add(Shapes.derived("Altitude", lk.getGeodetic().getHeight(), 2, "m"));
        out.add(Shapes.derived("Heading", lk.getHeadingDegrees(), 2, "deg"));
        out.add(Shapes.derived("Pitch", lk.getPitchDegrees(), 2, "deg"));
        out.add(Shapes.derived("Roll", lk.getRollDegrees(), 2, "deg"));
        if (alg.isMoving()) {
            Vec3 v = lk.getVelocityNed();
            out.add(Shapes.derivedVector("VelocityNED", new String[] {"North", "East", "Down"},
                new double[] {v.getX(), v.getY(), v.getZ()}, 2, "m/s"));
            Vec3 bv = lk.getVelocityBody();
            out.add(Shapes.derivedVector("BodyVelocity", new String[] {"Forward", "Right", "Down"},
                new double[] {bv.getX(), bv.getY(), bv.getZ()}, 2, "m/s"));
            out.add(Shapes.derived("GroundSpeed", lk.getGroundSpeed(), 2, "m/s"));
            if (!Double.isNaN(lk.getCourseDegrees())) {
                out.add(Shapes.derived("Course", lk.getCourseDegrees(), 2, "deg"));
            }
            out.add(Shapes.derived("VerticalSpeed", lk.getVerticalSpeed(), 2, "m/s"));
            out.add(Shapes.derived("Speed", lk.getSpeed(), 2, "m/s"));
            if (alg.isUsingAcceleration()) {
                Vec3 a = lk.getAccelerationNed();
                out.add(Shapes.derivedVector("AccelerationNED", new String[] {"North", "East", "Down"},
                    new double[] {a.getX(), a.getY(), a.getZ()}, 3, "m/s^2"));
            }
            if (alg.isRotating()) {
                Vec3 w = lk.getAngularVelocity();
                out.add(Shapes.derivedVector("AngularVelocityDeg", new String[] {"X", "Y", "Z"},
                    new double[] {Math.toDegrees(w.getX()), Math.toDegrees(w.getY()), Math.toDegrees(w.getZ())},
                    3, "deg/s"));
            }
        }
        return out;
    }

    private static Vec3 vec(DecodedValue v) {
        if (v == null || v.getChildren().size() < 3) {
            return Vec3.ZERO;
        }
        return new Vec3(Shapes.num(v, 0), Shapes.num(v, 1), Shapes.num(v, 2));
    }

    private static Vec3 finiteOrZero(Vec3 v) {
        return v.isFinite() ? v : Vec3.ZERO;
    }

    private static double zeroIfNaN(double d) {
        return Double.isNaN(d) || Double.isInfinite(d) ? 0.0 : d;
    }

    static String fmt(double v, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }
}
