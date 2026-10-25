package com.simweaver.common.dis;

import com.simweaver.common.hla.codec.HValue;
import com.simweaver.common.hla.codec.HlaCodec;
import com.simweaver.common.iem.InformationExchangeModel;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * DIS -> RPR v2 -> DIS: PDUs are read with {@link DisToRpr}, the RPR values decoded with
 * {@code HlaCodec}, and written again with {@link RprToDis}; the result must equal the input
 * (apart from the timestamp, and the parts the RPR FOM has no place for, which the input
 * leaves at 0).
 *
 * <pre>java -cp out/classes:out/test com.simweaver.common.dis.DisRoundTripSelfTest [fom root]</pre>
 */
public final class DisRoundTripSelfTest {

    private static int checks;
    private static int failures;
    private static InformationExchangeModel iem;
    private static HlaCodec codec;

    private DisRoundTripSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File fomRoot = new File(args.length > 0 ? args[0] : "simwrift/rtdir/etc/fom");
        iem = InformationExchangeModel.fromHlaEvolved(Collections.singletonList(
                new File(fomRoot, "ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath()));
        codec = new HlaCodec(iem);
        for (int v : new int[] {6, 7}) {
            entityState(v);
            fire(v);
            detonation(v);
            collision(v);
            radios(v);
            simulationManagement(v);
            emission(v);
            designatorAndIff(v);
            link16(v);
        }
        names();
        link16Decoder();
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static final DisEntityId TANK = new DisEntityId(1, 2, 3);
    private static final DisEntityId TRUCK = new DisEntityId(1, 2, 4);
    private static final DisEntityId SHELL = new DisEntityId(1, 2, 99);
    private static final RprToDis.Names NAMES = n -> null;     // "s-a-e" names resolve by themselves

    private static DisWriter header(int v, int type, int family) {
        return new DisWriter(64).header(v, 7, type, family, 0, 0);
    }

    // ------------------------------------------------------------------ entities

    private static void entityState(int v) {
        DisWriter w = header(v, 1, 1);
        w.entityId(TANK).u8(2).u8(2);
        w.entityType(new DisEntityType(1, 1, 225, 1, 1, 3, 0));
        w.entityType(new DisEntityType(1, 1, 222, 2, 1, 1, 0));
        w.f32(1.5f).f32(-2.25f).f32(0.125f);
        w.f64(3912345.25).f64(1012345.5).f64(4912345.75);
        w.f32(0.5f).f32(-0.25f).f32(1.75f);
        // damage 1 (bits 3-4), smoke plume (5), flames (15), power plant (22), head lights (12), hatch 2 (9-11), frozen (21)
        int appearance = (1 << 3) | (1 << 5) | (1 << 15) | (1 << 22) | (1 << 12) | (2 << 9) | (1 << 21);
        w.i32(appearance);
        w.u8(4).zeros(15);                                                // DRM_RVW
        w.f32(0.1f).f32(0.2f).f32(0.3f);
        w.f32(0.01f).f32(0.02f).f32(0.03f);
        w.u8(1).text("TANK 1", 11);
        w.i32(0x3);                                                        // ammunition and fuel supply
        // articulated part: turret azimuth (4096 + 11), attached part: station 2 with a store
        w.u8(0).u8(1).u16(0).u32(4096 + 11).f32(0.75f).zeros(4);
        w.u8(1).u8(0).u16(0).u32(2).entityType(new DisEntityType(2, 2, 225, 1, 1, 0, 0));
        byte[] in = w.finish();

        DisReader r = new DisReader(in, 0, in.length);
        r.seek(DisPduHeader.SIZE);
        DisEntityState s = DisEntityState.read(r);
        String cls = DisToRpr.entityClass(s.type);
        Function<String, HValue> values = decodeAttributes(cls, new DisToRpr(v).entityAttributes(s));
        check("v" + v + " entity: can be sent", RprToDis.canSendEntity(values));
        byte[] out = new RprToDis(v, 7, 900, 1).entityState(TANK, values, false, 0);
        same("v" + v + " Entity State", in, out);
        byte[] off = new RprToDis(v, 7, 900, 1).entityState(TANK, values, true, 0);
        DisReader r2 = new DisReader(off, 0, off.length);
        r2.seek(DisPduHeader.SIZE);
        check("v" + v + " entity: deactivated", DisEntityState.read(r2).isDeactivated());
    }

    // ------------------------------------------------------------------ warfare

    private static void fire(int v) {
        DisWriter w = header(v, 2, 2);
        w.entityId(TANK).entityId(TRUCK).entityId(SHELL);
        w.u16(1).u16(2).u16(77);                                          // event id of the firing entity
        w.u32(5);
        w.f64(3912345.25).f64(1012345.5).f64(4912345.75);
        w.entityType(new DisEntityType(2, 9, 225, 2, 1, 0, 0)).u16(1000).u16(1100).u16(3).u16(60);
        w.f32(100f).f32(200f).f32(300f);
        w.f32(2500f);
        byte[] in = w.finish();
        DisToRpr.Interaction ix = new DisToRpr(v).fire(header(in), reader(in));
        byte[] out = new RprToDis(v, 7, 900, 1).fire(decodeParameters(ix), NAMES, 0);
        same("v" + v + " Fire", in, out);
    }

    private static void detonation(int v) {
        DisWriter w = header(v, 3, 2);
        w.entityId(TANK).entityId(TRUCK).entityId(SHELL);
        w.u16(1).u16(2).u16(78);
        w.f32(-10f).f32(-20f).f32(-30f);
        w.f64(3912000.5).f64(1012000.25).f64(4912000.125);
        w.entityType(new DisEntityType(2, 9, 225, 2, 1, 0, 0)).u16(1000).u16(1100).u16(1).u16(0);
        w.f32(1f).f32(2f).f32(3f);
        w.u8(1).u8(1).u16(0);                                             // entity impact, one articulation
        w.u8(0).u8(2).u16(0).u32(4096 + 11).f32(0.5f).zeros(4);
        byte[] in = w.finish();
        DisToRpr.Interaction ix = new DisToRpr(v).detonation(header(in), reader(in));
        byte[] out = new RprToDis(v, 7, 900, 1).detonation(decodeParameters(ix), NAMES, 0);
        same("v" + v + " Detonation", in, out);
    }

    private static void collision(int v) {
        DisWriter w = header(v, 4, 1);
        w.entityId(TANK).entityId(TRUCK);
        w.u16(1).u16(2).u16(79);
        w.u8(1).u8(0);
        w.f32(4f).f32(5f).f32(6f);
        w.f32(42000f);
        w.f32(0.5f).f32(1.5f).f32(-0.5f);
        byte[] in = w.finish();
        DisToRpr.Interaction ix = new DisToRpr(v).collision(header(in), reader(in));
        byte[] out = new RprToDis(v, 7, 900, 1).collision(decodeParameters(ix), NAMES, 0);
        same("v" + v + " Collision", in, out);
    }

    // ------------------------------------------------------------------ radio

    private static void radios(int v) {
        DisWriter w = header(v, 25, 4);
        w.entityId(TANK).u16(2);
        w.u8(7).u8(1).u16(225).u8(2).u8(1).u16(1234);                     // radio entity type
        w.u8(2).u8(1).u16(0);                                             // on and transmitting, pilot
        w.f64(3912345.25).f64(1012345.5).f64(4912345.75);
        w.f32(0.5f).f32(0.25f).f32(2f);
        w.u16(0).u16(0);
        w.u64(251_000_000L);
        w.f32(25000f).f32(40f);
        w.u16(0x4).u16(3).u16(2).u16(1);                                   // time hopping; angle / FM; system
        w.u16(4).u16(v >= 7 ? 0x8000 | 12 : 12);
        w.u8(0).zeros(3);
        byte[] in = w.finish();
        DisToRpr.Radio tx = new DisToRpr(v).transmitter(header(in), reader(in));
        Function<String, HValue> tv = decodeAttributes(tx.className, tx.attributes);
        byte[] out = new RprToDis(v, 7, 900, 1).transmitter(TANK, 2, tv, false, 0);
        same("v" + v + " Transmitter", in, out);
        check("v" + v + " transmitter StreamTag packs host and radio",
                tv.apply("StreamTag").asLong() == DisToRpr.streamTag(TANK, 2));

        byte[] pcm = new byte[] {1, 2, 3, 4, 5, 6, 7};
        w = header(v, 26, 4);
        w.entityId(TANK).u16(2).u16(4).u16(0).u32(8000).u16(56).u16(7).bytes(pcm).zeros(1);
        in = w.finish();
        DisToRpr.Interaction sig = new DisToRpr(v).signal(header(in), reader(in));
        Function<String, HValue> sp = decodeParameters(sig);
        out = new RprToDis(v, 7, 900, 1).encodedAudio(TANK, 2, sp.apply("AudioData"), 0);
        same("v" + v + " Signal (audio)", in, out);
        check("v" + v + " audio StreamTag is the transmitter's",
                sp.apply("AudioData").field("StreamTag").asLong() == DisToRpr.streamTag(TANK, 2));

        w = header(v, 26, 4);
        w.entityId(TANK).u16(2).u16((1 << 14) | 1).u16(5).u32(9600).u16(32).u16(0).u32(0xCAFEBABEL);   // one TDL message
        in = w.finish();
        sig = new DisToRpr(v).signal(header(in), reader(in));
        out = new RprToDis(v, 7, 900, 1).binarySignal(TANK, 2, false, decodeParameters(sig), 0);
        same("v" + v + " Signal (raw binary)", in, out);

        w = header(v, 26, 4);
        w.entityId(TANK).u16(2).u16((3 << 14) | 2).u16(5).u32(0).u16(96).u16(0).u32(77).u32(1500).u32(0x00ABCDEFL);
        in = w.finish();
        sig = new DisToRpr(v).signal(header(in), reader(in));
        out = new RprToDis(v, 7, 900, 1).databaseIndexSignal(TANK, 2, decodeParameters(sig), 0);
        same("v" + v + " Signal (database index: index, offset, duration, 2 messages)", in, out);

        w = header(v, 27, 4);
        w.entityId(TRUCK).u16(1).u16(2).u16(0).f32(-80f).entityId(TANK).u16(2);
        in = w.finish();
        DisToRpr.Radio rx = new DisToRpr(v).receiver(header(in), reader(in));
        Function<String, HValue> rv = decodeAttributes(rx.className, rx.attributes);
        int[] t = RprToDis.parseRadioName(rv.apply("ReceivedTransmitterIdentifier").asTrimmedText());
        check("v" + v + " receiver refers to the transmitter by name", t != null && t[2] == 3 && t[3] == 2);
        out = new RprToDis(v, 7, 900, 1).receiver(TRUCK, 1, rv, new DisEntityId(t[0], t[1], t[2]), t[3], false, 0);
        same("v" + v + " Receiver", in, out);
    }

    // ------------------------------------------------------------------ simulation management

    /** Electromagnetic Emission: two systems, a radar beam tracking the truck, a jamming beam, and a system with no beams. */
    private static void emission(int v) {
        DisWriter w = new DisWriter(256).header(v, 7, 23, 6, 0, 0);
        w.entityId(TANK).u16(1).u16(2).u16(77);
        w.u8(0).u8(2).u16(0);                                            // heartbeat, two systems
        w.u8((20 + 60 + 52) / 4).u8(2).u16(0).u16(9200).u8(2).u8(1).f32(1f).f32(0f).f32(-2f);
        w.u8(15).u8(1).u16(7).f32(3.1e9f).f32(1e6f).f32(70f).f32(1000f).f32(1.5f);
        w.f32(0.5f).f32(0.25f).f32(0.05f).f32(0.1f).f32(37.5f);
        w.u8(1).u8(1).u8(1).u8(0).u32(0);
        w.entityId(TRUCK).u8(0).u8(0);                                    // tracked: the truck
        w.u8(13).u8(2).u16(0).f32(9.4e9f).f32(0f).f32(60f).f32(0f).f32(0f);
        w.f32(-0.5f).f32(0.1f).f32(0f).f32(0.05f).f32(0f);
        w.u8(12).u8(0).u8(0).u8(0).u32(0x01020304L);                     // jamming
        w.u8(5).u8(0).u16(0).u16(4511).u8(5).u8(2).f32(0f).f32(0f).f32(0f);   // a system that radiates nothing
        byte[] in = w.finish();
        DisToRpr.Emission e = new DisToRpr(v).emission(header(in), reader(in));
        List<RprToDis.EmitterOut> systems = new java.util.ArrayList<RprToDis.EmitterOut>();
        for (DisToRpr.Embedded sys : e.systems) {
            List<RprToDis.BeamOut> beams = new java.util.ArrayList<RprToDis.BeamOut>();
            for (DisToRpr.Embedded b : e.beams.get(sys.instanceName)) {
                Function<String, HValue> bv = decodeAttributes(b.className, b.attributes);
                beams.add(new RprToDis.BeamOut((int) bv.apply("BeamIdentifier").asLong(), bv, b.className.equals(DisToRpr.JAMMER_BEAM)));
            }
            Function<String, HValue> sv = decodeAttributes(sys.className, sys.attributes);
            systems.add(new RprToDis.EmitterOut((int) sv.apply("EmitterIndex").asLong(), sv, beams));
        }
        check("v" + v + " emission: two systems, 2 + 0 beams", systems.size() == 2 && systems.get(0).beams.size() == 2 && systems.get(1).beams.isEmpty());
        check("v" + v + " emission: beam 2 is a jammer", systems.size() == 2 && systems.get(0).beams.size() == 2 && systems.get(0).beams.get(1).jammer);
        byte[] out = new RprToDis(v, 7, 900, 1).emission(TANK, systems, e.complete, 77, NAMES, 0);
        same("v" + v + " Electromagnetic Emission", in, out);
    }

    /** Designator; IFF transponder (layers 1 and 2), interrogator, Soviet, RRB. */
    private static void designatorAndIff(int v) {
        DisWriter w = new DisWriter(88).header(v, 7, 24, 6, 0, 0);
        w.entityId(TANK).u16(1100).entityId(TRUCK).u16(1688).f32(2.5f).f32(1.064f);
        w.f32(0.5f).f32(-0.25f).f32(1f).f64(3912345.25).f64(1012345.5).f64(4912345.75);
        w.u8(2).zeros(3).f32(0f).f32(0.1f).f32(0f);
        byte[] in = w.finish();
        DisToRpr.Embedded d = new DisToRpr(v).designator(header(in), reader(in));
        check("v" + v + " designator: class and name", d.className.equals(DisToRpr.DESIGNATOR) && d.instanceName.equals("1-2-3/designator"));
        byte[] out = new RprToDis(v, 7, 900, 1).designator(TANK, decodeAttributes(d.className, d.attributes), NAMES, false, 0);
        same("v" + v + " Designator", in, out);

        // Mark X/XII transponder: modes 1, 2, 3/A on, 4 with pseudo crypto, C at -300 ft, S TCAS II; emergency + ident; layer 2
        int[] params = {0x2000 | 012, 0x2000 | 01234, 0x2000 | 07700, 0x2000 | 0x123, 0x2000 | (3 << 1) | 1, 0x2000 | 1};
        byte[] iffIn = iffPdu(v, 1, 4, 0xFF & ~0x80 | 0, params, 0x6, 3, true);
        DisToRpr.Embedded t = new DisToRpr(v).iff(header(iffIn), reader(iffIn));
        check("v" + v + " IFF transponder class " + t.className, t.className.equals(DisToRpr.NATO_IFF_TRANSPONDER));
        check("v" + v + " IFF name " + t.instanceName, t.instanceName.equals(v >= 7 ? "1-2-3/iff-1-3" : "1-2-3/iff-1"));
        Function<String, HValue> tv = decodeAttributes(t.className, t.attributes);
        check("v" + v + " Mode 3/A 7700, Mode 1 12, Mode 2 1234 (octal)", tv.apply("Mode3ACode").asLong() == 07700
                && tv.apply("Mode1Code").asLong() == 012 && tv.apply("Mode2Code").asLong() == 01234);
        check("v" + v + " Mode C -300 ft", tv.apply("Mode5CAltitude").asLong() == -3 && tv.apply("Mode5CAltitudeAvailable").asLong() == 1);
        check("v" + v + " emergency and ident, not STI", tv.apply("EmergencyOn").asLong() == 1 && tv.apply("IdentSquawkFlashOn").asLong() == 1
                && tv.apply("StiOn").asLong() == 0);
        check("v" + v + " layer 2: beam and two parameter sets", tv.apply("Layer2DataAvailable").asLong() == 1
                && tv.apply("FundamentalParameterData").size() == 2 && tv.apply("BeamAzimuthSweep").asDouble() == 0.5);
        out = new RprToDis(v, 7, 900, 1).iff(TANK, t.className, v >= 7 ? 3 : 0, tv, 77, false, 0);
        same("v" + v + " IFF transponder", iffIn, out);

        // interrogator (no codes), Soviet, RRB (code 9, power reduction)
        for (int[] c : new int[][] {{2, 0x2000, 0x2000}, {3, 0x6000, 0x8000}, {5, 0x2000 | (1 << 9) | 9, 0}}) {
            int[] pp = {c[1], c[2], 0, 0, 0, 0};
            byte[] x = iffPdu(v, c[0], 0, c[0] == 5 ? 0x01 : 0x07, pp, 0, 0, false);
            DisToRpr.Embedded e = new DisToRpr(v).iff(header(x), reader(x));
            Function<String, HValue> ev = decodeAttributes(e.className, e.attributes);
            out = new RprToDis(v, 7, 900, 1).iff(TANK, e.className, 0, ev, 77, false, 0);
            same("v" + v + " IFF type " + c[0] + " (" + e.className.substring(e.className.lastIndexOf('.') + 1) + ")", x, out);
        }
    }

    private static byte[] iffPdu(int v, int type, int mode, int status, int[] params, int modifier, int designator, boolean layer2) {
        DisWriter w = new DisWriter(160).header(v, 7, 28, 6, 0, 0);
        w.entityId(TANK).u16(1).u16(2).u16(77).f32(1f).f32(0f).f32(-1f);
        w.u16(type).u16(type == 1 ? 7 : 0).u8(mode).u8(0);
        if (v >= 7) w.u8(designator).u8(0); else w.zeros(2);
        w.u8(status).u8(type == 1 ? 1 : 0).u8(0x2 | (layer2 ? 0x4 : 0)).u8(modifier);
        for (int p : params) w.u16(p);
        if (layer2) {
            w.u8(2).u8(0).u16(28 + 48);
            w.f32(0.25f).f32(0.5f).f32(0f).f32(0.1f).f32(10f).u8(1).u8(2).u16(2);
            for (int i = 0; i < 2; i++) {
                w.f32(60f + i).f32(1030e6f + i * 60e6f).f32(400f).f32(0.8f).u32(3).u8(i + 1).u8(0xA).u8(0xB).u8(0xC);
            }
        }
        return w.finish();
    }

    private static void simulationManagement(int v) {
        DisWriter w = header(v, 13, 5);
        w.entityId(TANK).entityId(new DisEntityId(65535, 65535, 65535)).i32(10).u32(123456).i32(1).u32(654321).u32(42);
        byte[] in = w.finish();
        byte[] out = new RprToDis(v, 7, 900, 1).startResume(decodeParameters(new DisToRpr(v).startResume(header(in), reader(in))), 0);
        same("v" + v + " Start/Resume", in, out);

        w = header(v, 14, 5);
        w.entityId(TANK).entityId(TRUCK).i32(10).u32(123456).u8(2).u8(0x5).u16(0).u32(43);
        in = w.finish();
        out = new RprToDis(v, 7, 900, 1).stopFreeze(decodeParameters(new DisToRpr(v).stopFreeze(header(in), reader(in))), 0);
        same("v" + v + " Stop/Freeze", in, out);

        w = header(v, 15, 5);
        w.entityId(TRUCK).entityId(TANK).u16(1).u16(1).u32(43);
        in = w.finish();
        out = new RprToDis(v, 7, 900, 1).acknowledge(decodeParameters(new DisToRpr(v).acknowledge(header(in), reader(in))), 0);
        same("v" + v + " Acknowledge", in, out);
    }

    private static void names() {
        check("parse entity name", TANK.equals(RprToDis.parseEntityName("1-2-3")));
        check("not an entity name", RprToDis.parseEntityName("Viper-1") == null && RprToDis.parseEntityName("1-2") == null);
        int[] r = RprToDis.parseRadioName("1-2-3/radio-4");
        check("parse radio name", r != null && r[0] == 1 && r[3] == 4);
        check("not a radio name", RprToDis.parseRadioName("1-2-3/thing-4") == null);
        long tag = DisToRpr.streamTag(new DisEntityId(65535, 2, 3), 65535);
        int[] u = DisToRpr.unpackStreamTag(tag);
        check("stream tag round trip", u[0] == 65535 && u[1] == 2 && u[2] == 3 && u[3] == 65535);
        long ts = DisWriter.timestamp(1_800_000L, true);                  // half past
        check("timestamp: half an hour, absolute", (ts & 1) == 1 && Math.abs((ts >>> 1) - (1L << 30)) < 2);
    }

    // ------------------------------------------------------------------ helpers

    private static DisPduHeader header(byte[] pdu) {
        return DisPduHeader.read(new DisReader(pdu, 0, pdu.length));
    }

    private static DisReader reader(byte[] pdu) {
        DisReader r = new DisReader(pdu, 0, pdu.length);
        r.seek(DisPduHeader.SIZE);
        return r;
    }

    private static Function<String, HValue> decodeAttributes(String cls, Map<String, byte[]> raw) {
        String c = new IemRprModel(iem).nearestObjectClass(cls);
        Map<String, HValue> m = new HashMap<String, HValue>();
        for (Map.Entry<String, byte[]> e : raw.entrySet()) {
            com.simweaver.common.iem.ClassAttribute a = iem.getClassAttribute(c, e.getKey());
            if (a == null) {
                fail("no attribute " + e.getKey() + " on " + c);
                continue;
            }
            m.put(e.getKey(), codec.decode(a.getDataType(), e.getValue()));
        }
        return m::get;
    }

    // ------------------------------------------------------------------ Link 16 (SISO-STD-002-2021)

    /** Bits of a Link 16 bit stream: field values at bit positions, bit 0 = LSB of octet 0. */
    private static byte[] bitStream(int octets, long[][] fields) {
        byte[] b = new byte[octets];
        for (long[] f : fields) {                                   // {first bit, length, value}
            for (int k = 0; k < f[1]; k++) {
                if (((f[2] >>> k) & 1) != 0) {
                    int i = (int) f[0] + k;
                    b[i >>> 3] |= (byte) (1 << (i & 7));
                }
            }
        }
        return b;
    }

    /** A J-word: format, and for an initial word label, sublabel and length; data bits filled with a pattern. */
    private static byte[] jWord(int format, int label, int sublabel, int length, long fill) {
        if (format == 0) {
            return bitStream(10, new long[][] {{0, 2, 0}, {2, 5, label}, {7, 3, sublabel}, {10, 3, length}, {13, 50, fill}, {70, 5, 0x15}});
        }
        if (format == 1) {
            return bitStream(10, new long[][] {{0, 2, 1}, {2, 5, label}, {7, 60, fill}, {70, 5, 0x0A}});
        }
        return bitStream(10, new long[][] {{0, 2, 2}, {2, 60, fill}, {70, 5, 0x1F}});
    }

    private static byte[] networkHeader(int npg, int mti, int version) {
        DisWriter h = new DisWriter(20);
        h.u16(npg).u8(2).u8(255).u8(255).u8(mti).u8(version).u8(0).u32((1L << 24) | 0x1234).u32(0xE5F01234L).u32(0x80000000L);
        return h.toBytes();
    }

    private static byte[] signalPdu(int v, int words, int tdl, int bits, byte[] data) {
        DisWriter w = header(v, 26, 4);
        w.entityId(TANK).u16(2).u16((1 << 14) | words).u16(tdl).u32(0).u16(bits).u16(0).bytes(data);
        w.zeros(((data.length + 3) & ~3) - data.length);
        return w.finish();
    }

    /** As decodeParameters, for a Link 16 class found by its simple name; parameters the FOM lacks are left out. */
    private static Function<String, HValue> decodeLink16(DisToRpr.Interaction ix) {
        com.simweaver.common.iem.InteractionClass ic = null;
        for (com.simweaver.common.iem.InteractionClass c : iem.getInteractionClasses()) {
            if (c.getName().equalsIgnoreCase(ix.simpleName())) ic = c;
        }
        Map<String, HValue> m = new HashMap<String, HValue>();
        if (ic == null) {
            fail("no class " + ix.simpleName());
            return m::get;
        }
        for (Map.Entry<String, byte[]> e : ix.parameters.entrySet()) {
            com.simweaver.common.iem.ClassParameter p = iem.getClassParameter(ic.getQualifiedName(), e.getKey());
            if (p == null) {
                if (!DisToRpr.OPTIONAL_PARAMETERS.contains(e.getKey())) fail("no parameter " + e.getKey() + " on " + ic.getQualifiedName());
                continue;
            }
            m.put(e.getKey(), codec.decode(p.getDataType(), e.getValue()));
        }
        return m::get;
    }

    /** Signal PDUs with Link 16 (TDL type 100/113) <-> the Link16RadioSignal subclasses, byte for byte. */
    private static void link16(int v) {
        String pre = "v" + v + " Link 16 ";
        byte[] headerWord = bitStream(6, new long[][] {{0, 3, 1}, {3, 1, 0}, {4, 15, 0417}, {19, 16, 1234}});
        // JTIDS Header/Messages: J2.2 (initial + extension + continuation C1) and J3.2 (initial only)
        java.io.ByteArrayOutputStream md = new java.io.ByteArrayOutputStream();
        byte[][] words = {jWord(0, 2, 2, 2, 0x2AAAAAAAAAAAAL), jWord(2, 0, 0, 0, 0x123456789ABCL), jWord(1, 1, 0, 0, 0xFEDCBA987654L),
            jWord(0, 3, 2, 0, 0x0F0F0F0F0F0FL)};
        md.write(networkHeader(7, 0, 1), 0, 20);
        md.write(headerWord, 0, 6);
        for (byte[] w : words) md.write(w, 0, 10);
        byte[] data = md.toByteArray();
        byte[] in = signalPdu(v, 4, 100, data.length * 8, data);
        DisToRpr.Interaction ix = new DisToRpr(v).signal(header(in), reader(in));
        check(pre + "JTIDS message -> JTIDSMessageRadioSignal", ix != null && ix.className.endsWith(".JTIDSMessageRadioSignal"));
        Function<String, HValue> p = decodeLink16(ix);
        check(pre + "NPG 7, net 2, CVLLs 255, time slot, message count 4", p.apply("NPGNumber").asLong() == 7 && p.apply("NetNumber").asLong() == 2
                && p.apply("TSEC_CVLL").asLong() == 255 && p.apply("TimeSlotID").asLong() == ((1L << 24) | 0x1234)
                && p.apply("TDLMessageCount").asLong() == 4 && p.apply("TacticalDataLinkType").asLong() == 100);
        check(pre + "perceived transmit time", p.apply("PerceivedTransmitTime").field("Seconds").asLong() == 0xE5F01234L
                && p.apply("PerceivedTransmitTime").field("FractionalSeconds").asLong() == 0x80000000L);
        com.simweaver.common.hla.decoders.rpr.Link16.Header hw = com.simweaver.common.hla.decoders.rpr.Link16.parseHeader(
                RprToDis.flatOctets(p.apply("JTIDSHeader")), com.simweaver.common.hla.decoders.rpr.Link16.BitOrder.AUTO);
        check(pre + "header word: STN 00417, SDU 1234", hw != null && hw.sourceTrackNumber == 0417 && hw.sduSerialNumber == 1234);
        byte[] flat = RprToDis.flatOctets(p.apply("TADILJMessage"));
        List<byte[]> jw = new ArrayList<byte[]>();
        for (int i = 0; i + 10 <= flat.length; i += 10) jw.add(Arrays.copyOfRange(flat, i, i + 10));
        String text = com.simweaver.common.hla.decoders.rpr.Link16.parseMessages(jw, com.simweaver.common.hla.decoders.rpr.Link16.BitOrder.AUTO).text();
        check(pre + "the J-words read as messages: " + text, text.equals("J2.2 Air PPLI (3 words) + J3.2 Air Track (1 word)"));
        check(pre + "raw binary fallback for a FOM without the module", ix.fallback != null
                && ix.fallback.className.equals(DisToRpr.RAW_BINARY_SIGNAL) && Arrays.equals(ix.fallback.parameters.get("SignalData"), data));
        // the 2006 FOM has no SISOSTD002Version: back to DIS as 0 (its packing); everything else the same
        byte[] data0 = data.clone();
        data0[6] = 0;
        byte[] in0 = signalPdu(v, 4, 100, data0.length * 8, data0);
        byte[] out = new RprToDis(v, 7, 900, 1).link16Signal(TANK, 2, 0, decodeLink16(new DisToRpr(v).signal(header(in0), reader(in0))), 0);
        same(pre + "JTIDS message round trip", in0, out);
        Map<String, HValue> withVersion = new HashMap<String, HValue>();
        withVersion.put("SISOSTD002Version", HValue.ofInt(1));
        withVersion.put("Link16Version", HValue.ofInt(3));
        out = new RprToDis(v, 7, 900, 1).link16Signal(TANK, 2, 0, x -> withVersion.containsKey(x) ? withVersion.get(x) : p.apply(x), 0);
        check(pre + "a FOM with SISOSTD002Version / Link16Version: sent as they are", out.length > 39 && out[32 + 6] == 1 && out[32 + 7] == 3);

        // RTT A/B, RTT reply: the 48-bit word only
        for (int mti : new int[] {1, 2}) {
            md = new java.io.ByteArrayOutputStream();
            md.write(networkHeader(2 + mti - 1, mti, 0), 0, 20);
            md.write(headerWord, 0, 6);
            data = md.toByteArray();
            in = signalPdu(v, 1, 100, data.length * 8, data);
            ix = new DisToRpr(v).signal(header(in), reader(in));
            check(pre + "RTT " + mti + " -> " + (ix == null ? null : ix.simpleName()), ix != null && DisToRpr.link16MessageType(ix.className) == mti);
            out = new RprToDis(v, 7, 900, 1).link16Signal(TANK, 2, mti, decodeLink16(ix), 0);
            same(pre + "RTT " + mti + " round trip", in, out);
        }
        // JTIDS voice (CVSD): header word + voice octets
        md = new java.io.ByteArrayOutputStream();
        md.write(networkHeader(12, 3, 0), 0, 20);
        md.write(headerWord, 0, 6);
        byte[] voice = new byte[29];
        for (int i = 0; i < voice.length; i++) voice[i] = (byte) (i * 7 + 1);
        md.write(voice, 0, voice.length);
        data = md.toByteArray();
        in = signalPdu(v, 1, 100, data.length * 8, data);
        ix = new DisToRpr(v).signal(header(in), reader(in));
        Function<String, HValue> pv = decodeLink16(ix);
        check(pre + "voice CVSD: header and 29 octets of voice", ix.simpleName().equals("JTIDSVoiceCVSDRadioSignal")
                && RprToDis.flatOctets(pv.apply("Data")).length == 29);
        same(pre + "voice round trip", in, new RprToDis(v, 7, 900, 1).link16Signal(TANK, 2, 3, pv, 0));
        // LET (spelled JTIDSLETradioSignal in this FOM) and VMF, and the non-NATO surrogate TDL type 113
        md = new java.io.ByteArrayOutputStream();
        md.write(networkHeader(8, 6, 0), 0, 20);
        md.write(headerWord, 0, 6);
        md.write(words[3], 0, 10);
        md.write(words[3], 0, 10);
        data = md.toByteArray();
        in = signalPdu(v, 2, 113, data.length * 8, data);
        ix = new DisToRpr(v).signal(header(in), reader(in));
        check(pre + "LET -> JTIDSLETRadioSignal (found by name)", ix != null && DisToRpr.link16MessageType(ix.className) == 6
                && DisToRpr.link16MessageType("HLAinteractionRoot.X.JTIDSLETradioSignal") == 6);
        same(pre + "LET round trip (TDL type 113 kept)", in, new RprToDis(v, 7, 900, 1).link16Signal(TANK, 2, 6, decodeLink16(ix), 0));
        md = new java.io.ByteArrayOutputStream();
        md.write(networkHeader(9, 7, 0), 0, 20);
        md.write(headerWord, 0, 6);
        md.write(words[3], 0, 10);
        data = md.toByteArray();
        in = signalPdu(v, 1, 100, data.length * 8, data);
        ix = new DisToRpr(v).signal(header(in), reader(in));
        same(pre + "VMF round trip", in, new RprToDis(v, 7, 900, 1).link16Signal(TANK, 2, 7, decodeLink16(ix), 0));
        // too short for the network header: raw binary
        byte[] shortData = new byte[12];
        in = signalPdu(v, 1, 100, 96, shortData);
        ix = new DisToRpr(v).signal(header(in), reader(in));
        check(pre + "data shorter than the headers stays a RawBinaryRadioSignal", ix.className.equals(DisToRpr.RAW_BINARY_SIGNAL));
    }

    /** The Link 16 decoder's tables and texts (names from the Ersatz MIL-STD-6016 schema, LET header, time slot, NTP). */
    private static void link16Decoder() {
        Class<?> c = com.simweaver.common.hla.decoders.rpr.Link16.class;
        check("J28.2 is U.S. National 3 (Air Force), J11.1 To the Weapon",
                "U.S. National 3 (Air Force)".equals(com.simweaver.common.hla.decoders.rpr.Link16.messageName(28, 2))
                && "To the Weapon".equals(com.simweaver.common.hla.decoders.rpr.Link16.messageName(11, 1)) && c != null);
        check("categories: J3 Surveillance, J28 National Use, J4 unknown",
                "Surveillance".equals(com.simweaver.common.hla.decoders.rpr.Link16.category(3))
                && "National Use".equals(com.simweaver.common.hla.decoders.rpr.Link16.category(28))
                && com.simweaver.common.hla.decoders.rpr.Link16.category(4) == null);
        check("NPG 19 and 20: Fighter-to-Fighter A and B", "Fighter-to-Fighter A".equals(com.simweaver.common.hla.decoders.rpr.Link16.npgName(19))
                && "Fighter-to-Fighter B".equals(com.simweaver.common.hla.decoders.rpr.Link16.npgName(20)));
        byte[] let = bitStream(6, new long[][] {{0, 4, 2}, {4, 1, 1}, {5, 4, 3}, {9, 15, 0417}, {24, 16, 5}});
        com.simweaver.common.hla.decoders.rpr.Link16.LetHeader lh = com.simweaver.common.hla.decoders.rpr.Link16.parseLetHeader(let,
                com.simweaver.common.hla.decoders.rpr.Link16.BitOrder.AUTO);
        check("LET header: id 2, relay, packing 3, STN 00417, SDU 5", lh.letIdSymbol == 2 && lh.relay && lh.packingType == 3
                && lh.sourceTrackNumber == 0417 && lh.sduSerialNumber == 5);
        check("time slot: epoch 1, slot A-4661; C-32768 is the last; all ones no statement",
                com.simweaver.common.hla.decoders.rpr.Link16.timeSlotText((1L << 24) | 4660).equals("epoch 1, slot A-4661")
                && com.simweaver.common.hla.decoders.rpr.Link16.timeSlotText(98303).equals("epoch 0, slot C-32768")
                && com.simweaver.common.hla.decoders.rpr.Link16.timeSlotText(0xFFFFFFFFL).equals("no statement"));
        check("NTP time: 2026-10-23T00:00:00.500Z; all ones no statement",
                com.simweaver.common.hla.decoders.rpr.Link16.ntpText(1792713600L + 2208988800L, 0x80000000L).equals("2026-10-23T00:00:00.500Z")
                && com.simweaver.common.hla.decoders.rpr.Link16.ntpText(0xFFFFFFFFL, 0xFFFFFFFFL).equals("no statement"));
    }

    private static Function<String, HValue> decodeParameters(DisToRpr.Interaction ix) {
        Map<String, HValue> m = new HashMap<String, HValue>();
        for (Map.Entry<String, byte[]> e : ix.parameters.entrySet()) {
            com.simweaver.common.iem.ClassParameter p = iem.getClassParameter(ix.className, e.getKey());
            if (p == null) {
                fail("no parameter " + e.getKey() + " on " + ix.className);
                continue;
            }
            m.put(e.getKey(), codec.decode(p.getDataType(), e.getValue()));
        }
        return m::get;
    }

    /** Equal apart from the timestamp (octets 4-7). */
    private static void same(String what, byte[] in, byte[] out) {
        byte[] a = in.clone();
        byte[] b = out.clone();
        Arrays.fill(a, 4, 8, (byte) 0);
        Arrays.fill(b, 4, 8, (byte) 0);
        if (Arrays.equals(a, b)) {
            check(what, true);
            return;
        }
        StringBuilder sb = new StringBuilder(what + ": differs (in " + a.length + ", out " + b.length + " octets) at");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] & 0xFF : -1;
            int y = i < b.length ? b[i] & 0xFF : -1;
            if (x != y) {
                sb.append(' ').append(i).append(':').append(x).append('/').append(y);
            }
        }
        check(sb.toString(), false);
    }

    private static void fail(String what) {
        check(what, false);
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }
}
