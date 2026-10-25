package com.simweaver.common.dis.cdis;

/**
 * Reads a C-DIS bit stream, most significant bit first (SISO-STD-023-2024 10.1). Reading past the
 * end throws {@link CdisFormatException}. Not thread safe.
 */
public final class BitReader {

    private final byte[] data;
    private final int startBit;
    private final int endBit;
    private int pos;

    /**
     * @param data      the octets
     * @param offset    first octet
     * @param bitLength bits that can be read
     */
    public BitReader(byte[] data, int offset, int bitLength) {
        if (offset < 0 || bitLength < 0 || (long) offset * 8 + bitLength > (long) data.length * 8) {
            throw new IllegalArgumentException("bit range outside the data");
        }
        this.data = data;
        this.startBit = offset * 8;
        this.endBit = startBit + bitLength;
        this.pos = startBit;
    }

    private BitReader(byte[] data, int startBit, int bitLength, boolean unused) {
        this.data = data;
        this.startBit = startBit;
        this.endBit = startBit + bitLength;
        this.pos = startBit;
    }

    /**
     * A reader over part of this one's bits (a PDU in a datagram), sharing the data.
     *
     * @param fromBit position in this reader
     * @param bits    how many bits
     */
    public BitReader sub(int fromBit, int bits) {
        if (fromBit < 0 || bits < 0 || startBit + fromBit + bits > endBit) {
            throw new CdisFormatException(bits + " bits at bit " + fromBit + " are outside the " + (endBit - startBit) + " bits");
        }
        return new BitReader(data, startBit + fromBit, bits, false);
    }

    /** @return bits read so far */
    public int position() {
        return pos - startBit;
    }

    /** Moves to a bit position (from the start of this reader). */
    public BitReader seek(int bitPosition) {
        if (bitPosition < 0 || startBit + bitPosition > endBit) {
            throw new CdisFormatException("bit " + bitPosition + " is outside the " + (endBit - startBit) + " bits");
        }
        pos = startBit + bitPosition;
        return this;
    }

    /** @return bits left */
    public int remaining() {
        return endBit - pos;
    }

    /** Reads {@code n} bits (0-64) as an unsigned value. */
    public long bits(int n) {
        need(n);
        long v = 0;
        for (int i = 0; i < n; i++) {
            v = (v << 1) | ((data[pos >>> 3] >>> (7 - (pos & 7))) & 1);
            pos++;
        }
        return v;
    }

    /** {@code n} bits (at most 31) as an unsigned int. */
    public int ubits(int n) {
        return (int) bits(n);
    }

    /** One bit. */
    public boolean flag() {
        return bits(1) != 0;
    }

    /** {@code n} bits, two's complement. */
    public long signed(int n) {
        long v = bits(n);
        if (n > 0 && n < 64 && (v & (1L << (n - 1))) != 0) {
            v -= 1L << n;
        }
        return v;
    }

    /** {@code n} whole octets. */
    public byte[] octets(int n) {
        need(n * 8);
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) bits(8);
        }
        return out;
    }

    /** {@code n} bits as a slice. */
    public BitSlice slice(int n) {
        need(n);
        byte[] out = new byte[(n + 7) >>> 3];
        int whole = n >>> 3;
        for (int i = 0; i < whole; i++) {
            out[i] = (byte) bits(8);
        }
        int rest = n & 7;
        if (rest > 0) {
            out[whole] = (byte) (bits(rest) << (8 - rest));
        }
        return new BitSlice(out, n);
    }

    /** The bits from {@code fromBit} up to the current position, as a slice; the position does not change. */
    public BitSlice sliceSince(int fromBit) {
        int here = position();
        seek(fromBit);
        BitSlice s = slice(here - fromBit);
        seek(here);
        return s;
    }

    private void need(int n) {
        if (n < 0 || pos + n > endBit) {
            throw new CdisFormatException("C-DIS PDU too short: " + n + " bits needed at bit " + position()
                    + " of " + (endBit - startBit));
        }
    }

    // ------------------------------------------------------------------ variable integers

    public long uvint8() {
        return VarInt.UVINT8.read(this);
    }

    public long uvint16() {
        return VarInt.UVINT16.read(this);
    }

    public long uvint32() {
        return VarInt.UVINT32.read(this);
    }
}
