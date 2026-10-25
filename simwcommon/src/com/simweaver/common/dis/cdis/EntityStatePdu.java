package com.simweaver.common.dis.cdis;

import com.simweaver.common.dis.DisReader;
import com.simweaver.common.dis.DisWriter;

/** Entity State PDU (SISO-STD-023-2024 13.2, Table 46) ⇄ DIS 7 (144 octets + 16 per VP record). */
final class EntityStatePdu {

    // field present flags, in PDU order
    static final int F_FORCE = 0;
    static final int F_VP = 1;
    static final int F_TYPE = 2;
    static final int F_ALT_TYPE = 3;
    static final int F_VELOCITY = 4;
    static final int F_LOCATION = 5;
    static final int F_ORIENTATION = 6;
    static final int F_APPEARANCE = 7;
    static final int F_DR_OTHER = 8;
    static final int F_ACCELERATION = 9;
    static final int F_ANGULAR_VELOCITY = 10;
    static final int F_MARKING = 11;
    static final int F_CAPABILITIES = 12;
    static final int FLAGS = 13;

    // groups: one per flag, plus the VP records (flag F_VP, after the marking)
    private static final int G_VP_RECORDS = 13;
    private static final int GROUPS = 14;

    private static final int U_ALTITUDE = 0;

    /** Appearance bit 23: object state deactivated (the entity's last update). */
    static final long DEACTIVATED = 1L << 23;

    private EntityStatePdu() {
    }

    static boolean usesVelocity(int dr) {
        return dr >= 2 && dr <= 9;
    }

    static boolean usesAcceleration(int dr) {
        return dr == 4 || dr == 5 || dr == 8 || dr == 9;
    }

    static boolean usesAngularVelocity(int dr) {
        return dr == 3 || dr == 4 || dr == 7 || dr == 8;
    }

    private static boolean otherAlgorithm(int dr) {
        return dr == 0 || dr > 9;
    }

    private static boolean nonZero(float[] v) {
        return v[0] != 0 || v[1] != 0 || v[2] != 0;
    }

    /** @return the encoded body, or null when the PDU is suppressed */
    static boolean encode(DisReader r, BitWriter out, CdisCodec c) {
        r.seek(12);
        int site = r.u16();
        int app = r.u16();
        int ent = r.u16();
        int force = r.u8();
        int vpCount = r.u8();
        int typeAt = r.position();
        r.skip(8);
        boolean altType = !CdisRecords.isZero(r, 8);
        int altAt = r.position();
        r.skip(8);
        float[] vel = {r.f32(), r.f32(), r.f32()};
        double x = r.f64();
        double y = r.f64();
        double z = r.f64();
        float[] ori = {r.f32(), r.f32(), r.f32()};
        long appearance = r.u32();
        int dr = r.u8();
        boolean drOther = !CdisRecords.isZero(r, 15);
        byte[] other = r.bytes(15);
        float[] acc = {r.f32(), r.f32(), r.f32()};
        float[] ang = {r.f32(), r.f32(), r.f32()};
        r.skip(1);
        byte[] marking = r.bytes(11);
        long capabilities = r.u32();
        int vpAt = r.position();
        vpCount = Math.min(vpCount, r.remaining() / 16);

        BodyWriter b = new BodyWriter(FLAGS, 1, true, GROUPS);
        b.fixed().uvint16(site).uvint16(app).uvint16(ent);
        b.group(F_FORCE, F_FORCE).uvint8(force);
        if (vpCount > 0) {
            b.group(F_VP, F_VP).uvint8(vpCount);
        }
        r.seek(typeAt);
        CdisRecords.encEntityType(r, b.group(F_TYPE, F_TYPE));
        if (altType) {
            r.seek(altAt);
            CdisRecords.encEntityType(r, b.group(F_ALT_TYPE, F_ALT_TYPE));
        }
        if (usesVelocity(dr) || otherAlgorithm(dr) && nonZero(vel)) {
            CdisRecords.encVelocity(b.group(F_VELOCITY, F_VELOCITY), vel[0], vel[1], vel[2]);
        }
        long[] world = CdisRecords.toWorld(x, y, z);
        b.unit(U_ALTITUDE, world[3] != 0);
        CdisRecords.encWorld(b.group(F_LOCATION, F_LOCATION, U_ALTITUDE), world);
        CdisRecords.encOrientation(b.group(F_ORIENTATION, F_ORIENTATION), ori[0], ori[1], ori[2]);
        b.group(F_APPEARANCE, F_APPEARANCE).bits(appearance, 32);
        b.fixed().unsigned(dr, 4);
        if (drOther) {
            b.group(F_DR_OTHER, F_DR_OTHER).octets(other, 0, 15);
        }
        if (usesAcceleration(dr) || otherAlgorithm(dr) && nonZero(acc)) {
            CdisRecords.encAcceleration(b.group(F_ACCELERATION, F_ACCELERATION), acc[0], acc[1], acc[2]);
        }
        if (usesAngularVelocity(dr) || otherAlgorithm(dr) && nonZero(ang)) {
            CdisRecords.encAngularVelocity(b.group(F_ANGULAR_VELOCITY, F_ANGULAR_VELOCITY), ang[0], ang[1], ang[2]);
        }
        CdisRecords.encMarking(b.group(F_MARKING, F_MARKING), marking);
        if (capabilities != 0) {
            b.group(F_CAPABILITIES, F_CAPABILITIES).uvint32(capabilities);
        }
        if (vpCount > 0) {
            r.seek(vpAt);
            BitWriter vw = b.group(G_VP_RECORDS, F_VP);
            for (int i = 0; i < vpCount; i++) {
                CdisRecords.encVariableParameter(r, vw);
            }
        }
        long key = ObjectStates.entityKey(site, app, ent);
        boolean last = (appearance & DEACTIVATED) != 0;
        c.stateful(CdisCodec.ENTITY_STATE, key, b, last);
        b.writeTo(out);
        return true;
    }

    static boolean decode(BitReader in, DisWriter w, CdisCodec c) {
        BodyReader b = new BodyReader(in, FLAGS, 1, true, GROUPS);
        int site = (int) in.uvint16();
        int app = (int) in.uvint16();
        int ent = (int) in.uvint16();
        long key = ObjectStates.entityKey(site, app, ent);
        if (!c.attach(CdisCodec.ENTITY_STATE, key, b)) {
            return false;
        }
        w.u16(site).u16(app).u16(ent);

        BodyReader.Group g = b.open(F_FORCE, F_FORCE);
        w.u8(g == null ? 0 : (int) g.in.uvint8());
        b.close(g);
        g = b.open(F_VP, F_VP);
        int vpCount = g == null ? 0 : (int) g.in.uvint8();
        b.close(g);
        w.u8(vpCount);
        typeOrZero(b, F_TYPE, w);
        typeOrZero(b, F_ALT_TYPE, w);
        g = b.open(F_VELOCITY, F_VELOCITY);
        if (g != null) {
            CdisRecords.decVelocity(g.in, w);
        } else {
            w.zeros(12);
        }
        b.close(g);
        g = b.open(F_LOCATION, F_LOCATION, U_ALTITUDE);
        if (g != null) {
            CdisRecords.decWorld(g.in, w, g.unit);
        } else {
            w.zeros(24);
        }
        b.close(g);
        g = b.open(F_ORIENTATION, F_ORIENTATION);
        if (g != null) {
            CdisRecords.decOrientation(g.in, w);
        } else {
            w.zeros(12);
        }
        b.close(g);
        g = b.open(F_APPEARANCE, F_APPEARANCE);
        long appearance = g == null ? 0 : g.in.bits(32);
        b.close(g);
        w.u32(appearance);
        w.u8(in.ubits(4));
        g = b.open(F_DR_OTHER, F_DR_OTHER);
        w.bytes(g == null ? new byte[15] : g.in.octets(15));
        b.close(g);
        g = b.open(F_ACCELERATION, F_ACCELERATION);
        if (g != null) {
            CdisRecords.decAcceleration(g.in, w);
        } else {
            w.zeros(12);
        }
        b.close(g);
        g = b.open(F_ANGULAR_VELOCITY, F_ANGULAR_VELOCITY);
        if (g != null) {
            CdisRecords.decAngularVelocity(g.in, w);
        } else {
            w.zeros(12);
        }
        b.close(g);
        g = b.open(F_MARKING, F_MARKING);
        if (g != null) {
            CdisRecords.decMarking(g.in, w);
        } else {
            w.u8(1).zeros(11);
        }
        b.close(g);
        g = b.open(F_CAPABILITIES, F_CAPABILITIES);
        w.u32(g == null ? 0 : g.in.uvint32());
        b.close(g);
        g = b.open(G_VP_RECORDS, F_VP);
        if (g != null) {
            for (int i = 0; i < vpCount; i++) {
                CdisRecords.decVariableParameter(g.in, w);
            }
        }
        b.close(g);
        c.commit(CdisCodec.ENTITY_STATE, key, b, (appearance & DEACTIVATED) != 0);
        return true;
    }

    private static void typeOrZero(BodyReader b, int flag, DisWriter w) {
        BodyReader.Group g = b.open(flag, flag);
        if (g != null) {
            CdisRecords.decEntityType(g.in, w);
        } else {
            w.zeros(8);
        }
        b.close(g);
    }
}
