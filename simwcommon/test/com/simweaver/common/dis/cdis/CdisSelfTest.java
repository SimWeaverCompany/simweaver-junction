package com.simweaver.common.dis.cdis;

import com.simweaver.common.dis.DisReader;
import com.simweaver.common.dis.DisWriter;
import com.simweaver.common.geo.Ellipsoid;
import com.simweaver.common.geo.Vec3;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * C-DIS (SISO-STD-023-2024): bit packing, variable integers, custom floats, the header layout,
 * all 22 PDU types DIS 7 → C-DIS → DIS 7 (exact for values at C-DIS precision, within precision
 * for others), partial updates, datagrams with several PDUs, unknown PDUs and timeouts.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.common.dis.cdis.CdisSelfTest</pre>
 */
public final class CdisSelfTest {

    private static int checks;
    private static int failures;

    /** A DIS timestamp whose 6 lowest time bits are 0 (C-DIS keeps 25 of the 31). */
    private static final long TS = ((123456L << 6) << 1) | 1;

    private CdisSelfTest() {
    }

    public static void main(String[] args) {
        bits();
        varints();
        floats();
        header();
        markings();
        world();
        roundTrips();
        precision();
        partialUpdates();
        datagrams();
        errors();
        disRsVectors();
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ================================================================== primitives

    private static void bits() {
        BitWriter w = new BitWriter(1);
        w.bits(0b101, 3).bits(0x1ABC, 13).bits(1, 1);
        check("13-bit value across octets", "1011101010111100".equals(new BitWriter().append(w).toSlice().toString().substring(0, 16)));
        BitReader r = new BitReader(w.toByteArray(), 0, w.bitLength());
        check("read back", r.bits(3) == 5 && r.bits(13) == 0x1ABC && r.flag() && r.remaining() == 0);
        BitWriter s = new BitWriter().signed(-3, 5).signed(100, 5).unsigned(-1, 4).unsigned(99, 4);
        BitReader sr = new BitReader(s.toByteArray(), 0, s.bitLength());
        check("signed and clamped", sr.signed(5) == -3 && sr.signed(5) == 15 && sr.bits(4) == 0 && sr.bits(4) == 15);
        BitWriter p = new BitWriter().bits(0, 20);
        p.patch(3, 0x2AB, 10);
        BitReader pr = new BitReader(p.toByteArray(), 0, 20);
        check("patch", pr.bits(3) == 0 && pr.bits(10) == 0x2AB && pr.bits(7) == 0);
        BitSlice a = new BitWriter().bits(0b1011, 4).toSlice();
        BitSlice b = new BitWriter().bits(0b10111, 5).toSlice();
        check("slices compare by their bits", a.equals(new BitWriter().bits(0b1011, 4).toSlice()) && !a.equals(b)
                && a.hashCode() == new BitWriter().bits(0b1011, 4).toSlice().hashCode());
        BitReader sub = new BitReader(new byte[] {(byte) 0xF0, (byte) 0x0F}, 0, 16).sub(4, 8);
        check("sub reader", sub.bits(8) == 0 && sub.remaining() == 0);
        boolean threw = false;
        try {
            new BitReader(new byte[1], 0, 8).bits(9);
        } catch (CdisFormatException e) {
            threw = true;
        }
        check("reading past the end throws", threw);
    }

    private static void varints() {
        check("UVINT8 4 bits up to 15", VarInt.UVINT8.sizeOf(15) == 5 && VarInt.UVINT8.sizeOf(16) == 9);
        check("UVINT16 sizes 8/11/14/16", VarInt.UVINT16.sizeOf(255) == 10 && VarInt.UVINT16.sizeOf(256) == 13
                && VarInt.UVINT16.sizeOf(2047) == 13 && VarInt.UVINT16.sizeOf(16383) == 16 && VarInt.UVINT16.sizeOf(16384) == 18);
        check("UVINT32 sizes 8/15/18/32", VarInt.UVINT32.sizeOf(255) == 10 && VarInt.UVINT32.sizeOf(32767) == 17
                && VarInt.UVINT32.sizeOf(262143) == 20 && VarInt.UVINT32.sizeOf(262144) == 34);
        check("SVINT12 +3/-4 in 3 bits", VarInt.SVINT12.sizeOf(3) == 5 && VarInt.SVINT12.sizeOf(-4) == 5
                && VarInt.SVINT12.sizeOf(4) == 8 && VarInt.SVINT12.sizeOf(-2048) == 14);
        check("SVINT13 sizes 5/7/10/13", VarInt.SVINT13.sizeOf(15) == 7 && VarInt.SVINT13.sizeOf(16) == 9
                && VarInt.SVINT13.sizeOf(63) == 9 && VarInt.SVINT13.sizeOf(64) == 12 && VarInt.SVINT13.sizeOf(512) == 15);
        check("SVINT14 sizes 4/7/9/14", VarInt.SVINT14.sizeOf(7) == 6 && VarInt.SVINT14.sizeOf(8) == 9
                && VarInt.SVINT14.sizeOf(255) == 11 && VarInt.SVINT14.sizeOf(256) == 16);
        check("SVINT16 sizes 8/12/13/16", VarInt.SVINT16.sizeOf(127) == 10 && VarInt.SVINT16.sizeOf(2047) == 14
                && VarInt.SVINT16.sizeOf(4095) == 15 && VarInt.SVINT16.sizeOf(4096) == 18);
        check("SVINT24 sizes 16/19/21/24", VarInt.SVINT24.sizeOf(32767) == 18 && VarInt.SVINT24.sizeOf(262143) == 21
                && VarInt.SVINT24.sizeOf(1048575) == 23 && VarInt.SVINT24.sizeOf(-8388608) == 26);
        BitWriter w = new BitWriter();
        VarInt.UVINT8.write(w, 9);
        check("UVINT8 9 = flag 0 + 1001", "01001".equals(w.toSlice().toString()));
        w = new BitWriter();
        VarInt.SVINT12.write(w, -1);
        check("SVINT12 -1 = flag 00 + 111", "00111".equals(w.toSlice().toString()));
        w = new BitWriter();
        VarInt.UVINT16.write(w, 300);
        check("UVINT16 300 = flag 01 + 11 bits", "0100100101100".equals(w.toSlice().toString()));
        w = new BitWriter();
        VarInt.UVINT8.write(w, 999);
        check("out of range clamps", new BitReader(w.toByteArray(), 0, w.bitLength()).uvint8() == 255);
        Random rnd = new Random(7);
        boolean ok = true;
        for (VarInt v : VarInt.values()) {
            for (int i = 0; i < 2000; i++) {
                long x = v.min() + (long) (rnd.nextDouble() * (v.max() - v.min() + 1));
                BitWriter bw = new BitWriter();
                v.write(bw, x);
                BitReader br = new BitReader(bw.toByteArray(), 0, bw.bitLength());
                ok &= v.read(br) == x && br.remaining() == 0 && bw.bitLength() == v.sizeOf(x);
            }
        }
        check("random values of every VARINT round trip in their size", ok);
    }

    private static void floats() {
        long[] pw = CdisRecords.toFloat(1_234_567, 14, false, 3, true);
        check("1 234 567 µs: 12 346 × 10^2 (the largest mantissa, rounded)", pw[0] == 12346 && pw[1] == 2);
        long[] small = CdisRecords.toFloat(0.5, 14, false, 3, true);
        check("0.5 µs: 5000 × 10^-4", small[0] == 5000 && small[1] == -4);
        long[] f = CdisRecords.toFloat(9.375e9, 17, false, 4, false);
        check("9.375 GHz: 93 750 × 10^5", f[0] == 93750 && f[1] == 5);
        long[] big = CdisRecords.toFloat(1e30, 17, false, 4, false);
        check("too large: the largest value", big[0] == 131071 && big[1] == 15);
        long[] neg = CdisRecords.toFloat(-12.5, 15, true, 3, true);
        check("signed: -12.5 = -12500 × 10^-3", neg[0] == -12500 && neg[1] == -3);
        check("unsigned takes no negatives", CdisRecords.toFloat(-3, 14, false, 3, true)[0] == 0);
        check("timestamp: 25 of 31 time bits", CdisRecords.timestampToCdis(TS) == ((123456L << 1) | 1)
                && CdisRecords.timestampToDis(CdisRecords.timestampToCdis(TS)) == TS);
    }

    /** The header and a bodyless Comment PDU, bit by bit. */
    private static void header() {
        byte[] dis = new DisWriter(32).header(7, 1, 22, 5, TS, 0x40).entityId(1, 2, 3).entityId(4, 5, 6).u32(0).u32(0).finish();
        CdisCodec c = new CdisCodec(CdisSettings.DEFAULTS);
        byte[] cdis = c.encode(dis, 0, dis.length, 0);
        String expected = "01" + "00001" + bin(22, 8) + bin((123456L << 1) | 1, 26) + "LLLLLLLLLLLLLL" + bin(0x40, 8)
                + "00" + uv16(1) + uv16(2) + uv16(3) + uv16(4) + uv16(5) + uv16(6);
        int len = expected.length();
        expected = expected.replace("LLLLLLLLLLLLLL", bin(len, 14));
        String got = new BitReader(cdis, 0, cdis.length * 8).slice(len).toString();
        check("header and Comment PDU bits: " + got, expected.equals(got));
        check("one PDU per datagram, zero padded", cdis.length == (len + 7) / 8);
        List<byte[]> back = c.decode(cdis, 0, cdis.length, 0);
        check("decoded back", back.size() == 1 && Arrays.equals(back.get(0), dis));
    }

    private static String bin(long v, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = n - 1; i >= 0; i--) {
            sb.append(((v >>> i) & 1) != 0 ? '1' : '0');
        }
        return sb.toString();
    }

    private static String uv16(int v) {
        return "00" + bin(v, 8);
    }

    private static void markings() {
        BitWriter w = new BitWriter();
        CdisRecords.encMarking(w, Arrays.copyOf("viper1".getBytes(), 11));
        check("lower case to upper, 5-bit characters: 4 + 1 + 6 × 5 bits", w.bitLength() == 35);
        DisWriter d = new DisWriter(12);
        CdisRecords.decMarking(new BitReader(w.toByteArray(), 0, w.bitLength()), d);
        check("VIPER1", "VIPER1".equals(new String(d.toBytes(), 1, 6)) && d.toBytes()[0] == 1 && d.toBytes()[7] == 0);
        w = new BitWriter();
        CdisRecords.encMarking(w, Arrays.copyOf("JAKE 7".getBytes(), 11));
        check("J, K and space need 6-bit characters", w.bitLength() == 5 + 6 * 6);
        w = new BitWriter();
        CdisRecords.encMarking(w, Arrays.copyOf("A^b".getBytes(), 11));
        d = new DisWriter(12);
        CdisRecords.decMarking(new BitReader(w.toByteArray(), 0, w.bitLength()), d);
        check("unsupported characters become *", "A*B".equals(new String(d.toBytes(), 1, 3)));
        w = new BitWriter();
        CdisRecords.encMarking(w, new byte[11]);
        check("an empty marking: 5 bits", w.bitLength() == 5);
    }

    private static void world() {
        double[] llh = new double[3];
        long[] wc = CdisRecords.toWorld(0, 0, 0);
        check("ECEF origin: the special altitude", wc[2] == CdisRecords.ALTITUDE_ECEF_ORIGIN);
        Vec3 sat = Ellipsoid.WGS84.toEcefRadians(0.5, 0.2, 400_000);
        wc = CdisRecords.toWorld(sat.getX(), sat.getY(), sat.getZ());
        check("400 km: dekameters", wc[3] == 1 && wc[2] == 40_000);
        Vec3 low = Ellipsoid.WGS84.toEcefRadians(0.5, 0.2, 83_886.07);
        wc = CdisRecords.toWorld(low.getX(), low.getY(), low.getZ());
        check("83 886.07 m: still centimeters", wc[3] == 0 && wc[2] == 8_388_607);
        Vec3 deep = Ellipsoid.WGS84.toEcefRadians(-0.3, -2.5, -120.25);
        wc = CdisRecords.toWorld(deep.getX(), deep.getY(), deep.getZ());
        BitWriter w = new BitWriter();
        CdisRecords.encWorld(w, wc);
        DisWriter d = new DisWriter(24);
        CdisRecords.decWorld(new BitReader(w.toByteArray(), 0, w.bitLength()), d, false);
        DisReader r = new DisReader(d.toBytes(), 0, 24);
        Ellipsoid.WGS84.toGeodeticRadians(r.f64(), r.f64(), r.f64(), llh);
        check("height above the ellipsoid kept to the centimeter", Math.abs(llh[2] + 120.25) < 0.006
                && Math.abs(llh[0] + 0.3) < 2e-9 && Math.abs(llh[1] + 2.5) < 2e-9);
        Vec3 dateLine = Ellipsoid.WGS84.toEcefRadians(0.1, Math.PI, 10);
        wc = CdisRecords.toWorld(dateLine.getX(), dateLine.getY(), dateLine.getZ());
        check("longitude ±180° fits", Math.abs(wc[1]) <= (1L << 31) - 1);
    }

    // ================================================================== exact round trips

    private static final double ANG13 = ((1 << 12) - 1) / Math.PI;
    private static final double ANGVEL = ((1 << 11) - 1) / (4 * Math.PI);
    private static final double LAT = ((1L << 30) - 1) / (Math.PI / 2);
    private static final double LON = ((1L << 31) - 1) / Math.PI;

    private static float ang(int k) {
        return (float) (k / ANG13);
    }

    private static float tenth(int k) {
        return (float) (k / 10.0);
    }

    private static float cm(int k) {
        return (float) (k / 100.0);
    }

    /** ECEF of a location at C-DIS precision. */
    private static double[] ecef(long lat, long lon, long centimeters) {
        Vec3 e = Ellipsoid.WGS84.toEcefRadians(lat / LAT, lon / LON, centimeters / 100.0);
        return new double[] {e.getX(), e.getY(), e.getZ()};
    }

    private static DisWriter pdu(int type, int family) {
        return new DisWriter(256).header(7, 9, type, family, TS, 0);
    }

    private static void roundTrips() {
        double[] loc = ecef(380_000_000L, 120_000_000L, 12_345);
        // Entity State, dead reckoning 4 (velocity, acceleration, rotation), two VP records
        DisWriter es = pdu(1, 1).entityId(1, 2, 3).u8(1).u8(2);
        es.u8(1).u8(2).u16(225).u8(1).u8(1).u8(3).u8(0);
        es.u8(1).u8(2).u16(222).u8(2).u8(1).u8(1).u8(0);
        es.f32(tenth(125)).f32(tenth(-34)).f32(tenth(1));
        es.f64(loc[0]).f64(loc[1]).f64(loc[2]);
        es.f32(ang(1234)).f32(ang(-200)).f32(ang(4095));
        es.u32(0x00400ABCL).u8(4);
        for (int i = 1; i <= 15; i++) {
            es.u8(i);
        }
        es.f32(tenth(5)).f32(tenth(-98)).f32(tenth(0));
        es.f32((float) (10 / ANGVEL)).f32((float) (-3 / ANGVEL)).f32((float) (2047 / ANGVEL));
        es.u8(1).bytes(Arrays.copyOf("VIPER1".getBytes(), 11)).u32(5);
        es.u8(0).u8(3).u16(0).u32(4107).f32(1.25f).zeros(4);
        es.u8(1).u8(0).u16(0).u32(896).u8(2).u8(1).u16(225).u8(2).u8(3).u8(0).u8(0);
        roundTrip("Entity State", es.finish());

        // Static entity, no velocity: the velocity field is not sent and comes back 0
        DisWriter st = pdu(1, 1).entityId(1, 2, 5).u8(2).u8(0);
        st.u8(1).u8(1).u16(225).u8(1).u8(1).u8(1).u8(0).zeros(8).zeros(12);
        st.f64(loc[0]).f64(loc[1]).f64(loc[2]).f32(0).f32(0).f32(ang(9)).u32(0).u8(1).zeros(15).zeros(24);
        st.u8(1).zeros(11).u32(0);
        byte[] staticEs = st.finish();
        roundTrip("Entity State, static", staticEs);

        DisWriter fire = pdu(2, 2).entityId(1, 2, 3).entityId(1, 2, 4).entityId(1, 2, 99).entityId(1, 2, 7).u32(77);
        fire.f64(loc[0]).f64(loc[1]).f64(loc[2]);
        fire.u8(2).u8(2).u16(225).u8(2).u8(1).u8(0).u8(0).u16(1000).u16(100).u16(1).u16(0);
        fire.f32(tenth(2500)).f32(tenth(10)).f32(tenth(-7)).f32(1500);
        roundTrip("Fire", fire.finish());

        DisWriter fire2 = pdu(2, 2).entityId(1, 2, 3).zeros(6).entityId(1, 2, 99).entityId(1, 2, 8).u32(0);
        fire2.f64(loc[0]).f64(loc[1]).f64(loc[2]);
        fire2.u8(2).u8(9).u16(225).u8(2).u8(1).u8(0).u8(0).zeros(8);
        fire2.f32(0).f32(0).f32(0).f32(0);
        roundTrip("Fire, no mission index, descriptor details or range", fire2.finish());

        DisWriter det = pdu(3, 2).entityId(1, 2, 3).entityId(1, 2, 4).entityId(1, 2, 99).entityId(1, 2, 7);
        det.f32(tenth(-100)).f32(tenth(30)).f32(tenth(-250));
        det.f64(loc[0]).f64(loc[1]).f64(loc[2]);
        det.u8(2).u8(2).u16(225).u8(2).u8(1).u8(0).u8(0).u16(1000).u16(100).u16(1).u16(0);
        det.f32(cm(150)).f32(cm(-20)).f32(cm(5)).u8(1).u8(1).zeros(2);
        det.u8(0).u8(1).u16(0).u32(4096).f32(-0.5f).zeros(4);
        roundTrip("Detonation", det.finish());

        DisWriter det2 = pdu(3, 2).entityId(1, 2, 3).zeros(6).entityId(1, 2, 99).entityId(1, 2, 7);
        det2.f32(0).f32(0).f32(0).f64(loc[0]).f64(loc[1]).f64(loc[2]);
        det2.u8(2).u8(2).u16(225).u8(2).u8(1).u8(0).u8(0).zeros(8);
        det2.f32(1000).f32(-2000).f32(5).u8(5).u8(0).zeros(2);
        roundTrip("Detonation, meters", det2.finish());

        DisWriter col = pdu(4, 1).entityId(1, 2, 3).entityId(1, 2, 4).entityId(1, 2, 10).u8(1).u8(0);
        col.f32(tenth(30)).f32(tenth(0)).f32(tenth(-12)).f32(1234.5f).f32(cm(100)).f32(cm(-50)).f32(cm(0));
        roundTrip("Collision", col.finish());

        siman();
        emission(loc);
        designator(loc);
        radio(loc);
        iff();
    }

    private static void siman() {
        roundTrip("Create Entity", pdu(11, 5).entityId(1, 2, 0).entityId(3, 4, 0).u32(42).finish());
        roundTrip("Remove Entity", pdu(12, 5).entityId(1, 2, 0).entityId(3, 4, 0).u32(43).finish());
        roundTrip("Start/Resume", pdu(13, 5).entityId(1, 2, 0).entityId(0xFFFF, 0xFFFF, 0xFFFF)
                .u32(5).u32(123456).u32(0).u32(999).u32(44).finish());
        roundTrip("Stop/Freeze", pdu(14, 5).entityId(1, 2, 0).entityId(3, 4, 0).u32(5).u32(1).u8(2).u8(1).zeros(2)
                .u32(45).finish());
        roundTrip("Acknowledge", pdu(15, 5).entityId(1, 2, 0).entityId(3, 4, 0).u16(1).u16(1).u32(46).finish());
        int[][] kinds = {{16, 7}, {17, 1}, {19, 0}, {20, 0}, {21, 3}, {22, -1}};
        String[] names = {"Action Request", "Action Response", "Set Data", "Data", "Event Report", "Comment"};
        for (int i = 0; i < kinds.length; i++) {
            int type = kinds[i][0];
            DisWriter w = pdu(type, 5).entityId(1, 2, 0).entityId(3, 4, 0);
            if (type == 16 || type == 17) {
                w.u32(47).u32(kinds[i][1]);
            } else if (type != 22) {
                w.u32(type == 21 ? kinds[i][1] : 48).zeros(4);
            }
            w.u32(2).u32(1);
            w.u32(10000).u32(0xDEADBEEFL).u32(10001).u32(7);
            w.u32(20000).u32(24).bytes(Arrays.copyOf("ABC".getBytes(), 8));
            roundTrip(names[i], w.finish());
        }
        roundTrip("Comment, long text", comment("This is a longer comment of more than one word."));
        roundTrip("Comment, no datums", pdu(22, 5).entityId(1, 2, 0).entityId(3, 4, 0).u32(0).u32(0).finish());
        long interval = ((5000L << 6) << 1);
        roundTrip("Data Query", pdu(18, 5).entityId(1, 2, 0).entityId(3, 4, 0).u32(49).u32(interval).u32(2).u32(1)
                .u32(10000).u32(10001).u32(20000).finish());
    }

    private static byte[] comment(String text) {
        byte[] t = text.getBytes();
        int octets = (t.length * 8 + 63) / 64 * 8;
        return pdu(22, 5).entityId(1, 2, 0).entityId(3, 4, 0).u32(0).u32(1).u32(50000).u32(t.length * 8L)
                .bytes(Arrays.copyOf(t, octets)).finish();
    }

    private static final double SYNC = 1023 / 100.0;

    private static void beam(DisWriter w, int id, float freq, int[] bd, int status, int[] jam, int[][] targets) {
        w.u8(13 + 2 * targets.length).u8(id).u16(7);
        w.f32(freq).f32(1e6f).f32(60).f32(1000).f32(1.5f);
        w.f32(ang(bd[0])).f32(ang(bd[1])).f32(ang(bd[2])).f32(ang(bd[3])).f32((float) (bd[4] / SYNC));
        w.u8(2).u8(targets.length).u8(0).u8(status).u8(jam[0]).u8(jam[1]).u8(jam[2]).u8(jam[3]);
        for (int[] t : targets) {
            w.u16(t[0]).u16(t[1]).u16(t[2]).u8(t[3]).u8(t[4]);
        }
    }

    private static byte[] emissionPdu(float freq1, float x) {
        DisWriter ee = pdu(23, 6).entityId(1, 2, 3).entityId(1, 2, 8).u8(0).u8(2).zeros(2);
        // system 1: two beams sharing their fundamental parameters
        ee.u8((20 + 52 + 16 + 52 + 8) / 4).u8(2).zeros(2).u16(1000).u8(2).u8(1).f32(x).f32(0).f32(-1);
        beam(ee, 1, freq1, new int[] {100, 2000, -50, 400, 512}, 0, new int[4], new int[][] {{1, 2, 10, 0, 0}, {1, 3, 11, 0, 0}});
        beam(ee, 2, freq1, new int[] {-100, 2000, 50, 400, 0}, 1, new int[] {1, 2, 3, 4}, new int[][] {{1, 2, 10, 1, 1}});
        // system 2: one beam, no location
        ee.u8((20 + 52) / 4).u8(1).zeros(2).u16(2000).u8(5).u8(2).f32(0).f32(0).f32(0);
        beam(ee, 1, 3e9f, new int[] {0, 0, 0, 0, 1023}, 0, new int[4], new int[0][]);
        return ee.finish();
    }

    private static void emission(double[] loc) {
        roundTrip("Electromagnetic Emission", emissionPdu((float) 9.375e9, 2));
        DisWriter empty = pdu(23, 6).entityId(1, 2, 3).entityId(1, 2, 9).u8(1).u8(0).zeros(2);
        roundTrip("Electromagnetic Emission, no systems", empty.finish());
    }

    private static void designator(double[] loc) {
        DisWriter d = pdu(24, 6).entityId(1, 2, 3).u16(1).entityId(1, 2, 4).u16(1688).f32(100).f32(1.064f);
        d.f32(cm(10)).f32(cm(-20)).f32(cm(30)).f64(loc[0]).f64(loc[1]).f64(loc[2]).u8(2).zeros(3);
        d.f32(tenth(1)).f32(tenth(2)).f32(tenth(3));
        roundTrip("Designator", d.finish());
        DisWriter d2 = pdu(24, 6).entityId(1, 2, 3).u16(1).zeros(6).u16(1688).f32(5).f32(1.5f).zeros(12);
        d2.f64(loc[0]).f64(loc[1]).f64(loc[2]).zeros(16);
        roundTrip("Designator, no designated entity, no dead reckoning", d2.finish());
    }

    private static void radio(double[] loc) {
        DisWriter t = pdu(25, 4).entityId(1, 2, 3).u16(1).u8(7).u8(1).u16(225).u8(4).u8(1).u8(0).u8(0);
        t.u8(2).u8(1).u16(1).f64(loc[0]).f64(loc[1]).f64(loc[2]).f32(cm(50)).f32(cm(0)).f32(cm(-120));
        t.u16(1).u16(40).u64(243_000_000L).f32(25000).f32(40).u16(1).u16(2).u16(3).u16(4).u16(1).u16(0x8005);
        t.u8(8).zeros(3).bytes(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        t.f32(ang(100)).f32(ang(-100)).f32(ang(0)).f32(ang(300)).f32(ang(150)).u8(1).zeros(3).f32(1).f32(0).f32(ang(-7)).zeros(4);
        t.u32(3000).u16(16).bytes(new byte[] {9, 8, 7, 6, 5, 4, 3, 2, 1, 0});
        roundTrip("Transmitter", t.finish());
        DisWriter t2 = pdu(25, 4).entityId(1, 2, 3).u16(2).u8(7).u8(1).u16(225).u8(4).u8(1).u8(0).u8(0);
        t2.u8(1).u8(0).u16(0).f64(0).f64(0).f64(0).zeros(12).u16(0).u16(0).u64(30_000_000L).f32(0).f32(10).zeros(8)
                .zeros(4).u8(0).zeros(3);
        roundTrip("Transmitter, minimal, antenna at the ECEF origin", t2.finish());
        byte[] audio = new byte[40];
        for (int i = 0; i < audio.length; i++) {
            audio[i] = (byte) (i * 7);
        }
        DisWriter s = pdu(26, 4).entityId(1, 2, 3).u16(1).u16(4).u16(0).u32(8000).u16(320).u16(40).bytes(audio);
        roundTrip("Signal", s.finish());
        DisWriter s2 = pdu(26, 4).entityId(1, 2, 3).u16(1).u16(1 << 14 | 5).u16(100).u32(0).u16(20).u16(0)
                .bytes(new byte[] {(byte) 0xAB, (byte) 0xC0, 0, 0});
        roundTrip("Signal, raw binary of 20 bits", s2.finish());
        DisWriter r = pdu(27, 4).entityId(1, 2, 3).u16(1).u16(1).zeros(2).f32(-100).entityId(1, 2, 4).u16(2);
        roundTrip("Receiver", r.finish());
    }

    private static byte[] iffPdu(int systemType, int infoLayers, int[] layerNumbers, byte[][] layers, int param1) {
        DisWriter w = pdu(28, 6).entityId(1, 2, 3).entityId(1, 2, 9).f32(cm(120)).f32(cm(0)).f32(cm(-35));
        w.u16(systemType).u16(2).u8(1).u8(0x41).u8(3).u8(0x10);
        w.u8(0x0F).u8(0x02).u8(infoLayers).u8(0).u16(param1).u16(0).u16(0).u16(7).u16(0).u16(0);
        for (int i = 0; i < layers.length; i++) {
            w.u8(layerNumbers[i]).u8(0).u16(layers[i].length + 4).bytes(layers[i]);
        }
        return w.finish();
    }

    private static void iff() {
        DisWriter l2 = new DisWriter(64);
        l2.f32(ang(10)).f32(ang(4095)).f32(ang(-10)).f32(ang(100)).f32((float) (300 / SYNC)).u8(1).u8(2).u16(1);
        l2.f32(50).f32(1.03e9f).f32(100).f32(tenth(8)).f32(10).u8(3).u8(1).u8(2).u8(3);
        DisWriter l3t = new DisWriter(64);
        l3t.u16(1).u16(2).u16(0x8001).u16(1234).u32(0xF0F0F0F0L).u16(0x0ABC).u16(77).u8(3).u8(2).u8(7).zeros(1);
        l3t.zeros(2).u16(1).u32(1000).u16(12).bytes(new byte[] {1, 2, 3, 4, 5, 6});
        DisWriter l5 = new DisWriter(64);
        l5.u16(1).u16(2).zeros(2).u8(0x0C).u8(1).zeros(2).u16(0);
        int info = (1 << 1) | (1 << 2) | (1 << 3) | (1 << 5);
        roundTrip("IFF transponder, layers 1, 2, 3 and 5",
                iffPdu(1, info, new int[] {2, 3, 5}, new byte[][] {l2.toBytes(), l3t.toBytes(), l5.toBytes()}, 0x1234));

        DisWriter l3i = new DisWriter(64);
        l3i.u16(1).u16(2).u8(0x81).zeros(3).u32(0x12345678L).entityId(3, 4, 5).zeros(2).zeros(2).u16(0);
        DisWriter l4i = new DisWriter(64);
        l4i.u16(1).u16(2).u8(0x42).zeros(1).u8(0x1F).zeros(21).zeros(2).u16(0);
        roundTrip("IFF Mode 5 interrogator, layers 3 and 4",
                iffPdu(7, (1 << 1) | (1 << 3) | (1 << 4), new int[] {3, 4}, new byte[][] {l3i.toBytes(), l4i.toBytes()}, 0));

        DisWriter l4t = new DisWriter(64);
        l4t.u16(1).u16(2).u16(0x8003).u8(0x0E).u8(2).bytes(Arrays.copyOf("NC1234".getBytes(), 8)).u32(0xABCDEF);
        l4t.u8(1).u8(4).u16(3500).u8(5).zeros(3).zeros(2).u16(1).u32(2000).u16(8).bytes(new byte[] {7, 7});
        roundTrip("IFF Mode S transponder, layer 4",
                iffPdu(11, (1 << 1) | (1 << 4), new int[] {4}, new byte[][] {l4t.toBytes()}, 0));
        roundTrip("IFF, layer 1 only", iffPdu(1, 1 << 1, new int[0], new byte[0][], 0));
    }

    private static int totalCdis;
    private static int totalDis;

    private static void roundTrip(String what, byte[] dis) {
        CdisCodec c = new CdisCodec(CdisSettings.DEFAULTS);
        byte[] cdis = c.encode(dis, 0, dis.length, 1000);
        if (cdis == null) {
            check(what + ": encodes (" + c.stats().getLastError() + ")", false);
            return;
        }
        List<byte[]> back = c.decode(cdis, 0, cdis.length, 1000);
        boolean same = back.size() == 1 && Arrays.equals(back.get(0), dis);
        totalCdis += cdis.length;
        totalDis += dis.length;
        check(what + ": " + dis.length + " → " + cdis.length + " octets → the same DIS PDU"
                + (same ? "" : diff(dis, back.isEmpty() ? new byte[0] : back.get(0)) + " " + c.stats().getLastError()), same);
    }

    private static String diff(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            if (a[i] != b[i]) {
                return " (first difference at octet " + i + ": " + (a[i] & 0xFF) + " vs " + (b[i] & 0xFF) + ", lengths "
                        + a.length + "/" + b.length + ")";
            }
        }
        return " (lengths " + a.length + "/" + b.length + ")";
    }

    // ================================================================== arbitrary values: within precision

    private static void precision() {
        Random rnd = new Random(11);
        boolean ok = true;
        double worstPos = 0;
        double worstVel = 0;
        double worstAng = 0;
        for (int i = 0; i < 500; i++) {
            double lat = (rnd.nextDouble() - 0.5) * Math.PI * 0.999;
            double lon = (rnd.nextDouble() - 0.5) * 2 * Math.PI;
            double h = rnd.nextDouble() * 20000 - 500;
            Vec3 e = Ellipsoid.WGS84.toEcefRadians(lat, lon, h);
            float[] v = {(float) (rnd.nextGaussian() * 300), (float) (rnd.nextGaussian() * 300), (float) (rnd.nextGaussian() * 30)};
            float[] o = {(float) ((rnd.nextDouble() - 0.5) * 2 * Math.PI), (float) ((rnd.nextDouble() - 0.5) * Math.PI),
                    (float) ((rnd.nextDouble() - 0.5) * 2 * Math.PI)};
            DisWriter es = pdu(1, 1).entityId(1, 2, 3).u8(1).u8(0).u8(1).u8(2).u16(225).u8(1).u8(1).u8(3).u8(0).zeros(8);
            es.f32(v[0]).f32(v[1]).f32(v[2]).f64(e.getX()).f64(e.getY()).f64(e.getZ()).f32(o[0]).f32(o[1]).f32(o[2]);
            es.u32(0).u8(2).zeros(15).zeros(24).u8(1).zeros(11).u32(0);
            byte[] dis = es.finish();
            CdisCodec c = new CdisCodec(CdisSettings.DEFAULTS);
            byte[] cdis = c.encode(dis, 0, dis.length, 0);
            DisReader r = new DisReader(c.decode(cdis, 0, cdis.length, 0).get(0), 0, 144);
            r.seek(36);
            for (int k = 0; k < 3; k++) {
                worstVel = Math.max(worstVel, Math.abs(r.f32() - v[k]));
            }
            double dx = r.f64() - e.getX();
            double dy = r.f64() - e.getY();
            double dz = r.f64() - e.getZ();
            worstPos = Math.max(worstPos, Math.sqrt(dx * dx + dy * dy + dz * dz));
            for (int k = 0; k < 3; k++) {
                double d = Math.abs(CdisRecords.normalize(r.f32() - o[k]));
                worstAng = Math.max(worstAng, d);
            }
        }
        ok &= worstPos < 0.02 && worstVel <= 0.05 + 1e-3 && worstAng <= 0.5 / ANG13 + 1e-6;
        check(String.format("arbitrary entity states within precision: position %.4f m, velocity %.3f m/s, angle %.5f rad",
                worstPos, worstVel, worstAng), ok);

        // clamping: 5000 m/s does not fit SVINT16 decimeters
        DisWriter fast = pdu(1, 1).entityId(1, 2, 3).u8(1).u8(0).zeros(16).f32(5000).f32(-5000).f32(0);
        double[] l = ecef(0, 0, 0);
        fast.f64(l[0]).f64(l[1]).f64(l[2]).zeros(12).u32(0).u8(2).zeros(15).zeros(24).u8(1).zeros(11).u32(0);
        byte[] dis = fast.finish();
        CdisCodec c = new CdisCodec(CdisSettings.DEFAULTS);
        byte[] cdis = c.encode(dis, 0, dis.length, 0);
        DisReader r = new DisReader(c.decode(cdis, 0, cdis.length, 0).get(0), 0, 144);
        r.seek(36);
        check("linear values out of range are clamped", Math.abs(r.f32() - 3276.7f) < 1e-3 && Math.abs(r.f32() + 3276.8f) < 1e-3);
        check(String.format("all exact round trips: %d DIS octets → %d C-DIS octets (%.0f %%)", totalDis, totalCdis,
                100.0 * totalCdis / Math.max(1, totalDis)), totalCdis < totalDis);
    }

    // ================================================================== partial updates

    private static byte[] es(int entity, long lat, float vx, long appearance, String marking) {
        double[] loc = ecef(lat, 120_000_000L, 1000);
        DisWriter w = pdu(1, 1).entityId(1, 2, entity).u8(1).u8(0);
        w.u8(1).u8(2).u16(225).u8(1).u8(1).u8(3).u8(0).zeros(8);
        w.f32(vx).f32(0).f32(0).f64(loc[0]).f64(loc[1]).f64(loc[2]).f32(ang(10)).f32(0).f32(0);
        w.u32(appearance).u8(vx == 0 ? 1 : 2).zeros(15).zeros(24).u8(1).bytes(Arrays.copyOf(marking.getBytes(), 11)).u32(0);
        return w.finish();
    }

    private static boolean fullFlag(byte[] cdis) {
        BitReader r = new BitReader(cdis, 0, cdis.length * 8);
        r.bits(2);
        r.uvint8();
        r.bits(8 + 26 + 14 + 8);
        r.bits(13 + 1);
        return r.flag();
    }

    private static void partialUpdates() {
        CdisSettings partial = CdisSettings.DEFAULTS.withUpdateMode(CdisSettings.UpdateMode.PARTIAL);
        CdisCodec tx = new CdisCodec(partial);
        CdisCodec rx = new CdisCodec(CdisSettings.DEFAULTS);
        CdisCodec late = new CdisCodec(CdisSettings.DEFAULTS);
        byte[] a = es(7, 380_000_000L, tenth(100), 0, "EAGLE");
        byte[] c1 = tx.encode(a, 0, a.length, 0);
        check("partial mode: the first update is a full update", fullFlag(c1));
        check("decoded", Arrays.equals(rx.decode(c1, 0, c1.length, 0).get(0), a));
        byte[] c2 = tx.encode(a, 0, a.length, 1000);
        check("nothing changed: a partial update of " + c2.length + " octets (full: " + c1.length + ")", !fullFlag(c2) && c2.length < c1.length / 2);
        check("decoded to the whole PDU", Arrays.equals(rx.decode(c2, 0, c2.length, 1000).get(0), a));
        byte[] b = es(7, 380_000_100L, tenth(100), 0, "EAGLE");
        byte[] c3 = tx.encode(b, 0, b.length, 2000);
        check("only the location changed: partial", !fullFlag(c3) && c3.length < c1.length);
        check("decoded with the new location", Arrays.equals(rx.decode(c3, 0, c3.length, 2000).get(0), b));
        check("a decoder that missed the full update drops partial updates",
                late.decode(c3, 0, c3.length, 2000).isEmpty() && late.stats().getWaitingForFull() == 1);
        byte[] c4 = tx.encode(b, 0, b.length, 2000 + partial.fullUpdatePeriod(1));
        check("full update period (2.4 × 5 s): a full update", fullFlag(c4));
        check("the late decoder takes it", Arrays.equals(late.decode(c4, 0, c4.length, 14000).get(0), b));
        byte[] gone = es(7, 380_000_100L, tenth(100), EntityStatePdu.DEACTIVATED, "EAGLE");
        byte[] c5 = tx.encode(gone, 0, gone.length, 15000);
        check("deactivated (the last update): a full update", fullFlag(c5));
        check("decoded", Arrays.equals(rx.decode(c5, 0, c5.length, 15000).get(0), gone));
        byte[] c6 = tx.encode(b, 0, b.length, 16000);
        check("after the last update the entity starts over with a full update", fullFlag(c6));
        byte[] still = es(7, 380_000_100L, 0, 0, "EAGLE");
        byte[] c7 = tx.encode(still, 0, still.length, 17000);
        check("velocity goes out of use (static): a full update", fullFlag(c7));
        byte[] c8 = tx.encode(still, 0, still.length, 18000);
        rx.decode(c6, 0, c6.length, 16000);
        rx.decode(c7, 0, c7.length, 17000);
        rx.expire(18000 + partial.timeout(1) + 1);
        check("timed out objects are forgotten: the next partial update waits",
                rx.decode(c8, 0, c8.length, 40000).isEmpty() && rx.stats().timedOut.get() >= 1);

        // the other stateful PDUs: unchanged → partial and smaller, decoded to the same PDU
        CdisCodec tx2 = new CdisCodec(partial);
        CdisCodec rx2 = new CdisCodec(CdisSettings.DEFAULTS);
        for (byte[] p : new byte[][] {emissionPdu((float) 9.375e9, 2), designatorPdu(), transmitterPdu(), iffLayer1()}) {
            int type = p[2] & 0xFF;
            byte[] f = tx2.encode(p, 0, p.length, 0);
            byte[] q = tx2.encode(p, 0, p.length, 100);
            boolean first = Arrays.equals(rx2.decode(f, 0, f.length, 0).get(0), p);
            List<byte[]> d = rx2.decode(q, 0, q.length, 100);
            check("PDU type " + type + ": unchanged → " + f.length + " then " + q.length + " octets, decoded the same",
                    first && q.length < f.length && d.size() == 1 && Arrays.equals(d.get(0), p));
        }
        byte[] ee2 = emissionPdu((float) 1.2e9, 3);
        byte[] q = tx2.encode(ee2, 0, ee2.length, 200);
        List<byte[]> d = rx2.decode(q, 0, q.length, 200);
        check("EE: a changed frequency and location as a partial update", d.size() == 1 && Arrays.equals(d.get(0), ee2));
        check("counted", tx2.stats().partialUpdates.get() >= 5 && tx2.stats().fullUpdates.get() >= 4);
    }

    private static byte[] designatorPdu() {
        double[] loc = ecef(1_000_000, 2_000_000, 500);
        DisWriter d = pdu(24, 6).entityId(1, 2, 3).u16(1).entityId(1, 2, 4).u16(1688).f32(100).f32(1.064f);
        d.f32(cm(10)).f32(cm(-20)).f32(cm(30)).f64(loc[0]).f64(loc[1]).f64(loc[2]).u8(2).zeros(3);
        d.f32(tenth(1)).f32(tenth(2)).f32(tenth(3));
        return d.finish();
    }

    private static byte[] transmitterPdu() {
        double[] loc = ecef(1_000_000, 2_000_000, 500);
        DisWriter t = pdu(25, 4).entityId(1, 2, 3).u16(1).u8(7).u8(1).u16(225).u8(4).u8(1).u8(0).u8(0);
        t.u8(2).u8(1).u16(0).f64(loc[0]).f64(loc[1]).f64(loc[2]).f32(0).f32(0).f32(0);
        t.u16(0).u16(0).u64(243_000_000L).f32(25000).f32(40).u16(1).u16(2).u16(3).u16(4).u16(1).u16(0x8005).u8(0).zeros(3);
        return t.finish();
    }

    private static byte[] iffLayer1() {
        return iffPdu(1, 1 << 1, new int[0], new byte[0][], 0x99);
    }

    // ================================================================== datagrams

    private static void datagrams() {
        CdisCodec c = new CdisCodec(CdisSettings.DEFAULTS);
        byte[] a = comment("first");
        byte[] b = pdu(27, 4).entityId(1, 2, 3).u16(1).u16(1).zeros(2).f32(-90).entityId(1, 2, 4).u16(2).finish();
        byte[] ca = c.encode(a, 0, a.length, 0);
        byte[] cb = c.encode(b, 0, b.length, 0);
        int la = pduBits(ca);
        int lb = pduBits(cb);
        BitWriter both = new BitWriter();
        both.slice(new BitReader(ca, 0, la).slice(la)).slice(new BitReader(cb, 0, lb).slice(lb));
        byte[] packed = both.toByteArray();
        List<byte[]> out = c.decode(packed, 0, packed.length, 0);
        check("two bit-packed PDUs in one datagram", out.size() == 2 && Arrays.equals(out.get(0), a) && Arrays.equals(out.get(1), b));

        // an unknown type between them is skipped by its length
        BitWriter odd = new BitWriter();
        odd.slice(new BitReader(ca, 0, la).slice(la));
        int at = odd.bitLength();
        odd.bits(1, 2).uvint8(9).bits(200, 8).bits(0, 26);
        int lenAt = odd.bitLength();
        odd.bits(0, 14).bits(0, 8).bits(0x5A5A, 16);
        odd.patch(lenAt, odd.bitLength() - at, 14);
        odd.slice(new BitReader(cb, 0, lb).slice(lb));
        byte[] withUnknown = odd.toByteArray();
        out = c.decode(withUnknown, 0, withUnknown.length, 0);
        check("an unknown PDU type is skipped by its length", out.size() == 2 && c.stats().getUnknownPdus() == 1);

        byte[] dis = Arrays.copyOf(b, b.length);
        check("a DIS PDU (version 0 in the first two bits) is not C-DIS", c.decode(dis, 0, dis.length, 0).isEmpty());
    }

    private static int pduBits(byte[] cdis) {
        BitReader r = new BitReader(cdis, 0, cdis.length * 8);
        r.bits(2);
        r.uvint8();
        r.bits(8 + 26);
        return r.ubits(14);
    }

    // ================================================================== errors

    private static void errors() {
        CdisCodec c = new CdisCodec(CdisSettings.DEFAULTS);
        byte[] esu = pdu(67, 1).entityId(1, 2, 3).zeros(60).finish();
        check("Entity State Update has no C-DIS form", c.encode(esu, 0, esu.length, 0) == null && c.stats().getNotConvertible() == 1);
        byte[] shortEs = Arrays.copyOf(es(1, 0, 0, 0, "X"), 60);
        shortEs[8] = 0;
        shortEs[9] = 60;
        check("a truncated PDU is an encode error", c.encode(shortEs, 0, shortEs.length, 0) == null && c.stats().getEncodeErrors() == 1);
        byte[] bigSignal = pdu(26, 4).entityId(1, 2, 3).u16(1).u16(4).u16(0).u32(8000).u16(20000).u16(2500).zeros(2500).finish();
        check("Signal data over 16383 bits cannot be encoded", c.encode(bigSignal, 0, bigSignal.length, 0) == null
                && c.stats().getLastError().contains("16383"));
        byte[] ok = comment("x");
        byte[] cdis = c.encode(ok, 0, ok.length, 0);
        byte[] cut = Arrays.copyOf(cdis, cdis.length - 2);
        check("a C-DIS PDU longer than the datagram is a decode error", c.decode(cut, 0, cut.length, 0).isEmpty()
                && c.stats().getDecodeErrors() == 1);
        boolean threw = false;
        try {
            CdisSettings.DEFAULTS.withFullUpdateMultiplier(0);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("settings are checked", threw && CdisSettings.UpdateMode.fromSetting(" Partial ") == CdisSettings.UpdateMode.PARTIAL
                && CdisSettings.UpdateMode.fromSetting("half") == null);
        check("22 PDU types", CdisCodec.PDU_TYPES.size() == 22);
    }

    // ================================================================== cross-check: bit vectors of the dis-rs unit tests

    /**
     * Bit vectors from the unit tests of dis-rs / cdis-assemble (MIT licence, an independent
     * C-DIS implementation by a member of the SISO ballot group), decoded here.
     */
    private static void disRsVectors() {
        BitWriter h = new BitWriter();
        h.bits(1, 2).uvint8(7).bits(1, 8).bits(0, 26).bits(0, 14).bits(0, 8);
        check("dis-rs header vector (exercise 7, Entity State)", Arrays.equals(Arrays.copyOf(h.toByteArray(), 2),
                new byte[] {0b0100_1110, 0b0000_0010}));
        BitWriter m = new BitWriter();
        CdisRecords.encMarking(m, Arrays.copyOf("ABCDE".getBytes(), 11));
        check("dis-rs marking vector ABCDE (5-bit)", Arrays.equals(m.toByteArray(),
                new byte[] {0b0101_0000, 0b0100_0100, 0b0011_0010, 0b0001_0100}));
        m = new BitWriter();
        CdisRecords.encMarking(m, Arrays.copyOf("AAJJ".getBytes(), 11));
        check("dis-rs marking vector AAJJ (6-bit)", Arrays.equals(m.toByteArray(),
                new byte[] {0b0100_1000, 0b0010_0000, (byte) 0b1001_0100, 0b0101_0000}));

        int[] collision = {0b00_000000, 0b0001_0000, 0b000001_00, 0b00000001, 0b00000000, 0b10_000000, 0b0010_0000,
            0b000010_00, 0b00000001, 0b00000000, 0b01_000000, 0b0011_0_000, 0b0000001_0, 0b00000000, 0b1_0000000,
            0b001_00011, 0b00100_000, 0b0000001_0, 0b00000000, 0b1_0000000, 0b001_00000};
        DisReader r = vector(CdisCodec.COLLISION, collision, 20 * 8 + 3);
        check("dis-rs Collision vector", r != null && ids(r, 1, 1, 1, 2, 2, 2, 1, 1, 3) && r.u8() == 0 && r.skip(1) != null
                && r.f32() == 0.1f && r.f32() == 0.1f && r.f32() == 0.1f && r.f32() == 0.1f
                && r.f32() == 0.01f && r.f32() == 0.01f && r.f32() == 0.01f);

        int[] fire = {0b0000_1_000, 0b0000001_0, 0b00000000, 0b1_0000000, 0b001_00000, 0b00010_000, 0b0000010_0,
            0b00000001, 0b0_0000000, 0b001_00000, 0b00001_000, 0b0000010_0, 0b00000000, 0b1_0000000, 0b001_00000,
            0b00011_000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0b000001_00, 0b10_0010_00, 0b0000000_0, 0, 0b0_00000_00,
            0b000_00000, 0b00001_000, 0b0000001_0, 0b00000000, 0b1_0000000};
        r = vector(CdisCodec.FIRE, fire, 34 * 8 + 1);
        boolean ok = r != null && ids(r, 1, 1, 1, 2, 2, 2, 1, 1, 2, 1, 1, 3) && r.u32() == 0;
        if (ok) {
            double x = r.f64();
            double y = r.f64();
            double z = r.f64();
            ok = Math.abs(x - (6378137 + 10)) < 1e-6 && Math.abs(y) < 1e-6 && Math.abs(z) < 1e-6
                    && r.u8() == 2 && r.u8() == 2 && r.u16() == 0 && r.u32() == 0 && r.u32() == 0 && r.u32() == 0
                    && r.f32() == 0.1f && r.f32() == 0.1f && r.f32() == 0.1f && r.f32() == 0;
        }
        check("dis-rs Fire vector (1 dam above the ellipsoid at 0°N 0°E)", ok);
    }

    /** Puts our header in front of a dis-rs body vector and decodes it; null when it does not decode. */
    private static DisReader vector(int type, int[] body, int bodyBits) {
        byte[] b = new byte[body.length];
        for (int i = 0; i < body.length; i++) {
            b[i] = (byte) body[i];
        }
        BitWriter w = new BitWriter();
        w.bits(1, 2).uvint8(1).bits(type, 8).bits(0, 26);
        int lengthAt = w.bitLength();
        w.bits(0, 14).bits(0, 8).bitsOf(b, bodyBits);
        w.patch(lengthAt, w.bitLength(), 14);
        byte[] pdu = w.toByteArray();
        List<byte[]> out = new CdisCodec(CdisSettings.DEFAULTS).decode(pdu, 0, pdu.length, 0);
        return out.size() == 1 ? new DisReader(out.get(0), 0, out.get(0).length).seek(12) : null;
    }

    private static boolean ids(DisReader r, int... v) {
        for (int x : v) {
            if (r.u16() != x) {
                return false;
            }
        }
        return true;
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (ok) {
            System.out.println("  ok   " + what);
        } else {
            failures++;
            System.out.println("  FAIL " + what);
        }
    }
}
