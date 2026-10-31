package com.simweaver.rift.api;

import com.simweaver.common.geo.Ellipsoid;
import com.simweaver.common.geo.Geodetic;
import com.simweaver.common.geo.Vec3;
import com.simweaver.rift.db.RiftParameter;

import java.util.Arrays;
import java.util.List;

/**
 * The map's layers: which interaction classes flash, which object classes are beams and laser
 * designators, how a location record becomes latitude/longitude, and the laser's range and spot
 * offset. No arguments. Prints "N checks, M failures".
 */
public final class MapOverlaySelfTest {

    private static int checks, failures;

    public static void main(String[] args) {
        // flashes: RPR v2, RPR v3 and subclasses; nothing else
        check("v2 WeaponFire", "fire".equals(RiftMapEvents.kindOf("HLAinteractionRoot.WeaponFire")));
        check("v3 Fire.WeaponFire", "fire".equals(RiftMapEvents.kindOf("HLAinteractionRoot.Fire.WeaponFire")));
        check("v3 DirectedEnergyFire", "fire".equals(RiftMapEvents.kindOf("HLAinteractionRoot.DirectedEnergyFire")));
        check("a subclass of WeaponFire", "fire".equals(RiftMapEvents.kindOf("HLAinteractionRoot.WeaponFire.MyGunFire")));
        check("v2 MunitionDetonation", "detonation".equals(RiftMapEvents.kindOf("HLAinteractionRoot.MunitionDetonation")));
        check("v3 Detonation.MunitionDetonation", "detonation".equals(RiftMapEvents.kindOf("HLAinteractionRoot.Detonation.MunitionDetonation")));
        check("v3 Detonation.Explosion", "detonation".equals(RiftMapEvents.kindOf("HLAinteractionRoot.Detonation.Explosion")));
        check("not: Collision, RadioSignal, FireControl-like names",
                RiftMapEvents.kindOf("HLAinteractionRoot.Collision") == null
                        && RiftMapEvents.kindOf("HLAinteractionRoot.RadioSignal.EncodedAudioRadioSignal") == null
                        && RiftMapEvents.kindOf("HLAinteractionRoot.FireControlUpdate") == null);

        // beams
        check("RadarBeam", "radar".equals(RiftMapBeams.kindOf("HLAobjectRoot.EmitterBeam.RadarBeam")));
        check("JammerBeam", "jammer".equals(RiftMapBeams.kindOf("HLAobjectRoot.EmitterBeam.JammerBeam")));
        check("a subclass of RadarBeam", "radar".equals(RiftMapBeams.kindOf("HLAobjectRoot.EmitterBeam.RadarBeam.Aesa")));
        check("not: EmitterSystem, RadioTransmitter",
                RiftMapBeams.kindOf("HLAobjectRoot.EmbeddedSystem.EmitterSystem") == null
                        && RiftMapBeams.kindOf("HLAobjectRoot.EmbeddedSystem.RadioTransmitter") == null);

        // laser designators: RPR v2 / v3 EmbeddedSystem.Designator and subclasses; nothing else
        check("Designator", RiftMapLasers.isDesignator("HLAobjectRoot.EmbeddedSystem.Designator"));
        check("a subclass of Designator", RiftMapLasers.isDesignator("HLAobjectRoot.EmbeddedSystem.Designator.MyPod"));
        check("not: EmitterSystem, a platform named Designator",
                !RiftMapLasers.isDesignator("HLAobjectRoot.EmbeddedSystem.EmitterSystem")
                        && !RiftMapLasers.isDesignator("HLAobjectRoot.BaseEntity.Designator")
                        && !RiftMapLasers.isDesignator(null));
        check("spot offset: length of the relative position", Math.abs(RiftMapLasers.magnitude(record3("BodyXDistance", 3, "BodyYDistance", 4, "BodyZDistance", 12)) - 13) < 1e-9);
        check("spot offset: none without numbers", Double.isNaN(RiftMapLasers.magnitude(null)));
        Geodetic a1 = new Geodetic(59.33, 18.07, 0), a2 = new Geodetic(59.33, 18.07, 1000);
        check("range: straight up 1000 m", Math.abs(RiftMapLasers.distance(a1, a2) - 1000) < 0.01);
        double oneMinute = RiftMapLasers.distance(new Geodetic(59, 18, 0), new Geodetic(59 + 1 / 60.0, 18, 0));
        check("range: one minute of latitude is about 1853 m", Math.abs(oneMinute - 1853) < 5);

        // location: ECEF X, Y, Z -> latitude/longitude; (0,0,0) and missing fields give none
        Vec3 sthlm = Ellipsoid.WGS84.toEcef(59.33, 18.07, 25);
        Geodetic g = RiftMapEvents.location(param(record(sthlm.getX(), sthlm.getY(), sthlm.getZ())));
        check("Stockholm from ECEF", g != null && Math.abs(g.getLatitude() - 59.33) < 1e-6 && Math.abs(g.getLongitude() - 18.07) < 1e-6
                && Math.abs(g.getHeight() - 25) < 0.01);
        check("(0,0,0) is no location", RiftMapEvents.location(param(record(0, 0, 0))) == null);
        check("no value is no location", RiftMapEvents.location(null) == null);

        // the bounding box, also across 180
        check("box", RiftMapEvents.inBox(new double[] {10, 50, 30, 70}, 59, 18) && !RiftMapEvents.inBox(new double[] {10, 50, 30, 70}, 40, 18));
        check("box across 180", RiftMapEvents.inBox(new double[] {170, -10, -170, 10}, 0, 179) && !RiftMapEvents.inBox(new double[] {170, -10, -170, 10}, 0, 0));

        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static RiftParameter param(ValueNode v) {
        return new RiftParameter(1, "FiringLocation", new byte[0]) {
            @Override
            public ValueNode value() {
                return v;
            }
        };
    }

    private static ValueNode record(double x, double y, double z) {
        return record3("X", x, "Y", y, "Z", z);
    }

    private static ValueNode record3(String n1, double x, String n2, double y, String n3, double z) {
        List<ValueNode.Named> f = Arrays.asList(new ValueNode.Named(n1, num(x)), new ValueNode.Named(n2, num(y)), new ValueNode.Named(n3, num(z)));
        return new ValueNode() {
            public String kind() { return "RECORD"; }
            public String text() { return ""; }
            public Object scalar() { return null; }
            @Override
            public List<Named> fields() { return f; }
        };
    }

    private static ValueNode num(double d) {
        return new ValueNode() {
            public String kind() { return "FLOAT"; }
            public String text() { return String.valueOf(d); }
            public Object scalar() { return d; }
        };
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
