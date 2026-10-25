package com.simweaver.rift.datasources.dis;

import com.simweaver.common.dis.DisWriter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Electromagnetic Emission PDU (DIS 6 and 7) to RPR EmitterSystem / RadarBeam / JammerBeam.
 * No arguments. Prints "N checks, M failures".
 */
public final class EmissionSelfTest {

    private static int checks, failures;

    public static void main(String[] args) {
        // DIS 7: entity 1-3-4, one system (emitter 9200, id 2) with a search beam, a jammer beam,
        // and a deactivated beam; the jammer beam has one jammed target and 8 extra octets
        byte[] pdu = emission(7, 0, new int[][] {
                // id, function, status, targets, extra octets
                {1, 1, 0, 0, 0},
                {2, 12, 0, 1, 8},
                {3, 4, 1, 0, 0}});
        DisToRpr.Emission e = parse(7, pdu);
        check("host and heartbeat", e.host.text().equals("1-3-4") && e.complete);
        check("one system", e.systems.size() == 1 && e.systems.get(0).instanceName.equals("1-3-4/emitter-2")
                && e.systems.get(0).className.equals(DisToRpr.EMITTER_SYSTEM));
        Map<String, byte[]> sys = e.systems.get(0).attributes;
        check("system: host reference", ascii(sys.get("HostObjectIdentifier")).equals("1-3-4"));
        check("system: EmitterType 9200, function 2, index 2", u16(sys.get("EmitterType")) == 9200
                && sys.get("EmitterFunctionCode")[0] == 2 && sys.get("EmitterIndex")[0] == 2);
        List<DisToRpr.Embedded> beams = e.beams.get("1-3-4/emitter-2");
        check("DIS 7: the deactivated beam is left out", beams.size() == 2);
        DisToRpr.Embedded radar = beams.get(0), jammer = beams.get(1);
        check("search beam is a RadarBeam: " + radar.instanceName, radar.className.equals(DisToRpr.RADAR_BEAM)
                && radar.instanceName.equals("1-3-4/emitter-2/beam-1"));
        check("jamming beam is a JammerBeam", jammer.className.equals(DisToRpr.JAMMER_BEAM)
                && jammer.attributes.containsKey("JammedObjectIdentifiers") && !jammer.attributes.containsKey("TrackObjectIdentifiers"));
        check("beam refers to its system", ascii(radar.attributes.get("EmitterSystemIdentifier")).equals("1-3-4/emitter-2"));
        check("azimuth centre and sweep in radians", near(f32(radar.attributes.get("BeamAzimuthCenter")), Math.toRadians(30))
                && near(f32(radar.attributes.get("BeamAzimuthSweep")), Math.toRadians(15)));
        check("frequency and ERP", near(f32(radar.attributes.get("EmissionFrequency")), 3.1e9) && near(f32(radar.attributes.get("EffectiveRadiatedPower")), 70));
        byte[] jammed = jammer.attributes.get("JammedObjectIdentifiers");
        check("jammed target: count 1, \"1-3-5\"", ByteBuffer.wrap(jammed).getInt() == 1
                && new String(jammed, 4, jammed.length - 5, StandardCharsets.US_ASCII).equals("1-3-5"));
        check("extra octets of a longer beam record skipped (nothing after it misread)", beams.size() == 2);

        // DIS 6: the status octet is padding, so beam 3 is kept; a changed-data update
        DisToRpr.Emission e6 = parse(6, emission(6, 1, new int[][] {{1, 1, 0, 0, 0}, {3, 4, 1, 0, 0}}));
        check("DIS 6: status octet ignored, both beams", e6.beams.get("1-3-4/emitter-2").size() == 2);
        check("changed-data update is not complete", !e6.complete);

        // a system with no beams: the system is listed, with an empty beam list (its beams are off)
        DisToRpr.Emission off = parse(7, emission(7, 0, new int[0][]));
        check("system without beams", off.systems.size() == 1 && off.beams.get("1-3-4/emitter-2").isEmpty());

        // truncated PDU: no exception, what could be read is returned
        byte[] cut = java.util.Arrays.copyOf(pdu, pdu.length - 30);
        ByteBuffer.wrap(cut).putShort(8, (short) cut.length);
        boolean ok;
        try {
            DisToRpr.Emission t = parse(7, cut);
            ok = t.systems.size() == 1;
        } catch (RuntimeException ex) {
            ok = ex instanceof DisFormatException;
        }
        check("truncated PDU: no unexpected exception", ok);

        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** One system (emitter name 9200, function 2, id 2) on entity 1-3-4 with the given beams. */
    private static byte[] emission(int version, int stateUpdate, int[][] beams) {
        int beamOctets = 0;
        for (int[] b : beams) beamOctets += 52 + 8 * b[3] + b[4];
        int sysLen = 20 + beamOctets;
        DisWriter w = new DisWriter(28 + sysLen).header(version, 1, 23, 6, 0, 0);
        w.u16(1).u16(3).u16(4);                                  // emitting entity
        w.u16(1).u16(3).u16(77);                                 // event id
        w.u8(stateUpdate).u8(1).u16(0);
        w.u8(sysLen / 4).u8(beams.length).u16(0);
        w.u16(9200).u8(2).u8(2).f32x3(new float[] {0, 0, -10});
        for (int[] b : beams) {
            int len = 52 + 8 * b[3] + b[4];
            w.u8(len / 4).u8(b[0]).u16(0);
            w.f32(3.1e9f).f32(0).f32(70).f32(1000).f32(1);
            w.f32((float) Math.toRadians(30)).f32((float) Math.toRadians(15)).f32(0).f32(0).f32(0);
            w.u8(b[1]).u8(b[3]).u8(0).u8(b[2]).u32(0);
            for (int t = 0; t < b[3]; t++) w.u16(1).u16(3).u16(5).u8(1).u8(1);
            for (int x = 0; x < b[4]; x++) w.u8(0xEE);
        }
        return w.finish();
    }

    private static DisToRpr.Emission parse(int version, byte[] pdu) {
        DisReader r = new DisReader(pdu, 0, pdu.length);
        DisPduHeader h = DisPduHeader.read(r);
        r.seek(DisPduHeader.SIZE);
        return new DisToRpr(version).emission(h, r);
    }

    private static String ascii(byte[] b) {
        int n = 0;
        while (n < b.length && b[n] != 0) n++;
        return new String(b, 0, n, StandardCharsets.US_ASCII);
    }

    private static int u16(byte[] b) {
        return ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
    }

    private static double f32(byte[] b) {
        return ByteBuffer.wrap(b).getFloat();
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) <= Math.abs(b) * 1e-6 + 1e-6;
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
