package com.simweaver.common.dis.cdis;

import com.simweaver.common.dis.DisReader;
import com.simweaver.common.dis.DisWriter;

import java.util.HashMap;
import java.util.Map;

/**
 * IFF PDU (SISO-STD-023-2024 13.23, Tables 67 to 73) ⇄ DIS 7: layer 1 (basic system data) and
 * layers 2 (emissions), 3 (Mode 5), 4 (Mode S) and 5 (data communications).
 *
 * <p>Layers 3 and 4 hold interrogator or transponder data by the system type. Interrogators are
 * system types 2, 4, 6, 7 and 8; every other type (combined interrogator/transponders and TCAS
 * too) is read as a transponder. The encoder always includes the system id when layer 3 or 4 is
 * present, so the decoder knows the type.
 *
 * <p>Only layer 1 is stateful: a partial update leaves out layer 1 fields that did not change;
 * layers 2 to 5 are in a PDU when they are in the DIS PDU.
 */
final class IffPdu {

    static final int F_EVENT_ID = 0;
    static final int F_RELATIVE_LOCATION = 1;
    static final int F_SYSTEM_ID = 2;
    static final int F_SYSTEM_SPECIFIC = 3;
    static final int F_DATA_FIELD_1 = 4;
    static final int F_DATA_FIELD_2 = 5;
    static final int F_PARAMETER_1 = 6;
    static final int FLAGS = 12;
    private static final int U_RELATIVE = 0;

    private static final int LAYER1_OCTETS = 60;

    private IffPdu() {
    }

    static boolean isInterrogator(int systemType) {
        return systemType == 2 || systemType == 4 || systemType == 6 || systemType == 7 || systemType == 8;
    }

    // ================================================================== encode

    static boolean encode(DisReader r, BitWriter out, CdisCodec c) {
        r.seek(12);
        int site = r.u16();
        int app = r.u16();
        int ent = r.u16();
        boolean eventId = !CdisRecords.isZero(r, 6);
        int eventAt = r.position();
        r.skip(6);
        float lx = r.f32();
        float ly = r.f32();
        float lz = r.f32();
        int systemType = r.u16();
        int systemName = r.u16();
        int systemMode = r.u8();
        int changeOptions = r.u8();
        int designator = r.u8();
        int systemSpecific = r.u8();
        int systemStatus = r.u8();
        int dataField1 = r.u8();
        int infoLayers = r.u8();
        int dataField2 = r.u8();
        int[] params = new int[6];
        for (int i = 0; i < 6; i++) {
            params[i] = r.u16();
        }

        // the layers that follow, by number
        Map<Integer, int[]> layers = new HashMap<Integer, int[]>(); // number -> {offset, length}
        int pos = LAYER1_OCTETS;
        while (pos + 4 <= r.length()) {
            r.seek(pos);
            int number = r.u8();
            r.skip(1);
            int length = r.u16();
            if (length < 4 || pos + length > r.length()) {
                break;
            }
            if (number >= 2 && number <= 5 && (infoLayers & (1 << number)) != 0 && !layers.containsKey(number)) {
                layers.put(number, new int[] {pos, length});
            }
            pos += length;
        }
        int sentLayers = infoLayers & 0x03;
        for (int n : layers.keySet()) {
            sentLayers |= 1 << n;
        }
        boolean needsType = layers.containsKey(3) || layers.containsKey(4);

        BodyWriter b = new BodyWriter(FLAGS, 1, true, FLAGS);
        b.fixed().uvint16(site).uvint16(app).uvint16(ent);
        if (eventId) {
            r.seek(eventAt);
            CdisRecords.encEntityId(r, b.group(F_EVENT_ID, F_EVENT_ID));
        }
        if (lx != 0 || ly != 0 || lz != 0) {
            boolean meters = CdisRecords.needsMeters(lx, ly, lz);
            b.unit(U_RELATIVE, meters);
            CdisRecords.encEntityCoordinates(b.group(F_RELATIVE_LOCATION, F_RELATIVE_LOCATION, U_RELATIVE), lx, ly, lz, meters);
        }
        b.group(F_SYSTEM_ID, F_SYSTEM_ID).unsigned(systemType, 4).unsigned(systemName, 5).unsigned(systemMode, 3)
                .bits(changeOptions, 8);
        b.fixed().bits(designator, 8);
        if (systemSpecific != 0) {
            b.group(F_SYSTEM_SPECIFIC, F_SYSTEM_SPECIFIC).bits(systemSpecific, 8);
        }
        b.fixed().bits(systemStatus, 8);
        if (dataField1 != 0) {
            b.group(F_DATA_FIELD_1, F_DATA_FIELD_1).bits(dataField1, 8);
        }
        b.fixed().bits(sentLayers, 8);
        if (dataField2 != 0) {
            b.group(F_DATA_FIELD_2, F_DATA_FIELD_2).bits(dataField2, 8);
        }
        for (int i = 0; i < 6; i++) {
            if (params[i] != 0) {
                b.group(F_PARAMETER_1 + i, F_PARAMETER_1 + i).bits(params[i], 16);
            }
        }
        BitWriter tail = b.fixed();
        for (int n = 2; n <= 5; n++) {
            int[] at = layers.get(n);
            if (at != null) {
                r.seek(at[0]);
                encodeLayer(n, new DisReader(r.bytes(at[1]), 0, at[1]), tail, systemType);
            }
        }
        long key = ObjectStates.entityKey(site, app, ent) << 8 | designator;
        if (needsType) {
            b.keep(F_SYSTEM_ID); // the decoder reads layers 3 and 4 by the system type
        }
        c.stateful(CdisCodec.IFF, key, b, false);
        b.writeTo(out);
        return true;
    }

    private static void encodeLayer(int number, DisReader r, BitWriter out, int systemType) {
        int start = out.bitLength();
        r.seek(1);
        int specific = r.u8();
        r.skip(2);
        BitWriter body = new BitWriter(32);
        boolean flag;
        switch (number) {
            case 2: {
                CdisRecords.encBeamData(r, body);
                body.bits(r.u8(), 8).bits(r.u8(), 8);
                int n = r.u16();
                n = Math.min(n, r.remaining() / 24);
                if (n > 255) {
                    throw new CdisFormatException(n + " IFF fundamental parameter records: C-DIS takes 255");
                }
                body.bits(n, 8);
                for (int i = 0; i < n; i++) {
                    float erp = r.f32();
                    float frequency = r.f32();
                    float pgrf = r.f32();
                    float pulseWidth = r.f32();
                    float burst = r.f32();
                    body.unsigned(CdisRecords.round(erp), 8);
                    CdisRecords.encFloat(body, frequency, 17, false, 4, false);
                    body.unsigned(CdisRecords.round(pgrf), 10).unsigned(CdisRecords.round(pulseWidth * 10.0), 10)
                            .unsigned(CdisRecords.round(burst), 10);
                    body.unsigned(r.u8(), 3);
                    body.bits(r.u8(), 8).bits(r.u8(), 8).bits(r.u8(), 8);
                }
                flag = true;
                break;
            }
            case 3: {
                body.uvint16(r.u16()).uvint16(r.u16());
                if (isInterrogator(systemType)) {
                    body.bits(r.u8(), 8);
                    r.skip(3);
                    body.bits(r.u32(), 32);
                    CdisRecords.encEntityId(r, body);
                    r.skip(2);
                } else {
                    body.bits(r.u16(), 16).bits(r.u16(), 16).bits(r.u32(), 32).bits(r.u16(), 16).bits(r.u16(), 16);
                    body.bits(r.u8(), 8).unsigned(r.u8(), 3).unsigned(r.u8(), 5);
                    r.skip(1);
                }
                r.skip(2);
                flag = encodeDataRecords(r, body);
                break;
            }
            case 4: {
                body.uvint16(r.u16()).uvint16(r.u16());
                if (isInterrogator(systemType)) {
                    body.bits(r.u8(), 8);
                    r.skip(1);
                    body.bits(r.u8(), 8);
                    r.skip(21);
                } else {
                    body.bits(r.u16(), 16).bits(r.u8(), 8).unsigned(r.u8(), 3);
                    byte[] id = r.bytes(8);
                    int n = 8;
                    while (n > 0 && id[n - 1] == 0) {
                        n--;
                    }
                    body.bits(n, 4).octets(id, 0, n);
                    body.bits(r.u32(), 32).unsigned(r.u8(), 3).bits(r.u8(), 8).bits(r.u16(), 16).bits(r.u8(), 8);
                    r.skip(3);
                }
                r.skip(2);
                flag = encodeDataRecords(r, body);
                break;
            }
            case 5: {
                body.uvint16(r.u16()).uvint16(r.u16());
                r.skip(2);
                body.unsigned(r.u8(), 6).unsigned(r.u8(), 3);
                r.skip(2);
                flag = encodeDataRecords(r, body);
                break;
            }
            default:
                throw new IllegalStateException("layer " + number);
        }
        out.flag(flag).bits(number, 4).bits(specific, 8);
        int lengthAt = out.bitLength();
        out.bits(0, 14).append(body);
        int bits = out.bitLength() - start;
        if (bits > 16383) {
            throw new CdisFormatException("IFF layer " + number + " of " + bits + " bits: C-DIS takes 16383");
        }
        out.patch(lengthAt, bits, 14);
    }

    /** DIS IFF data specification (count u16, records) to C-DIS ([count 5 bits, records]). @return the flag */
    private static boolean encodeDataRecords(DisReader r, BitWriter out) {
        int n = r.u16();
        if (n == 0) {
            return false;
        }
        if (n > 31) {
            throw new CdisFormatException(n + " IFF data records: C-DIS takes 31");
        }
        out.bits(n, 5);
        for (int i = 0; i < n; i++) {
            long type = r.u32();
            int length = r.u16();
            if (length < 6 || length - 6 > r.remaining()) {
                throw new CdisFormatException("an IFF data record of " + length + " octets");
            }
            byte[] data = r.bytes(length - 6);
            if (type > 0xFFFF || data.length + 3 > 255) {
                throw new CdisFormatException("IFF data record type " + type + " of " + length + " octets does not fit C-DIS");
            }
            out.bits(type, 16).bits(data.length + 3, 8).octets(data, 0, data.length);
        }
        return true;
    }

    // ================================================================== decode

    static boolean decode(BitReader in, DisWriter w, CdisCodec c) {
        BodyReader b = new BodyReader(in, FLAGS, 1, true, FLAGS);
        int site = (int) in.uvint16();
        int app = (int) in.uvint16();
        int ent = (int) in.uvint16();
        // the groups in this PDU come before the designator, which completes the object's key
        byte[] eventId = null;
        byte[] location = null;
        int[] systemId = null;
        BodyReader.Group g;
        if (b.has(F_EVENT_ID)) {
            g = b.open(F_EVENT_ID, F_EVENT_ID);
            eventId = readEventId(g);
            b.close(g);
        }
        if (b.has(F_RELATIVE_LOCATION)) {
            g = b.open(F_RELATIVE_LOCATION, F_RELATIVE_LOCATION, U_RELATIVE);
            location = readLocation(g);
            b.close(g);
        }
        if (b.has(F_SYSTEM_ID)) {
            g = b.open(F_SYSTEM_ID, F_SYSTEM_ID);
            systemId = readSystemId(g);
            b.close(g);
        }
        int designator = in.ubits(8);
        long key = ObjectStates.entityKey(site, app, ent) << 8 | designator;
        if (!c.attach(CdisCodec.IFF, key, b)) {
            return false;
        }
        if (eventId == null) {
            g = b.open(F_EVENT_ID, F_EVENT_ID);
            eventId = g == null ? new byte[6] : readEventId(g);
        }
        if (location == null) {
            g = b.open(F_RELATIVE_LOCATION, F_RELATIVE_LOCATION, U_RELATIVE);
            location = g == null ? new byte[12] : readLocation(g);
        }
        if (systemId == null) {
            g = b.open(F_SYSTEM_ID, F_SYSTEM_ID);
            systemId = g == null ? new int[4] : readSystemId(g);
        }
        w.u16(site).u16(app).u16(ent).bytes(eventId).bytes(location);
        w.u16(systemId[0]).u16(systemId[1]).u8(systemId[2]).u8(systemId[3]);
        w.u8(designator);
        w.u8(field(b, F_SYSTEM_SPECIFIC, 8));
        w.u8(in.ubits(8));
        w.u8(field(b, F_DATA_FIELD_1, 8));
        int infoLayers = in.ubits(8);
        w.u8(infoLayers);
        w.u8(field(b, F_DATA_FIELD_2, 8));
        for (int i = 0; i < 6; i++) {
            w.u16(field(b, F_PARAMETER_1 + i, 16));
        }
        c.commit(CdisCodec.IFF, key, b, false);
        for (int n = 2; n <= 5; n++) {
            if ((infoLayers & (1 << n)) != 0) {
                decodeLayer(n, in, w, systemId[0]);
            }
        }
        return true;
    }

    private static byte[] readEventId(BodyReader.Group g) {
        DisWriter e = new DisWriter(6);
        CdisRecords.decEntityId(g.in, e);
        return e.toBytes();
    }

    private static byte[] readLocation(BodyReader.Group g) {
        DisWriter l = new DisWriter(12);
        CdisRecords.decEntityCoordinates(g.in, l, g.unit);
        return l.toBytes();
    }

    private static int[] readSystemId(BodyReader.Group g) {
        return new int[] {g.in.ubits(4), g.in.ubits(5), g.in.ubits(3), g.in.ubits(8)};
    }

    private static int field(BodyReader b, int flag, int bits) {
        BodyReader.Group g = b.open(flag, flag);
        int v = g == null ? 0 : g.in.ubits(bits);
        b.close(g);
        return v;
    }

    private static void decodeLayer(int expected, BitReader in, DisWriter w, int systemType) {
        int start = in.position();
        boolean flag = in.flag();
        int number = in.ubits(4);
        int specific = in.ubits(8);
        int lengthBits = in.ubits(14);
        if (number != expected) {
            throw new CdisFormatException("IFF layer " + number + " where layer " + expected + " was expected");
        }
        DisWriter body = new DisWriter(64);
        switch (number) {
            case 2: {
                CdisRecords.decBeamData(in, body);
                body.u8(in.ubits(8)).u8(in.ubits(8));
                int n = in.ubits(8);
                body.u16(n);
                for (int i = 0; i < n; i++) {
                    int erp = in.ubits(8);
                    double frequency = CdisRecords.decFloat(in, 17, false, 4, false);
                    int pgrf = in.ubits(10);
                    int pulseWidth = in.ubits(10);
                    int burst = in.ubits(10);
                    body.f32(erp).f32((float) frequency).f32(pgrf).f32((float) (pulseWidth / 10.0)).f32(burst);
                    body.u8(in.ubits(3)).u8(in.ubits(8)).u8(in.ubits(8)).u8(in.ubits(8));
                }
                break;
            }
            case 3:
                body.u16((int) in.uvint16()).u16((int) in.uvint16());
                if (isInterrogator(systemType)) {
                    body.u8(in.ubits(8)).zeros(3).u32(in.bits(32));
                    CdisRecords.decEntityId(in, body);
                    body.zeros(2);
                } else {
                    body.u16(in.ubits(16)).u16(in.ubits(16)).u32(in.bits(32)).u16(in.ubits(16)).u16(in.ubits(16));
                    body.u8(in.ubits(8)).u8(in.ubits(3)).u8(in.ubits(5)).zeros(1);
                }
                body.zeros(2);
                decodeDataRecords(in, body, flag);
                break;
            case 4:
                body.u16((int) in.uvint16()).u16((int) in.uvint16());
                if (isInterrogator(systemType)) {
                    body.u8(in.ubits(8)).zeros(1).u8(in.ubits(8)).zeros(21);
                } else {
                    body.u16(in.ubits(16)).u8(in.ubits(8)).u8(in.ubits(3));
                    int n = in.ubits(4);
                    byte[] id = new byte[8];
                    for (int i = 0; i < n; i++) {
                        int ch = in.ubits(8);
                        if (i < 8) {
                            id[i] = (byte) ch;
                        }
                    }
                    body.bytes(id).u32(in.bits(32)).u8(in.ubits(3)).u8(in.ubits(8)).u16(in.ubits(16)).u8(in.ubits(8)).zeros(3);
                }
                body.zeros(2);
                decodeDataRecords(in, body, flag);
                break;
            case 5:
                body.u16((int) in.uvint16()).u16((int) in.uvint16()).zeros(2);
                body.u8(in.ubits(6)).u8(in.ubits(3)).zeros(2);
                decodeDataRecords(in, body, flag);
                break;
            default:
                throw new IllegalStateException("layer " + number);
        }
        int used = in.position() - start;
        if (lengthBits > used && start + lengthBits <= in.position() + in.remaining()) {
            in.seek(start + lengthBits);
        }
        byte[] octets = body.toBytes();
        w.u8(number).u8(specific).u16(octets.length + 4).bytes(octets);
    }

    private static void decodeDataRecords(BitReader in, DisWriter w, boolean present) {
        int n = present ? in.ubits(5) : 0;
        w.u16(n);
        for (int i = 0; i < n; i++) {
            int type = in.ubits(16);
            int length = in.ubits(8);
            if (length < 3) {
                throw new CdisFormatException("an IFF data record of " + length + " octets");
            }
            byte[] data = in.octets(length - 3);
            int dis = 6 + data.length;
            int padded = (dis + 3) / 4 * 4;
            w.u32(type).u16(padded).bytes(data).zeros(padded - dis);
        }
    }
}
