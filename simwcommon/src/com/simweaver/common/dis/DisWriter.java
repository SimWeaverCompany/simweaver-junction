package com.simweaver.common.dis;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Writes DIS PDUs: big-endian, no alignment (DIS records are laid out explicitly). Starts with
 * {@link #header}, ends with {@link #finish}, which fills in the PDU length. Not thread safe.
 */
public final class DisWriter {

    private byte[] buf;
    private int len;

    /** @param capacity expected size */
    public DisWriter(int capacity) {
        buf = new byte[Math.max(16, capacity)];
    }

    private void ensure(int n) {
        if (len + n > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + n));
        }
    }

    /**
     * The 12-octet PDU header; the length is filled in by {@link #finish}.
     *
     * @param version   protocol version (6 or 7)
     * @param exercise  exercise id 1-255
     * @param type      PDU type
     * @param family    protocol family
     * @param timestamp DIS timestamp ({@link #timestamp(long, boolean)})
     * @param status    DIS 7 PDU status octet (ignored, written as 0, for DIS 6)
     */
    public DisWriter header(int version, int exercise, int type, int family, long timestamp, int status) {
        u8(version).u8(exercise).u8(type).u8(family).u32(timestamp).u16(0).u8(version >= 7 ? status : 0).u8(0);
        return this;
    }

    public DisWriter u8(int v) {
        ensure(1);
        buf[len++] = (byte) v;
        return this;
    }

    public DisWriter u16(int v) {
        ensure(2);
        buf[len++] = (byte) (v >>> 8);
        buf[len++] = (byte) v;
        return this;
    }

    public DisWriter u32(long v) {
        ensure(4);
        buf[len++] = (byte) (v >>> 24);
        buf[len++] = (byte) (v >>> 16);
        buf[len++] = (byte) (v >>> 8);
        buf[len++] = (byte) v;
        return this;
    }

    public DisWriter i32(int v) {
        return u32(v & 0xFFFFFFFFL);
    }

    public DisWriter u64(long v) {
        u32(v >>> 32);
        return u32(v & 0xFFFFFFFFL);
    }

    public DisWriter f32(float v) {
        return u32(Float.floatToRawIntBits(v) & 0xFFFFFFFFL);
    }

    public DisWriter f64(double v) {
        return u64(Double.doubleToRawLongBits(v));
    }

    public DisWriter f32x3(float[] v) {
        return f32(v[0]).f32(v[1]).f32(v[2]);
    }

    public DisWriter f64x3(double[] v) {
        return f64(v[0]).f64(v[1]).f64(v[2]);
    }

    public DisWriter bytes(byte[] b) {
        ensure(b.length);
        System.arraycopy(b, 0, buf, len, b.length);
        len += b.length;
        return this;
    }

    /** Exactly {@code n} octets of the text (ISO-8859-1), cut or padded with zeros. */
    public DisWriter text(String s, int n) {
        byte[] b = s == null ? new byte[0] : s.getBytes(StandardCharsets.ISO_8859_1);
        return bytes(Arrays.copyOf(b, n));
    }

    public DisWriter zeros(int n) {
        ensure(n);
        len += n;
        return this;
    }

    public DisWriter entityId(DisEntityId id) {
        return u16(id.site).u16(id.application).u16(id.entity);
    }

    /** An entity (or event, or simulation address with 0) identifier: site, application, number. */
    public DisWriter entityId(int site, int application, int number) {
        return u16(site).u16(application).u16(number);
    }

    public DisWriter entityType(DisEntityType t) {
        return u8(t.kind).u8(t.domain).u16(t.country).u8(t.category).u8(t.subcategory).u8(t.specific).u8(t.extra);
    }

    /** @return octets written so far */
    public int size() {
        return len;
    }

    /** The octets written so far, as they are (for a field built apart, e.g. Signal PDU data); no PDU length. */
    public byte[] toBytes() {
        return Arrays.copyOf(buf, len);
    }

    /** Writes the PDU length into the header and returns the PDU. */
    public byte[] finish() {
        if (len >= 10) {
            buf[8] = (byte) (len >>> 8);
            buf[9] = (byte) len;
        }
        return Arrays.copyOf(buf, len);
    }

    /**
     * A DIS timestamp: time past the hour in units of 3600 s / 2^31, shifted left by one; the
     * lowest bit is 1 for an absolute timestamp (clocks synchronised), 0 for a relative one.
     *
     * @param epochMillis the time
     * @param absolute    the absolute flag
     */
    public static long timestamp(long epochMillis, boolean absolute) {
        long pastHour = epochMillis % 3_600_000L;
        long units = (long) (pastHour * (2147483648.0 / 3_600_000.0));
        return ((units & 0x7FFFFFFFL) << 1 | (absolute ? 1 : 0)) & 0xFFFFFFFFL;
    }
}
