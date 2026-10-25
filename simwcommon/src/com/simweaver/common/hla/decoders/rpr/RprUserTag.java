package com.simweaver.common.hla.decoders.rpr;

import com.simweaver.common.SimwTimeStamp;
import com.simweaver.common.hla.decoders.DisplayText;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The user-supplied tag of an update or interaction ({@code byte[] userSuppliedTag} in
 * {@code reflectAttributeValues} / {@code receiveInteraction}), read as an RPR time stamp
 * when it is one, otherwise as text.
 *
 * <h2>RPR format ({@code RPRUserDefinedTag})</h2>
 * At least 8 ASCII characters: the 32-bit DIS time stamp (IEEE 1278.1 5.2.31) as upper case
 * hex with leading zeros, most significant first ({@code sprintf("%08X", t)}), optionally
 * followed by federation specific characters, null terminated. In the time stamp, bits 31..1
 * count units of 3600/2^31 s (about 1.68 us) past the hour and bit 0 is 1 for absolute (UTC)
 * time, 0 for relative (simulation) time.
 *
 * <h2>Which hour</h2>
 * The tag carries only the time past the hour. {@link #parse(byte[], long)} takes a reference
 * time (normally the receive time) and picks the hour that puts the result closest to it, so
 * a tag sent at 10:59:59.9 and received at 11:00:00.1 resolves to 10:59:59.9. Relative time
 * stamps are resolved the same way but only mean something if the sender's simulation clock
 * follows UTC; see {@link #isAbsolute()}.
 *
 * <pre>
 * SimwTimeStamp ts = RprUserTag.toTimeStamp(tag, receivedMillis);   // null when not an RPR time stamp
 * String shown     = RprUserTag.describe(tag, receivedMillis);       // "2026-10-19T11:43:12.345Z" or the tag text
 * </pre>
 * Immutable.
 */
public final class RprUserTag {

    /** Milliseconds per hour. */
    static final long HOUR_MS = 3_600_000L;

    private final byte[] tag;
    private final boolean timestamp;
    private final long disTimestamp;
    private final long epochMillis;
    private final String extra;
    private final String text;

    private RprUserTag(byte[] tag, boolean timestamp, long disTimestamp, long epochMillis, String extra, String text) {
        this.tag = tag;
        this.timestamp = timestamp;
        this.disTimestamp = disTimestamp;
        this.epochMillis = epochMillis;
        this.extra = extra;
        this.text = text;
    }

    /**
     * Reads a tag.
     *
     * @param tag             the user-supplied tag (null or empty allowed)
     * @param referenceMillis reference time for the hour, epoch ms UTC (normally the receive time)
     * @return the parsed tag
     */
    public static RprUserTag parse(byte[] tag, long referenceMillis) {
        byte[] t = tag == null ? new byte[0] : tag.clone();
        int end = 0;
        while (end < t.length && t[end] != 0) {
            end++;
        }
        String content = new String(t, 0, end, StandardCharsets.ISO_8859_1);
        if (end >= 8 && isHex(t, 8)) {
            long dis = Long.parseLong(content.substring(0, 8), 16);
            long ms = resolve(dis, referenceMillis);
            return new RprUserTag(t, true, dis, ms, content.substring(8), content);
        }
        return new RprUserTag(t, false, 0, Long.MIN_VALUE, "", content);
    }

    /**
     * @param tag             the user-supplied tag
     * @param referenceMillis reference time for the hour (normally the receive time)
     * @return the time stamp, or null when the tag is not an RPR time stamp
     */
    public static SimwTimeStamp toTimeStamp(byte[] tag, long referenceMillis) {
        return parse(tag, referenceMillis).getTimeStamp();
    }

    /**
     * Same as {@link #toTimeStamp(byte[], long)} with the current time as reference.
     *
     * @param tag the user-supplied tag
     * @return the time stamp, or null when the tag is not an RPR time stamp
     */
    public static SimwTimeStamp toTimeStamp(byte[] tag) {
        return toTimeStamp(tag, System.currentTimeMillis());
    }

    /**
     * Display form: the ISO time ({@code 2026-10-19T11:43:12.345Z}, with {@code " (relative)"}
     * for relative time stamps and any extra characters appended) or, when the tag is not an
     * RPR time stamp, its content as safe text ({@code ""} for an empty tag, hex for binary data).
     *
     * @param tag             the user-supplied tag
     * @param referenceMillis reference time for the hour
     * @return the text
     */
    public static String describe(byte[] tag, long referenceMillis) {
        return parse(tag, referenceMillis).toString();
    }

    /**
     * Encodes an RPR tag (8 hex characters and a terminating null), e.g. for publishing.
     *
     * @param epochMillis time, epoch ms UTC
     * @param absolute    true for an absolute (UTC) time stamp
     * @return the tag octets
     */
    public static byte[] encode(long epochMillis, boolean absolute) {
        long pastHour = Math.floorMod(epochMillis, HOUR_MS);
        long units = (pastHour << 31) / HOUR_MS;                // exact: ms * 2^31 / 3.6e6, floor
        long dis = (units << 1) | (absolute ? 1L : 0L);
        String hex = String.format(java.util.Locale.ROOT, "%08X", dis);
        byte[] out = Arrays.copyOf(hex.getBytes(StandardCharsets.US_ASCII), 9);
        out[8] = 0;
        return out;
    }

    /** @return true when the tag starts with 8 hex characters (an RPR time stamp) */
    public boolean isTimestamp() {
        return timestamp;
    }

    /** @return the 32-bit DIS time stamp (unsigned), 0 when not a time stamp */
    public long getDisTimestamp() {
        return disTimestamp;
    }

    /** @return true for an absolute (UTC) time stamp (bit 0 set) */
    public boolean isAbsolute() {
        return timestamp && (disTimestamp & 1L) != 0;
    }

    /** @return seconds past the hour (resolution about 1.68 us), NaN when not a time stamp */
    public double getSecondsPastHour() {
        return timestamp ? (disTimestamp >>> 1) * 3600.0 / 2147483648.0 : Double.NaN;
    }

    /** @return resolved time, epoch ms UTC, or {@link Long#MIN_VALUE} when not a time stamp */
    public long getEpochMillis() {
        return epochMillis;
    }

    /** @return resolved time, or null when not a time stamp */
    public SimwTimeStamp getTimeStamp() {
        return timestamp ? SimwTimeStamp.ofMillis(epochMillis) : null;
    }

    /** @return characters after the 8 hex characters (federation specific), possibly empty */
    public String getExtra() {
        return extra;
    }

    /** @return the tag content up to the first null, as ISO-8859-1 text (not escaped) */
    public String getContent() {
        return text;
    }

    /** @return a copy of the tag octets */
    public byte[] getBytes() {
        return tag.clone();
    }

    /** @return see {@link #describe(byte[], long)} */
    @Override
    public String toString() {
        if (timestamp) {
            String s = SimwTimeStamp.ofMillis(epochMillis).toString();
            if (!isAbsolute()) {
                s += " (relative)";
            }
            if (!extra.isEmpty()) {
                s += " " + DisplayText.escape(extra, 256);
            }
            return s;
        }
        if (isPrintable(text)) {
            return DisplayText.escape(text, 1024);
        }
        return "0x" + DisplayText.hex(tag, 0, tag.length, 64);
    }

    private static long resolve(long dis, long ref) {
        long units = dis >>> 1;
        long pastHour = (units * HOUR_MS + (1L << 30)) >>> 31;   // rounded to the nearest ms
        long hourStart = Math.floorDiv(ref, HOUR_MS) * HOUR_MS;
        long best = hourStart + pastHour;
        for (long h = -1; h <= 1; h += 2) {
            long c = hourStart + h * HOUR_MS + pastHour;
            if (Math.abs(c - ref) < Math.abs(best - ref)) {
                best = c;
            }
        }
        return best;
    }

    private static boolean isHex(byte[] b, int n) {
        for (int i = 0; i < n; i++) {
            int c = b[i];
            boolean ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPrintable(String s) {
        int bad = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f || (c >= 0x80 && c < 0xa0)) {
                bad++;
            }
        }
        return bad * 4 <= s.length();   // mostly text: show as text (escaped)
    }
}
