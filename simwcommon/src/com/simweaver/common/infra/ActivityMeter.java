package com.simweaver.common.infra;

/**
 * Messages and octets per second over the last minute, in one-second buckets. Not thread safe:
 * the owner synchronizes.
 */
final class ActivityMeter {

    /** Seconds of history kept (and shown as a sparkline). */
    static final int SECONDS = 60;
    /** Seconds the rate is averaged over (whole seconds before the current one). */
    static final int RATE_SECONDS = 5;

    private final int[] count = new int[SECONDS];
    private final long[] octets = new long[SECONDS];
    private long second = -1;            // epoch second of the newest bucket
    private long total;
    private long totalOctets;

    void add(long nowMillis, int messages, long bytes) {
        roll(nowMillis);
        int i = (int) (second % SECONDS);
        count[i] += messages;
        octets[i] += bytes;
        total += messages;
        totalOctets += bytes;
    }

    private void roll(long nowMillis) {
        long s = nowMillis / 1000;
        if (second < 0) {
            second = s;
            return;
        }
        if (s <= second) {
            return;
        }
        long gap = Math.min(SECONDS, s - second);
        for (long k = 1; k <= gap; k++) {
            int i = (int) ((second + k) % SECONDS);
            count[i] = 0;
            octets[i] = 0;
        }
        second = s;
    }

    /** @return messages per second over the last {@link #RATE_SECONDS} whole seconds */
    double rate(long nowMillis) {
        roll(nowMillis);
        if (second < 0) {
            return 0;
        }
        long sum = 0;
        for (int k = 1; k <= RATE_SECONDS; k++) {
            sum += count[(int) ((second - k + SECONDS) % SECONDS)];
        }
        return sum / (double) RATE_SECONDS;
    }

    /** @return octets per second over the last {@link #RATE_SECONDS} whole seconds */
    double octetRate(long nowMillis) {
        roll(nowMillis);
        if (second < 0) {
            return 0;
        }
        long sum = 0;
        for (int k = 1; k <= RATE_SECONDS; k++) {
            sum += octets[(int) ((second - k + SECONDS) % SECONDS)];
        }
        return sum / (double) RATE_SECONDS;
    }

    /** @return messages per second, oldest first, for the last {@code SECONDS - 1} whole seconds */
    int[] history(long nowMillis) {
        roll(nowMillis);
        int[] h = new int[SECONDS - 1];
        if (second < 0) {
            return h;
        }
        for (int k = 1; k < SECONDS; k++) {
            long s = second - SECONDS + k;          // the current (partial) second is left out
            h[k - 1] = s < 0 ? 0 : count[(int) (s % SECONDS)];
        }
        return h;
    }

    long total() {
        return total;
    }

    long totalOctets() {
        return totalOctets;
    }

    static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
