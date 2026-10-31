package com.simweaver.rift.api;

import com.simweaver.common.geo.Ellipsoid;
import com.simweaver.common.geo.Geodetic;
import com.simweaver.common.geo.LocalKinematics;
import com.simweaver.common.geo.Vec3;
import com.simweaver.rift.db.RiftDatabase;
import com.simweaver.rift.db.RiftInstance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Laser designators for the map view ({@code GET …/map?lasers=true}): a line from the designating
 * object to the laser spot.
 *
 * <p>Every active object of a {@code Designator} class ({@code EmbeddedSystem.Designator} in RPR
 * v2 and v3, and subclasses) that is lasing: {@code DesignatorOutputPower} above 0 (or not given).
 * DIS sends a designator that stopped with power 0.
 *
 * <p>Where the spot is:
 * <ol>
 *   <li>on the designated object ({@code DesignatedObjectIdentifier}), dead reckoned to now, when
 *       that object has a position and {@code RelativeSpotLocation} puts the spot within
 *       {@value #ON_TARGET_METRES} m of it: the spot then moves with the target between updates;</li>
 *   <li>else at {@code DesignatorSpotLocation} (ECEF);</li>
 *   <li>else on the designated object, whatever the offset.</li>
 * </ol>
 * The line starts at the host ({@code HostObjectIdentifier}, then {@code IsPartOf}). When the
 * host is drawn, {@code host} names it, so the client starts the line at the moving symbol;
 * otherwise the line starts at {@code fromlat} / {@code fromlon}, when the host has a position.
 * Designators with neither a spot nor a host position are counted in {@code lasershidden}.
 *
 * <p>Simplifications: the spot's own dead reckoning ({@code DeadReckoningAlgorithm},
 * {@code SpotLinearAccelerationVector}) is not applied, and the body offset of an on-target spot
 * is left out (it is metres).
 */
final class RiftMapLasers {

    static final int MAX_LASERS = 500;
    /** A spot this close to its designated object is drawn on that object. */
    static final double ON_TARGET_METRES = 50;

    private RiftMapLasers() {
    }

    /** True for a designator class: one with a {@code Designator} segment under {@code EmbeddedSystem}. */
    static boolean isDesignator(String classNameQ) {
        if (classNameQ == null) {
            return false;
        }
        boolean embedded = false;
        for (String seg : classNameQ.split("\\.")) {
            if ("EmbeddedSystem".equals(seg)) {
                embedded = true;
            } else if (embedded && "Designator".equals(seg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param shown ids of the objects the map answer draws (top-level symbols)
     * @return {@code lasers}, {@code lasershidden} (no place for the line) and {@code lasersoff}
     *         (designators with power 0)
     */
    static Map<String, Object> build(RiftDatabase db, Set<String> shown, long now) {
        List<Object> out = new ArrayList<>();
        int hidden = 0, off = 0;
        for (RiftInstance d : db.getActiveInstances()) {
            if (!isDesignator(d.getClassName())) {
                continue;
            }
            Double power = RiftMapBeams.number(d, "DesignatorOutputPower");
            if (power != null && power <= 0) {
                off++;
                continue;
            }
            RiftInstance host = RiftMapBeams.ref(db, d, "HostObjectIdentifier");
            if (host == null) {
                host = RiftMapBeams.ref(db, d, "IsPartOf");
            }
            RiftInstance target = RiftMapBeams.ref(db, d, "DesignatedObjectIdentifier");
            Geodetic targetAt = positionOf(target, now);
            Geodetic world = RiftMapEvents.locationOf(value(d, "DesignatorSpotLocation"));
            double offset = magnitude(value(d, "RelativeSpotLocation"));

            Geodetic spot;
            String placed;
            if (targetAt != null && (world == null || !(offset > ON_TARGET_METRES))) {
                spot = targetAt;
                placed = "target";
            } else if (world != null) {
                spot = world;
                placed = "location";
            } else {
                spot = null;
                placed = null;
            }
            Geodetic from = positionOf(host, now);
            RiftInstance drawn = RiftMapBeams.drawnHost(db, host, shown);
            if (spot == null || (from == null && drawn == null) || out.size() >= MAX_LASERS) {
                hidden++;
                continue;
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", d.externalId());
            e.put("class", RiftJson.simpleName(d.getClassName()));
            e.put("label", RiftJson.labelOf(d));
            if (drawn != null) {
                e.put("host", drawn.externalId());
            }
            if (host != null) {
                e.put("hostlabel", RiftJson.labelOf(host));
            }
            if (from != null) {
                e.put("fromlat", RiftMapEvents.round(from.getLatitude(), 7));
                e.put("fromlon", RiftMapEvents.round(from.getLongitude(), 7));
                e.put("range", RiftMapEvents.round(distance(from, spot), 0));
            }
            e.put("lat", RiftMapEvents.round(spot.getLatitude(), 7));
            e.put("lon", RiftMapEvents.round(spot.getLongitude(), 7));
            e.put("alt", RiftMapEvents.round(spot.getHeight(), 1));
            e.put("placed", placed);
            if (target != null) {
                e.put("target", target.externalId());
                e.put("targetlabel", RiftJson.labelOf(target));
            }
            if (power != null) {
                e.put("power", RiftMapBeams.round(power, 2));
            }
            RiftMapBeams.putNumber(e, "wavelength", d, "DesignatorEmissionWavelength", 3);
            RiftMapBeams.putText(e, "code", d, "DesignatorCode");
            RiftMapBeams.putText(e, "codename", d, "CodeName");
            out.add(e);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lasers", out);
        m.put("lasershidden", hidden);
        m.put("lasersoff", off);
        return m;
    }

    private static ValueNode value(RiftInstance o, String attribute) {
        RiftMemberView a = o.attributes().get(attribute);
        return a == null ? null : a.value();
    }

    /** Length of a record of numbers (a relative position), or NaN when it has none. */
    static double magnitude(ValueNode v) {
        if (v == null) {
            return Double.NaN;
        }
        double sum = 0;
        int n = 0;
        for (ValueNode.Named f : v.fields()) {
            double d = RiftMapEvents.number(f.node);
            if (!Double.isNaN(d) && !Double.isInfinite(d)) {
                sum += d * d;
                n++;
            }
        }
        return n == 0 ? Double.NaN : Math.sqrt(sum);
    }

    /** Straight-line distance in metres. */
    static double distance(Geodetic a, Geodetic b) {
        Vec3 p = Ellipsoid.WGS84.toEcef(a), q = Ellipsoid.WGS84.toEcef(b);
        double dx = p.getX() - q.getX(), dy = p.getY() - q.getY(), dz = p.getZ() - q.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static Geodetic positionOf(RiftInstance o, long now) {
        LocalKinematics k = o == null ? null : o.kinematicsNow(now);
        if (k == null || Double.isNaN(k.getGeodetic().getLatitude()) || Double.isNaN(k.getGeodetic().getLongitude())) {
            return null;
        }
        return k.getGeodetic();
    }
}
