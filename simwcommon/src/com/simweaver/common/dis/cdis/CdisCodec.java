package com.simweaver.common.dis.cdis;

import com.simweaver.common.dis.DisFormatException;
import com.simweaver.common.dis.DisReader;
import com.simweaver.common.dis.DisWriter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Compressed DIS (SISO-STD-023-2024) encoder and decoder: DIS 7 PDUs to C-DIS PDUs and back,
 * for the 22 PDU types C-DIS has.
 *
 * <ul>
 *   <li>{@link #encode} takes one DIS PDU (version 6 or 7) and gives one C-DIS PDU, zero padded
 *       to whole octets: one PDU per datagram.</li>
 *   <li>{@link #decode} takes a datagram with one or more C-DIS PDUs (bit packed one after the
 *       other) and gives DIS 7 PDUs. A PDU of another C-DIS version or an unknown type is skipped
 *       by its length; a version of 0 (DIS) or fewer bits than a header end the datagram.</li>
 *   <li>In {@link CdisSettings.UpdateMode#PARTIAL partial} mode the encoder sends only what changed
 *       for Entity State, EE, Designator, Transmitter and IFF PDUs, with a full update at least
 *       every full update period. The decoder takes partial updates in either mode: it keeps the
 *       state of each object, drops partial updates until the object's first full update and
 *       forgets objects that time out.</li>
 * </ul>
 *
 * Thread safe (encode and decode are serialised).
 */
public final class CdisCodec {

    /** The C-DIS protocol version written (SISO-STD-023-2024: 1). */
    public static final int VERSION = 1;

    public static final int ENTITY_STATE = 1;
    public static final int FIRE = 2;
    public static final int DETONATION = 3;
    public static final int COLLISION = 4;
    public static final int CREATE_ENTITY = 11;
    public static final int REMOVE_ENTITY = 12;
    public static final int START_RESUME = 13;
    public static final int STOP_FREEZE = 14;
    public static final int ACKNOWLEDGE = 15;
    public static final int ACTION_REQUEST = 16;
    public static final int ACTION_RESPONSE = 17;
    public static final int DATA_QUERY = 18;
    public static final int SET_DATA = 19;
    public static final int DATA = 20;
    public static final int EVENT_REPORT = 21;
    public static final int COMMENT = 22;
    public static final int ELECTROMAGNETIC_EMISSION = 23;
    public static final int DESIGNATOR = 24;
    public static final int TRANSMITTER = 25;
    public static final int SIGNAL = 26;
    public static final int RECEIVER = 27;
    public static final int IFF = 28;

    /** The PDU types C-DIS has. */
    public static final List<Integer> PDU_TYPES = Collections.unmodifiableList(java.util.Arrays.asList(
            ENTITY_STATE, FIRE, DETONATION, COLLISION, CREATE_ENTITY, REMOVE_ENTITY, START_RESUME, STOP_FREEZE,
            ACKNOWLEDGE, ACTION_REQUEST, ACTION_RESPONSE, DATA_QUERY, SET_DATA, DATA, EVENT_REPORT, COMMENT,
            ELECTROMAGNETIC_EMISSION, DESIGNATOR, TRANSMITTER, SIGNAL, RECEIVER, IFF));

    /** Header: version 2, exercise UVINT8 (5 or 9), type 8, timestamp 26, length 14, status 8. */
    static final int MIN_HEADER_BITS = 2 + 5 + 8 + 26 + 14 + 8;
    /** The largest PDU length (14 bits). */
    public static final int MAX_PDU_BITS = 16383;

    private static final int[] STATEFUL = {ENTITY_STATE, ELECTROMAGNETIC_EMISSION, DESIGNATOR, TRANSMITTER, IFF};

    private final CdisSettings settings;
    private final CdisStats stats = new CdisStats();
    @SuppressWarnings("unchecked")
    private final ObjectStates<ObjectStates.Encoded>[] encoderStates = new ObjectStates[29];
    @SuppressWarnings("unchecked")
    private final ObjectStates<ObjectStates.Decoded>[] decoderStates = new ObjectStates[29];
    private long now;

    public CdisCodec(CdisSettings settings) {
        this.settings = settings == null ? CdisSettings.DEFAULTS : settings;
        for (int t : STATEFUL) {
            encoderStates[t] = ObjectStates.encoded();
            decoderStates[t] = ObjectStates.decoded();
        }
    }

    public CdisSettings settings() {
        return settings;
    }

    public CdisStats stats() {
        return stats;
    }

    /** @return whether C-DIS has the DIS PDU type */
    public static boolean isSupported(int disType) {
        return disType >= ENTITY_STATE && disType <= COLLISION || disType >= CREATE_ENTITY && disType <= IFF;
    }

    /** @return the DIS protocol family of a PDU type */
    public static int family(int type) {
        switch (type) {
            case ENTITY_STATE:
            case COLLISION:
                return 1;
            case FIRE:
            case DETONATION:
                return 2;
            case TRANSMITTER:
            case SIGNAL:
            case RECEIVER:
                return 4;
            case ELECTROMAGNETIC_EMISSION:
            case DESIGNATOR:
            case IFF:
                return 6;
            default:
                return type >= CREATE_ENTITY && type <= COMMENT ? 5 : 0;
        }
    }

    // ================================================================== encode

    /**
     * Encodes one DIS PDU.
     *
     * @param pdu    the octets
     * @param offset where the PDU starts
     * @param length its length (at least its header's length)
     * @param now    the time, ms (full update periods)
     * @return the C-DIS PDU zero padded to whole octets, or null when it cannot be encoded (a type
     *         C-DIS does not have, or malformed; see {@link #stats()})
     */
    public synchronized byte[] encode(byte[] pdu, int offset, int length, long now) {
        this.now = now;
        if (length < 12) {
            stats.encodeErrors.incrementAndGet();
            stats.error("a DIS PDU of " + length + " octets");
            return null;
        }
        int version = pdu[offset] & 0xFF;
        int exercise = pdu[offset + 1] & 0xFF;
        int type = pdu[offset + 2] & 0xFF;
        int disLength = ((pdu[offset + 8] & 0xFF) << 8) | (pdu[offset + 9] & 0xFF);
        if (!isSupported(type) || version < 6 || version > 7) {
            stats.notConvertible.incrementAndGet();
            return null;
        }
        if (disLength < 12 || disLength > length) {
            stats.encodeErrors.incrementAndGet();
            stats.error("PDU type " + type + " claims " + disLength + " octets, " + length + " are there");
            return null;
        }
        DisReader r = new DisReader(pdu, offset, disLength);
        long timestamp = ((pdu[offset + 4] & 0xFFL) << 24) | ((pdu[offset + 5] & 0xFFL) << 16)
                | ((pdu[offset + 6] & 0xFFL) << 8) | (pdu[offset + 7] & 0xFFL);
        int status = version >= 7 ? pdu[offset + 10] & 0xFF : 0;
        BitWriter w = new BitWriter(disLength);
        w.bits(VERSION, 2).uvint8(exercise).bits(type, 8).bits(CdisRecords.timestampToCdis(timestamp), 26);
        int lengthAt = w.bitLength();
        w.bits(0, 14).bits(status, 8);
        try {
            boolean ok = encodeBody(type, status, r, w);
            if (!ok) {
                return null;
            }
        } catch (DisFormatException | CdisFormatException e) {
            stats.encodeErrors.incrementAndGet();
            stats.error("PDU type " + type + ": " + e.getMessage());
            return null;
        }
        int bits = w.bitLength();
        if (bits > MAX_PDU_BITS) {
            stats.encodeErrors.incrementAndGet();
            stats.error("PDU type " + type + " needs " + bits + " bits; a C-DIS PDU has at most " + MAX_PDU_BITS);
            return null;
        }
        w.patch(lengthAt, bits, 14);
        byte[] out = w.toByteArray();
        stats.encoded.incrementAndGet();
        stats.encodedDisOctets.addAndGet(disLength);
        stats.encodedCdisOctets.addAndGet(out.length);
        return out;
    }

    private boolean encodeBody(int type, int status, DisReader r, BitWriter w) {
        switch (type) {
            case ENTITY_STATE:
                return EntityStatePdu.encode(r, w, this);
            case FIRE:
                return WarfarePdus.encodeFire(r, w);
            case DETONATION:
                return WarfarePdus.encodeDetonation(r, w, ((status >> 4) & 0x3) == 2);
            case COLLISION:
                return WarfarePdus.encodeCollision(r, w);
            case ELECTROMAGNETIC_EMISSION:
                return EmissionPdu.encode(r, w, this);
            case DESIGNATOR:
                return DesignatorPdu.encode(r, w, this);
            case TRANSMITTER:
                return RadioPdus.encodeTransmitter(r, w, this);
            case SIGNAL:
                return RadioPdus.encodeSignal(r, w);
            case RECEIVER:
                return RadioPdus.encodeReceiver(r, w);
            case IFF:
                return IffPdu.encode(r, w, this);
            default:
                return SimanPdus.encode(type, r, w);
        }
    }

    // ================================================================== decode

    /**
     * Decodes the C-DIS PDUs of a datagram.
     *
     * @param now the time, ms (object timeouts)
     * @return DIS 7 PDUs, in order; PDUs that cannot be decoded are left out (see {@link #stats()})
     */
    public synchronized List<byte[]> decode(byte[] data, int offset, int length, long now) {
        this.now = now;
        List<byte[]> out = new ArrayList<byte[]>(1);
        BitReader all = new BitReader(data, offset, length * 8);
        while (all.remaining() >= MIN_HEADER_BITS) {
            int start = all.position();
            int version = all.ubits(2);
            if (version == 0) {
                break; // DIS, or padding
            }
            int exercise = (int) all.uvint8();
            int type = all.ubits(8);
            long timestamp = all.bits(26);
            int bits = all.ubits(14);
            int status = all.ubits(8);
            int headerBits = all.position() - start;
            if (bits < headerBits || start + bits > length * 8) {
                stats.decodeErrors.incrementAndGet();
                stats.error("a C-DIS PDU (type " + type + ") claims " + bits + " bits; "
                        + (all.remaining() + headerBits) + " are left in the datagram");
                break;
            }
            all.seek(start + bits);
            if (version != VERSION || !isSupported(type)) {
                stats.unknownPdus.incrementAndGet();
                continue;
            }
            BitReader in = all.sub(start + headerBits, bits - headerBits);
            DisWriter w = new DisWriter(160);
            w.header(7, exercise, type, family(type), CdisRecords.timestampToDis(timestamp), status);
            try {
                if (decodeBody(type, in, w)) {
                    byte[] pdu = w.finish();
                    out.add(pdu);
                    stats.decoded.incrementAndGet();
                    stats.decodedCdisOctets.addAndGet((bits + 7) / 8);
                    stats.decodedDisOctets.addAndGet(pdu.length);
                }
            } catch (CdisFormatException | DisFormatException e) {
                stats.decodeErrors.incrementAndGet();
                stats.error("C-DIS PDU type " + type + ": " + e.getMessage());
            }
        }
        return out;
    }

    private boolean decodeBody(int type, BitReader in, DisWriter w) {
        switch (type) {
            case ENTITY_STATE:
                return EntityStatePdu.decode(in, w, this);
            case FIRE:
                return WarfarePdus.decodeFire(in, w);
            case DETONATION:
                return WarfarePdus.decodeDetonation(in, w);
            case COLLISION:
                return WarfarePdus.decodeCollision(in, w);
            case ELECTROMAGNETIC_EMISSION:
                return EmissionPdu.decode(in, w, this);
            case DESIGNATOR:
                return DesignatorPdu.decode(in, w, this);
            case TRANSMITTER:
                return RadioPdus.decodeTransmitter(in, w, this);
            case SIGNAL:
                return RadioPdus.decodeSignal(in, w);
            case RECEIVER:
                return RadioPdus.decodeReceiver(in, w);
            case IFF:
                return IffPdu.decode(in, w, this);
            default:
                return SimanPdus.decode(type, in, w);
        }
    }

    // ================================================================== object state (package private, for the PDU classes)

    long now() {
        return now;
    }

    boolean partialMode() {
        return settings.getUpdateMode() == CdisSettings.UpdateMode.PARTIAL;
    }

    /**
     * Encoder: makes a stateful PDU a partial update when partial mode allows; counts it.
     *
     * @param last the object's last update (it is forgotten): always a full update
     */
    void stateful(int type, long key, BodyWriter b, boolean last) {
        if (!partialMode()) {
            stats.fullUpdates.incrementAndGet();
            return;
        }
        ObjectStates<ObjectStates.Encoded> table = encoderStates[type];
        ObjectStates.Encoded s = table.getOrCreate(key, now);
        b.partial(s, last || fullUpdateDue(type, s));
        if (b.isFull()) {
            s.lastFull = now;
        }
        countUpdate(b.isFull());
        if (last) {
            table.remove(key);
        }
    }

    boolean fullUpdateDue(int type, ObjectStates.Encoded s) {
        return s.lastFull == Long.MIN_VALUE || now - s.lastFull >= settings.fullUpdatePeriod(type);
    }

    void countUpdate(boolean full) {
        (full ? stats.fullUpdates : stats.partialUpdates).incrementAndGet();
    }

    ObjectStates.Encoded encoderState(int type, long key) {
        return partialMode() ? encoderStates[type].get(key) : null;
    }

    ObjectStates.Encoded encoderStateCreate(int type, long key) {
        return encoderStates[type].getOrCreate(key, now);
    }

    /**
     * Decoder: gives a partial update the object's state.
     *
     * @return false when the update must be dropped (partial, and no full update of the object yet)
     */
    boolean attach(int type, long key, BodyReader b) {
        if (b.isFull()) {
            return true;
        }
        ObjectStates.Decoded s = decoderStates[type].get(key);
        if (s == null || s.groups == null) {
            waitingForFull();
            return false;
        }
        b.useState(s);
        return true;
    }

    /** Decoder: stores the update as the object's state, or forgets the object after its last update. */
    void commit(int type, long key, BodyReader b, boolean last) {
        if (last) {
            decoderStates[type].remove(key);
            return;
        }
        b.commit(decoderStates[type].getOrCreate(key, now));
    }

    ObjectStates.Decoded decoderState(int type, long key) {
        return decoderStates[type].get(key);
    }

    ObjectStates.Decoded decoderStateCreate(int type, long key) {
        return decoderStates[type].getOrCreate(key, now);
    }

    void waitingForFull() {
        stats.waitingForFull.incrementAndGet();
    }

    /**
     * Forgets objects not heard from for their timeout (heartbeat × timeout multiplier), on both
     * sides. Call now and then.
     */
    public synchronized void expire(long now) {
        for (int t : STATEFUL) {
            long limit = now - settings.timeout(t);
            stats.timedOut.addAndGet(decoderStates[t].expire(limit));
            encoderStates[t].expire(limit);
        }
    }

    /** @return objects the decoder holds state for (all stateful PDU types) */
    public synchronized int decoderObjects() {
        int n = 0;
        for (int t : STATEFUL) {
            n += decoderStates[t].size();
        }
        return n;
    }
}
