package com.simweaver.rift.db;

import com.simweaver.common.SimwTimeStamp;
import com.simweaver.rift.api.RiftMember;
import com.simweaver.rift.api.RiftMemberView;
import com.simweaver.rift.api.ValueNode;

import java.time.Instant;

/**
 * One received attribute value of an object instance.
 *
 * <p>Created by the datasource (RTI callback thread) with the raw octets, handed to
 * {@link RiftDatabase#reflectObjectUpdate}; the database worker sets the decoded value and the
 * receive time once, before the attribute becomes visible in its {@link RiftInstance}. After that
 * it is never changed again (a newer update brings a new RiftAttribute), so readers on REST/SSE
 * threads see a consistent value without locking.
 */
public class RiftAttribute implements RiftMemberView {

    /** Max octets shown as hex in {@code valueRaw} (contract 1: size caps). */
    public static final int RAW_HEX_CAP = 256;

    private final Integer iemId;
    private final String name;
    private final byte[] rawValue;
    private final String producingFederate;
    private final SimwTimeStamp timestamp;
    private final byte[] ust;
    private final String datatype;

    // set once by the worker before publication (volatile: safe even if read early)
    private volatile ValueNode value;
    private volatile long receivedMillis;
    private volatile String rawHex;          // lazily computed, idempotent

    public RiftAttribute(Integer iemId, String name, byte[] rawValue, String producingFederate,
                         SimwTimeStamp timestamp, byte[] ust) {
        this(iemId, name, null, rawValue, producingFederate, timestamp, ust);
    }

    /**
     * @param datatype FOM datatype name (e.g. "SpatialVariantStruct"); null = taken from the
     *                 decoded value when available
     */
    public RiftAttribute(Integer iemId, String name, String datatype, byte[] rawValue, String producingFederate,
                         SimwTimeStamp timestamp, byte[] ust) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("attribute name must not be empty");
        this.iemId = iemId;
        this.name = name;
        this.datatype = datatype;
        this.rawValue = rawValue;
        this.producingFederate = producingFederate;
        this.timestamp = timestamp;
        this.ust = ust;
    }

    /** Worker only: the decoded value and the receive time. */
    void setDecoded(ValueNode value, long receivedMillis) {
        this.value = value;
        this.receivedMillis = receivedMillis;
    }

    public Integer getIemId() {
        return iemId;
    }

    public String getName() {
        return name;
    }

    /** The received octets (do not modify). */
    public byte[] getRawValue() {
        return rawValue;
    }

    public String getProducingFederate() {
        return producingFederate;
    }

    /** The time stamp the datasource passed in (e.g. from the user-supplied tag); may be null. */
    public SimwTimeStamp getTimestamp() {
        return timestamp;
    }

    public byte[] getUst() {
        return ust;
    }

    /** Wall-clock receive time in epoch millis (0 until decoded). */
    public long getReceivedMillis() {
        return receivedMillis;
    }

    /** The decoded value, or null before the worker has decoded it. */
    public ValueNode getValue() {
        return value;
    }

    /** One-line display text of the decoded value ("" if none). */
    public String getText() {
        ValueNode v = value;
        return v == null || v.text() == null ? "" : v.text();
    }

    /**
     * @deprecated the attribute now keeps the decoded value itself ({@link #getValue()}); JSON is
     * built by {@code RiftJson}. This returns {@link #getText()}, which is what was stored before.
     */
    @Deprecated
    public String getJsonStringValue() {
        return getText();
    }

    // ------------------------------------------------------------------ RiftMemberView

    @Override
    public String name() {
        return name;
    }

    @Override
    public String datatype() {
        if (datatype != null) return datatype;
        ValueNode v = value;
        return v == null ? null : v.type();
    }

    @Override
    public Instant lastUpdated() {
        long t = receivedMillis;
        return t == 0 ? null : Instant.ofEpochMilli(t);
    }

    @Override
    public ValueNode value() {
        return value;
    }

    @Override
    public String rawHex() {
        if (rawValue == null) return null;
        String h = rawHex;
        if (h == null) rawHex = h = RiftMember.hex(rawValue, Math.min(rawValue.length, RAW_HEX_CAP));
        return h;
    }

    @Override
    public int rawSize() {
        return rawValue == null ? 0 : rawValue.length;
    }

    @Override
    public boolean rawTruncated() {
        return rawValue != null && rawValue.length > RAW_HEX_CAP;
    }

    @Override
    public String toString() {
        return name + "=" + getText();
    }
}
