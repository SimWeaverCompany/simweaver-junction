package com.simweaver.common.dis.cdis;

import com.simweaver.common.dis.DisReader;
import com.simweaver.common.dis.DisWriter;

/**
 * The simulation management PDUs (SISO-STD-023-2024 13.6 to 13.17): Create Entity, Remove
 * Entity, Start/Resume, Stop/Freeze, Acknowledge, Action Request, Action Response, Data Query,
 * Set Data, Data, Event Report and Comment ⇄ DIS 7. All start with the originating and receiving
 * ids; the datum PDUs have two field present flags, fixed datums and variable datums.
 */
final class SimanPdus {

    private static final int F_FIXED = 0;
    private static final int F_VARIABLE = 1;

    private SimanPdus() {
    }

    static boolean encode(int type, DisReader r, BitWriter out) {
        r.seek(12);
        boolean datums = hasDatums(type);
        BodyWriter b = new BodyWriter(datums ? 2 : 0, 0, false, 2);
        BitWriter f = b.fixed();
        CdisRecords.encEntityId(r, f);
        CdisRecords.encEntityId(r, f);
        switch (type) {
            case CdisCodec.CREATE_ENTITY:
            case CdisCodec.REMOVE_ENTITY:
                f.uvint32(r.u32());
                break;
            case CdisCodec.START_RESUME:
                f.bits(r.u32(), 32).bits(r.u32(), 32).bits(r.u32(), 32).bits(r.u32(), 32);
                f.uvint32(r.u32());
                break;
            case CdisCodec.STOP_FREEZE:
                f.bits(r.u32(), 32).bits(r.u32(), 32);
                f.unsigned(r.u8(), 4).unsigned(r.u8(), 2);
                r.skip(2);
                f.uvint32(r.u32());
                break;
            case CdisCodec.ACKNOWLEDGE:
                f.unsigned(r.u16(), 3).unsigned(r.u16(), 2);
                f.uvint32(r.u32());
                break;
            case CdisCodec.ACTION_REQUEST:
            case CdisCodec.ACTION_RESPONSE:
                f.uvint32(r.u32()).uvint32(r.u32());
                break;
            case CdisCodec.DATA_QUERY:
                f.uvint32(r.u32());
                f.bits(CdisRecords.timestampToCdis(r.u32()), 26);
                break;
            case CdisCodec.SET_DATA:
            case CdisCodec.DATA:
                f.uvint32(r.u32());
                r.skip(4);
                break;
            case CdisCodec.EVENT_REPORT:
                f.uvint32(r.u32());
                r.skip(4);
                break;
            case CdisCodec.COMMENT:
                break;
            default:
                throw new IllegalArgumentException("not a simulation management PDU: " + type);
        }
        if (datums) {
            long nFixed = r.u32();
            long nVariable = r.u32();
            if (nFixed > 255 || nVariable > 255) {
                throw new CdisFormatException(nFixed + " fixed and " + nVariable + " variable datums: C-DIS takes 255 of each");
            }
            boolean ids = type == CdisCodec.DATA_QUERY;
            if (nFixed > 0) {
                b.group(0, F_FIXED).uvint8(nFixed);
            }
            if (nVariable > 0) {
                b.group(1, F_VARIABLE).uvint8(nVariable);
            }
            BitWriter d = b.fixed();
            if (ids) {
                for (long i = 0; i < nFixed + nVariable; i++) {
                    d.bits(r.u32(), 32);
                }
            } else {
                CdisRecords.encFixedDatums(r, d, (int) nFixed);
                CdisRecords.encVariableDatums(r, d, (int) nVariable);
            }
        }
        b.writeTo(out);
        return true;
    }

    static boolean decode(int type, BitReader in, DisWriter w) {
        boolean datums = hasDatums(type);
        BodyReader b = new BodyReader(in, datums ? 2 : 0, 0, false, 2);
        CdisRecords.decEntityId(in, w);
        CdisRecords.decEntityId(in, w);
        switch (type) {
            case CdisCodec.CREATE_ENTITY:
            case CdisCodec.REMOVE_ENTITY:
                w.u32(in.uvint32());
                break;
            case CdisCodec.START_RESUME:
                w.u32(in.bits(32)).u32(in.bits(32)).u32(in.bits(32)).u32(in.bits(32));
                w.u32(in.uvint32());
                break;
            case CdisCodec.STOP_FREEZE:
                w.u32(in.bits(32)).u32(in.bits(32));
                w.u8(in.ubits(4)).u8(in.ubits(2)).zeros(2);
                w.u32(in.uvint32());
                break;
            case CdisCodec.ACKNOWLEDGE:
                w.u16(in.ubits(3)).u16(in.ubits(2));
                w.u32(in.uvint32());
                break;
            case CdisCodec.ACTION_REQUEST:
            case CdisCodec.ACTION_RESPONSE:
                w.u32(in.uvint32()).u32(in.uvint32());
                break;
            case CdisCodec.DATA_QUERY:
                w.u32(in.uvint32());
                w.u32(CdisRecords.timestampToDis(in.bits(26)));
                break;
            case CdisCodec.SET_DATA:
            case CdisCodec.DATA:
            case CdisCodec.EVENT_REPORT:
                w.u32(in.uvint32()).zeros(4);
                break;
            case CdisCodec.COMMENT:
                break;
            default:
                throw new IllegalArgumentException("not a simulation management PDU: " + type);
        }
        if (datums) {
            int nFixed = b.has(F_FIXED) ? (int) in.uvint8() : 0;
            int nVariable = b.has(F_VARIABLE) ? (int) in.uvint8() : 0;
            w.u32(nFixed).u32(nVariable);
            if (type == CdisCodec.DATA_QUERY) {
                for (int i = 0; i < nFixed + nVariable; i++) {
                    w.u32(in.bits(32));
                }
            } else {
                CdisRecords.decFixedDatums(in, w, nFixed);
                CdisRecords.decVariableDatums(in, w, nVariable);
            }
        }
        return true;
    }

    private static boolean hasDatums(int type) {
        return type >= CdisCodec.ACTION_REQUEST && type <= CdisCodec.COMMENT;
    }
}
