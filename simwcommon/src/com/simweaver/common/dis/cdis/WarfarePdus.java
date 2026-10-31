package com.simweaver.common.dis.cdis;

import com.simweaver.common.dis.DisReader;
import com.simweaver.common.dis.DisWriter;

/**
 * Fire (13.3, Table 47), Detonation (13.4, Table 48) and Collision (13.5, Table 49) PDUs ⇄ DIS 7.
 * These are events: always complete, no state.
 */
final class WarfarePdus {

    private WarfarePdus() {
    }

    // ================================================================== Fire (DIS 7: 96 octets)

    private static final int FIRE_MISSION = 0;
    private static final int FIRE_WARHEAD_FUSE = 1;
    private static final int FIRE_QUANTITY_RATE = 2;
    private static final int FIRE_RANGE = 3;

    static boolean encodeFire(DisReader r, BitWriter out) {
        r.seek(12);
        BodyWriter b = new BodyWriter(4, 1, false, 4);
        BitWriter ids = b.fixed();
        for (int i = 0; i < 4; i++) {
            CdisRecords.encEntityId(r, ids);
        }
        long mission = r.u32();
        if (mission != 0) {
            b.group(FIRE_MISSION, FIRE_MISSION).uvint32(mission);
        }
        long[] world = CdisRecords.toWorld(r.f64(), r.f64(), r.f64());
        b.unit(0, world[3] != 0);
        BitWriter mid = b.fixed();
        CdisRecords.encWorld(mid, world);
        CdisRecords.encEntityType(r, mid);
        descriptorTail(r, b, FIRE_WARHEAD_FUSE, FIRE_QUANTITY_RATE, true);
        CdisRecords.encVelocity(b.fixed(), r.f32(), r.f32(), r.f32());
        long range = CdisRecords.round(r.f32());
        if (range > 0) {
            b.group(FIRE_RANGE, FIRE_RANGE).uvint32(range);
        }
        b.writeTo(out);
        return true;
    }

    /** Warhead, fuse (16 bits each), quantity, rate (8 bits each), each pair when not zero. */
    private static void descriptorTail(DisReader r, BodyWriter b, int warheadFlag, int quantityFlag, boolean quantityRate) {
        int warhead = r.u16();
        int fuse = r.u16();
        int quantity = r.u16();
        int rate = r.u16();
        if (warhead != 0 || fuse != 0) {
            b.group(warheadFlag, warheadFlag).bits(warhead, 16).bits(fuse, 16);
        }
        if (quantityRate && (quantity != 0 || rate != 0)) {
            b.group(quantityFlag, quantityFlag).unsigned(quantity, 8).unsigned(rate, 8);
        }
    }

    static boolean decodeFire(BitReader in, DisWriter w) {
        BodyReader b = new BodyReader(in, 4, 1, false, 4);
        for (int i = 0; i < 4; i++) {
            CdisRecords.decEntityId(in, w);
        }
        w.u32(b.has(FIRE_MISSION) ? in.uvint32() : 0);
        CdisRecords.decWorld(in, w, b.unit(0));
        CdisRecords.decEntityType(in, w);
        decodeDescriptorTail(in, b, w, FIRE_WARHEAD_FUSE, FIRE_QUANTITY_RATE);
        CdisRecords.decVelocity(in, w);
        w.f32(b.has(FIRE_RANGE) ? in.uvint32() : 0);
        return true;
    }

    private static void decodeDescriptorTail(BitReader in, BodyReader b, DisWriter w, int warheadFlag, int quantityFlag) {
        if (b.has(warheadFlag)) {
            w.u16(in.ubits(16)).u16(in.ubits(16));
        } else {
            w.zeros(4);
        }
        if (b.has(quantityFlag)) {
            w.u16(in.ubits(8)).u16(in.ubits(8));
        } else {
            w.zeros(4);
        }
    }

    // ================================================================== Detonation (DIS 7: 104 octets + 16 per VP record)

    private static final int DET_WARHEAD_FUSE = 0;
    private static final int DET_QUANTITY_RATE = 1;
    private static final int DET_VP = 2;

    /**
     * @param explosion the PDU status says the descriptor is an Explosion descriptor (explosive
     *                  material and force): the force cannot be carried in C-DIS 2024 and is dropped
     */
    static boolean encodeDetonation(DisReader r, BitWriter out, boolean explosion) {
        r.seek(12);
        BodyWriter b = new BodyWriter(3, 2, false, 3);
        BitWriter ids = b.fixed();
        for (int i = 0; i < 4; i++) {
            CdisRecords.encEntityId(r, ids);
        }
        BitWriter mid = b.fixed();
        CdisRecords.encVelocity(mid, r.f32(), r.f32(), r.f32());
        long[] world = CdisRecords.toWorld(r.f64(), r.f64(), r.f64());
        b.unit(0, world[3] != 0);
        CdisRecords.encWorld(mid, world);
        CdisRecords.encEntityType(r, mid);
        descriptorTail(r, b, DET_WARHEAD_FUSE, DET_QUANTITY_RATE, !explosion);
        float ex = r.f32();
        float ey = r.f32();
        float ez = r.f32();
        boolean meters = CdisRecords.needsMeters(ex, ey, ez);
        b.unit(1, meters);
        BitWriter tail = b.fixed();
        CdisRecords.encEntityCoordinates(tail, ex, ey, ez, meters);
        tail.uvint8(r.u8());
        int vpCount = Math.min(r.u8(), (r.remaining() - 2) / 16);
        r.skip(2);
        if (vpCount > 0) {
            BitWriter vw = b.group(DET_VP, DET_VP);
            vw.bits(vpCount, 8);
            for (int i = 0; i < vpCount; i++) {
                CdisRecords.encVariableParameter(r, vw);
            }
        }
        b.writeTo(out);
        return true;
    }

    static boolean decodeDetonation(BitReader in, DisWriter w) {
        BodyReader b = new BodyReader(in, 3, 2, false, 3);
        for (int i = 0; i < 4; i++) {
            CdisRecords.decEntityId(in, w);
        }
        CdisRecords.decVelocity(in, w);
        CdisRecords.decWorld(in, w, b.unit(0));
        CdisRecords.decEntityType(in, w);
        decodeDescriptorTail(in, b, w, DET_WARHEAD_FUSE, DET_QUANTITY_RATE);
        CdisRecords.decEntityCoordinates(in, w, b.unit(1));
        w.u8((int) in.uvint8());
        int vpCount = b.has(DET_VP) ? in.ubits(8) : 0;
        w.u8(vpCount).zeros(2);
        for (int i = 0; i < vpCount; i++) {
            CdisRecords.decVariableParameter(in, w);
        }
        return true;
    }

    // ================================================================== Collision (DIS 7: 60 octets)

    static boolean encodeCollision(DisReader r, BitWriter out) {
        r.seek(12);
        BodyWriter b = new BodyWriter(0, 2, false, 0);
        BitWriter f = b.fixed();
        for (int i = 0; i < 3; i++) {
            CdisRecords.encEntityId(r, f);
        }
        f.unsigned(r.u8(), 1);
        r.skip(1);
        CdisRecords.encVelocity(f, r.f32(), r.f32(), r.f32());
        float kg = r.f32();
        long grams = CdisRecords.round(kg * 1000.0);
        boolean inKg = grams > 0xFFFFFFFFL;
        b.unit(1, inKg);
        f.uvint32(inKg ? CdisRecords.round(kg) : Math.max(0, grams));
        float lx = r.f32();
        float ly = r.f32();
        float lz = r.f32();
        boolean meters = CdisRecords.needsMeters(lx, ly, lz);
        b.unit(0, meters);
        CdisRecords.encEntityCoordinates(f, lx, ly, lz, meters);
        b.writeTo(out);
        return true;
    }

    static boolean decodeCollision(BitReader in, DisWriter w) {
        BodyReader b = new BodyReader(in, 0, 2, false, 0);
        for (int i = 0; i < 3; i++) {
            CdisRecords.decEntityId(in, w);
        }
        w.u8(in.ubits(1)).zeros(1);
        CdisRecords.decVelocity(in, w);
        long mass = in.uvint32();
        w.f32(b.unit(1) ? mass : (float) (mass / 1000.0));
        CdisRecords.decEntityCoordinates(in, w, b.unit(0));
        return true;
    }
}
