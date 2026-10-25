package com.simweaver.rift.api;

import com.simweaver.common.geo.LocalKinematics;
import com.simweaver.rift.db.RiftDatabase;
import com.simweaver.rift.db.RiftInstance;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Radar and jammer beams for the map view ({@code GET …/map?beams=true}).
 *
 * <p>Every active object of an {@code EmitterBeam} class ({@code RadarBeam}, {@code JammerBeam}
 * and their subclasses, in RPR v2 and v3) whose emitter system ({@code EmitterSystemIdentifier})
 * sits on an object that the map draws ({@code HostObjectIdentifier} / {@code IsPartOf}, followed
 * upwards). Its direction is the host's heading plus {@code BeamAzimuthCenter}; its width
 * {@code BeamAzimuthSweep} on either side. The RPR FOM has no range for a beam: the map draws a
 * length of the user's choice.
 *
 * <p>Simplification: the azimuth is taken in the horizontal plane of the host, so pitch and roll
 * of the host are ignored.
 */
final class RiftMapBeams {

    private static final int MAX_DEPTH = 6;
    static final int MAX_BEAMS = 2000;

    private RiftMapBeams() {
    }

    /** "radar", "jammer", or null for a class that is not an emitter beam. */
    static String kindOf(String classNameQ) {
        if (classNameQ == null) {
            return null;
        }
        boolean beam = false, jammer = false;
        for (String seg : classNameQ.split("\\.")) {
            if ("EmitterBeam".equals(seg)) {
                beam = true;
            } else if ("JammerBeam".equals(seg)) {
                jammer = true;
            }
        }
        return beam ? (jammer ? "jammer" : "radar") : null;
    }

    /**
     * @param shown ids of the objects the map answer draws (top-level symbols)
     * @return {@code beams} and {@code beamshidden} (beams whose host is not drawn or has no position)
     */
    static Map<String, Object> build(RiftDatabase db, Set<String> shown, long now) {
        List<Object> out = new ArrayList<>();
        int hidden = 0;
        for (RiftInstance b : db.getActiveInstances()) {
            String kind = kindOf(b.getClassName());
            if (kind == null) {
                continue;
            }
            RiftInstance system = ref(db, b, "EmitterSystemIdentifier");
            RiftInstance host = system == null ? null : ref(db, system, "HostObjectIdentifier");
            if (host == null && system != null) {
                host = ref(db, system, "IsPartOf");
            }
            RiftInstance drawn = drawnHost(db, host, shown);
            Double az = number(b, "BeamAzimuthCenter");
            LocalKinematics k = host == null ? null : host.kinematicsNow(now);
            if (drawn == null || az == null || k == null || out.size() >= MAX_BEAMS) {
                hidden++;
                continue;
            }
            Double sweep = number(b, "BeamAzimuthSweep");
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", b.externalId());
            e.put("host", drawn.externalId());
            e.put("kind", kind);
            e.put("class", RiftJson.simpleName(b.getClassName()));
            e.put("label", RiftJson.labelOf(b));
            e.put("system", system.externalId());
            e.put("systemlabel", RiftJson.labelOf(system));
            putText(e, "emitter", system, "EmitterType");
            putText(e, "emitterfunction", system, "EmitterFunctionCode");
            putText(e, "function", b, "BeamFunctionCode");
            double relaz = Math.toDegrees(az);
            e.put("relaz", round(relaz, 2));
            e.put("az", round(norm(k.getHeadingDegrees() + relaz), 2));
            e.put("sweep", round(sweep == null ? 0 : Math.abs(Math.toDegrees(sweep)), 2));
            putDeg(e, "el", b, "BeamElevationCenter");
            putDeg(e, "elsweep", b, "BeamElevationSweep");
            putNumber(e, "erp", b, "EffectiveRadiatedPower", 1);
            putNumber(e, "freq", b, "EmissionFrequency", 0);
            putNumber(e, "prf", b, "PulseRepetitionFrequency", 1);
            putNumber(e, "sweepsync", b, "SweepSynch", 1);
            out.add(e);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("beams", out);
        m.put("beamshidden", hidden);
        return m;
    }

    /** The object an RTIobjectId attribute refers to (as resolved by the database), or null. */
    static RiftInstance ref(RiftDatabase db, RiftInstance o, String attribute) {
        for (RiftRelation r : db.getOutgoing(o)) {
            if (attribute.equals(r.attribute) && r.other instanceof RiftInstance && r.other != o && !r.other.removed()) {
                return (RiftInstance) r.other;
            }
        }
        return null;
    }

    /** The host itself or the first of its hosts (HostObjectIdentifier, IsPartOf) that the map draws. */
    static RiftInstance drawnHost(RiftDatabase db, RiftInstance host, Set<String> shown) {
        Set<RiftInstance> seen = new HashSet<>();
        RiftInstance cur = host;
        for (int i = 0; cur != null && i < MAX_DEPTH && seen.add(cur); i++) {
            if (shown.contains(cur.externalId())) {
                return cur;
            }
            RiftInstance up = ref(db, cur, "HostObjectIdentifier");
            cur = up != null ? up : ref(db, cur, "IsPartOf");
        }
        return null;
    }

    static Double number(RiftInstance o, String attribute) {
        RiftMemberView a = o.attributes().get(attribute);
        ValueNode v = a == null ? null : a.value();
        Object s = v == null ? null : v.scalar();
        if (!(s instanceof Number)) {
            return null;
        }
        double d = ((Number) s).doubleValue();
        return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
    }

    private static void putDeg(Map<String, Object> e, String key, RiftInstance o, String attribute) {
        Double v = number(o, attribute);
        if (v != null) {
            e.put(key, round(Math.toDegrees(v), 2));
        }
    }

    static void putNumber(Map<String, Object> e, String key, RiftInstance o, String attribute, int decimals) {
        Double v = number(o, attribute);
        if (v != null) {
            e.put(key, round(v, decimals));
        }
    }

    static void putText(Map<String, Object> e, String key, RiftInstance o, String attribute) {
        RiftMemberView a = o == null ? null : o.attributes().get(attribute);
        ValueNode v = a == null ? null : a.value();
        String t = v == null ? null : v.text();
        if (t != null && !t.isEmpty()) {
            e.put(key, t);
        }
    }

    private static double norm(double deg) {
        double d = deg % 360;
        return d < 0 ? d + 360 : d;
    }

    static double round(double v, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }
}
