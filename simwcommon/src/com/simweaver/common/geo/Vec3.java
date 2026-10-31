package com.simweaver.common.geo;

import java.util.Locale;

/**
 * Immutable three component vector of doubles.
 * <p>
 * Used for ECEF positions (metres), velocities (m/s), accelerations (m/s^2), angular
 * velocities (rad/s) and for local NED vectors. The class carries no frame information;
 * the frame is defined by whoever holds the vector.
 */
public final class Vec3 {

    /** The zero vector. */
    public static final Vec3 ZERO = new Vec3(0.0, 0.0, 0.0);

    private final double x;
    private final double y;
    private final double z;

    /**
     * Creates a vector.
     *
     * @param x first component
     * @param y second component
     * @param z third component
     */
    public Vec3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /**
     * Creates a vector.
     *
     * @param x first component
     * @param y second component
     * @param z third component
     * @return the vector
     */
    public static Vec3 of(double x, double y, double z) {
        return new Vec3(x, y, z);
    }

    /** @return first component */
    public double getX() {
        return x;
    }

    /** @return second component */
    public double getY() {
        return y;
    }

    /** @return third component */
    public double getZ() {
        return z;
    }

    /**
     * @param other vector to add
     * @return {@code this + other}
     */
    public Vec3 plus(Vec3 other) {
        return new Vec3(x + other.x, y + other.y, z + other.z);
    }

    /**
     * @param other vector to subtract
     * @return {@code this - other}
     */
    public Vec3 minus(Vec3 other) {
        return new Vec3(x - other.x, y - other.y, z - other.z);
    }

    /**
     * @param factor scale factor
     * @return {@code this * factor}
     */
    public Vec3 times(double factor) {
        return new Vec3(x * factor, y * factor, z * factor);
    }

    /**
     * Returns {@code this + other * factor} without an intermediate object.
     *
     * @param other  vector to add
     * @param factor factor applied to {@code other}
     * @return the sum
     */
    public Vec3 plusScaled(Vec3 other, double factor) {
        return new Vec3(x + other.x * factor, y + other.y * factor, z + other.z * factor);
    }

    /** @return {@code -this} */
    public Vec3 negate() {
        return new Vec3(-x, -y, -z);
    }

    /**
     * @param other other vector
     * @return dot product
     */
    public double dot(Vec3 other) {
        return x * other.x + y * other.y + z * other.z;
    }

    /**
     * @param other other vector
     * @return cross product {@code this x other}
     */
    public Vec3 cross(Vec3 other) {
        return new Vec3(y * other.z - z * other.y,
                        z * other.x - x * other.z,
                        x * other.y - y * other.x);
    }

    /** @return Euclidean length, computed without intermediate overflow for large components */
    public double norm() {
        double ax = Math.abs(x);
        double ay = Math.abs(y);
        double az = Math.abs(z);
        double max = Math.max(ax, Math.max(ay, az));
        if (Double.isNaN(ax) || Double.isNaN(ay) || Double.isNaN(az)) {
            return Double.NaN;
        }
        if (max == 0.0 || Double.isInfinite(max)) {
            return max;
        }
        double sx = ax / max;
        double sy = ay / max;
        double sz = az / max;
        return max * Math.sqrt(sx * sx + sy * sy + sz * sz);
    }

    /** @return squared length */
    public double normSquared() {
        return x * x + y * y + z * z;
    }

    /**
     * @param other other vector
     * @return Euclidean distance between the two points
     */
    public double distance(Vec3 other) {
        return minus(other).norm();
    }

    /** @return true when all components are finite (not NaN, not infinite) */
    public boolean isFinite() {
        return !Double.isNaN(x) && !Double.isInfinite(x)
            && !Double.isNaN(y) && !Double.isInfinite(y)
            && !Double.isNaN(z) && !Double.isInfinite(z);
    }

    /** @return true when all components are exactly zero */
    public boolean isZero() {
        return x == 0.0 && y == 0.0 && z == 0.0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Vec3)) {
            return false;
        }
        Vec3 v = (Vec3) o;
        return Double.compare(x, v.x) == 0 && Double.compare(y, v.y) == 0 && Double.compare(z, v.z) == 0;
    }

    @Override
    public int hashCode() {
        int h = Double.hashCode(x);
        h = 31 * h + Double.hashCode(y);
        return 31 * h + Double.hashCode(z);
    }

    /** @return {@code (x, y, z)} with full precision */
    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }

    /**
     * Formats the components with a fixed number of decimals, comma separated.
     *
     * @param decimals number of decimals (0..17)
     * @return for example {@code "3101532.1, 1013113.4, 5438914.9"}
     */
    public String format(int decimals) {
        String f = "%." + decimals + "f";
        return String.format(Locale.ROOT, f + ", " + f + ", " + f, x, y, z);
    }
}
