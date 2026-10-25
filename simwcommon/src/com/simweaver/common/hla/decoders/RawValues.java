package com.simweaver.common.hla.decoders;

import com.simweaver.common.iem.IemElement;

/**
 * The values of one attribute update or one interaction, captured on the RTI callback thread:
 * handles already mapped to IEM ids, octets copied. Cheap to build, immutable, and decoded
 * later (on a worker thread) with {@link HlaDataDecoder#decode(RawValues)}.
 * <p>
 * A member whose handle is not in the model has id {@link IemElement#NO_ID} and a label
 * describing the handle; it is decoded as raw octets.
 */
public final class RawValues {

    /** What the values belong to. */
    public enum Kind {
        /** Attribute values of an object instance. */
        ATTRIBUTES,
        /** Parameter values of an interaction. */
        PARAMETERS
    }

    private final Kind kind;
    private final int classId;
    private final long receivedMillis;
    private final int[] memberIds;
    private final String[] labels;
    private final byte[][] values;

    /**
     * @param kind           attributes or parameters
     * @param classId        IEM id of the object class (known class) or interaction class
     * @param receivedMillis receive time, epoch ms UTC, or {@link HlaDataDecoder#NO_TIME}
     * @param memberIds      IEM attribute / parameter ids ({@link IemElement#NO_ID} when unknown)
     * @param labels         labels for unknown members (entries may be null for known ones)
     * @param values         copied octets, one per member
     */
    public RawValues(Kind kind, int classId, long receivedMillis, int[] memberIds, String[] labels, byte[][] values) {
        if (memberIds.length != values.length || labels.length != values.length) {
            throw new IllegalArgumentException("ids, labels and values differ in length");
        }
        this.kind = kind;
        this.classId = classId;
        this.receivedMillis = receivedMillis;
        this.memberIds = memberIds.clone();
        this.labels = labels.clone();
        this.values = values.clone();
    }

    /** @return attributes or parameters */
    public Kind getKind() {
        return kind;
    }

    /** @return IEM class id ({@link IemElement#NO_ID} when the class handle was unknown) */
    public int getClassId() {
        return classId;
    }

    /** @return receive time, or {@link HlaDataDecoder#NO_TIME} */
    public long getReceivedMillis() {
        return receivedMillis;
    }

    /** @return number of members */
    public int size() {
        return values.length;
    }

    /**
     * @param i index
     * @return IEM member id, or {@link IemElement#NO_ID}
     */
    public int getMemberId(int i) {
        return memberIds[i];
    }

    /**
     * @param i index
     * @return label of an unknown member (handle text), or null
     */
    public String getLabel(int i) {
        return labels[i];
    }

    /**
     * @param i index
     * @return the octets (not copied again; do not modify)
     */
    public byte[] getValue(int i) {
        return values[i];
    }
}
