package com.simweaver.common.geo;

/**
 * Local tangent frame NED (north, east, down) at a geodetic position. Immutable and thread
 * safe; build one per position and reuse it for all vectors and angles of that entity.
 * <p>
 * Down is along the ellipsoid normal (geodetic vertical), north is tangent to the meridian.
 * Local yaw/pitch/roll ({@link EulerAngles} relative to NED) is what DIS 7 Annex E.8.2.2 and
 * RPR v3 {@code LocalEulerAnglesStruct} call local Euler angles: yaw from true north positive
 * to the right, pitch positive nose up, roll positive right wing down.
 * <p>
 * At the poles north and east are taken from the longitude, as usual; a pole has no
 * physical north direction.
 */
public final class LocalFrame {

    private final Geodetic origin;
    /** Rows are the north, east and down unit vectors in ECEF: maps ECEF to NED. */
    private final Matrix3 ecefToNed;

    private LocalFrame(Geodetic origin) {
        this.origin = origin;
        double lat = origin.getLatitudeRadians();
        double lon = origin.getLongitudeRadians();
        double sLat = Math.sin(lat);
        double cLat = Math.cos(lat);
        double sLon = Math.sin(lon);
        double cLon = Math.cos(lon);
        this.ecefToNed = new Matrix3(
            -sLat * cLon, -sLat * sLon, cLat,
            -sLon, cLon, 0.0,
            -cLat * cLon, -cLat * sLon, -sLat);
    }

    /**
     * @param origin geodetic position of the frame
     * @return the frame
     */
    public static LocalFrame at(Geodetic origin) {
        if (origin == null) {
            throw new NullPointerException("origin");
        }
        return new LocalFrame(origin);
    }

    /**
     * @param ecefPosition ECEF position
     * @param ellipsoid    ellipsoid for the geodetic conversion
     * @return the frame at that position
     */
    public static LocalFrame at(Vec3 ecefPosition, Ellipsoid ellipsoid) {
        return new LocalFrame(ellipsoid.toGeodetic(ecefPosition));
    }

    /** @return the frame origin */
    public Geodetic getOrigin() {
        return origin;
    }

    /** @return rotation ECEF to NED (rows: north, east, down in ECEF) */
    public Matrix3 getEcefToNed() {
        return ecefToNed;
    }

    /** @return north unit vector, ECEF */
    public Vec3 north() {
        return ecefToNed.row(0);
    }

    /** @return east unit vector, ECEF */
    public Vec3 east() {
        return ecefToNed.row(1);
    }

    /** @return down unit vector, ECEF */
    public Vec3 down() {
        return ecefToNed.row(2);
    }

    /**
     * Rotates a free vector (velocity, acceleration, offset) from ECEF to NED components.
     * Not for positions; use {@link #offsetToNed(Vec3, Ellipsoid)} for those.
     *
     * @param ecefVector vector, ECEF components
     * @return (north, east, down) components
     */
    public Vec3 toNed(Vec3 ecefVector) {
        return ecefToNed.transform(ecefVector);
    }

    /**
     * Rotates a free vector from NED to ECEF components.
     *
     * @param ned (north, east, down) components
     * @return ECEF components
     */
    public Vec3 fromNed(Vec3 ned) {
        return ecefToNed.transformTransposed(ned);
    }

    /**
     * Offset of an ECEF point from the frame origin, in NED components (straight line,
     * no curvature correction).
     *
     * @param ecefPoint ECEF position
     * @param ellipsoid ellipsoid the origin refers to
     * @return (north, east, down) offset in metres
     */
    public Vec3 offsetToNed(Vec3 ecefPoint, Ellipsoid ellipsoid) {
        return toNed(ecefPoint.minus(ellipsoid.toEcef(origin)));
    }

    /**
     * Converts ECEF referenced Euler angles (DIS Psi/Theta/Phi) to local yaw/pitch/roll.
     *
     * @param ecefOrientation orientation relative to ECEF
     * @return orientation relative to NED (heading, pitch, roll)
     */
    public EulerAngles toLocal(EulerAngles ecefOrientation) {
        Matrix3 ecefToBody = ecefOrientation.toReferenceToBody();
        // NED -> body = (ECEF -> body) * (NED -> ECEF)
        return EulerAngles.fromReferenceToBody(ecefToBody.multiply(ecefToNed.transpose()));
    }

    /**
     * Converts local yaw/pitch/roll to ECEF referenced Euler angles (DIS Psi/Theta/Phi).
     *
     * @param localOrientation orientation relative to NED
     * @return orientation relative to ECEF
     */
    public EulerAngles toEcef(EulerAngles localOrientation) {
        Matrix3 nedToBody = localOrientation.toReferenceToBody();
        // ECEF -> body = (NED -> body) * (ECEF -> NED)
        return EulerAngles.fromReferenceToBody(nedToBody.multiply(ecefToNed));
    }

    @Override
    public String toString() {
        return "NED@" + origin;
    }
}
