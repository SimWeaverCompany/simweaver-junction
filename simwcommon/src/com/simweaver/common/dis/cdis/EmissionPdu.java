package com.simweaver.common.dis.cdis;

import com.simweaver.common.dis.DisReader;
import com.simweaver.common.dis.DisWriter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Electromagnetic Emission PDU (SISO-STD-023-2024 13.18, Table 62) ⇄ DIS 7. C-DIS lists the
 * unique fundamental parameters, beam data and site/application pairs once and lets the beams and
 * track/jam records index them.
 *
 * <p>Partial updates: a system's name and function and its location, and a beam's fundamental
 * parameters and beam data, are left out when they did not change; the decoder fills them in from
 * the last update of the same emitter system (number) and beam (id).
 */
final class EmissionPdu {

    private static final int MAX_LIST = 31;
    private static final int MAX_SITE_APPS = 63;

    private EmissionPdu() {
    }

    /** One DIS beam as read. */
    private static final class Beam {
        int id;
        int parameterIndex;
        float[] fundamental = new float[5];
        float[] beamData = new float[5];
        int function;
        int targets;
        int highDensity;
        int status;
        int[] jamming = new int[4];
        int[][] trackJam; // site, app, entity, emitter, beam
    }

    /** One DIS emitter system as read. */
    private static final class System {
        int name;
        int function;
        int number;
        float[] location = new float[3];
        List<Beam> beams = new ArrayList<Beam>();
    }

    /** Encoder / decoder state of an emitting entity: per system and beam, the last values (as bits or DIS octets). */
    private static final class Memory {
        final Map<Integer, Object> systemDetails = new HashMap<Integer, Object>();
        final Map<Integer, Object> systemLocation = new HashMap<Integer, Object>();
        final Map<Integer, Object> fundamental = new HashMap<Integer, Object>();
        final Map<Integer, Object> beamData = new HashMap<Integer, Object>();
    }

    private static int beamKey(int system, int beam) {
        return system << 8 | beam;
    }

    // ================================================================== encode

    static boolean encode(DisReader r, BitWriter out, CdisCodec c) {
        r.seek(12);
        int site = r.u16();
        int app = r.u16();
        int ent = r.u16();
        int evSite = r.u16();
        int evApp = r.u16();
        int evNum = r.u16();
        int stateUpdate = r.u8();
        int systemCount = r.u8();
        r.skip(2);
        List<System> systems = new ArrayList<System>();
        for (int s = 0; s < systemCount; s++) {
            int systemStart = r.position();
            int systemWords = r.u8();
            int beamCount = r.u8();
            r.skip(2);
            System sys = new System();
            sys.name = r.u16();
            sys.function = r.u8();
            sys.number = r.u8();
            sys.location = new float[] {r.f32(), r.f32(), r.f32()};
            for (int bi = 0; bi < beamCount; bi++) {
                int beamStart = r.position();
                int beamWords = r.u8();
                Beam b = new Beam();
                b.id = r.u8();
                b.parameterIndex = r.u16();
                for (int i = 0; i < 5; i++) {
                    b.fundamental[i] = r.f32();
                }
                for (int i = 0; i < 5; i++) {
                    b.beamData[i] = r.f32();
                }
                b.function = r.u8();
                b.targets = r.u8();
                b.highDensity = r.u8();
                b.status = r.u8();
                for (int i = 0; i < 4; i++) {
                    b.jamming[i] = r.u8();
                }
                int n = Math.min(b.targets, r.remaining() / 8);
                b.trackJam = new int[n][];
                for (int t = 0; t < n; t++) {
                    b.trackJam[t] = new int[] {r.u16(), r.u16(), r.u16(), r.u8(), r.u8()};
                }
                if (beamWords > 0) {
                    r.seek(Math.min(beamStart + beamWords * 4, r.length()));
                }
                sys.beams.add(b);
            }
            if (systemWords > 0) {
                r.seek(Math.min(systemStart + systemWords * 4, r.length()));
            }
            systems.add(sys);
        }

        long key = ObjectStates.entityKey(site, app, ent);
        ObjectStates.Encoded state = c.encoderState(CdisCodec.ELECTROMAGNETIC_EMISSION, key);
        boolean full = true;
        Memory last = null;
        if (state != null) {
            last = (Memory) state.extra;
            boolean due = c.fullUpdateDue(CdisCodec.ELECTROMAGNETIC_EMISSION, state) || last == null || systems.isEmpty();
            if (!due) {
                for (System sys : systems) {
                    if (!last.systemDetails.containsKey(sys.number)) {
                        due = true;
                    }
                    for (Beam b : sys.beams) {
                        if (!last.fundamental.containsKey(beamKey(sys.number, b.id))) {
                            due = true;
                        }
                    }
                }
            }
            full = due;
        }
        Memory now = new Memory();

        // unique lists
        Map<BitSlice, Integer> fps = new LinkedHashMap<BitSlice, Integer>();
        Map<BitSlice, Integer> bds = new LinkedHashMap<BitSlice, Integer>();
        Map<Long, Integer> siteApps = new LinkedHashMap<Long, Integer>();
        BitWriter systemBits = new BitWriter(64);
        for (System sys : systems) {
            BitWriter details = new BitWriter(4).bits(sys.name, 16).bits(sys.function, 8);
            BitWriter location = new BitWriter(8);
            CdisRecords.encEntityCoordinates(location, sys.location[0], sys.location[1], sys.location[2], true);
            BitSlice detailsKey = details.toSlice();
            BitSlice locationKey = location.toSlice();
            now.systemDetails.put(sys.number, detailsKey);
            now.systemLocation.put(sys.number, locationKey);
            boolean sendDetails = full || !detailsKey.equals(last.systemDetails.get(sys.number));
            boolean locationNonZero = sys.location[0] != 0 || sys.location[1] != 0 || sys.location[2] != 0;
            boolean sendLocation = full ? locationNonZero : !locationKey.equals(last.systemLocation.get(sys.number));
            if (sys.beams.size() > MAX_LIST) {
                throw new CdisFormatException("an emitter system with " + sys.beams.size() + " beams: C-DIS takes 31");
            }
            systemBits.flag(sendDetails).flag(sendLocation).bits(sys.beams.size(), 5);
            if (sendDetails) {
                systemBits.append(details);
            }
            systemBits.uvint8(sys.number);
            if (sendLocation) {
                systemBits.append(location);
            }
            for (Beam b : sys.beams) {
                int bk = beamKey(sys.number, b.id);
                BitWriter fp = new BitWriter(8);
                CdisRecords.encFloat(fp, b.fundamental[0], 17, false, 4, false);
                CdisRecords.encFloat(fp, b.fundamental[1], 17, false, 4, false);
                fp.unsigned(CdisRecords.round(b.fundamental[2]), 8);
                fp.uvint16(CdisRecords.round(b.fundamental[3] / 100.0));
                CdisRecords.encFloat(fp, b.fundamental[4], 14, false, 3, true);
                BitWriter bd = new BitWriter(8);
                CdisRecords.encBeamData(bd, b.beamData[0], b.beamData[1], b.beamData[2], b.beamData[3], b.beamData[4]);
                BitSlice fpKey = fp.toSlice();
                BitSlice bdKey = bd.toSlice();
                now.fundamental.put(bk, fpKey);
                now.beamData.put(bk, bdKey);
                boolean sendFp = full || !fpKey.equals(last.fundamental.get(bk));
                boolean sendBd = full || !bdKey.equals(last.beamData.get(bk));
                boolean jamming = b.jamming[0] != 0 || b.jamming[1] != 0 || b.jamming[2] != 0 || b.jamming[3] != 0;
                boolean many = b.trackJam.length > 15;
                boolean jammerTrack = false;
                if (!many) {
                    for (int[] tj : b.trackJam) {
                        jammerTrack |= tj[3] != 0 || tj[4] != 0;
                    }
                }
                systemBits.flag(sendFp).flag(sendBd).flag(jamming).flag(jammerTrack);
                systemBits.uvint8(b.id).bits(b.parameterIndex, 16);
                if (sendFp) {
                    systemBits.bits(index(fps, fpKey, MAX_LIST, "fundamental parameter sets"), 5);
                }
                if (sendBd) {
                    systemBits.bits(index(bds, bdKey, MAX_LIST, "beam data sets"), 5);
                }
                systemBits.unsigned(b.function, 5);
                systemBits.bits(many ? 0 : b.trackJam.length, 4);
                systemBits.flag(many || b.highDensity != 0);
                systemBits.flag((b.status & 1) == 0);
                if (jamming) {
                    systemBits.uvint8(b.jamming[0]).uvint8(b.jamming[1]).uvint8(b.jamming[2]).uvint8(b.jamming[3]);
                }
                if (!many) {
                    for (int[] tj : b.trackJam) {
                        long sa = (long) tj[0] << 16 | tj[1];
                        systemBits.bits(siteAppIndex(siteApps, sa), 6).uvint16(tj[2]);
                        if (jammerTrack) {
                            systemBits.uvint8(tj[3]).uvint8(tj[4]);
                        }
                    }
                }
            }
        }

        out.flag(full).bits(fps.size(), 5).bits(bds.size(), 5).bits(siteApps.size(), 6);
        out.uvint16(site).uvint16(app).uvint16(ent).uvint16(evSite).uvint16(evApp).uvint16(evNum);
        out.unsigned(stateUpdate, 1).uvint8(systems.size());
        for (BitSlice s : fps.keySet()) {
            out.slice(s);
        }
        for (BitSlice s : bds.keySet()) {
            out.slice(s);
        }
        for (long sa : siteApps.keySet()) {
            out.uvint16(sa >>> 16).uvint16(sa & 0xFFFF);
        }
        out.append(systemBits);

        if (state != null || c.partialMode()) {
            ObjectStates.Encoded s = c.encoderStateCreate(CdisCodec.ELECTROMAGNETIC_EMISSION, key);
            s.extra = now;
            if (full) {
                s.lastFull = c.now();
            }
            c.countUpdate(full);
        }
        return true;
    }

    private static int index(Map<BitSlice, Integer> list, BitSlice key, int max, String what) {
        Integer i = list.get(key);
        if (i == null) {
            if (list.size() >= max) {
                throw new CdisFormatException("more than " + max + " different " + what + " in one EE PDU");
            }
            i = list.size();
            list.put(key, i);
        }
        return i;
    }

    private static int siteAppIndex(Map<Long, Integer> list, long key) {
        Integer i = list.get(key);
        if (i == null) {
            if (list.size() >= MAX_SITE_APPS) {
                throw new CdisFormatException("more than 63 site/application pairs in one EE PDU");
            }
            i = list.size();
            list.put(key, i);
        }
        return i;
    }

    // ================================================================== decode

    static boolean decode(BitReader in, DisWriter w, CdisCodec c) {
        boolean full = in.flag();
        int nFp = in.ubits(5);
        int nBd = in.ubits(5);
        int nSa = in.ubits(6);
        int site = (int) in.uvint16();
        int app = (int) in.uvint16();
        int ent = (int) in.uvint16();
        long key = ObjectStates.entityKey(site, app, ent);
        Memory last = null;
        if (!full) {
            ObjectStates.Decoded s = c.decoderState(CdisCodec.ELECTROMAGNETIC_EMISSION, key);
            if (s == null || s.extra == null) {
                c.waitingForFull();
                return false;
            }
            last = (Memory) s.extra;
        }
        Memory now = new Memory();
        w.u16(site).u16(app).u16(ent);
        CdisRecords.decEntityId(in, w);
        w.u8(in.ubits(1));
        int systemCount = (int) in.uvint8();
        w.u8(systemCount).zeros(2);
        byte[][] fps = new byte[nFp][];
        for (int i = 0; i < nFp; i++) {
            DisWriter f = new DisWriter(20);
            f.f32((float) CdisRecords.decFloat(in, 17, false, 4, false));
            f.f32((float) CdisRecords.decFloat(in, 17, false, 4, false));
            f.f32(in.ubits(8));
            f.f32((float) (in.uvint16() * 100.0));
            f.f32((float) CdisRecords.decFloat(in, 14, false, 3, true));
            fps[i] = f.toBytes();
        }
        byte[][] bds = new byte[nBd][];
        for (int i = 0; i < nBd; i++) {
            DisWriter f = new DisWriter(20);
            CdisRecords.decBeamData(in, f);
            bds[i] = f.toBytes();
        }
        int[][] sas = new int[nSa][];
        for (int i = 0; i < nSa; i++) {
            sas[i] = new int[] {(int) in.uvint16(), (int) in.uvint16()};
        }
        for (int s = 0; s < systemCount; s++) {
            boolean details = in.flag();
            boolean location = in.flag();
            int beamCount = in.ubits(5);
            byte[] nameFunction = details ? new DisWriter(3).u16(in.ubits(16)).u8(in.ubits(8)).toBytes() : null;
            int number = (int) in.uvint8();
            byte[] loc = null;
            if (location) {
                DisWriter l = new DisWriter(12);
                CdisRecords.decEntityCoordinates(in, l, true);
                loc = l.toBytes();
            }
            if (nameFunction == null) {
                nameFunction = remembered(last == null ? null : last.systemDetails, number, 3);
            }
            if (loc == null) {
                loc = remembered(last == null ? null : last.systemLocation, number, 12);
            }
            now.systemDetails.put(number, nameFunction);
            now.systemLocation.put(number, loc);
            DisWriter beams = new DisWriter(64);
            for (int bi = 0; bi < beamCount; bi++) {
                boolean hasFp = in.flag();
                boolean hasBd = in.flag();
                boolean jamming = in.flag();
                boolean jammerTrack = in.flag();
                int beamId = (int) in.uvint8();
                int parameterIndex = in.ubits(16);
                int bk = beamKey(number, beamId);
                byte[] fp = hasFp ? listEntry(fps, in.ubits(5), "fundamental parameter") : null;
                byte[] bd = hasBd ? listEntry(bds, in.ubits(5), "beam data") : null;
                if (fp == null) {
                    fp = remembered(last == null ? null : last.fundamental, bk, 20);
                }
                if (bd == null) {
                    bd = remembered(last == null ? null : last.beamData, bk, 20);
                }
                now.fundamental.put(bk, fp);
                now.beamData.put(bk, bd);
                int function = in.ubits(5);
                int targets = in.ubits(4);
                boolean highDensity = in.flag();
                boolean active = in.flag();
                int[] jam = new int[4];
                if (jamming) {
                    for (int i = 0; i < 4; i++) {
                        jam[i] = (int) in.uvint8();
                    }
                }
                beams.u8(13 + 2 * targets).u8(beamId).u16(parameterIndex).bytes(fp).bytes(bd);
                beams.u8(function).u8(targets).u8(highDensity ? 1 : 0).u8(active ? 0 : 1);
                beams.u8(jam[0]).u8(jam[1]).u8(jam[2]).u8(jam[3]);
                for (int t = 0; t < targets; t++) {
                    int sai = in.ubits(6);
                    if (sai >= sas.length) {
                        throw new CdisFormatException("site/application pair index " + sai + " beyond the list of " + sas.length);
                    }
                    int[] sa = sas[sai];
                    int e = (int) in.uvint16();
                    int em = jammerTrack ? (int) in.uvint8() : 0;
                    int bm = jammerTrack ? (int) in.uvint8() : 0;
                    beams.u16(sa[0]).u16(sa[1]).u16(e).u8(em).u8(bm);
                }
            }
            byte[] beamOctets = beams.toBytes();
            w.u8((20 + beamOctets.length) / 4).u8(beamCount).zeros(2);
            w.bytes(nameFunction).u8(number).bytes(loc).bytes(beamOctets);
        }
        ObjectStates.Decoded s = c.decoderStateCreate(CdisCodec.ELECTROMAGNETIC_EMISSION, key);
        s.extra = now;
        return true;
    }

    private static byte[] listEntry(byte[][] list, int index, String what) {
        if (index >= list.length) {
            throw new CdisFormatException(what + " index " + index + " beyond the list of " + list.length);
        }
        return list[index];
    }

    private static byte[] remembered(Map<Integer, Object> map, int key, int octets) {
        Object o = map == null ? null : map.get(key);
        return o instanceof byte[] ? (byte[]) o : new byte[octets];
    }
}
