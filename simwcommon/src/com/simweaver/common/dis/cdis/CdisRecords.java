package com.simweaver.common.dis.cdis;

import com.simweaver.common.dis.DisReader;
import com.simweaver.common.dis.DisWriter;
import com.simweaver.common.geo.Ellipsoid;

/**
 * The C-DIS records (SISO-STD-023-2024 clause 11 and 12), converted between their DIS 7 octets
 * and their C-DIS bits. {@code enc*} methods read DIS (or take values) and write C-DIS;
 * {@code dec*} methods read C-DIS and write DIS.
 *
 * <p>Scales follow the field descriptions of the standard (its clause 9 b): values are rounded
 * to the nearest integer; linear values out of range are clamped; angles are first reduced to the
 * field's range.
 */
public final class CdisRecords {

    /** The altitude that stands for the ECEF origin (x = y = z = 0). */
    public static final int ALTITUDE_ECEF_ORIGIN = -8_388_608;

    private static final double ORIENTATION_SCALE = ((1 << 12) - 1) / Math.PI;
    private static final double ANGULAR_VELOCITY_SCALE = ((1 << 11) - 1) / (4 * Math.PI);
    private static final double LATITUDE_SCALE = ((1L << 30) - 1) / (Math.PI / 2);
    private static final double LONGITUDE_SCALE = ((1L << 31) - 1) / Math.PI;
    private static final double SWEEP_SYNC_SCALE = ((1 << 10) - 1) / 100.0;

    private CdisRecords() {
    }

    // ================================================================== numbers

    /** Rounds half away from zero, clamped to the long range. */
    static long round(double v) {
        if (Double.isNaN(v)) {
            return 0;
        }
        return v < 0 ? -Math.round(-v) : Math.round(v);
    }

    /** Reduces an angle to -π..π. */
    static double normalize(double rad) {
        if (Double.isNaN(rad) || Double.isInfinite(rad)) {
            return 0;
        }
        // ±π as a float is a little beyond π as a double: keep its sign rather than wrap it
        if (rad >= -Math.PI - 1e-6 && rad <= Math.PI + 1e-6) {
            return Math.max(-Math.PI, Math.min(Math.PI, rad));
        }
        return Math.IEEEremainder(rad, 2 * Math.PI);
    }

    /** An angle of ±π in a signed {@code bits}-bit field, scale (2^(bits-1)-1)/π. */
    static void encAngle(BitWriter w, double rad, int bits) {
        double scale = ((1L << (bits - 1)) - 1) / Math.PI;
        w.signed(round(normalize(rad) * scale), bits);
    }

    static double decAngle(BitReader r, int bits) {
        double scale = ((1L << (bits - 1)) - 1) / Math.PI;
        return r.signed(bits) / scale;
    }

    /**
     * A custom floating point number (10.3): mantissa × 10^exponent, the largest mantissa that
     * fits and the smallest exponent.
     */
    static void encFloat(BitWriter w, double value, int mantissaBits, boolean signedMantissa,
                         int exponentBits, boolean signedExponent) {
        long[] me = toFloat(value, mantissaBits, signedMantissa, exponentBits, signedExponent);
        if (signedMantissa) {
            w.signed(me[0], mantissaBits);
        } else {
            w.unsigned(me[0], mantissaBits);
        }
        if (signedExponent) {
            w.signed(me[1], exponentBits);
        } else {
            w.unsigned(me[1], exponentBits);
        }
    }

    static long[] toFloat(double value, int mantissaBits, boolean signedMantissa, int exponentBits, boolean signedExponent) {
        if (Double.isNaN(value) || value == 0 || (!signedMantissa && value < 0)) {
            return new long[] {0, 0};
        }
        long mMax = signedMantissa ? (1L << (mantissaBits - 1)) - 1 : (1L << mantissaBits) - 1;
        long mMin = signedMantissa ? -(1L << (mantissaBits - 1)) : 0;
        int eMin = signedExponent ? -(1 << (exponentBits - 1)) : 0;
        int eMax = signedExponent ? (1 << (exponentBits - 1)) - 1 : (1 << exponentBits) - 1;
        for (int e = eMin; e <= eMax; e++) {
            long m = round(value / Math.pow(10, e));
            if (m <= mMax && m >= mMin) {
                return new long[] {m, m == 0 ? 0 : e};
            }
        }
        return new long[] {value < 0 ? mMin : mMax, eMax};
    }

    static double decFloat(BitReader r, int mantissaBits, boolean signedMantissa, int exponentBits, boolean signedExponent) {
        long m = signedMantissa ? r.signed(mantissaBits) : r.bits(mantissaBits);
        long e = signedExponent ? r.signed(exponentBits) : r.bits(exponentBits);
        return m * Math.pow(10, e);
    }

    // ================================================================== header time

    /** DIS timestamp (31 time bits + absolute flag) to the C-DIS one (25 + flag). */
    public static long timestampToCdis(long dis) {
        long units = (dis >>> 1) & 0x7FFFFFFFL;
        return ((units >>> 6) << 1) | (dis & 1);
    }

    /** C-DIS timestamp to the DIS one. */
    public static long timestampToDis(long cdis) {
        long units = (cdis >>> 1) & 0x1FFFFFFL;
        return ((units << 6) << 1 | (cdis & 1)) & 0xFFFFFFFFL;
    }

    // ================================================================== identifiers and types

    /** Entity / event / simulation identifier: 3 × UVINT16. */
    static void encEntityId(DisReader r, BitWriter w) {
        w.uvint16(r.u16()).uvint16(r.u16()).uvint16(r.u16());
    }

    static void decEntityId(BitReader r, DisWriter w) {
        w.u16((int) r.uvint16()).u16((int) r.uvint16()).u16((int) r.uvint16());
    }

    /** Entity type: 4:4:9 bits then 4 × UVINT8. */
    static void encEntityType(DisReader r, BitWriter w) {
        w.unsigned(r.u8(), 4).unsigned(r.u8(), 4).unsigned(r.u16(), 9);
        w.uvint8(r.u8()).uvint8(r.u8()).uvint8(r.u8()).uvint8(r.u8());
    }

    static void decEntityType(BitReader r, DisWriter w) {
        w.u8(r.ubits(4)).u8(r.ubits(4)).u16(r.ubits(9));
        w.u8((int) r.uvint8()).u8((int) r.uvint8()).u8((int) r.uvint8()).u8((int) r.uvint8());
    }

    /** @return true when the DIS entity type at the reader's position fits the C-DIS ranges; the position does not change */
    static boolean entityTypeFits(DisReader r) {
        int at = r.position();
        boolean ok = r.u8() <= 15 && r.u8() <= 15 && r.u16() <= 511;
        r.seek(at);
        return ok;
    }

    static boolean isZero(DisReader r, int octets) {
        int at = r.position();
        boolean zero = true;
        for (int i = 0; i < octets; i++) {
            zero &= r.u8() == 0;
        }
        r.seek(at);
        return zero;
    }

    // ================================================================== vectors

    /** Linear velocity: 3 × SVINT16 in decimeters per second. */
    static void encVelocity(BitWriter w, float x, float y, float z) {
        VarInt.SVINT16.write(w, round(x * 10.0));
        VarInt.SVINT16.write(w, round(y * 10.0));
        VarInt.SVINT16.write(w, round(z * 10.0));
    }

    static void decVelocity(BitReader r, DisWriter w) {
        for (int i = 0; i < 3; i++) {
            w.f32((float) (VarInt.SVINT16.read(r) / 10.0));
        }
    }

    /** Linear acceleration: 3 × SVINT14 in decimeters per second squared. */
    static void encAcceleration(BitWriter w, float x, float y, float z) {
        VarInt.SVINT14.write(w, round(x * 10.0));
        VarInt.SVINT14.write(w, round(y * 10.0));
        VarInt.SVINT14.write(w, round(z * 10.0));
    }

    static void decAcceleration(BitReader r, DisWriter w) {
        for (int i = 0; i < 3; i++) {
            w.f32((float) (VarInt.SVINT14.read(r) / 10.0));
        }
    }

    /** Angular velocity: 3 × SVINT12, ±4π rad/s. */
    static void encAngularVelocity(BitWriter w, float x, float y, float z) {
        VarInt.SVINT12.write(w, round(x * ANGULAR_VELOCITY_SCALE));
        VarInt.SVINT12.write(w, round(y * ANGULAR_VELOCITY_SCALE));
        VarInt.SVINT12.write(w, round(z * ANGULAR_VELOCITY_SCALE));
    }

    static void decAngularVelocity(BitReader r, DisWriter w) {
        for (int i = 0; i < 3; i++) {
            w.f32((float) (VarInt.SVINT12.read(r) / ANGULAR_VELOCITY_SCALE));
        }
    }

    /** Orientation (Euler angles): 3 × 13 bits, ±π. */
    static void encOrientation(BitWriter w, float psi, float theta, float phi) {
        w.signed(round(normalize(psi) * ORIENTATION_SCALE), 13);
        w.signed(round(normalize(theta) * ORIENTATION_SCALE), 13);
        w.signed(round(normalize(phi) * ORIENTATION_SCALE), 13);
    }

    static void decOrientation(BitReader r, DisWriter w) {
        for (int i = 0; i < 3; i++) {
            w.f32((float) (r.signed(13) / ORIENTATION_SCALE));
        }
    }

    /**
     * @return true when an entity coordinate vector needs meter units (some component beyond ±327.67 m)
     */
    static boolean needsMeters(float x, float y, float z) {
        return !fitsCm(x) || !fitsCm(y) || !fitsCm(z);
    }

    private static boolean fitsCm(float v) {
        long cm = round(v * 100.0);
        return cm >= -32768 && cm <= 32767;
    }

    /** Entity coordinate vector: 3 × SVINT16 in centimeters or meters. */
    static void encEntityCoordinates(BitWriter w, float x, float y, float z, boolean meters) {
        double f = meters ? 1 : 100;
        VarInt.SVINT16.write(w, round(x * f));
        VarInt.SVINT16.write(w, round(y * f));
        VarInt.SVINT16.write(w, round(z * f));
    }

    static void decEntityCoordinates(BitReader r, DisWriter w, boolean meters) {
        double f = meters ? 1 : 100;
        for (int i = 0; i < 3; i++) {
            w.f32((float) (VarInt.SVINT16.read(r) / f));
        }
    }

    // ================================================================== world coordinates

    /**
     * Converts ECEF to the C-DIS world coordinates: latitude, longitude (scaled integers) and
     * altitude (height above the WGS84 ellipsoid, see the C-DIS decisions in the docs).
     *
     * @return {latitude, longitude, altitude, dekameters (0/1)}
     */
    static long[] toWorld(double x, double y, double z) {
        boolean finite = !Double.isNaN(x + y + z) && !Double.isInfinite(x + y + z);
        if (!finite || x == 0 && y == 0 && z == 0) {
            return new long[] {0, 0, ALTITUDE_ECEF_ORIGIN, 0};
        }
        double[] llh = new double[3];
        Ellipsoid.WGS84.toGeodeticRadians(x, y, z, llh);
        long lat = round(llh[0] * LATITUDE_SCALE);
        long lon = round(normalize(llh[1]) * LONGITUDE_SCALE);
        long cm = round(llh[2] * 100.0);
        if (cm > ALTITUDE_ECEF_ORIGIN && cm <= 8_388_607) {
            return new long[] {lat, lon, cm, 0};
        }
        long dam = Math.max(ALTITUDE_ECEF_ORIGIN + 1, Math.min(8_388_607, round(llh[2] / 10.0)));
        return new long[] {lat, lon, dam, 1};
    }

    /** Writes latitude, longitude and altitude of {@link #toWorld} (the units flag goes elsewhere). */
    static void encWorld(BitWriter w, long[] world) {
        w.signed(world[0], 31).signed(world[1], 32);
        VarInt.SVINT24.write(w, world[2]);
    }

    /** Reads world coordinates and writes the DIS location (3 × f64 ECEF). */
    static void decWorld(BitReader r, DisWriter w, boolean dekameters) {
        double lat = r.signed(31) / LATITUDE_SCALE;
        double lon = r.signed(32) / LONGITUDE_SCALE;
        long alt = VarInt.SVINT24.read(r);
        if (alt == ALTITUDE_ECEF_ORIGIN) {
            w.f64(0).f64(0).f64(0);
            return;
        }
        double h = dekameters ? alt * 10.0 : alt / 100.0;
        com.simweaver.common.geo.Vec3 e = Ellipsoid.WGS84.toEcefRadians(lat, lon, h);
        w.f64(e.getX()).f64(e.getY()).f64(e.getZ());
    }

    // ================================================================== markings

    private static final String FIVE_BIT = "\0ABCDEFGHILMNOPRSTUVWY0123456789";
    private static final String SIX_BIT = "\0ABCDEFGHIJKLMNOPQRSTUVWXYZ.?!0123456789 []()" + "{}+-_@&\"':;,~\\/%#$*";

    /** Entity marking (13.2 q): 4-bit length, 1-bit character size, the characters. */
    static void encMarking(BitWriter w, byte[] chars11) {
        int n = chars11.length;
        while (n > 0 && (chars11[n - 1] == 0 || chars11[n - 1] == ' ')) {
            n--;
        }
        int[] five = new int[n];
        int[] six = new int[n];
        boolean fits5 = true;
        for (int i = 0; i < n; i++) {
            char c = Character.toUpperCase((char) (chars11[i] & 0xFF));
            int p5 = c == 0 ? -1 : FIVE_BIT.indexOf(c);
            int p6 = c == 0 ? 0 : SIX_BIT.indexOf(c);
            fits5 &= p5 > 0;
            five[i] = p5;
            six[i] = p6 < 0 ? 63 : p6;
        }
        w.unsigned(n, 4).flag(!fits5);
        for (int i = 0; i < n; i++) {
            w.bits(fits5 ? five[i] : six[i], fits5 ? 5 : 6);
        }
    }

    /** Writes the DIS marking: character set ASCII (1) and 11 characters. */
    static void decMarking(BitReader r, DisWriter w) {
        int n = r.ubits(4);
        boolean six = r.flag();
        byte[] out = new byte[11];
        for (int i = 0; i < n; i++) {
            int v = r.ubits(six ? 6 : 5);
            if (i < 11) {
                out[i] = (byte) (six ? SIX_BIT : FIVE_BIT).charAt(v);
            }
        }
        w.u8(1).bytes(out);
    }

    // ================================================================== datum specification

    /** Fixed datums: {@code n} × (id 32, value 32). */
    static void encFixedDatums(DisReader r, BitWriter w, int n) {
        for (int i = 0; i < n; i++) {
            w.bits(r.u32(), 32).bits(r.u32(), 32);
        }
    }

    static void decFixedDatums(BitReader r, DisWriter w, int n) {
        for (int i = 0; i < n; i++) {
            w.u32(r.bits(32)).u32(r.bits(32));
        }
    }

    /** Variable datums: {@code n} × (id 32, length in bits 14, the bits unpadded). */
    static void encVariableDatums(DisReader r, BitWriter w, int n) {
        for (int i = 0; i < n; i++) {
            long id = r.u32();
            long lengthBits = r.u32();
            int octets = (int) ((lengthBits + 63) / 64 * 8);
            byte[] data = r.bytes(Math.min(octets, r.remaining()));
            int bits = (int) Math.min(lengthBits, Math.min(16383, data.length * 8L));
            w.bits(id, 32).bits(bits, 14).bitsOf(data, bits);
        }
    }

    static void decVariableDatums(BitReader r, DisWriter w, int n) {
        for (int i = 0; i < n; i++) {
            long id = r.bits(32);
            int bits = r.ubits(14);
            BitSlice s = r.slice(bits);
            int octets = (bits + 63) / 64 * 8;
            w.u32(id).u32(bits);
            byte[] data = new byte[octets];
            System.arraycopy(s.octets(), 0, data, 0, s.octets().length);
            w.bytes(data);
        }
    }

    // ================================================================== beam records

    /** Beam data (11.3): az center, az sweep, el center, el sweep (SVINT13 of ±π), sweep sync (10 bits of 0-100 %). */
    static void encBeamData(BitWriter w, float azCenter, float azSweep, float elCenter, float elSweep, float sync) {
        double s = ((1 << 12) - 1) / Math.PI;
        VarInt.SVINT13.write(w, round(normalize(azCenter) * s));
        VarInt.SVINT13.write(w, round(normalize(azSweep) * s));
        VarInt.SVINT13.write(w, round(normalize(elCenter) * s));
        VarInt.SVINT13.write(w, round(normalize(elSweep) * s));
        w.unsigned(round(sync * SWEEP_SYNC_SCALE), 10);
    }

    static void decBeamData(BitReader r, DisWriter w) {
        double s = ((1 << 12) - 1) / Math.PI;
        for (int i = 0; i < 4; i++) {
            w.f32((float) (VarInt.SVINT13.read(r) / s));
        }
        w.f32((float) (r.bits(10) / SWEEP_SYNC_SCALE));
    }

    /** DIS beam data (5 × f32) to C-DIS. */
    static void encBeamData(DisReader r, BitWriter w) {
        encBeamData(w, r.f32(), r.f32(), r.f32(), r.f32(), r.f32());
    }

    /** Beam antenna pattern (11.2): DIS 40 octets to 112 bits. */
    static void encBeamAntennaPattern(DisReader r, BitWriter w) {
        float psi = r.f32();
        float theta = r.f32();
        float phi = r.f32();
        float az = r.f32();
        float el = r.f32();
        int ref = r.u8();
        r.skip(3);
        float ez = r.f32();
        float ex = r.f32();
        float phase = r.f32();
        r.skip(4);
        encAngle(w, psi, 13);
        encAngle(w, theta, 13);
        encAngle(w, phi, 13);
        encAngle(w, az, 13);
        encAngle(w, el, 13);
        w.unsigned(ref, 2);
        w.signed(round(ez), 16).signed(round(ex), 16);
        encAngle(w, phase, 13);
    }

    /** The C-DIS beam antenna pattern size in bits. */
    static final int BEAM_ANTENNA_PATTERN_BITS = 5 * 13 + 2 + 16 + 16 + 13;

    static void decBeamAntennaPattern(BitReader r, DisWriter w) {
        for (int i = 0; i < 5; i++) {
            w.f32((float) decAngle(r, 13));
        }
        w.u8(r.ubits(2)).zeros(3);
        w.f32(r.signed(16)).f32(r.signed(16));
        w.f32((float) decAngle(r, 13)).zeros(4);
    }

    // ================================================================== variable parameter records

    /** DIS VP record: 16 octets. Writes the compressed form when the values fit, else the raw 128 bits. */
    static void encVariableParameter(DisReader r, BitWriter w) {
        int at = r.position();
        int type = r.u8();
        BitWriter c = new BitWriter(16);
        boolean ok = false;
        switch (type) {
            case 0: { // articulated part
                int change = r.u8();
                int attachedTo = r.u16();
                long paramType = r.u32();
                float value = r.f32();
                if (attachedTo <= 1023 && paramType <= 16383) {
                    c.bits(change, 8).bits(attachedTo, 10).bits(paramType, 14);
                    encFloat(c, value, 15, true, 3, true);
                    ok = true;
                }
                break;
            }
            case 1: { // attached part
                int detached = r.u8();
                int attachedTo = r.u16();
                long paramType = r.u32();
                if (detached <= 1 && attachedTo <= 1023 && paramType <= 2047 && entityTypeFits(r)) {
                    c.bits(detached, 1).bits(attachedTo, 10).bits(paramType, 11);
                    encEntityType(r, c);
                    ok = true;
                }
                break;
            }
            case 2: { // separation
                int reason = r.u8();
                int pre = r.u8();
                r.skip(1);
                int s = r.u16();
                int a = r.u16();
                int e = r.u16();
                r.skip(2);
                int stationName = r.u16();
                int stationNumber = r.u16();
                if (reason <= 7 && pre <= 7 && stationName <= 63 && stationNumber <= 4095) {
                    c.bits(reason, 3).bits(pre, 3).uvint16(s).uvint16(a).uvint16(e).bits(stationName, 6).bits(stationNumber, 12);
                    ok = true;
                }
                break;
            }
            case 3: { // entity type
                int change = r.u8();
                if (change <= 1 && entityTypeFits(r)) {
                    c.bits(change, 1);
                    encEntityType(r, c);
                    ok = true;
                }
                break;
            }
            case 4: { // entity association
                int change = r.u8();
                int status = r.u8();
                int assocType = r.u8();
                int s = r.u16();
                int a = r.u16();
                int e = r.u16();
                int station = r.u16();
                int connection = r.u8();
                int member = r.u8();
                int group = r.u16();
                if (change <= 1 && status <= 15 && station <= 63 && connection <= 31 && member <= 15) {
                    c.bits(change, 1).bits(status, 4).bits(assocType, 8).uvint16(s).uvint16(a).uvint16(e)
                            .bits(station, 6).bits(connection, 5).bits(member, 4).bits(group, 16);
                    ok = true;
                }
                break;
            }
            default:
                break;
        }
        r.seek(at + 16);
        if (ok) {
            w.flag(true).bits(type, 3).append(c);
        } else {
            r.seek(at);
            w.flag(false).bits(r.u8(), 8);
            for (int i = 0; i < 15; i++) {
                w.bits(r.u8(), 8);
            }
        }
    }

    static void decVariableParameter(BitReader r, DisWriter w) {
        boolean compressed = r.flag();
        if (!compressed) {
            w.u8(r.ubits(8));
            for (int i = 0; i < 15; i++) {
                w.u8(r.ubits(8));
            }
            return;
        }
        int type = r.ubits(3);
        w.u8(type);
        switch (type) {
            case 0:
                w.u8(r.ubits(8)).u16(r.ubits(10)).u32(r.bits(14));
                w.f32((float) decFloat(r, 15, true, 3, true)).zeros(4);
                break;
            case 1:
                w.u8(r.ubits(1)).u16(r.ubits(10)).u32(r.bits(11));
                decEntityType(r, w);
                break;
            case 2:
                w.u8(r.ubits(3)).u8(r.ubits(3)).zeros(1);
                decEntityId(r, w);
                w.zeros(2).u16(r.ubits(6)).u16(r.ubits(12));
                break;
            case 3:
                w.u8(r.ubits(1));
                decEntityType(r, w);
                w.zeros(6);
                break;
            case 4:
                w.u8(r.ubits(1)).u8(r.ubits(4)).u8(r.ubits(8));
                decEntityId(r, w);
                w.u16(r.ubits(6)).u8(r.ubits(5)).u8(r.ubits(4)).u16(r.ubits(16));
                break;
            default:
                throw new CdisFormatException("compressed variable parameter record of type " + type);
        }
    }
}
