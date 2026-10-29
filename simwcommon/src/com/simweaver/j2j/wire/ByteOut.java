package com.simweaver.j2j.wire;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** A growing big-endian byte buffer for building J2J frames and messages. */
public final class ByteOut {

    private byte[] buf;
    private int len;

    public ByteOut() {
        this(64);
    }

    public ByteOut(int capacity) {
        buf = new byte[Math.max(16, capacity)];
    }

    private void room(int n) {
        if (len + n > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + n));
        }
    }

    public ByteOut u8(int v) {
        room(1);
        buf[len++] = (byte) v;
        return this;
    }

    public ByteOut u16(int v) {
        room(2);
        buf[len++] = (byte) (v >>> 8);
        buf[len++] = (byte) v;
        return this;
    }

    public ByteOut u32(long v) {
        room(4);
        buf[len++] = (byte) (v >>> 24);
        buf[len++] = (byte) (v >>> 16);
        buf[len++] = (byte) (v >>> 8);
        buf[len++] = (byte) v;
        return this;
    }

    public ByteOut u64(long v) {
        room(8);
        for (int s = 56; s >= 0; s -= 8) {
            buf[len++] = (byte) (v >>> s);
        }
        return this;
    }

    public ByteOut bytes(byte[] b) {
        return bytes(b, 0, b.length);
    }

    public ByteOut bytes(byte[] b, int off, int n) {
        room(n);
        System.arraycopy(b, off, buf, len, n);
        len += n;
        return this;
    }

    /** A TLV: u16 tag, u16 length, the value. */
    public ByteOut tlv(int tag, byte[] value) {
        if (value.length > 0xFFFF) {
            throw new IllegalArgumentException("TLV value too long: " + value.length);
        }
        return u16(tag).u16(value.length).bytes(value);
    }

    public ByteOut tlv(int tag, String utf8) {
        return tlv(tag, utf8.getBytes(StandardCharsets.UTF_8));
    }

    public ByteOut tlvU64(int tag, long v) {
        return tlv(tag, new ByteOut(8).u64(v).toBytes());
    }

    /** Overwrites 4 octets at {@code at} (a length field written first as 0). */
    public void putU32(int at, long v) {
        buf[at] = (byte) (v >>> 24);
        buf[at + 1] = (byte) (v >>> 16);
        buf[at + 2] = (byte) (v >>> 8);
        buf[at + 3] = (byte) v;
    }

    public int size() {
        return len;
    }

    public byte[] toBytes() {
        return Arrays.copyOf(buf, len);
    }
}
