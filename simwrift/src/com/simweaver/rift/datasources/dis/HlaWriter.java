package com.simweaver.rift.datasources.dis;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Writes one value in the HLA encoding (IEEE 1516.2-2010, section 4.13) as the RPR FOM types use
 * it: big-endian basics, and padding so that every field starts on its octet boundary,
 * counted from the start of the value. Callers align before each field with {@link #align}.
 *
 * <p>Not thread safe; one writer per value.
 */
public final class HlaWriter {

    private byte[] buf;
    private int len;

    public HlaWriter() {
        this(64);
    }

    public HlaWriter(int capacity) {
        buf = new byte[Math.max(16, capacity)];
    }

    /** Current size, i.e. the offset of the next octet. */
    public int size() {
        return len;
    }

    /** Pads with zero octets up to the next multiple of {@code boundary}. */
    public HlaWriter align(int boundary) {
        int pad = (boundary - (len % boundary)) % boundary;
        ensure(pad);
        len += pad;                                    // the buffer is zero filled
        return this;
    }

    public HlaWriter octet(int v) {
        ensure(1);
        buf[len++] = (byte) v;
        return this;
    }

    public HlaWriter u16(int v) {
        ensure(2);
        buf[len++] = (byte) (v >>> 8);
        buf[len++] = (byte) v;
        return this;
    }

    public HlaWriter u32(long v) {
        ensure(4);
        buf[len++] = (byte) (v >>> 24);
        buf[len++] = (byte) (v >>> 16);
        buf[len++] = (byte) (v >>> 8);
        buf[len++] = (byte) v;
        return this;
    }

    public HlaWriter i32(int v) {
        return u32(v & 0xFFFFFFFFL);
    }

    public HlaWriter u64(long v) {
        u32(v >>> 32);
        return u32(v & 0xFFFFFFFFL);
    }

    public HlaWriter f32(float v) {
        return u32(Float.floatToRawIntBits(v) & 0xFFFFFFFFL);
    }

    public HlaWriter f64(double v) {
        return u64(Double.doubleToRawLongBits(v));
    }

    public HlaWriter bytes(byte[] b) {
        return bytes(b, 0, b.length);
    }

    public HlaWriter bytes(byte[] b, int off, int n) {
        ensure(n);
        System.arraycopy(b, off, buf, len, n);
        len += n;
        return this;
    }

    /** RPRnullTerminatedArray of HLAASCIIchar (RPR {@code RTIobjectId}): the characters, then a 0 octet. */
    public HlaWriter nullTerminatedAscii(String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        bytes(b);
        return octet(0);
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buf, len);
    }

    private void ensure(int n) {
        if (len + n > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + n));
    }
}
