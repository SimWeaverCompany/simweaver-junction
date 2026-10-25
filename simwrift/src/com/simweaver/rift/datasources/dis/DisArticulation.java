package com.simweaver.rift.datasources.dis;

/**
 * One articulation / variable parameter record (16 octets): type designator, change indicator,
 * part attached to, parameter type, and the 8-octet parameter value.
 */
public final class DisArticulation {

    public static final int SIZE = 16;
    public static final int ARTICULATED_PART = 0;
    public static final int ATTACHED_PART = 1;

    public final int designator;
    public final int change;
    public final int attachedTo;
    public final long parameterType;
    /** The 8-octet value: a float32 (then padding) for an articulated part, an entity type for an attached part. */
    public final byte[] value;

    public DisArticulation(int designator, int change, int attachedTo, long parameterType, byte[] value) {
        this.designator = designator;
        this.change = change;
        this.attachedTo = attachedTo;
        this.parameterType = parameterType;
        this.value = value;
    }

    public static DisArticulation read(DisReader r) {
        int designator = r.u8();
        int change = r.u8();
        int attachedTo = r.u16();
        long type = r.u32();
        byte[] value = r.bytes(8);
        return new DisArticulation(designator, change, attachedTo, type, value);
    }
}
