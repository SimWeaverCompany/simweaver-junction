package com.simweaver.common.geo;

import java.util.Objects;

/**
 * Immutable kinematic state of an entity at one instant, in the form DIS and RPR-FOM carry
 * it: ECEF position, ECEF referenced Euler angles, linear velocity and acceleration,
 * body angular velocity, the dead reckoning algorithm and the frozen flag.
 *
 * <h2>Frames of the linear vectors</h2>
 * Each linear vector has its own {@link Frame}. When not set explicitly it follows the
 * algorithm: {@link Frame#BODY} for the B algorithms (6..9), {@link Frame#WORLD} otherwise.
 * This matches IEEE 1278.1-2012 and RPR v3 ("in reference or own body coordinates depending
 * on the Dead Reckoning Algorithm"). Producers following the older reading of 1278.1-1995,
 * where the velocity is always in world coordinates, can be handled by setting
 * {@link Builder#velocityFrame(Frame)} to {@code WORLD}.
 * <p>
 * The angular velocity is always in body coordinates (rad/s about body x, y, z).
 *
 * <h2>Time</h2>
 * {@link #getTimeMillis()} is the instant the state refers to (epoch millis, UTC), typically
 * the receive time of the update; {@link #NO_TIME} when unknown.
 */
public final class KinematicState {

    /** Value of {@link #getTimeMillis()} when the time is unknown. */
    public static final long NO_TIME = Long.MIN_VALUE;

    /** Coordinate frame of a linear vector. */
    public enum Frame {
        /** World (ECEF) components. */
        WORLD,
        /** Body (entity x forward, y right, z down) components. */
        BODY
    }

    private final DeadReckoningAlgorithm algorithm;
    private final Vec3 position;
    private final EulerAngles orientation;
    private final Vec3 velocity;
    private final Frame velocityFrame;
    private final Vec3 acceleration;
    private final Frame accelerationFrame;
    private final Vec3 angularVelocity;
    private final boolean frozen;
    private final long timeMillis;

    private KinematicState(Builder b) {
        this.algorithm = b.algorithm;
        this.position = b.position;
        this.orientation = b.orientation;
        this.velocity = b.velocity;
        this.acceleration = b.acceleration;
        this.angularVelocity = b.angularVelocity;
        Frame def = b.algorithm.isBodyAxis() ? Frame.BODY : Frame.WORLD;
        this.velocityFrame = b.velocityFrame != null ? b.velocityFrame : def;
        this.accelerationFrame = b.accelerationFrame != null ? b.accelerationFrame : def;
        this.frozen = b.frozen;
        this.timeMillis = b.timeMillis;
    }

    /** @return a new builder (algorithm STATIC, everything zero, no time) */
    public static Builder builder() {
        return new Builder();
    }

    /** @return a builder initialised from this state (frames copied explicitly) */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.algorithm = algorithm;
        b.position = position;
        b.orientation = orientation;
        b.velocity = velocity;
        b.velocityFrame = velocityFrame;
        b.acceleration = acceleration;
        b.accelerationFrame = accelerationFrame;
        b.angularVelocity = angularVelocity;
        b.frozen = frozen;
        b.timeMillis = timeMillis;
        return b;
    }

    /** @return the dead reckoning algorithm */
    public DeadReckoningAlgorithm getAlgorithm() {
        return algorithm;
    }

    /** @return ECEF position, metres */
    public Vec3 getPosition() {
        return position;
    }

    /** @return orientation relative to ECEF (DIS Psi/Theta/Phi) */
    public EulerAngles getOrientation() {
        return orientation;
    }

    /** @return linear velocity as received, in {@link #getVelocityFrame()} components, m/s */
    public Vec3 getVelocity() {
        return velocity;
    }

    /** @return frame of {@link #getVelocity()} */
    public Frame getVelocityFrame() {
        return velocityFrame;
    }

    /** @return linear acceleration as received, in {@link #getAccelerationFrame()} components, m/s^2 */
    public Vec3 getAcceleration() {
        return acceleration;
    }

    /** @return frame of {@link #getAcceleration()} */
    public Frame getAccelerationFrame() {
        return accelerationFrame;
    }

    /** @return angular velocity about body x, y, z, rad/s */
    public Vec3 getAngularVelocity() {
        return angularVelocity;
    }

    /** @return the frozen flag; a frozen entity is never extrapolated */
    public boolean isFrozen() {
        return frozen;
    }

    /** @return the instant of this state, epoch millis UTC, or {@link #NO_TIME} */
    public long getTimeMillis() {
        return timeMillis;
    }

    /** @return true when {@link #getTimeMillis()} is known */
    public boolean hasTime() {
        return timeMillis != NO_TIME;
    }

    /** @return velocity in ECEF components */
    public Vec3 getWorldVelocity() {
        return toWorld(velocity, velocityFrame);
    }

    /** @return velocity in body components (u forward, v right, w down) */
    public Vec3 getBodyVelocity() {
        return toBody(velocity, velocityFrame);
    }

    /** @return acceleration in ECEF components */
    public Vec3 getWorldAcceleration() {
        return toWorld(acceleration, accelerationFrame);
    }

    /** @return acceleration in body components */
    public Vec3 getBodyAcceleration() {
        return toBody(acceleration, accelerationFrame);
    }

    private Vec3 toWorld(Vec3 v, Frame frame) {
        if (frame == Frame.WORLD || v.isZero()) {
            return v;
        }
        return orientation.toReferenceToBody().transformTransposed(v);
    }

    private Vec3 toBody(Vec3 v, Frame frame) {
        if (frame == Frame.BODY || v.isZero()) {
            return v;
        }
        return orientation.toReferenceToBody().transform(v);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof KinematicState)) {
            return false;
        }
        KinematicState k = (KinematicState) o;
        return algorithm == k.algorithm && frozen == k.frozen && timeMillis == k.timeMillis
            && velocityFrame == k.velocityFrame && accelerationFrame == k.accelerationFrame
            && position.equals(k.position) && orientation.equals(k.orientation)
            && velocity.equals(k.velocity) && acceleration.equals(k.acceleration)
            && angularVelocity.equals(k.angularVelocity);
    }

    @Override
    public int hashCode() {
        return Objects.hash(algorithm, position, orientation, velocity, velocityFrame,
                            acceleration, accelerationFrame, angularVelocity, frozen, timeMillis);
    }

    @Override
    public String toString() {
        return algorithm.getRprName() + (frozen ? " FROZEN" : "")
            + " pos " + position.format(2) + " ori " + orientation
            + " vel " + velocity.format(3) + " " + velocityFrame
            + " acc " + acceleration.format(3) + " " + accelerationFrame
            + " angvel " + angularVelocity.format(4)
            + (hasTime() ? " t " + timeMillis : "");
    }

    /** Builder; not thread safe. */
    public static final class Builder {
        private DeadReckoningAlgorithm algorithm = DeadReckoningAlgorithm.STATIC;
        private Vec3 position = Vec3.ZERO;
        private EulerAngles orientation = EulerAngles.ZERO;
        private Vec3 velocity = Vec3.ZERO;
        private Frame velocityFrame;
        private Vec3 acceleration = Vec3.ZERO;
        private Frame accelerationFrame;
        private Vec3 angularVelocity = Vec3.ZERO;
        private boolean frozen;
        private long timeMillis = NO_TIME;

        private Builder() {
        }

        /**
         * @param v algorithm (not null)
         * @return this
         */
        public Builder algorithm(DeadReckoningAlgorithm v) {
            this.algorithm = Objects.requireNonNull(v, "algorithm");
            return this;
        }

        /**
         * @param v ECEF position, metres (not null)
         * @return this
         */
        public Builder position(Vec3 v) {
            this.position = Objects.requireNonNull(v, "position");
            return this;
        }

        /**
         * @param v orientation relative to ECEF (not null)
         * @return this
         */
        public Builder orientation(EulerAngles v) {
            this.orientation = Objects.requireNonNull(v, "orientation");
            return this;
        }

        /**
         * @param v linear velocity, m/s (not null)
         * @return this
         */
        public Builder velocity(Vec3 v) {
            this.velocity = Objects.requireNonNull(v, "velocity");
            return this;
        }

        /**
         * @param v frame of the velocity; null = follow the algorithm
         * @return this
         */
        public Builder velocityFrame(Frame v) {
            this.velocityFrame = v;
            return this;
        }

        /**
         * @param v linear acceleration, m/s^2 (not null)
         * @return this
         */
        public Builder acceleration(Vec3 v) {
            this.acceleration = Objects.requireNonNull(v, "acceleration");
            return this;
        }

        /**
         * @param v frame of the acceleration; null = follow the algorithm
         * @return this
         */
        public Builder accelerationFrame(Frame v) {
            this.accelerationFrame = v;
            return this;
        }

        /**
         * @param v body angular velocity, rad/s (not null)
         * @return this
         */
        public Builder angularVelocity(Vec3 v) {
            this.angularVelocity = Objects.requireNonNull(v, "angularVelocity");
            return this;
        }

        /**
         * @param v frozen flag
         * @return this
         */
        public Builder frozen(boolean v) {
            this.frozen = v;
            return this;
        }

        /**
         * @param v instant, epoch millis UTC, or {@link KinematicState#NO_TIME}
         * @return this
         */
        public Builder timeMillis(long v) {
            this.timeMillis = v;
            return this;
        }

        /** @return the state */
        public KinematicState build() {
            return new KinematicState(this);
        }
    }
}
