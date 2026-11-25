package com.simweaver.rift.mcp;

import com.simweaver.common.geo.Ellipsoid;
import com.simweaver.common.geo.Geodetic;
import com.simweaver.common.geo.LocalFrame;
import com.simweaver.common.geo.Vec3;

/**
 * 0.14.0: the geometry the MCP tools answer with (WGS84): range, bearing, elevation, closing speed
 * and closest point of approach. Computed in ECEF, so long ranges and altitudes are right; bearings
 * and elevations are in the local frame (north, east, down) of the first point.
 */
final class McpGeo {

    static final double M_PER_NM = 1852.0;
    static final double KT_PER_MPS = 3600.0 / 1852.0;

    private McpGeo() {
    }

    /** From A to B. Speeds and times are NaN when no velocities were given. */
    static final class Relation {
        double slantM;
        double horizontalM;
        double bearingDeg;
        double elevationDeg;
        double closingMps = Double.NaN;
        double cpaSeconds = Double.NaN;
        double cpaM = Double.NaN;
    }

    /**
     * @param vaNed A's velocity in A's local frame (north, east, down, m/s), or null
     * @param vbNed B's velocity in B's local frame, or null (a fixed point)
     */
    static Relation between(Geodetic a, Vec3 vaNed, Geodetic b, Vec3 vbNed) {
        Ellipsoid e = Ellipsoid.WGS84;
        Vec3 pa = e.toEcef(a);
        Vec3 pb = e.toEcef(b);
        Vec3 r = pb.minus(pa);
        LocalFrame fa = LocalFrame.at(a);
        Vec3 ned = fa.toNed(r);
        Relation x = new Relation();
        x.slantM = r.norm();
        x.horizontalM = Math.hypot(ned.getX(), ned.getY());
        x.bearingDeg = norm360(Math.toDegrees(Math.atan2(ned.getY(), ned.getX())));
        x.elevationDeg = Math.toDegrees(Math.atan2(-ned.getZ(), x.horizontalM));
        if (vaNed != null || vbNed != null) {
            Vec3 va = vaNed == null ? Vec3.ZERO : fa.fromNed(vaNed);
            Vec3 vb = vbNed == null ? Vec3.ZERO : LocalFrame.at(b).fromNed(vbNed);
            Vec3 vr = vb.minus(va);                                     // B relative to A, ECEF
            x.closingMps = x.slantM > 0 ? -vr.dot(r) / x.slantM : 0;
            double vv = vr.normSquared();
            double t = vv > 1e-9 ? -r.dot(vr) / vv : 0;
            if (t <= 0) {
                x.cpaSeconds = 0;
                x.cpaM = x.slantM;
            } else {
                x.cpaSeconds = t;
                x.cpaM = r.plusScaled(vr, t).norm();
            }
        }
        return x;
    }

    /** {horizontal range m, bearing deg} from A to B. */
    static double[] rangeBearing(Geodetic a, Geodetic b) {
        Vec3 ned = LocalFrame.at(a).offsetToNed(Ellipsoid.WGS84.toEcef(b), Ellipsoid.WGS84);
        return new double[] {Math.hypot(ned.getX(), ned.getY()), norm360(Math.toDegrees(Math.atan2(ned.getY(), ned.getX())))};
    }

    static double norm360(double deg) {
        double d = deg % 360;
        return d < 0 ? d + 360 : d;
    }

    /** A direction rounded to 0.1 degree in [0, 360): 359.99 is 0.0, not 360.0. */
    static Double heading(double deg) {
        Double r = round(norm360(deg), 1);
        return r != null && r >= 360.0 ? Double.valueOf(0.0) : r;
    }

    static double norm180(double deg) {
        double d = norm360(deg);
        return d > 180 ? d - 360 : d;
    }

    /** Rounded; null for NaN and infinities (JSON has no NaN). */
    static Double round(double v, int decimals) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return null;
        }
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }
}
