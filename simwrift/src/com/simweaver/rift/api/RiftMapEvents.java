package com.simweaver.rift.api;

import com.simweaver.common.geo.Ellipsoid;
import com.simweaver.common.geo.Geodetic;
import com.simweaver.common.geo.LocalKinematics;
import com.simweaver.rift.db.RiftDatabase;
import com.simweaver.rift.db.RiftInstance;
import com.simweaver.rift.db.RiftInteraction;
import com.simweaver.rift.db.RiftParameter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fire and detonation events for the map view's flashes ({@code GET …/map?events=<seq>}).
 *
 * <p>Which interactions: every class with a {@code WeaponFire}, {@code Fire} or
 * {@code DirectedEnergyFire} segment in its name ("fire"), and every class with a
 * {@code MunitionDetonation}, {@code Detonation} or {@code Explosion} segment ("detonation").
 * That covers RPR v2 ({@code WeaponFire}, {@code MunitionDetonation}), RPR v3
 * ({@code Fire.WeaponFire}, {@code Detonation.MunitionDetonation}, {@code Detonation.Explosion})
 * and subclasses of them in other FOMs.
 *
 * <p>Where: {@code FiringLocation} / {@code DetonationLocation} (ECEF), else the firing object
 * (fire) or the target, then the firing object (detonation), dead reckoned to now. Events
 * without a place are counted, not listed.
 */
final class RiftMapEvents {

    /** Events at most this old are listed (the client flashes only fresh ones). */
    static final long MAX_AGE_MILLIS = 10_000;
    /** The first call (events=0) gets the events of this last stretch of time. */
    static final long FIRST_CALL_MILLIS = 3_000;
    static final int MAX_EVENTS = 300;

    private RiftMapEvents() {
    }

    /** "fire", "detonation" or null for any other interaction class. */
    static String kindOf(String classNameQ) {
        if (classNameQ == null) {
            return null;
        }
        String kind = null;
        for (String seg : classNameQ.split("\\.")) {
            switch (seg) {
                case "WeaponFire":
                case "Fire":
                case "DirectedEnergyFire":
                    kind = "fire";
                    break;
                case "MunitionDetonation":
                case "Detonation":
                case "Explosion":
                    kind = "detonation";
                    break;
                default:
                    break;
            }
        }
        return kind;
    }

    /**
     * @param afterSeq the {@code eventseq} of the previous answer; 0 = first call
     * @param bbox     west, south, east, north, or null
     * @return {@code eventseq}, {@code events} and {@code eventsunplaced}
     */
    static Map<String, Object> build(RiftDatabase db, long afterSeq, double[] bbox, long now) {
        long latest = db.getLatestInteractionSeq();
        long since = afterSeq <= 0 || afterSeq > latest ? now - FIRST_CALL_MILLIS : now - MAX_AGE_MILLIS;
        long from = afterSeq > latest ? 0 : Math.max(0, afterSeq);       // the buffer started again
        List<RiftInteraction> list = db.getInteractionsAfter(from, MAX_EVENTS,
                ix -> ix.getReceivedMillis() >= since && kindOf(ix.classNameQ()) != null);
        List<Object> out = new ArrayList<>();
        int unplaced = 0;
        for (RiftInteraction ix : list) {
            String kind = kindOf(ix.classNameQ());
            boolean fire = "fire".equals(kind);
            Geodetic where = location(ix.getParameter(fire ? "FiringLocation" : "DetonationLocation"));
            String placedBy = "location";
            RiftInstance firing = resolve(db, ix.getParameter("FiringObjectIdentifier"));
            RiftInstance target = resolve(db, ix.getParameter("TargetObjectIdentifier"));
            if (where == null) {
                RiftInstance at = fire ? firing : (target != null ? target : firing);
                where = at == null ? null : positionOf(at, now);
                placedBy = at == null ? null : at == firing ? "firing" : "target";
            }
            if (where == null) {
                unplaced++;
                continue;
            }
            double lat = where.getLatitude(), lon = where.getLongitude();
            if (bbox != null && !inBox(bbox, lat, lon)) {
                continue;
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", "i" + ix.seq());
            e.put("kind", kind);
            e.put("class", RiftJson.simpleName(ix.classNameQ()));
            e.put("classnameq", ix.classNameQ());
            e.put("lat", round(lat, 7));
            e.put("lon", round(lon, 7));
            e.put("alt", round(where.getHeight(), 1));
            e.put("placed", placedBy);
            e.put("age", round((now - ix.getReceivedMillis()) / 1000.0, 2));
            if (firing != null) {
                e.put("firing", firing.externalId());
                e.put("firinglabel", RiftJson.labelOf(firing));
            }
            if (target != null) {
                e.put("target", target.externalId());
                e.put("targetlabel", RiftJson.labelOf(target));
            }
            String munition = text(ix.getParameter("MunitionType"));
            if (munition != null) {
                e.put("munition", munition);
            }
            if (!fire) {
                String result = text(ix.getParameter("DetonationResultCode"));
                if (result != null) {
                    e.put("result", result);
                }
            }
            out.add(e);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventseq", latest);
        m.put("events", out);
        m.put("eventsunplaced", unplaced);
        return m;
    }

    /** An ECEF location record (fields X, Y, Z in metres) as latitude/longitude, or null. */
    static Geodetic location(RiftParameter p) {
        return locationOf(p == null ? null : p.value());
    }

    /** An ECEF location record (fields X, Y, Z in metres) as latitude/longitude, or null. */
    static Geodetic locationOf(ValueNode v) {
        if (v == null) {
            return null;
        }
        double x = Double.NaN, y = Double.NaN, z = Double.NaN;
        for (ValueNode.Named f : v.fields()) {
            double d = number(f.node);
            switch (f.name) {
                case "X":
                    x = d;
                    break;
                case "Y":
                    y = d;
                    break;
                case "Z":
                    z = d;
                    break;
                default:
                    break;
            }
        }
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z) || x * x + y * y + z * z < 1e12) {
            return null;                     // missing, or (0,0,0) and other points far inside the Earth
        }
        Geodetic g = Ellipsoid.WGS84.toGeodetic(x, y, z);
        return Double.isNaN(g.getLatitude()) || Double.isNaN(g.getLongitude()) ? null : g;
    }

    static double number(ValueNode n) {
        Object s = n == null ? null : n.scalar();
        return s instanceof Number ? ((Number) s).doubleValue() : Double.NaN;
    }

    /** The object an RTIobjectId parameter names (instance name, or a DIS entity id), or null. */
    static RiftInstance resolve(RiftDatabase db, RiftParameter p) {
        ValueNode v = p == null ? null : p.value();
        if (v == null) {
            return null;
        }
        List<String> keys = new ArrayList<>(v.refKeys());
        String t = v.text();
        if (t != null && !t.trim().isEmpty()) {
            keys.add(t.trim());
        }
        for (String k : keys) {
            RiftInstance o = db.findByInstanceName(k);
            if (o == null) {
                o = db.findByEntityId(k);
            }
            if (o != null && !o.isRemoved()) {
                return o;
            }
        }
        return null;
    }

    private static Geodetic positionOf(RiftInstance o, long now) {
        LocalKinematics k = o.kinematicsNow(now);
        if (k == null || Double.isNaN(k.getGeodetic().getLatitude())) {
            return null;
        }
        return k.getGeodetic();
    }

    private static String text(RiftParameter p) {
        ValueNode v = p == null ? null : p.value();
        if (v == null) {
            return null;
        }
        String t = v.text();
        return t == null || t.isEmpty() ? null : t;
    }

    static boolean inBox(double[] b, double lat, double lon) {
        if (lat < b[1] || lat > b[3]) {
            return false;
        }
        return b[0] <= b[2] ? lon >= b[0] && lon <= b[2] : lon >= b[0] || lon <= b[2];
    }

    static double round(double v, int decimals) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return 0;
        }
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }
}
