package com.simweaver.rift.api;

import com.simweaver.common.geo.LocalKinematics;
import com.simweaver.common.geo.Vec3;
import com.simweaver.rift.db.RiftDatabase;
import com.simweaver.rift.db.RiftInstance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The map view of a datasource ({@code GET /api/datasources/{dsid}/map}): the objects of the
 * object list's filter that have a position, dead reckoned to now.
 *
 * <ul>
 *   <li>Position: the RPR {@code Spatial}, dead reckoned (as DRSpatial). Objects without one are
 *       only counted ({@code unpositioned}).</li>
 *   <li>Objects hosted by or part of another object ({@code HostObjectIdentifier}, e.g. radios;
 *       {@code IsPartOf}, mounted entities; {@code EmitterSystemIdentifier}, beams of an emitter) are listed as {@code children} of their top-most
 *       positioned host, not as symbols of their own. A host outside the filter is still drawn,
 *       with {@code context: true}, so its children can be reached.</li>
 * </ul>
 */
final class RiftMapObjects {

    static final int DEFAULT_LIMIT = 5000;
    static final int MAX_LIMIT = 20000;
    private static final int MAX_DEPTH = 6;

    private RiftMapObjects() {
    }

    static Map<String, Object> build(RiftDatabase db, List<RiftInstance> matched, double[] bbox, int limit, long now) {
        Map<RiftInstance, LocalKinematics> pos = new HashMap<>();
        Map<RiftInstance, RiftInstance> hostOf = new HashMap<>();
        Map<RiftInstance, String> via = new HashMap<>();
        Set<RiftInstance> inFilter = new HashSet<>(matched);

        // roots: top-most positioned host (or the object itself); children by root
        Map<RiftInstance, List<RiftInstance>> children = new LinkedHashMap<>();
        Set<RiftInstance> roots = new java.util.LinkedHashSet<>();
        int unpositioned = 0, childCount = 0;
        for (RiftInstance o : matched) {
            RiftInstance top = topHost(db, o, pos, hostOf, via, now);
            if (top != null) {
                roots.add(top);
                children.computeIfAbsent(top, k -> new ArrayList<>()).add(o);
                childCount++;
            } else if (position(o, pos, now) != null) {
                roots.add(o);
            } else {
                unpositioned++;
            }
        }

        List<Object> out = new ArrayList<>();
        int shown = 0, outside = 0;
        boolean truncated = false;
        for (RiftInstance r : roots) {
            LocalKinematics k = pos.get(r);
            if (k == null) {
                continue;
            }
            double lat = k.getGeodetic().getLatitude(), lon = k.getGeodetic().getLongitude();
            if (bbox != null && !inBox(bbox, lat, lon)) {
                outside++;
                continue;
            }
            if (out.size() >= limit) {
                truncated = true;
                break;
            }
            out.add(entry(r, k, !inFilter.contains(r), children.get(r), hostOf, via, now));
            shown++;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("now", now);
        m.put("matched", matched.size());
        m.put("shown", shown);
        m.put("children", childCount);
        m.put("unpositioned", unpositioned);
        if (bbox != null) {
            m.put("outside", outside);
        }
        m.put("truncated", truncated);
        m.put("objects", out);
        return m;
    }

    private static boolean inBox(double[] b, double lat, double lon) {
        if (lat < b[1] || lat > b[3]) {
            return false;
        }
        return b[0] <= b[2] ? lon >= b[0] && lon <= b[2] : lon >= b[0] || lon <= b[2];      // across 180
    }

    private static LocalKinematics position(RiftInstance o, Map<RiftInstance, LocalKinematics> pos, long now) {
        if (pos.containsKey(o)) {
            return pos.get(o);
        }
        LocalKinematics k = o.kinematicsNow(now);
        if (k != null && (Double.isNaN(k.getGeodetic().getLatitude()) || Double.isNaN(k.getGeodetic().getLongitude()))) {
            k = null;
        }
        pos.put(o, k);
        return k;
    }

    /** The host an object is on or part of (HostObjectIdentifier, IsPartOf), or null. */
    private static RiftInstance host(RiftDatabase db, RiftInstance o, Map<RiftInstance, RiftInstance> hostOf, Map<RiftInstance, String> via) {
        if (hostOf.containsKey(o)) {
            return hostOf.get(o);
        }
        RiftInstance h = null;
        for (RiftRelation r : db.getOutgoing(o)) {
            if (!(r.other instanceof RiftInstance) || r.other == o || r.other.removed()) {
                continue;
            }
            if ("HostObjectIdentifier".equals(r.attribute)) {
                h = (RiftInstance) r.other;
                via.put(o, "hosted");
                break;
            }
            if ("IsPartOf".equals(r.attribute)) {
                h = (RiftInstance) r.other;
                via.put(o, "partof");
            } else if ("EmitterSystemIdentifier".equals(r.attribute) && h == null) {
                h = (RiftInstance) r.other;                 // a beam: under its emitter system
                via.put(o, "beam");
            }
        }
        hostOf.put(o, h);
        return h;
    }

    /** The top-most positioned host of an object (following hosts of hosts), or null. */
    private static RiftInstance topHost(RiftDatabase db, RiftInstance o, Map<RiftInstance, LocalKinematics> pos,
            Map<RiftInstance, RiftInstance> hostOf, Map<RiftInstance, String> via, long now) {
        RiftInstance top = null;
        RiftInstance cur = o;
        Set<RiftInstance> seen = new HashSet<>();
        for (int i = 0; i < MAX_DEPTH && seen.add(cur); i++) {
            RiftInstance h = host(db, cur, hostOf, via);
            if (h == null) {
                break;
            }
            if (position(h, pos, now) != null) {
                top = h;
            }
            cur = h;
        }
        return top;
    }

    private static Map<String, Object> entry(RiftInstance o, LocalKinematics k, boolean context, List<RiftInstance> kids,
            Map<RiftInstance, RiftInstance> hostOf, Map<RiftInstance, String> via, long now) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", o.externalId());
        e.put("label", RiftJson.labelOf(o));
        e.put("name", o.getInstanceName());
        e.put("classnameq", o.getClassName());
        e.put("class", RiftJson.simpleName(o.getClassName()));
        e.put("entityid", o.getEntityKey());
        String forceText = text(o, "ForceIdentifier");
        e.put("force", force(forceText));
        e.put("forcetext", forceText);
        e.put("lat", round(k.getGeodetic().getLatitude(), 7));
        e.put("lon", round(k.getGeodetic().getLongitude(), 7));
        e.put("alt", round(k.getGeodetic().getHeight(), 1));
        e.put("hdg", round(norm(k.getHeadingDegrees()), 1));
        e.put("gs", round(k.getGroundSpeed(), 2));
        Vec3 v = k.getVelocityNed();
        e.put("vn", round(v.getX(), 3));
        e.put("ve", round(v.getY(), 3));
        e.put("vd", round(v.getZ(), 3));
        long rx = o.spatialReceivedMillis();
        e.put("age", rx > 0 ? round((now - rx) / 1000.0, 1) : null);
        e.put("frozen", k.getState().isFrozen());
        if (context) {
            e.put("context", true);
        }
        List<Object> c = new ArrayList<>();
        if (kids != null) {
            for (RiftInstance x : kids) {
                Map<String, Object> ce = new LinkedHashMap<>();
                ce.put("id", x.externalId());
                ce.put("label", RiftJson.labelOf(x));
                ce.put("name", x.getInstanceName());
                ce.put("class", RiftJson.simpleName(x.getClassName()));
                ce.put("classnameq", x.getClassName());
                ce.put("entityid", x.getEntityKey());
                ce.put("relation", via.getOrDefault(x, "hosted"));
                ce.put("force", force(text(x, "ForceIdentifier")));     // 0.9.22: a carried entity's symbol colour
                RiftInstance h = hostOf.get(x);
                if (h != null && h != o) {
                    ce.put("parent", h.externalId());          // a host below the drawn one
                }
                c.add(ce);
            }
        }
        e.put("children", c);
        return e;
    }

    private static String text(RiftInstance o, String attribute) {
        RiftMemberView a = o.attributes().get(attribute);
        return a == null || a.value() == null ? null : a.value().text();
    }

    /** "friendly", "opposing", "neutral", "other", or null (no ForceIdentifier). */
    static String force(String text) {
        if (text == null) {
            return null;
        }
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("friend")) {
            return "friendly";
        }
        if (t.contains("oppos") || t.contains("hostile")) {
            return "opposing";
        }
        if (t.contains("neutral")) {
            return "neutral";
        }
        return "other";
    }

    private static double norm(double deg) {
        double d = deg % 360;
        return d < 0 ? d + 360 : d;
    }

    private static double round(double v, int decimals) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return 0;
        }
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }
}
