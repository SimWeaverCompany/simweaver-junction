package com.simweaver.rift.datasources.dis;

/**
 * The 12-octet PDU header, the same in DIS 6 and DIS 7 except octet 10: padding in DIS 6, the PDU
 * status record in DIS 7.
 */
public final class DisPduHeader {

    public static final int SIZE = 12;

    // PDU types handled by Rift (IEEE 1278.1, SISO-REF-010)
    public static final int ENTITY_STATE = 1;
    public static final int FIRE = 2;
    public static final int DETONATION = 3;
    public static final int COLLISION = 4;
    public static final int START_RESUME = 13;
    public static final int STOP_FREEZE = 14;
    public static final int ACKNOWLEDGE = 15;
    public static final int ELECTROMAGNETIC_EMISSION = 23;
    public static final int DESIGNATOR = 24;
    public static final int IFF = 28;
    public static final int TRANSMITTER = 25;
    public static final int SIGNAL = 26;
    public static final int RECEIVER = 27;
    /** DIS 7 only. */
    public static final int ENTITY_STATE_UPDATE = 67;

    public final int protocolVersion;
    public final int exerciseId;
    public final int pduType;
    public final int protocolFamily;
    public final long timestamp;
    /** PDU length from the header, in octets. */
    public final int length;
    /** DIS 7 PDU status octet (0 in DIS 6). */
    public final int pduStatus;

    private DisPduHeader(int protocolVersion, int exerciseId, int pduType, int protocolFamily, long timestamp, int length, int pduStatus) {
        this.protocolVersion = protocolVersion;
        this.exerciseId = exerciseId;
        this.pduType = pduType;
        this.protocolFamily = protocolFamily;
        this.timestamp = timestamp;
        this.length = length;
        this.pduStatus = pduStatus;
    }

    /** Reads the header at the reader's position (12 octets). */
    public static DisPduHeader read(DisReader r) {
        int version = r.u8();
        int exercise = r.u8();
        int type = r.u8();
        int family = r.u8();
        long ts = r.u32();
        int length = r.u16();
        int status = r.u8();
        r.skip(1);
        return new DisPduHeader(version, exercise, type, family, ts, length, version >= 7 ? status : 0);
    }

    /**
     * DIS 7 Fire PDU "Fire Type Indicator" (PDU status bit 4): false = munition, true = expendable.
     */
    public boolean fireTypeExpendable() {
        return (pduStatus & 0x10) != 0;
    }

    /**
     * DIS 7 Detonation PDU "Detonation Type Indicator" (PDU status bits 4-5): 0 munition,
     * 1 expendable, 2 non-munition explosion.
     */
    public int detonationTypeIndicator() {
        return (pduStatus >> 4) & 0x3;
    }
}
