package com.simweaver.common.geo;

/**
 * Reference ellipsoid of revolution and the conversions between geocentric (ECEF) and
 * geodetic coordinates. Immutable and thread safe.
 * <p>
 * DIS and RPR-FOM world coordinates are WGS84 ECEF: origin at the Earth's centre, X through
 * (0 N, 0 E), Y through (0 N, 90 E), Z through the North Pole, metres.
 *
 * <h2>ECEF to geodetic</h2>
 * Closed form, no iteration: H. Vermeille, "An analytical method to transform geocentric
 * into geodetic coordinates", J. Geodesy 85 (2011) 105-117. It is exact to rounding for
 * every point, including far outside the Earth, at the poles, and deep inside the evolute
 * near the centre, where the nearest point on the ellipsoid is not unique and a
 * valid (northern) solution is returned.
 */
public final class Ellipsoid {

    /** WGS84: a = 6378137 m, 1/f = 298.257223563. */
    public static final Ellipsoid WGS84 = new Ellipsoid("WGS84", 6378137.0, 298.257223563);

    private final String name;
    private final double a;
    private final double inverseFlattening;
    private final double f;
    private final double b;
    private final double e2;
    private final double e4;
    private final double e;
    private final double oneMinusE2;

    /**
     * @param name              label, e.g. "WGS84"
     * @param semiMajorAxis     a, metres (positive)
     * @param inverseFlattening 1/f; must be greater than 1 (a sphere is not supported by
     *                          this constructor; use {@link #sphere(String, double)})
     */
    public Ellipsoid(String name, double semiMajorAxis, double inverseFlattening) {
        this(name, semiMajorAxis, inverseFlattening, 1.0 / inverseFlattening);
    }

    private Ellipsoid(String name, double a, double inverseFlattening, double f) {
        if (!(a > 0.0) || Double.isInfinite(a)) {
            throw new IllegalArgumentException("semi-major axis must be positive and finite: " + a);
        }
        if (!(f >= 0.0 && f < 1.0)) {
            throw new IllegalArgumentException("flattening must be in [0, 1): " + f);
        }
        this.name = name == null ? "" : name;
        this.a = a;
        this.inverseFlattening = inverseFlattening;
        this.f = f;
        this.b = a * (1.0 - f);
        this.e2 = f * (2.0 - f);
        this.e4 = e2 * e2;
        this.e = Math.sqrt(e2);
        this.oneMinusE2 = 1.0 - e2;
    }

    /**
     * A sphere (flattening zero), for simulations that use a spherical Earth.
     *
     * @param name   label
     * @param radius radius, metres
     * @return the sphere
     */
    public static Ellipsoid sphere(String name, double radius) {
        return new Ellipsoid(name, radius, Double.POSITIVE_INFINITY, 0.0);
    }

    /** @return the label */
    public String getName() {
        return name;
    }

    /** @return semi-major axis a, metres */
    public double getSemiMajorAxis() {
        return a;
    }

    /** @return semi-minor axis b, metres */
    public double getSemiMinorAxis() {
        return b;
    }

    /** @return flattening f */
    public double getFlattening() {
        return f;
    }

    /** @return 1/f (infinite for a sphere) */
    public double getInverseFlattening() {
        return inverseFlattening;
    }

    /** @return first eccentricity squared e^2 = f(2 - f) */
    public double getEccentricitySquared() {
        return e2;
    }

    /**
     * Prime vertical radius of curvature N at a latitude.
     *
     * @param latitudeRad geodetic latitude, radians
     * @return N, metres
     */
    public double primeVerticalRadius(double latitudeRad) {
        double s = Math.sin(latitudeRad);
        return a / Math.sqrt(1.0 - e2 * s * s);
    }

    /**
     * Geodetic to ECEF.
     *
     * @param g geodetic position
     * @return ECEF position, metres
     */
    public Vec3 toEcef(Geodetic g) {
        return toEcefRadians(g.getLatitudeRadians(), g.getLongitudeRadians(), g.getHeight());
    }

    /**
     * Geodetic to ECEF.
     *
     * @param latitudeDeg  degrees
     * @param longitudeDeg degrees
     * @param heightM      metres above the ellipsoid
     * @return ECEF position, metres
     */
    public Vec3 toEcef(double latitudeDeg, double longitudeDeg, double heightM) {
        return toEcefRadians(Math.toRadians(latitudeDeg), Math.toRadians(longitudeDeg), heightM);
    }

    /**
     * Geodetic to ECEF.
     *
     * @param lat radians
     * @param lon radians
     * @param h   metres above the ellipsoid
     * @return ECEF position, metres
     */
    public Vec3 toEcefRadians(double lat, double lon, double h) {
        double sLat = Math.sin(lat);
        double cLat = Math.cos(lat);
        double n = a / Math.sqrt(1.0 - e2 * sLat * sLat);
        double r = (n + h) * cLat;
        return new Vec3(r * Math.cos(lon), r * Math.sin(lon), (n * oneMinusE2 + h) * sLat);
    }

    /**
     * ECEF to geodetic (Vermeille 2011, closed form).
     *
     * @param ecef ECEF position, metres; must be finite
     * @return geodetic position
     * @throws IllegalArgumentException if a component is NaN or infinite
     */
    public Geodetic toGeodetic(Vec3 ecef) {
        return toGeodetic(ecef.getX(), ecef.getY(), ecef.getZ());
    }

    /**
     * ECEF to geodetic (Vermeille 2011, closed form).
     *
     * @param x metres
     * @param y metres
     * @param z metres
     * @return geodetic position
     * @throws IllegalArgumentException if a component is NaN or infinite
     */
    public Geodetic toGeodetic(double x, double y, double z) {
        double[] out = new double[3];
        toGeodeticRadians(x, y, z, out);
        return Geodetic.ofRadians(out[0], out[1], out[2]);
    }

    /**
     * Allocation free variant of {@link #toGeodetic(double, double, double)}.
     *
     * @param x   metres
     * @param y   metres
     * @param z   metres
     * @param out receives latitude (rad), longitude (rad), height (m) in elements 0..2
     * @throws IllegalArgumentException if a component is NaN or infinite
     */
    public void toGeodeticRadians(double x, double y, double z, double[] out) {
        if (!isFinite(x) || !isFinite(y) || !isFinite(z)) {
            throw new IllegalArgumentException("ECEF position not finite: (" + x + ", " + y + ", " + z + ")");
        }
        double rho = Math.hypot(x, y);
        double lon = (x == 0.0 && y == 0.0) ? 0.0 : Math.atan2(y, x);

        if (e2 == 0.0) {
            // sphere: geocentric = geodetic
            out[0] = Math.atan2(z, rho);
            out[1] = lon;
            out[2] = Math.hypot(rho, z) - a;
            return;
        }

        double p = (rho / a) * (rho / a);
        double q = oneMinusE2 * (z / a) * (z / a);
        double r = (p + q - e4) / 6.0;
        double evoluteBorderTest = 8.0 * r * r * r + e4 * p * q;

        double lat;
        double h;
        if (evoluteBorderTest > 0.0 || q != 0.0) {
            double u;
            if (evoluteBorderTest > 0.0) {
                // outside the evolute: the usual case, all points near or above the surface
                double rad1 = Math.sqrt(evoluteBorderTest);
                double rad2 = Math.sqrt(e4 * p * q);
                if (evoluteBorderTest > 10.0 * e2) {
                    double rad3 = Math.cbrt((rad1 + rad2) * (rad1 + rad2));
                    u = r + 0.5 * rad3 + 2.0 * r * r / rad3;
                } else {
                    u = r + 0.5 * Math.cbrt((rad1 + rad2) * (rad1 + rad2))
                          + 0.5 * Math.cbrt((rad1 - rad2) * (rad1 - rad2));
                }
            } else {
                // inside the evolute (within ~ 43 km of the centre) but not on the singular disc
                double rad1 = Math.sqrt(-evoluteBorderTest);
                double rad2 = Math.sqrt(-8.0 * r * r * r);
                double rad3 = Math.sqrt(e4 * p * q);
                double atan = 2.0 * Math.atan2(rad3, rad1 + rad2) / 3.0;
                u = -4.0 * r * Math.sin(atan) * Math.cos(Math.PI / 6.0 + atan);
            }
            double v = Math.sqrt(u * u + e4 * q);
            double w = e2 * (u + v - q) / (2.0 * v);
            double k = (u + v) / (Math.sqrt(w * w + u + v) + w);
            double d = k * rho / (k + e2);
            double sqrtDdZz = Math.hypot(d, z);
            lat = 2.0 * Math.atan2(z, sqrtDdZz + d);
            h = (k + e2 - 1.0) * sqrtDdZz / k;
        } else {
            // singular disc: z == 0 and rho <= a e^2. Every point of the disc has two
            // symmetric nearest points on the ellipsoid; the northern one is returned.
            double rad1 = Math.sqrt(oneMinusE2);
            double rad2 = Math.sqrt(e2 - p);
            h = -a * rad1 * rad2 / e;
            double xr = Math.sqrt(p) / e2;                 // = rho / (a e^2), in [0, 1]
            lat = Math.atan2(Math.sqrt(Math.max(0.0, 1.0 - xr * xr)), xr * rad1);
        }
        out[0] = lat;
        out[1] = lon;
        out[2] = h;
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    /**
     * Outward unit normal of the ellipsoid (the local "up") at a geodetic latitude/longitude.
     *
     * @param latitudeRad  radians
     * @param longitudeRad radians
     * @return unit vector in ECEF
     */
    public static Vec3 up(double latitudeRad, double longitudeRad) {
        double cLat = Math.cos(latitudeRad);
        return new Vec3(cLat * Math.cos(longitudeRad), cLat * Math.sin(longitudeRad), Math.sin(latitudeRad));
    }

    @Override
    public String toString() {
        return name + " (a=" + a + ", 1/f=" + inverseFlattening + ")";
    }
}
