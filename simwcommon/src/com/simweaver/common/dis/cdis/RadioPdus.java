package com.simweaver.common.dis.cdis;

import com.simweaver.common.dis.DisReader;
import com.simweaver.common.dis.DisWriter;

/**
 * Transmitter (13.20, Table 64), Signal (13.21, Table 65) and Receiver (13.22, Table 66) PDUs ⇄
 * DIS 7.
 */
final class RadioPdus {

    // Transmitter field present flags
    static final int F_RADIO_TYPE = 0;
    static final int F_VTP = 1;
    static final int F_ANTENNA_LOCATION = 2;
    static final int F_RELATIVE_LOCATION = 3;
    static final int F_ANTENNA_PATTERN = 4;
    static final int F_DETAILS = 5;
    static final int F_CRYPTO = 6;
    static final int F_MODULATION_PARAMETERS = 7;

    private static final int G_RADIO_TYPE = 0;
    private static final int G_VTP_COUNT = 1;
    private static final int G_ANTENNA_LOCATION = 2;
    private static final int G_RELATIVE_LOCATION = 3;
    private static final int G_PATTERN_HEADER = 4;
    private static final int G_DETAILS = 5;
    private static final int G_CRYPTO = 6;
    private static final int G_MODULATION_LENGTH = 7;
    private static final int G_MODULATION = 8;
    private static final int G_PATTERN = 9;
    private static final int G_VTPS = 10;
    private static final int GROUPS = 11;

    private static final int U_ALTITUDE = 0;
    private static final int U_RELATIVE = 1;

    /** DIS antenna pattern type 1: beam (the Beam Antenna Pattern record). */
    private static final int PATTERN_BEAM = 1;
    private static final int BEAM_PATTERN_DIS_OCTETS = 40;
    private static final int BEAM_PATTERN_CDIS_OCTETS = CdisRecords.BEAM_ANTENNA_PATTERN_BITS / 8;

    private RadioPdus() {
    }

    // ================================================================== Transmitter (DIS 7: 104 octets + parameter records)

    static boolean encodeTransmitter(DisReader r, BitWriter out, CdisCodec c) {
        r.seek(12);
        int site = r.u16();
        int app = r.u16();
        int ent = r.u16();
        int radio = r.u16();
        int typeAt = r.position();
        r.skip(8);
        int transmitState = r.u8();
        int inputSource = r.u8();
        int vtpCount = r.u16();
        double ax = r.f64();
        double ay = r.f64();
        double az = r.f64();
        float rx = r.f32();
        float ry = r.f32();
        float rz = r.f32();
        int patternType = r.u16();
        int patternLength = r.u16();
        long frequency = r.i64();
        float bandwidth = r.f32();
        float power = r.f32();
        int spread = r.u16();
        int major = r.u16();
        int detail = r.u16();
        int system = r.u16();
        int cryptoSystem = r.u16();
        int cryptoKey = r.u16();
        int modLength = r.u8();
        r.skip(3);
        byte[] modulation = r.bytes(Math.min(modLength, r.remaining()));
        byte[] pattern = r.bytes(Math.min(patternLength, r.remaining()));

        BodyWriter b = new BodyWriter(8, 2, true, GROUPS);
        b.fixed().uvint16(site).uvint16(app).uvint16(ent).uvint16(radio);
        r.seek(typeAt);
        CdisRecords.encEntityType(r, b.group(G_RADIO_TYPE, F_RADIO_TYPE));
        b.fixed().unsigned(transmitState, 2).uvint8(inputSource);
        int vtpAt = 12 + 92 + modulation.length + pattern.length;
        if (vtpCount > 0) {
            b.group(G_VTP_COUNT, F_VTP).uvint8(vtpCount);
        }
        long[] world = CdisRecords.toWorld(ax, ay, az);
        b.unit(U_ALTITUDE, world[3] != 0);
        CdisRecords.encWorld(b.group(G_ANTENNA_LOCATION, F_ANTENNA_LOCATION, U_ALTITUDE), world);
        if (rx != 0 || ry != 0 || rz != 0) {
            boolean meters = CdisRecords.needsMeters(rx, ry, rz);
            b.unit(U_RELATIVE, meters);
            CdisRecords.encEntityCoordinates(b.group(G_RELATIVE_LOCATION, F_RELATIVE_LOCATION, U_RELATIVE), rx, ry, rz, meters);
        }
        boolean beam = patternType == PATTERN_BEAM && pattern.length == BEAM_PATTERN_DIS_OCTETS;
        if (pattern.length > 0) {
            if (!beam && pattern.length > 1023) {
                throw new CdisFormatException("an antenna pattern of " + pattern.length + " octets: C-DIS takes 1023");
            }
            b.group(G_PATTERN_HEADER, F_ANTENNA_PATTERN).unsigned(patternType, 3)
                    .bits(beam ? BEAM_PATTERN_CDIS_OCTETS : pattern.length, 10);
        }
        BitWriter d = b.group(G_DETAILS, F_DETAILS);
        CdisRecords.encFloat(d, unsignedToDouble(frequency), 24, false, 4, false);
        CdisRecords.encFloat(d, bandwidth, 17, false, 4, false);
        d.unsigned(CdisRecords.round(power), 8);
        d.unsigned(spread, 4).unsigned(major, 4).unsigned(detail, 4).unsigned(system, 4);
        if (cryptoSystem != 0 || cryptoKey != 0) {
            b.group(G_CRYPTO, F_CRYPTO).unsigned(cryptoSystem, 4).bits(cryptoKey, 16);
        }
        if (modulation.length > 0) {
            b.group(G_MODULATION_LENGTH, F_MODULATION_PARAMETERS).bits(modulation.length, 8);
            b.group(G_MODULATION, F_MODULATION_PARAMETERS).octets(modulation, 0, modulation.length);
        }
        if (pattern.length > 0) {
            BitWriter p = b.group(G_PATTERN, F_ANTENNA_PATTERN);
            if (beam) {
                CdisRecords.encBeamAntennaPattern(new DisReader(pattern, 0, pattern.length), p);
            } else {
                p.octets(pattern, 0, pattern.length);
            }
        }
        if (vtpCount > 0) {
            r.seek(vtpAt);
            BitWriter v = b.group(G_VTPS, F_VTP);
            for (int i = 0; i < vtpCount; i++) {
                long type = r.u32();
                int length = r.u16();
                if (length < 6 || length - 6 > r.remaining()) {
                    throw new CdisFormatException("a variable transmitter parameter record of " + length + " octets");
                }
                v.bits(type, 32).bits(length, 16);
                byte[] data = r.bytes(length - 6);
                v.octets(data, 0, data.length);
            }
        }
        long key = ObjectStates.entityKey(site, app, ent) << 16 | radio;
        c.stateful(CdisCodec.TRANSMITTER, key, b, false);
        b.writeTo(out);
        return true;
    }

    private static double unsignedToDouble(long v) {
        return v >= 0 ? v : (double) (v >>> 1) * 2.0 + (v & 1);
    }

    static boolean decodeTransmitter(BitReader in, DisWriter w, CdisCodec c) {
        BodyReader b = new BodyReader(in, 8, 2, true, GROUPS);
        int site = (int) in.uvint16();
        int app = (int) in.uvint16();
        int ent = (int) in.uvint16();
        int radio = (int) in.uvint16();
        long key = ObjectStates.entityKey(site, app, ent) << 16 | radio;
        if (!c.attach(CdisCodec.TRANSMITTER, key, b)) {
            return false;
        }
        w.u16(site).u16(app).u16(ent).u16(radio);
        BodyReader.Group g = b.open(G_RADIO_TYPE, F_RADIO_TYPE);
        if (g != null) {
            CdisRecords.decEntityType(g.in, w);
        } else {
            w.zeros(8);
        }
        b.close(g);
        w.u8(in.ubits(2)).u8((int) in.uvint8());
        g = b.open(G_VTP_COUNT, F_VTP);
        int vtpCount = g == null ? 0 : (int) g.in.uvint8();
        b.close(g);
        w.u16(vtpCount);
        g = b.open(G_ANTENNA_LOCATION, F_ANTENNA_LOCATION, U_ALTITUDE);
        if (g != null) {
            CdisRecords.decWorld(g.in, w, g.unit);
        } else {
            w.zeros(24);
        }
        b.close(g);
        g = b.open(G_RELATIVE_LOCATION, F_RELATIVE_LOCATION, U_RELATIVE);
        if (g != null) {
            CdisRecords.decEntityCoordinates(g.in, w, g.unit);
        } else {
            w.zeros(12);
        }
        b.close(g);
        g = b.open(G_PATTERN_HEADER, F_ANTENNA_PATTERN);
        int patternType = 0;
        int patternLength = 0;
        if (g != null) {
            patternType = g.in.ubits(3);
            patternLength = g.in.ubits(10);
        }
        b.close(g);
        boolean beam = patternType == PATTERN_BEAM && patternLength == BEAM_PATTERN_CDIS_OCTETS;
        w.u16(patternType).u16(beam ? BEAM_PATTERN_DIS_OCTETS : patternLength);
        g = b.open(G_DETAILS, F_DETAILS);
        if (g != null) {
            double f = CdisRecords.decFloat(g.in, 24, false, 4, false);
            w.u64(f >= 9.223372036854775807E18 ? Long.MAX_VALUE : (long) Math.rint(f));
            w.f32((float) CdisRecords.decFloat(g.in, 17, false, 4, false));
            w.f32(g.in.ubits(8));
            w.u16(g.in.ubits(4)).u16(g.in.ubits(4)).u16(g.in.ubits(4)).u16(g.in.ubits(4));
        } else {
            w.zeros(24);
        }
        b.close(g);
        g = b.open(G_CRYPTO, F_CRYPTO);
        if (g != null) {
            w.u16(g.in.ubits(4)).u16(g.in.ubits(16));
        } else {
            w.zeros(4);
        }
        b.close(g);
        g = b.open(G_MODULATION_LENGTH, F_MODULATION_PARAMETERS);
        int modLength = g == null ? 0 : g.in.ubits(8);
        b.close(g);
        w.u8(modLength).zeros(3);
        g = b.open(G_MODULATION, F_MODULATION_PARAMETERS);
        if (g != null) {
            w.bytes(g.in.octets(modLength));
        }
        b.close(g);
        g = b.open(G_PATTERN, F_ANTENNA_PATTERN);
        if (g != null) {
            if (beam) {
                CdisRecords.decBeamAntennaPattern(g.in, w);
            } else {
                w.bytes(g.in.octets(patternLength));
            }
        }
        b.close(g);
        g = b.open(G_VTPS, F_VTP);
        if (g != null) {
            for (int i = 0; i < vtpCount; i++) {
                long type = g.in.bits(32);
                int length = g.in.ubits(16);
                if (length < 6) {
                    throw new CdisFormatException("a variable transmitter parameter record of " + length + " octets");
                }
                w.u32(type).u16(length).bytes(g.in.octets(length - 6));
            }
        }
        b.close(g);
        c.commit(CdisCodec.TRANSMITTER, key, b, false);
        return true;
    }

    // ================================================================== Signal (DIS 7: 32 octets + data padded to 32 bits)

    private static final int F_SAMPLE_RATE = 0;
    private static final int F_SAMPLES = 1;

    static boolean encodeSignal(DisReader r, BitWriter out) {
        r.seek(12);
        BodyWriter b = new BodyWriter(2, 0, false, 2);
        BitWriter f = b.fixed();
        CdisRecords.encEntityId(r, f);
        f.uvint16(r.u16());
        int scheme = r.u16();
        f.bits(scheme >>> 14, 2).uvint8(scheme & 0x3FFF);
        f.unsigned(r.u16(), 8);
        long sampleRate = r.u32();
        int dataBits = r.u16();
        int samples = r.u16();
        if (dataBits > 16383) {
            throw new CdisFormatException("Signal data of " + dataBits + " bits: C-DIS takes 16383");
        }
        byte[] data = r.bytes(Math.min((dataBits + 7) / 8, r.remaining()));
        dataBits = Math.min(dataBits, data.length * 8);
        if (sampleRate != 0) {
            b.group(0, F_SAMPLE_RATE).uvint32(sampleRate);
        }
        b.fixed().bits(dataBits, 14);
        if (samples != 0) {
            b.group(1, F_SAMPLES).uvint16(samples);
        }
        b.fixed().bitsOf(data, dataBits);
        b.writeTo(out);
        return true;
    }

    static boolean decodeSignal(BitReader in, DisWriter w) {
        BodyReader b = new BodyReader(in, 2, 0, false, 2);
        CdisRecords.decEntityId(in, w);
        w.u16((int) in.uvint16());
        int cls = in.ubits(2);
        int type = (int) Math.min(0x3FFF, in.uvint8());
        w.u16(cls << 14 | type);
        w.u16(in.ubits(8));
        w.u32(b.has(F_SAMPLE_RATE) ? in.uvint32() : 0);
        int dataBits = in.ubits(14);
        int samples = b.has(F_SAMPLES) ? (int) in.uvint16() : 0;
        w.u16(dataBits).u16(samples);
        BitSlice s = in.slice(dataBits);
        int octets = (dataBits + 31) / 32 * 4;
        byte[] data = new byte[octets];
        System.arraycopy(s.octets(), 0, data, 0, s.octets().length);
        w.bytes(data);
        return true;
    }

    // ================================================================== Receiver (DIS 7: 36 octets)

    static boolean encodeReceiver(DisReader r, BitWriter out) {
        r.seek(12);
        CdisRecords.encEntityId(r, out);
        out.uvint16(r.u16());
        out.unsigned(r.u16(), 2);
        r.skip(2);
        out.signed(CdisRecords.round(r.f32()), 9);
        CdisRecords.encEntityId(r, out);
        out.uvint16(r.u16());
        return true;
    }

    static boolean decodeReceiver(BitReader in, DisWriter w) {
        CdisRecords.decEntityId(in, w);
        w.u16((int) in.uvint16());
        w.u16(in.ubits(2)).zeros(2);
        w.f32(in.signed(9));
        CdisRecords.decEntityId(in, w);
        w.u16((int) in.uvint16());
        return true;
    }
}
