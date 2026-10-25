package com.simweaver.common.geo;

/**
 * Dead reckoning per IEEE 1278.1 Annex E: extrapolates a {@link KinematicState} forward
 * (or backward) in time. Stateless and thread safe.
 *
 * <h2>Model</h2>
 * <ul>
 *   <li><b>World (W) algorithms</b>: {@code P = P0 + V0 dt [+ A0 dt^2/2]} in ECEF;
 *       V algorithms also update the velocity {@code V = V0 + A0 dt}.</li>
 *   <li><b>Body (B) algorithms</b>: velocity and acceleration are constant in the
 *       (possibly rotating) body frame, so
 *       {@code P = P0 + R_bw(t0) (R1 v_b + R2 a_b)} with the integration matrices of
 *       Annex E:
 *       <pre>
 * R1 = (wdt - sin wdt)/w^3 ww^T + sin(wdt)/w I + (1 - cos wdt)/w^2 [w]x
 * R2 = (w^2dt^2/2 - cos wdt - wdt sin wdt + 1)/w^4 ww^T
 *    + (cos wdt + wdt sin wdt - 1)/w^2 I + (sin wdt - wdt cos wdt)/w^3 [w]x
 *       </pre>
 *       For F (fixed orientation) algorithms w = 0, i.e. {@code R1 = dt I}, {@code R2 = dt^2/2 I}.</li>
 *   <li><b>Rotating (R) algorithms</b>: {@code R_wb(t) = DR R_wb(t0)} with
 *       {@code DR = (1 - cos wdt)/w^2 ww^T + cos(wdt) I - sin(wdt)/w [w]x},
 *       w the body angular velocity.</li>
 * </ul>
 * Below {@code |w dt| = 1e-3} the coefficients are evaluated with their Taylor series, so the
 * result is continuous and exact to rounding as w goes to zero.
 * <p>
 * Static, Other and frozen states are returned unchanged except for the time stamp.
 * Linear vectors of the result keep the frames of the input; body vectors are expressed in
 * the extrapolated body frame.
 */
public final class DeadReckoner {

    /** Threshold on |w dt| below which series expansions are used. */
    static final double SERIES_THRESHOLD = 1e-3;

    private DeadReckoner() {
    }

    /**
     * Extrapolates by a time step.
     *
     * @param state state at t0
     * @param dt    seconds to extrapolate; negative extrapolates backwards
     * @return state at t0 + dt; its time is advanced when the input has one
     * @throws IllegalArgumentException if dt is NaN or infinite
     */
    public static KinematicState extrapolate(KinematicState state, double dt) {
        if (Double.isNaN(dt) || Double.isInfinite(dt)) {
            throw new IllegalArgumentException("dt not finite: " + dt);
        }
        long newTime = state.hasTime() ? state.getTimeMillis() + Math.round(dt * 1000.0) : KinematicState.NO_TIME;
        DeadReckoningAlgorithm alg = state.getAlgorithm();
        if (dt == 0.0 || state.isFrozen() || !alg.isMoving()) {
            return newTime == state.getTimeMillis() ? state : state.toBuilder().timeMillis(newTime).build();
        }

        Matrix3 worldToBody0 = state.getOrientation().toReferenceToBody();
        Vec3 omega = alg.isRotating() ? state.getAngularVelocity() : Vec3.ZERO;

        // orientation
        Matrix3 worldToBody;
        EulerAngles orientation;
        if (alg.isRotating() && !omega.isZero()) {
            worldToBody = rotationMatrix(omega, dt).multiply(worldToBody0);
            orientation = EulerAngles.fromReferenceToBody(worldToBody);
        } else {
            worldToBody = worldToBody0;
            orientation = state.getOrientation();
        }

        Vec3 position;
        Vec3 newWorldVelocity;   // used when the stored velocity is in WORLD components
        Vec3 newBodyVelocity;    // used when the stored velocity is in BODY components
        if (alg.isBodyAxis()) {
            Vec3 vb = state.getBodyVelocity();
            Vec3 ab = alg.isUsingAcceleration() ? state.getBodyAcceleration() : Vec3.ZERO;
            Vec3 bodyDisplacement = r1(omega, dt).transform(vb);
            if (!ab.isZero()) {
                bodyDisplacement = bodyDisplacement.plus(r2(omega, dt).transform(ab));
            }
            position = state.getPosition().plus(worldToBody0.transformTransposed(bodyDisplacement));
            newBodyVelocity = ab.isZero() ? vb : vb.plusScaled(ab, dt);
            newWorldVelocity = worldToBody.transformTransposed(newBodyVelocity);
        } else {
            Vec3 vw = state.getWorldVelocity();
            Vec3 aw = alg.isUsingAcceleration() ? state.getWorldAcceleration() : Vec3.ZERO;
            position = state.getPosition().plusScaled(vw, dt);
            if (!aw.isZero()) {
                position = position.plusScaled(aw, 0.5 * dt * dt);
            }
            newWorldVelocity = aw.isZero() ? vw : vw.plusScaled(aw, dt);
            newBodyVelocity = worldToBody.transform(newWorldVelocity);
        }

        KinematicState.Builder b = state.toBuilder()
            .position(position)
            .orientation(orientation)
            .velocity(state.getVelocityFrame() == KinematicState.Frame.BODY ? newBodyVelocity : newWorldVelocity)
            .timeMillis(newTime);

        // Acceleration and angular velocity are constant in their own frame. A body frame
        // vector needs no change; a world vector stays as is. Only a mixed case (world
        // acceleration on a body algorithm or the reverse) needs re-expression.
        if (alg.isRotating() && !omega.isZero()) {
            Vec3 acc = state.getAcceleration();
            boolean accConstantInBody = alg.isBodyAxis();
            if (accConstantInBody && state.getAccelerationFrame() == KinematicState.Frame.WORLD) {
                b.acceleration(worldToBody.transformTransposed(state.getBodyAcceleration()));
            } else if (!accConstantInBody && state.getAccelerationFrame() == KinematicState.Frame.BODY) {
                b.acceleration(worldToBody.transform(state.getWorldAcceleration()));
            } else {
                b.acceleration(acc);
            }
        }
        return b.build();
    }

    /**
     * Extrapolates to an absolute time.
     *
     * @param state           state with a known time
     * @param targetMillis    target instant, epoch millis UTC
     * @param maxAbsDtSeconds clamp for |dt| (e.g. 30 s so a silent entity does not fly off
     *                        indefinitely); {@code Double.POSITIVE_INFINITY} for no clamp
     * @return the extrapolated state, stamped with the time actually reached
     * @throws IllegalArgumentException if the state has no time or the clamp is negative/NaN
     */
    public static KinematicState extrapolateTo(KinematicState state, long targetMillis, double maxAbsDtSeconds) {
        if (!state.hasTime()) {
            throw new IllegalArgumentException("state has no time");
        }
        if (!(maxAbsDtSeconds >= 0.0)) {
            throw new IllegalArgumentException("maxAbsDtSeconds must be >= 0: " + maxAbsDtSeconds);
        }
        double dt = (targetMillis - state.getTimeMillis()) / 1000.0;
        if (dt > maxAbsDtSeconds) {
            dt = maxAbsDtSeconds;
        } else if (dt < -maxAbsDtSeconds) {
            dt = -maxAbsDtSeconds;
        }
        return extrapolate(state, dt);
    }

    /**
     * The DR rotation matrix: {@code R_wb(t0 + dt) = DR * R_wb(t0)}.
     *
     * @param omega body angular velocity, rad/s
     * @param dt    seconds
     * @return DR
     */
    public static Matrix3 rotationMatrix(Vec3 omega, double dt) {
        double w = omega.norm();
        double x = w * dt;
        double cosX = Math.cos(x);
        double outerCoef;   // (1 - cos x) / w^2
        double skewCoef;    // sin x / w
        if (Math.abs(x) < SERIES_THRESHOLD) {
            double x2 = x * x;
            outerCoef = dt * dt * (0.5 - x2 / 24.0 + x2 * x2 / 720.0);
            skewCoef = dt * (1.0 - x2 / 6.0 + x2 * x2 / 120.0);
        } else {
            outerCoef = (1.0 - cosX) / (w * w);
            skewCoef = Math.sin(x) / w;
        }
        return Matrix3.outer(omega, omega).times(outerCoef)
            .plus(Matrix3.IDENTITY.times(cosX))
            .plus(Matrix3.skew(omega).times(-skewCoef));
    }

    /**
     * Body axis velocity integration matrix R1 (displacement in initial body coordinates
     * per unit body velocity).
     *
     * @param omega body angular velocity, rad/s (zero for F algorithms)
     * @param dt    seconds
     * @return R1
     */
    public static Matrix3 r1(Vec3 omega, double dt) {
        double w = omega.norm();
        double x = w * dt;
        if (w == 0.0) {
            return Matrix3.IDENTITY.times(dt);
        }
        double outerCoef;   // (x - sin x) / w^3
        double idCoef;      // sin x / w
        double skewCoef;    // (1 - cos x) / w^2
        if (Math.abs(x) < SERIES_THRESHOLD) {
            double x2 = x * x;
            double dt2 = dt * dt;
            outerCoef = dt2 * dt * (1.0 / 6.0 - x2 / 120.0 + x2 * x2 / 5040.0);
            idCoef = dt * (1.0 - x2 / 6.0 + x2 * x2 / 120.0);
            skewCoef = dt2 * (0.5 - x2 / 24.0 + x2 * x2 / 720.0);
        } else {
            double w2 = w * w;
            outerCoef = (x - Math.sin(x)) / (w2 * w);
            idCoef = Math.sin(x) / w;
            skewCoef = (1.0 - Math.cos(x)) / w2;
        }
        return Matrix3.outer(omega, omega).times(outerCoef)
            .plus(Matrix3.IDENTITY.times(idCoef))
            .plus(Matrix3.skew(omega).times(skewCoef));
    }

    /**
     * Body axis acceleration integration matrix R2 (displacement in initial body coordinates
     * per unit body acceleration).
     *
     * @param omega body angular velocity, rad/s (zero for F algorithms)
     * @param dt    seconds
     * @return R2
     */
    public static Matrix3 r2(Vec3 omega, double dt) {
        double w = omega.norm();
        double x = w * dt;
        if (w == 0.0) {
            return Matrix3.IDENTITY.times(0.5 * dt * dt);
        }
        double outerCoef;   // (x^2/2 - cos x - x sin x + 1) / w^4
        double idCoef;      // (cos x + x sin x - 1) / w^2
        double skewCoef;    // (sin x - x cos x) / w^3
        double dt2 = dt * dt;
        if (Math.abs(x) < SERIES_THRESHOLD) {
            double x2 = x * x;
            outerCoef = dt2 * dt2 * (1.0 / 8.0 - x2 / 144.0 + x2 * x2 / 5760.0);
            idCoef = dt2 * (0.5 - x2 / 8.0 + x2 * x2 / 144.0);
            skewCoef = dt2 * dt * (1.0 / 3.0 - x2 / 30.0 + x2 * x2 / 840.0);
        } else {
            double w2 = w * w;
            double s = Math.sin(x);
            double c = Math.cos(x);
            outerCoef = (0.5 * x * x - c - x * s + 1.0) / (w2 * w2);
            idCoef = (c + x * s - 1.0) / w2;
            skewCoef = (s - x * c) / (w2 * w);
        }
        return Matrix3.outer(omega, omega).times(outerCoef)
            .plus(Matrix3.IDENTITY.times(idCoef))
            .plus(Matrix3.skew(omega).times(skewCoef));
    }
}
