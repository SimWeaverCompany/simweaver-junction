package com.simweaver.rift.db;

import com.simweaver.rift.api.RiftMember;
import com.simweaver.rift.api.RiftMemberView;
import com.simweaver.rift.api.ValueNode;

import java.time.Instant;

/**
 * One parameter of a received interaction. Created by the datasource with the raw octets; the
 * database worker sets the decoded value once, before the interaction becomes visible.
 */
public class RiftParameter implements RiftMemberView {

    private final Integer iemId;
    private final String name;
    private final String datatype;
    private final byte[] rawValue;

    private volatile ValueNode value;
    private volatile String rawHex;

    public RiftParameter(Integer iemId, String name, byte[] rawValue) {
        this(iemId, name, null, rawValue);
    }

    /** @param datatype FOM datatype name; null = taken from the decoded value when available */
    public RiftParameter(Integer iemId, String name, String datatype, byte[] rawValue) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("parameter name must not be empty");
        this.iemId = iemId;
        this.name = name;
        this.datatype = datatype;
        this.rawValue = rawValue;
    }

    /** Worker only. */
    void setDecoded(ValueNode value) {
        this.value = value;
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

    /** The decoded value, or null before decoding. */
    public ValueNode getValue() {
        return value;
    }

    /** One-line display text of the decoded value ("" if none). */
    public String getPrintedValue() {
        ValueNode v = value;
        return v == null || v.text() == null ? "" : v.text();
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

    /** Parameters share the interaction's receive time; not repeated per parameter. */
    @Override
    public Instant lastUpdated() {
        return null;
    }

    @Override
    public ValueNode value() {
        return value;
    }

    @Override
    public String rawHex() {
        if (rawValue == null) return null;
        String h = rawHex;
        if (h == null) rawHex = h = RiftMember.hex(rawValue, Math.min(rawValue.length, RiftAttribute.RAW_HEX_CAP));
        return h;
    }

    @Override
    public int rawSize() {
        return rawValue == null ? 0 : rawValue.length;
    }

    @Override
    public boolean rawTruncated() {
        return rawValue != null && rawValue.length > RiftAttribute.RAW_HEX_CAP;
    }

    @Override
    public String toString() {
        return name + "=" + getPrintedValue();
    }
}
