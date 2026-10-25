package com.simweaver.common.dis.cdis;

import java.util.Arrays;

/**
 * Writes a C-DIS bit stream: values are packed most significant bit first, without alignment; a
 * value that crosses an octet continues with the most significant bit of the next octet
 * (SISO-STD-023-2024 10.1). Not thread safe.
 */
public final class BitWriter {

    private byte[] buf;
    private int bits;

    public BitWriter() {
        this(64);
    }

    /** @param capacityOctets expected size */
    public BitWriter(int capacityOctets) {
        buf = new byte[Math.max(8, capacityOctets)];
    }

    /** @return bits written so far */
    public int bitLength() {
        return bits;
    }

    /**
     * Writes the lowest {@code n} bits of {@code value}.
     *
     * @param value the value; higher bits are ignored
     * @param n     0 to 64
     */
    public BitWriter bits(long value, int n) {
        if (n < 0 || n > 64) {
            throw new IllegalArgumentException("bit count " + n);
        }
        ensure(n);
        for (int i = n - 1; i >= 0; i--) {
            if (((value >>> i) & 1L) != 0) {
                buf[bits >>> 3] |= (byte) (0x80 >>> (bits & 7));
            }
            bits++;
        }
        return this;
    }

    /** One bit. */
    public BitWriter flag(boolean set) {
        return bits(set ? 1 : 0, 1);
    }

    /**
     * An unsigned value in {@code n} bits, clamped to 0..2^n-1.
     */
    public BitWriter unsigned(long value, int n) {
        long max = n >= 64 ? Long.MAX_VALUE : (1L << n) - 1;
        return bits(value < 0 ? 0 : Math.min(value, max), n);
    }

    /**
     * A two's complement value in {@code n} bits, clamped to -2^(n-1)..2^(n-1)-1.
     */
    public BitWriter signed(long value, int n) {
        long max = (1L << (n - 1)) - 1;
        long min = -(1L << (n - 1));
        return bits(Math.max(min, Math.min(max, value)), n);
    }

    /** Whole octets, eight bits each. */
    public BitWriter octets(byte[] b, int off, int len) {
        for (int i = 0; i < len; i++) {
            bits(b[off + i] & 0xFF, 8);
        }
        return this;
    }

    /** The first {@code n} bits of an octet array (most significant bit first). */
    public BitWriter bitsOf(byte[] b, int n) {
        int whole = n >>> 3;
        octets(b, 0, whole);
        int rest = n & 7;
        if (rest > 0) {
            bits((b[whole] & 0xFF) >>> (8 - rest), rest);
        }
        return this;
    }

    /** Appends a slice. */
    public BitWriter slice(BitSlice s) {
        return bitsOf(s.octets(), s.bitLength());
    }

    /** Appends everything another writer holds. */
    public BitWriter append(BitWriter other) {
        return bitsOf(other.buf, other.bits);
    }

    /** @return what was written, as a slice */
    public BitSlice toSlice() {
        return new BitSlice(Arrays.copyOf(buf, (bits + 7) >>> 3), bits);
    }

    /** @return the bits, zero padded to whole octets */
    public byte[] toByteArray() {
        return Arrays.copyOf(buf, (bits + 7) >>> 3);
    }

    /** Overwrites {@code n} bits at a bit position already written (for a length field). */
    public void patch(int bitPosition, long value, int n) {
        if (bitPosition < 0 || bitPosition + n > bits) {
            throw new IllegalArgumentException("patch outside the written bits");
        }
        for (int i = n - 1; i >= 0; i--) {
            int p = bitPosition + (n - 1 - i);
            int mask = 0x80 >>> (p & 7);
            if (((value >>> i) & 1L) != 0) {
                buf[p >>> 3] |= (byte) mask;
            } else {
                buf[p >>> 3] &= (byte) ~mask;
            }
        }
    }

    private void ensure(int n) {
        int need = (bits + n + 7) >>> 3;
        if (need > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, need));
        }
    }

    // ------------------------------------------------------------------ variable integers

    /** UVINT8: 1 flag bit; 4 or 8 data bits. */
    public BitWriter uvint8(long value) {
        return VarInt.UVINT8.write(this, value);
    }

    /** UVINT16: 2 flag bits; 8, 11, 14 or 16 data bits. */
    public BitWriter uvint16(long value) {
        return VarInt.UVINT16.write(this, value);
    }

    /** UVINT32: 2 flag bits; 8, 15, 18 or 32 data bits. */
    public BitWriter uvint32(long value) {
        return VarInt.UVINT32.write(this, value);
    }
}
