package com.simweaver.common.geo;

/**
 * Small angle helpers. All methods are pure functions.
 */
public final class Angles {

    /** 2 pi. */
    public static final double TWO_PI = 2.0 * Math.PI;

    private Angles() {
    }

    /**
     * Normalizes to {@code [0, 360)} degrees. NaN and infinities give NaN.
     *
     * @param degrees any angle
     * @return the equivalent angle in {@code [0, 360)}
     */
    public static double normalize360(double degrees) {
        if (Double.isNaN(degrees) || Double.isInfinite(degrees)) {
            return Double.NaN;
        }
        double r = degrees % 360.0;
        if (r < 0.0) {
            r += 360.0;
        }
        // r + 360 may round to exactly 360 for tiny negative inputs
        return r >= 360.0 ? 0.0 : r;
    }

    /**
     * Normalizes to {@code (-180, 180]} degrees. NaN and infinities give NaN.
     *
     * @param degrees any angle
     * @return the equivalent angle in {@code (-180, 180]}
     */
    public static double normalize180(double degrees) {
        double r = normalize360(degrees);
        return r > 180.0 ? r - 360.0 : r;
    }

    /**
     * Normalizes to {@code (-pi, pi]} radians. NaN and infinities give NaN.
     *
     * @param radians any angle
     * @return the equivalent angle in {@code (-pi, pi]}
     */
    public static double normalizePi(double radians) {
        if (Double.isNaN(radians) || Double.isInfinite(radians)) {
            return Double.NaN;
        }
        double r = radians % TWO_PI;
        if (r <= -Math.PI) {
            r += TWO_PI;
        } else if (r > Math.PI) {
            r -= TWO_PI;
        }
        return r;
    }

    /**
     * Smallest signed difference {@code a - b} in degrees, in {@code (-180, 180]}.
     *
     * @param a first angle (degrees)
     * @param b second angle (degrees)
     * @return the difference
     */
    public static double differenceDegrees(double a, double b) {
        return normalize180(a - b);
    }

    /**
     * Clamps to {@code [-1, 1]} so rounding noise does not turn asin/acos into NaN.
     *
     * @param v value
     * @return clamped value (NaN stays NaN)
     */
    static double clampUnit(double v) {
        return v > 1.0 ? 1.0 : (v < -1.0 ? -1.0 : v);
    }
}
