package com.simweaver.rift.api;

import java.time.Instant;

/**
 * Immutable {@link RiftMemberView}: a ready-made holder for an attribute or parameter value,
 * for backends that do not want to implement the view on their own classes (and for tests).
 */
public final class RiftMember implements RiftMemberView {

    private final String name;
    private final String datatype;
    private final Instant lastUpdated;
    private final ValueNode value;
    private final String rawHex;
    private final int rawSize;
    private final boolean rawTruncated;

    public RiftMember(String name, String datatype, Instant lastUpdated, ValueNode value,
                      String rawHex, int rawSize, boolean rawTruncated) {
        this.name = name;
        this.datatype = datatype;
        this.lastUpdated = lastUpdated;
        this.value = value;
        this.rawHex = rawHex;
        this.rawSize = rawSize;
        this.rawTruncated = rawTruncated;
    }

    /** Hex of up to {@code rawCap} octets of {@code raw}; sets rawTruncated when cut. */
    public static RiftMember of(String name, String datatype, Instant lastUpdated, ValueNode value,
                                byte[] raw, int rawCap) {
        int size = raw == null ? 0 : raw.length;
        int n = Math.min(size, Math.max(0, rawCap));
        return new RiftMember(name, datatype, lastUpdated, value, raw == null ? null : hex(raw, n), size, n < size);
    }

    /** Upper-case hex of the first n octets of b. */
    public static String hex(byte[] b, int n) {
        final char[] digits = "0123456789ABCDEF".toCharArray();
        char[] out = new char[n * 2];
        for (int i = 0; i < n; i++) {
            out[2 * i] = digits[(b[i] >> 4) & 0xF];
            out[2 * i + 1] = digits[b[i] & 0xF];
        }
        return new String(out);
    }

    @Override public String name() { return name; }
    @Override public String datatype() { return datatype; }
    @Override public Instant lastUpdated() { return lastUpdated; }
    @Override public ValueNode value() { return value; }
    @Override public String rawHex() { return rawHex; }
    @Override public int rawSize() { return rawSize; }
    @Override public boolean rawTruncated() { return rawTruncated; }
}
