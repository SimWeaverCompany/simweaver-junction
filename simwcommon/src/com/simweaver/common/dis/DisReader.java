package com.simweaver.common.dis;

/**
 * Reads big-endian DIS fields from one PDU. Every read is bounds checked against the PDU, so
 * a truncated or corrupt PDU ends in a {@link DisFormatException}, never in reading the next
 * PDU of a bundle or past the datagram.
 *
 * <p>Not thread safe; one reader per PDU.
 */
public final class DisReader {

    private final byte[] data;
    private final int start;
    private final int end;
    private int pos;

    /**
     * @param data   the datagram
     * @param offset start of the PDU in {@code data}
     * @param length length of the PDU
     */
    public DisReader(byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException("PDU range " + offset + "+" + length + " outside " + data.length + " octets");
        }
        this.data = data;
        this.start = offset;
        this.end = offset + length;
        this.pos = offset;
    }

    /** Position relative to the start of the PDU. */
    public int position() {
        return pos - start;
    }

    /** Moves to a position relative to the start of the PDU. */
    public DisReader seek(int pduOffset) {
        if (pduOffset < 0 || start + pduOffset > end) throw new DisFormatException("offset " + pduOffset + " outside the PDU (" + length() + " octets)");
        pos = start + pduOffset;
        return this;
    }

    public int length() {
        return end - start;
    }

    public int remaining() {
        return end - pos;
    }

    public DisReader skip(int n) {
        need(n);
        pos += n;
        return this;
    }

    public int u8() {
        need(1);
        return data[pos++] & 0xFF;
    }

    public int u16() {
        need(2);
        int v = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
        pos += 2;
        return v;
    }

    public int i32() {
        need(4);
        int v = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16) | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
        pos += 4;
        return v;
    }

    /** Unsigned 32-bit value. */
    public long u32() {
        return i32() & 0xFFFFFFFFL;
    }

    /** 64-bit value (callers treat it as unsigned where the field is). */
    public long i64() {
        long hi = u32();
        long lo = u32();
        return (hi << 32) | lo;
    }

    public float f32() {
        return Float.intBitsToFloat(i32());
    }

    public double f64() {
        return Double.longBitsToDouble(i64());
    }

    /** A copy of the next {@code n} octets. */
    public byte[] bytes(int n) {
        need(n);
        byte[] out = new byte[n];
        System.arraycopy(data, pos, out, 0, n);
        pos += n;
        return out;
    }

    private void need(int n) {
        if (n < 0 || pos + n > end) {
            throw new DisFormatException("PDU too short: " + n + " octets needed at offset " + position() + " of " + length());
        }
    }
}
