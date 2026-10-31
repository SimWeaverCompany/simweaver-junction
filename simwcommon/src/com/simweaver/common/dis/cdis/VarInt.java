package com.simweaver.common.dis.cdis;

/**
 * The C-DIS variable integers (SISO-STD-023-2024 10.2): flag bits that give the number of data
 * bits that follow. Values are written with the smallest size that holds them; values that do
 * not fit the largest size are clamped to it.
 */
public enum VarInt {

    UVINT8(false, 4, 8),
    UVINT16(false, 8, 11, 14, 16),
    UVINT32(false, 8, 15, 18, 32),
    SVINT12(true, 3, 6, 9, 12),
    SVINT13(true, 5, 7, 10, 13),
    SVINT14(true, 4, 7, 9, 14),
    SVINT16(true, 8, 12, 13, 16),
    SVINT24(true, 16, 19, 21, 24);

    private final boolean signed;
    private final int[] sizes;
    private final int flagBits;

    VarInt(boolean signed, int... sizes) {
        this.signed = signed;
        this.sizes = sizes;
        this.flagBits = sizes.length == 2 ? 1 : 2;
    }

    /** @return the number of data bits of the largest size */
    public int maxBits() {
        return sizes[sizes.length - 1];
    }

    /** @return the largest value */
    public long max() {
        int n = maxBits();
        return signed ? (1L << (n - 1)) - 1 : (1L << n) - 1;
    }

    /** @return the smallest value */
    public long min() {
        return signed ? -(1L << (maxBits() - 1)) : 0;
    }

    /** @return the bits the value takes, flag included */
    public int sizeOf(long value) {
        return flagBits + sizes[index(clamp(value))];
    }

    private long clamp(long v) {
        return Math.max(min(), Math.min(max(), v));
    }

    private int index(long v) {
        for (int i = 0; i < sizes.length; i++) {
            int n = sizes[i];
            boolean fits = signed ? v >= -(1L << (n - 1)) && v <= (1L << (n - 1)) - 1 : v <= (1L << n) - 1;
            if (fits) {
                return i;
            }
        }
        return sizes.length - 1;
    }

    /** Writes the value, clamped to the range. */
    public BitWriter write(BitWriter w, long value) {
        long v = clamp(value);
        int i = index(v);
        w.bits(i, flagBits);
        return w.bits(v, sizes[i]);
    }

    /** Reads a value. */
    public long read(BitReader r) {
        int i = (int) r.bits(flagBits);
        int n = sizes[i];
        return signed ? r.signed(n) : r.bits(n);
    }
}
