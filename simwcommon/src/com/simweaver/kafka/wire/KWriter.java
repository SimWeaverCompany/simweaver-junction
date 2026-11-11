package com.simweaver.kafka.wire;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Writes the Kafka protocol's primitive types, big endian (the non-flexible request versions this
 * client uses): int8 … int64, strings with an int16 length (-1 = null), bytes with an int32 length
 * (-1 = null), arrays with an int32 count; and the zig-zag varints of record batches.
 */
public final class KWriter {

    private byte[] buf;
    private int pos;

    public KWriter() {
        this(256);
    }

    public KWriter(int capacity) {
        buf = new byte[Math.max(16, capacity)];
    }

    private void need(int n) {
        if (pos + n > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, pos + n));
        }
    }

    public KWriter int8(int v) {
        need(1);
        buf[pos++] = (byte) v;
        return this;
    }

    public KWriter bool(boolean v) {
        return int8(v ? 1 : 0);
    }

    public KWriter int16(int v) {
        need(2);
        buf[pos++] = (byte) (v >>> 8);
        buf[pos++] = (byte) v;
        return this;
    }

    public KWriter int32(int v) {
        need(4);
        buf[pos++] = (byte) (v >>> 24);
        buf[pos++] = (byte) (v >>> 16);
        buf[pos++] = (byte) (v >>> 8);
        buf[pos++] = (byte) v;
        return this;
    }

    public KWriter int64(long v) {
        int32((int) (v >>> 32));
        return int32((int) v);
    }

    /** A string with an int16 length; null writes -1. */
    public KWriter string(String s) {
        if (s == null) {
            return int16(-1);
        }
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length > Short.MAX_VALUE) {
            throw new IllegalArgumentException("string too long for the Kafka protocol: " + b.length + " octets");
        }
        int16(b.length);
        return raw(b);
    }

    /** Octets with an int32 length; null writes -1. */
    public KWriter bytes(byte[] b) {
        if (b == null) {
            return int32(-1);
        }
        int32(b.length);
        return raw(b);
    }

    /** An array count (int32); -1 = null. */
    public KWriter count(int n) {
        return int32(n);
    }

    /** An array of int32. */
    public KWriter int32s(List<Integer> values) {
        int32(values.size());
        for (int v : values) {
            int32(v);
        }
        return this;
    }

    /** An array of strings. */
    public KWriter strings(List<String> values) {
        int32(values.size());
        for (String s : values) {
            string(s);
        }
        return this;
    }

    public KWriter raw(byte[] b) {
        return raw(b, 0, b.length);
    }

    public KWriter raw(byte[] b, int off, int len) {
        need(len);
        System.arraycopy(b, off, buf, pos, len);
        pos += len;
        return this;
    }

    /** Zig-zag varint (32-bit), as in record batches. */
    public KWriter varint(int v) {
        return uvarint((v << 1) ^ (v >> 31));
    }

    /** Unsigned varint. */
    public KWriter uvarint(int v) {
        while ((v & 0xFFFFFF80) != 0) {
            int8((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        return int8(v);
    }

    /** Zig-zag varlong (64-bit), as in record batches. */
    public KWriter varlong(long v) {
        long z = (v << 1) ^ (v >> 63);
        while ((z & 0xFFFFFFFFFFFFFF80L) != 0) {
            int8((int) ((z & 0x7F) | 0x80));
            z >>>= 7;
        }
        return int8((int) z);
    }

    /** Octets with a zig-zag varint length; null writes -1 (record keys, values, header values). */
    public KWriter varbytes(byte[] b) {
        if (b == null) {
            return varint(-1);
        }
        varint(b.length);
        return raw(b);
    }

    /** @return the position, for {@link #patchInt32} */
    public int position() {
        return pos;
    }

    /** Writes an int32 at an earlier position (lengths, CRCs). */
    public void patchInt32(int at, int v) {
        buf[at] = (byte) (v >>> 24);
        buf[at + 1] = (byte) (v >>> 16);
        buf[at + 2] = (byte) (v >>> 8);
        buf[at + 3] = (byte) v;
    }

    /** @return the internal buffer (valid up to {@link #position()}) */
    public byte[] buffer() {
        return buf;
    }

    /** @return a copy of what was written */
    public byte[] toBytes() {
        return Arrays.copyOf(buf, pos);
    }
}
