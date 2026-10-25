package com.simweaver.common.dis.cdis;

import java.util.Arrays;

/** An immutable run of bits (most significant bit of the first octet first). */
public final class BitSlice {

    /** No bits. */
    public static final BitSlice EMPTY = new BitSlice(new byte[0], 0);

    private final byte[] octets;
    private final int bitLength;

    /**
     * @param octets    the bits, zero padded; kept, not copied
     * @param bitLength how many bits are used
     */
    public BitSlice(byte[] octets, int bitLength) {
        if (bitLength < 0 || bitLength > octets.length * 8) {
            throw new IllegalArgumentException(bitLength + " bits in " + octets.length + " octets");
        }
        this.octets = octets;
        this.bitLength = bitLength;
    }

    /** @return the bits; not a copy, do not change */
    byte[] octets() {
        return octets;
    }

    public int bitLength() {
        return bitLength;
    }

    /** @return a reader over the bits */
    public BitReader reader() {
        return new BitReader(octets, 0, bitLength);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof BitSlice)) {
            return false;
        }
        BitSlice s = (BitSlice) o;
        if (s.bitLength != bitLength) {
            return false;
        }
        int whole = bitLength >>> 3;
        for (int i = 0; i < whole; i++) {
            if (octets[i] != s.octets[i]) {
                return false;
            }
        }
        int rest = bitLength & 7;
        if (rest == 0) {
            return true;
        }
        int mask = (0xFF << (8 - rest)) & 0xFF;
        return (octets[whole] & mask) == (s.octets[whole] & mask);
    }

    @Override
    public int hashCode() {
        int whole = bitLength >>> 3;
        int h = Arrays.hashCode(Arrays.copyOf(octets, whole)) * 31 + bitLength;
        int rest = bitLength & 7;
        if (rest > 0) {
            h = h * 31 + (octets[whole] & ((0xFF << (8 - rest)) & 0xFF));
        }
        return h;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(bitLength);
        for (int i = 0; i < bitLength; i++) {
            sb.append((octets[i >>> 3] & (0x80 >>> (i & 7))) != 0 ? '1' : '0');
        }
        return sb.toString();
    }
}
