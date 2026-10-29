package com.simweaver.j2j.crypto;

/**
 * A sliding replay window of 1024 counters for datagram channels (as in IPsec and DTLS): a
 * counter is accepted once, and only if it is newer than the highest seen minus the window.
 * Not thread safe.
 */
public final class ReplayWindow {

    public static final int SIZE = 1024;

    private final long[] bits = new long[SIZE / 64];
    private long highest = -1;

    /**
     * @return true when {@code counter} is new; it is then marked as seen. Call only after the
     *         frame authenticated, so forged counters cannot move the window.
     */
    public boolean accept(long counter) {
        if (counter < 0) {
            return false;
        }
        if (counter > highest) {
            long shift = highest < 0 ? SIZE : counter - highest;
            if (shift >= SIZE) {
                java.util.Arrays.fill(bits, 0L);
            } else {
                for (long c = highest + 1; c <= counter; c++) {
                    clear(c);
                }
            }
            highest = counter;
            set(counter);
            return true;
        }
        if (highest - counter >= SIZE) {
            return false;
        }
        if (isSet(counter)) {
            return false;
        }
        set(counter);
        return true;
    }

    private int slot(long c) {
        return (int) (c % SIZE);
    }

    private void set(long c) {
        int s = slot(c);
        bits[s >>> 6] |= 1L << (s & 63);
    }

    private void clear(long c) {
        int s = slot(c);
        bits[s >>> 6] &= ~(1L << (s & 63));
    }

    private boolean isSet(long c) {
        int s = slot(c);
        return (bits[s >>> 6] & (1L << (s & 63))) != 0;
    }
}
