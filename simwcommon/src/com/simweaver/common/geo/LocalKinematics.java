package com.simweaver.common.geo;

import java.util.Locale;

/**
 * The human readable view of a {@link KinematicState}: geodetic position, local heading /
 * pitch / roll, velocity and acceleration in NED and body components, and the derived
 * scalars (ground speed, course, vertical speed, flight path angle). Immutable.
 * <p>
 * Use it twice per entity to get both "as received" and "now":
 * <pre>
 * LocalKinematics latest = LocalKinematics.of(state, Ellipsoid.WGS84);
 * LocalKinematics now    = LocalKinematics.of(DeadReckoner.extrapolateTo(state, nowMillis, 30.0), Ellipsoid.WGS84);
 * </pre>
 */
public final class LocalKinematics {

    /** Ground speeds below this (m/s) have no meaningful course; {@link #getCourseDegrees()} is NaN. */
    public static final double MIN_SPEED_FOR_COURSE = 1e-6;

    private final KinematicState state;
    private final Geodetic geodetic;
    private final EulerAngles attitude;
    private final Vec3 velocityNed;
    private final Vec3 velocityBody;
    private final Vec3 accelerationNed;
    private final Vec3 accelerationBody;
    private final double groundSpeed;
    private final double speed;
    private final double verticalSpeed;
    private final double courseDegrees;
    private final double flightPathAngleDegrees;

    private LocalKinematics(KinematicState state, Ellipsoid ellipsoid) {
        this.state = state;
        LocalFrame frame = LocalFrame.at(state.getPosition(), ellipsoid);
        this.geodetic = frame.getOrigin();
        this.attitude = frame.toLocal(state.getOrientation());
        this.velocityNed = frame.toNed(state.getWorldVelocity());
        this.velocityBody = state.getBodyVelocity();
        this.accelerationNed = frame.toNed(state.getWorldAcceleration());
        this.accelerationBody = state.getBodyAcceleration();
        this.groundSpeed = Math.hypot(velocityNed.getX(), velocityNed.getY());
        this.speed = velocityNed.norm();
        this.verticalSpeed = -velocityNed.getZ();
        this.courseDegrees = groundSpeed >= MIN_SPEED_FOR_COURSE
            ? Angles.normalize360(Math.toDegrees(Math.atan2(velocityNed.getY(), velocityNed.getX())))
            : Double.NaN;
        this.flightPathAngleDegrees = speed >= MIN_SPEED_FOR_COURSE
            ? Math.toDegrees(Math.atan2(verticalSpeed, groundSpeed))
            : Double.NaN;
    }

    /**
     * @param state     the state
     * @param ellipsoid the ellipsoid the ECEF position refers to (WGS84 for DIS/RPR)
     * @return the local view
     * @throws IllegalArgumentException if the position is not finite
     */
    public static LocalKinematics of(KinematicState state, Ellipsoid ellipsoid) {
        return new LocalKinematics(state, ellipsoid);
    }

    /** @return the underlying state */
    public KinematicState getState() {
        return state;
    }

    /** @return geodetic position (height above ellipsoid) */
    public Geodetic getGeodetic() {
        return geodetic;
    }

    /** @return local yaw/pitch/roll relative to NED */
    public EulerAngles getAttitude() {
        return attitude;
    }

    /** @return heading, degrees true, [0, 360) */
    public double getHeadingDegrees() {
        return attitude.getHeadingDegrees();
    }

    /** @return pitch, degrees, nose up positive */
    public double getPitchDegrees() {
        return attitude.getPitchDegrees();
    }

    /** @return roll, degrees, right wing down positive, (-180, 180] */
    public double getRollDegrees() {
        return attitude.getRollDegrees();
    }

    /** @return velocity (north, east, down), m/s */
    public Vec3 getVelocityNed() {
        return velocityNed;
    }

    /** @return velocity (forward, right, down) in body components, m/s */
    public Vec3 getVelocityBody() {
        return velocityBody;
    }

    /** @return acceleration (north, east, down), m/s^2 */
    public Vec3 getAccelerationNed() {
        return accelerationNed;
    }

    /** @return acceleration in body components, m/s^2 */
    public Vec3 getAccelerationBody() {
        return accelerationBody;
    }

    /** @return angular velocity about body x, y, z, rad/s (as received) */
    public Vec3 getAngularVelocity() {
        return state.getAngularVelocity();
    }

    /** @return horizontal speed, m/s */
    public double getGroundSpeed() {
        return groundSpeed;
    }

    /** @return 3D speed, m/s */
    public double getSpeed() {
        return speed;
    }

    /** @return climb rate, m/s, up positive */
    public double getVerticalSpeed() {
        return verticalSpeed;
    }

    /** @return course over ground, degrees true [0, 360), NaN when (nearly) not moving horizontally */
    public double getCourseDegrees() {
        return courseDegrees;
    }

    /** @return flight path angle, degrees, NaN when not moving */
    public double getFlightPathAngleDegrees() {
        return flightPathAngleDegrees;
    }

    /**
     * Compact one line summary, for example
     * {@code "59.329312 18.068581 120.4 m hdg 274.1 pitch 2.0 roll -0.5 gs 113.2 m/s crs 274.0 vs 0.5 m/s"}.
     * Speed parts are left out for a non moving algorithm.
     *
     * @return the text
     */
    public String summary() {
        StringBuilder sb = new StringBuilder(96);
        sb.append(String.format(Locale.ROOT, "%.6f %.6f %.1f m hdg %.1f pitch %.1f roll %.1f",
            geodetic.getLatitude(), geodetic.getLongitude(), geodetic.getHeight(),
            getHeadingDegrees(), getPitchDegrees(), getRollDegrees()));
        if (state.getAlgorithm().isMoving()) {
            sb.append(String.format(Locale.ROOT, " gs %.1f m/s", groundSpeed));
            if (!Double.isNaN(courseDegrees)) {
                sb.append(String.format(Locale.ROOT, " crs %.1f", courseDegrees));
            }
            sb.append(String.format(Locale.ROOT, " vs %.1f m/s", verticalSpeed));
        }
        if (state.isFrozen()) {
            sb.append(" FROZEN");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return summary();
    }
}
