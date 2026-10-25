package com.simweaver.common.geo;

import java.util.Locale;

/**
 * Tait-Bryan angles in radians, rotation sequence z-y'-x'' (yaw, pitch, roll), as used by
 * DIS (IEEE 1278.1, Psi/Theta/Phi) and by RPR-FOM {@code OrientationStruct}.
 * <p>
 * The angles describe the successive frame rotations that take a <em>reference</em> frame
 * into the <em>body</em> frame: first {@code psi} about z, then {@code theta} about the new
 * y, then {@code phi} about the new x. The same class serves two reference frames:
 * <ul>
 *   <li>ECEF (DIS world coordinates): the values carried on the wire;</li>
 *   <li>local NED (north, east, down): heading, pitch and roll as a person reads them,
 *       obtained with {@link LocalFrame#toLocal(EulerAngles)}.</li>
 * </ul>
 * The values are kept exactly as given; {@link #fromReferenceToBody(Matrix3)} returns the
 * canonical ranges {@code psi, phi in (-pi, pi]}, {@code theta in [-pi/2, pi/2]}.
 */
public final class EulerAngles {

    /** All zero: body axes aligned with the reference axes. */
    public static final EulerAngles ZERO = new EulerAngles(0.0, 0.0, 0.0);

    /** Below this |cos(theta)| the attitude is treated as gimbal locked during extraction. */
    private static final double GIMBAL_EPS = 1e-12;

    private final double psi;
    private final double theta;
    private final double phi;

    /**
     * @param psi   rotation about z (yaw / heading), radians
     * @param theta rotation about y' (pitch), radians
     * @param phi   rotation about x'' (roll), radians
     */
    public EulerAngles(double psi, double theta, double phi) {
        this.psi = psi;
        this.theta = theta;
        this.phi = phi;
    }

    /**
     * @param psi   radians
     * @param theta radians
     * @param phi   radians
     * @return the angles
     */
    public static EulerAngles ofRadians(double psi, double theta, double phi) {
        return new EulerAngles(psi, theta, phi);
    }

    /**
     * Creates angles from degrees, typically local heading, pitch and roll.
     *
     * @param yawDeg   rotation about z, degrees
     * @param pitchDeg rotation about y', degrees
     * @param rollDeg  rotation about x'', degrees
     * @return the angles
     */
    public static EulerAngles ofDegrees(double yawDeg, double pitchDeg, double rollDeg) {
        return new EulerAngles(Math.toRadians(yawDeg), Math.toRadians(pitchDeg), Math.toRadians(rollDeg));
    }

    /** @return psi (yaw), radians, as given */
    public double getPsi() {
        return psi;
    }

    /** @return theta (pitch), radians, as given */
    public double getTheta() {
        return theta;
    }

    /** @return phi (roll), radians, as given */
    public double getPhi() {
        return phi;
    }

    /** @return psi in degrees, as given */
    public double getPsiDegrees() {
        return Math.toDegrees(psi);
    }

    /** @return theta in degrees, as given */
    public double getThetaDegrees() {
        return Math.toDegrees(theta);
    }

    /** @return phi in degrees, as given */
    public double getPhiDegrees() {
        return Math.toDegrees(phi);
    }

    /**
     * Yaw as a compass heading. Meaningful when the reference frame is local NED.
     *
     * @return psi in degrees, normalized to {@code [0, 360)}
     */
    public double getHeadingDegrees() {
        return Angles.normalize360(Math.toDegrees(psi));
    }

    /** @return theta in degrees (alias for {@link #getThetaDegrees()} in local use) */
    public double getPitchDegrees() {
        return Math.toDegrees(theta);
    }

    /** @return phi in degrees, normalized to {@code (-180, 180]} */
    public double getRollDegrees() {
        return Angles.normalize180(Math.toDegrees(phi));
    }

    /** @return true when all three angles are finite */
    public boolean isFinite() {
        return !Double.isNaN(psi) && !Double.isInfinite(psi)
            && !Double.isNaN(theta) && !Double.isInfinite(theta)
            && !Double.isNaN(phi) && !Double.isInfinite(phi);
    }

    /**
     * The direction cosine matrix that maps reference coordinates to body coordinates,
     * {@code R = Rx(phi) * Ry(theta) * Rz(psi)} (frame rotations):
     * <pre>
     * | c(t)c(p)                 c(t)s(p)                 -s(t)    |
     * | s(f)s(t)c(p) - c(f)s(p)  s(f)s(t)s(p) + c(f)c(p)  s(f)c(t) |
     * | c(f)s(t)c(p) + s(f)s(p)  c(f)s(t)s(p) - s(f)c(p)  c(f)c(t) |
     * </pre>
     * with p = psi, t = theta, f = phi. Its rows are the body x, y, z axes expressed in the
     * reference frame.
     *
     * @return reference-to-body rotation
     */
    public Matrix3 toReferenceToBody() {
        double cp = Math.cos(psi);
        double sp = Math.sin(psi);
        double ct = Math.cos(theta);
        double st = Math.sin(theta);
        double cf = Math.cos(phi);
        double sf = Math.sin(phi);
        return new Matrix3(
            ct * cp, ct * sp, -st,
            sf * st * cp - cf * sp, sf * st * sp + cf * cp, sf * ct,
            cf * st * cp + sf * sp, cf * st * sp - sf * cp, cf * ct);
    }

    /** @return body-to-reference rotation (transpose of {@link #toReferenceToBody()}) */
    public Matrix3 toBodyToReference() {
        return toReferenceToBody().transpose();
    }

    /**
     * Extracts the angles from a reference-to-body rotation matrix.
     * At gimbal lock (pitch +-90 degrees) the roll is set to zero and the whole rotation
     * about the vertical is put into psi.
     *
     * @param referenceToBody a rotation matrix
     * @return canonical angles: psi, phi in (-pi, pi], theta in [-pi/2, pi/2]
     */
    public static EulerAngles fromReferenceToBody(Matrix3 referenceToBody) {
        Matrix3 r = referenceToBody;
        double theta = Math.asin(Angles.clampUnit(-r.m02));
        double cosTheta = Math.hypot(r.m00, r.m01);
        double psi;
        double phi;
        if (cosTheta > GIMBAL_EPS) {
            psi = Math.atan2(r.m01, r.m00);
            phi = Math.atan2(r.m12, r.m22);
        } else {
            // phi := 0  ->  m10 = -sin(psi), m11 = cos(psi)
            psi = Math.atan2(-r.m10, r.m11);
            phi = 0.0;
        }
        return new EulerAngles(fixMinusPi(psi), theta, fixMinusPi(phi));
    }

    private static double fixMinusPi(double a) {
        return a == -Math.PI ? Math.PI : a;
    }

    /**
     * @return the same attitude expressed with canonical ranges
     *         (psi, phi in (-pi, pi], theta in [-pi/2, pi/2])
     */
    public EulerAngles canonical() {
        return fromReferenceToBody(toReferenceToBody());
    }

    /**
     * Angle of the single rotation that takes this attitude into {@code other}
     * (independent of angle representation).
     *
     * @param other other attitude, same reference frame
     * @return rotation angle in radians, {@code [0, pi]}
     */
    public double angleTo(EulerAngles other) {
        Matrix3 d = other.toReferenceToBody().multiply(toBodyToReference());
        double trace = d.m00 + d.m11 + d.m22;
        return Math.acos(Angles.clampUnit((trace - 1.0) / 2.0));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EulerAngles)) {
            return false;
        }
        EulerAngles e = (EulerAngles) o;
        return Double.compare(psi, e.psi) == 0
            && Double.compare(theta, e.theta) == 0
            && Double.compare(phi, e.phi) == 0;
    }

    @Override
    public int hashCode() {
        int h = Double.hashCode(psi);
        h = 31 * h + Double.hashCode(theta);
        return 31 * h + Double.hashCode(phi);
    }

    /** @return {@code psi/theta/phi} in degrees with 3 decimals */
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "psi %.3f theta %.3f phi %.3f deg",
                             getPsiDegrees(), getThetaDegrees(), getPhiDegrees());
    }
}
