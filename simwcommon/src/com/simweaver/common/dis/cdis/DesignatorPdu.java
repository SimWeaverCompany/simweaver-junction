package com.simweaver.common.dis.cdis;

import com.simweaver.common.dis.DisReader;
import com.simweaver.common.dis.DisWriter;

/**
 * Designator PDU (SISO-STD-023-2024 13.19, Table 63) ⇄ DIS 7 (88 octets). DIS gives the
 * wavelength in micrometres, C-DIS in nanometres.
 */
final class DesignatorPdu {

    static final int F_DESIGNATED = 0;
    static final int F_DETAILS = 1;
    static final int F_WORLD = 2;
    static final int F_DR = 3;

    private static final int G_CODE_NAME = 0;
    private static final int G_DESIGNATED_ID = 1;
    private static final int G_DETAILS = 2;
    private static final int G_SPOT_ENTITY = 3;
    private static final int G_WORLD = 4;
    private static final int G_DR = 5;
    private static final int GROUPS = 6;

    private static final int U_ENTITY = 0;
    private static final int U_ALTITUDE = 1;

    private DesignatorPdu() {
    }

    static boolean encode(DisReader r, BitWriter out, CdisCodec c) {
        r.seek(12);
        int site = r.u16();
        int app = r.u16();
        int ent = r.u16();
        int codeName = r.u16();
        int dsSite = r.u16();
        int dsApp = r.u16();
        int dsEnt = r.u16();
        int code = r.u16();
        float power = r.f32();
        float wavelength = r.f32();
        float sx = r.f32();
        float sy = r.f32();
        float sz = r.f32();
        double wx = r.f64();
        double wy = r.f64();
        double wz = r.f64();
        int dr = r.u8();
        r.skip(3);
        float ax = r.f32();
        float ay = r.f32();
        float az = r.f32();

        BodyWriter b = new BodyWriter(4, 2, true, GROUPS);
        b.fixed().uvint16(site).uvint16(app).uvint16(ent);
        b.group(G_CODE_NAME, F_DETAILS).bits(codeName, 16);
        boolean designated = dsSite != 0 || dsApp != 0 || dsEnt != 0 || sx != 0 || sy != 0 || sz != 0;
        if (designated) {
            b.group(G_DESIGNATED_ID, F_DESIGNATED).uvint16(dsSite).uvint16(dsApp).uvint16(dsEnt);
        }
        b.group(G_DETAILS, F_DETAILS).uvint16(code).uvint32(CdisRecords.round(power))
                .uvint32(CdisRecords.round(wavelength * 1000.0));
        if (designated) {
            boolean meters = CdisRecords.needsMeters(sx, sy, sz);
            b.unit(U_ENTITY, meters);
            CdisRecords.encEntityCoordinates(b.group(G_SPOT_ENTITY, F_DESIGNATED, U_ENTITY), sx, sy, sz, meters);
        }
        long[] world = CdisRecords.toWorld(wx, wy, wz);
        b.unit(U_ALTITUDE, world[3] != 0);
        CdisRecords.encWorld(b.group(G_WORLD, F_WORLD, U_ALTITUDE), world);
        if (dr != 0 || ax != 0 || ay != 0 || az != 0) {
            BitWriter d = b.group(G_DR, F_DR);
            d.unsigned(dr, 4);
            CdisRecords.encAcceleration(d, ax, ay, az);
        }
        c.stateful(CdisCodec.DESIGNATOR, ObjectStates.entityKey(site, app, ent), b, false);
        b.writeTo(out);
        return true;
    }

    static boolean decode(BitReader in, DisWriter w, CdisCodec c) {
        BodyReader b = new BodyReader(in, 4, 2, true, GROUPS);
        int site = (int) in.uvint16();
        int app = (int) in.uvint16();
        int ent = (int) in.uvint16();
        long key = ObjectStates.entityKey(site, app, ent);
        if (!c.attach(CdisCodec.DESIGNATOR, key, b)) {
            return false;
        }
        w.u16(site).u16(app).u16(ent);
        BodyReader.Group g = b.open(G_CODE_NAME, F_DETAILS);
        w.u16(g == null ? 0 : g.in.ubits(16));
        b.close(g);
        g = b.open(G_DESIGNATED_ID, F_DESIGNATED);
        if (g != null) {
            CdisRecords.decEntityId(g.in, w);
        } else {
            w.zeros(6);
        }
        b.close(g);
        g = b.open(G_DETAILS, F_DETAILS);
        if (g != null) {
            w.u16((int) g.in.uvint16()).f32(g.in.uvint32()).f32((float) (g.in.uvint32() / 1000.0));
        } else {
            w.zeros(10);
        }
        b.close(g);
        g = b.open(G_SPOT_ENTITY, F_DESIGNATED, U_ENTITY);
        if (g != null) {
            CdisRecords.decEntityCoordinates(g.in, w, g.unit);
        } else {
            w.zeros(12);
        }
        b.close(g);
        g = b.open(G_WORLD, F_WORLD, U_ALTITUDE);
        if (g != null) {
            CdisRecords.decWorld(g.in, w, g.unit);
        } else {
            w.zeros(24);
        }
        b.close(g);
        g = b.open(G_DR, F_DR);
        if (g != null) {
            w.u8(g.in.ubits(4)).zeros(3);
            CdisRecords.decAcceleration(g.in, w);
        } else {
            w.zeros(16);
        }
        b.close(g);
        c.commit(CdisCodec.DESIGNATOR, key, b, false);
        return true;
    }
}
