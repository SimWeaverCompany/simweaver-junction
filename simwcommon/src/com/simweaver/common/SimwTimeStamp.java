package com.simweaver.common;

import java.io.Serializable;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An immutable point in time with millisecond precision, held as milliseconds since
 * 1970-01-01T00:00:00Z (the same scale as {@link System#currentTimeMillis()}).
 *
 * <h2>Creation</h2>
 * <pre>{@code
 * new SimwTimeStamp()                          // now
 * new SimwTimeStamp(1792410192345L)            // epoch milliseconds
 * new SimwTimeStamp(peer)                      // same instant as another SimwTimeStamp
 * new SimwTimeStamp("2026-10-19T11:43:12.345Z")// text, see below
 * new SimwTimeStamp("2026-10-19 13:43", ZoneId.of("Europe/Stockholm"))
 * }</pre>
 * Equivalent static factories: {@link #now()}, {@link #ofMillis(long)}, {@link #of(Instant)},
 * {@link #of(Date)}, {@link #parse(String)}, {@link #parse(String, ZoneId)}, and the
 * non-throwing {@link #tryParse(String)}.
 *
 * <h2>Accepted text</h2>
 * Leading and trailing whitespace is ignored. Month names and zone letters are case-insensitive.
 * <ul>
 *   <li><b>ISO 8601 / RFC 3339</b>, extended or basic form:
 *     <ul>
 *       <li>{@code 2026-10-19T11:43:12.345Z}, {@code 2026-10-19 11:43:12Z},
 *           {@code 2026-10-19T11:43}, {@code 2026-10-19} (midnight),
 *           {@code 20261019T114312.345Z}</li>
 *       <li>date/time separator {@code T}, {@code t}, {@code _} or one or more spaces</li>
 *       <li>seconds and fraction optional; the fraction may use {@code .} or {@code ,} and any
 *           number of digits, and is <i>truncated</i> to milliseconds</li>
 *       <li>zone: {@code Z}, {@code UTC}, {@code GMT}, {@code +02:00}, {@code +0200},
 *           {@code +02} (optionally after a space), and an optional {@code [Region/City]}
 *           suffix as written by {@code ZonedDateTime.toString()}. Without an offset the
 *           region applies; without either, the <i>zone for local times</i> applies
 *           (UTC unless given)</li>
 *       <li>{@code 24:00} means midnight at the end of the day; a leap second {@code :60}
 *           is read as {@code :59}</li>
 *       <li>years outside 0000..9999 need a sign: {@code +12026-01-01}, {@code -0044-03-15}</li>
 *     </ul></li>
 *   <li><b>Military date-time group (DTG)</b> {@code DDHHMM[SS]<zone> MON YY[YY]}, with or
 *       without spaces: {@code 170610ZOCT26}, {@code 170610Z OCT 2026}. Zone letters
 *       {@code A}..{@code M} (no {@code J}) are UTC+1..+12, {@code N}..{@code Y} are
 *       UTC-1..-12, {@code Z} is UTC and {@code J} (local time) uses the zone for local times.
 *       Two-digit years 70..99 are 1970..1999, 00..69 are 2000..2069.</li>
 *   <li><b>RFC 1123</b> (HTTP dates): {@code Mon, 19 Oct 2026 11:43:12 GMT}.</li>
 *   <li><b>Epoch milliseconds</b>: an optionally signed integer, {@code 1792410192345}.
 *       A string of digits only is <i>always</i> epoch milliseconds, never a basic-form date.</li>
 *   <li>{@code now}: the current system time.</li>
 *   <li>Any of the above inside double quotes, as a JSON string value:
 *       {@code "2026-10-19T11:43:12.345Z"} with the quotes.</li>
 * </ul>
 * Anything else fails with an {@link IllegalArgumentException} that names the text and the
 * reason, for example {@code Cannot parse time stamp '2026-02-30': day 30 out of range 1..28}.
 *
 * <h2>Output</h2>
 * {@link #toString()} and {@link #getText()} give ISO 8601 UTC with exactly three fraction
 * digits, {@code 2026-10-19T11:43:12.345Z}: fixed width (for years 0000..9999), sortable as
 * text, and accepted by {@link #parse(String)}, {@code Instant.parse} and JavaScript
 * {@code Date}. Other forms: {@link #getText(String)} (any {@code DateTimeFormatter} pattern),
 * {@link #toIsoSecondsString()}, {@link #toBasicIsoString()} (no colons, safe in Windows file
 * names), {@link #toDtg()}, {@link #toZuluTime()}, {@link #toZuluTimeMillis()},
 * {@link #toRfc1123String()}, {@link #toJson()} and {@link #toJsonValue()}.
 *
 * <h2>Fields, comparison and differences</h2>
 * Calendar fields ({@link #getYear()} .. {@link #getMillisecond()}) are in UTC; use
 * {@link #toLocalDateTime(ZoneId)} for local fields. {@link #before}, {@link #after} and
 * {@link #same} compare. {@code a.diffMillis(b)} and the coarser {@code diffSeconds},
 * {@code diffMinutes}, {@code diffHours}, {@code diffDays} are signed {@code a - b}, truncated
 * towards zero; {@link #diffText(SimwTimeStamp)} gives the distance as text,
 * {@code "1 day, 14 hours and 3 minutes"}.
 *
 * <p>Instances are immutable and therefore thread safe. Equality, hash code and ordering are
 * by the millisecond value only.
 */
public final class SimwTimeStamp implements Comparable<SimwTimeStamp>, Serializable {

    private static final long serialVersionUID = 1L;

    /** 1970-01-01T00:00:00.000Z. */
    public static final SimwTimeStamp EPOCH = new SimwTimeStamp(0L);

    private static final long MILLIS_PER_SECOND = 1_000L;
    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final long MILLIS_PER_HOUR = 3_600_000L;
    private static final long MILLIS_PER_DAY = 86_400_000L;

    /** Marks "no explicit offset in the text". */
    private static final int NO_OFFSET = Integer.MIN_VALUE;

    private static final int MAX_OFFSET_SECONDS = 18 * 3600;

    private static final String[] MONTHS = {
        "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"
    };

    private static final Pattern DTG = Pattern.compile(
            "(\\d{2})(\\d{2})(\\d{2})(\\d{2})?\\s*([A-Za-z])\\s*([A-Za-z]{3})\\s*(\\d{4}|\\d{2})");

    private static final Pattern RFC1123_WITHOUT_WEEKDAY = Pattern.compile("\\d{1,2} [A-Za-z].*");

    private final long millis;

    // ------------------------------------------------------------------------------------------
    // Creation
    // ------------------------------------------------------------------------------------------

    /** The current system time, {@link System#currentTimeMillis()}. */
    public SimwTimeStamp() {
        this(System.currentTimeMillis());
    }

    /**
     * A given instant.
     *
     * @param epochMillis milliseconds since 1970-01-01T00:00:00Z, as from
     *                    {@link System#currentTimeMillis()}
     */
    public SimwTimeStamp(long epochMillis) {
        this.millis = epochMillis;
    }

    /**
     * The same instant as a peer. As the class is immutable, sharing the peer itself is
     * equally safe; this constructor exists for symmetry with the other creation forms.
     *
     * @param peer the time stamp to copy
     * @throws NullPointerException if {@code peer} is null
     */
    public SimwTimeStamp(SimwTimeStamp peer) {
        this.millis = Objects.requireNonNull(peer, "peer").millis;
    }

    /**
     * Parses text; times without an offset or zone are taken as UTC.
     *
     * @param text one of the forms listed in the class description
     * @throws NullPointerException     if {@code text} is null
     * @throws IllegalArgumentException if the text is not a recognised time stamp
     */
    public SimwTimeStamp(String text) {
        this(text, ZoneOffset.UTC);
    }

    /**
     * Parses text; times without an offset or zone (and DTG zone letter {@code J}) are
     * taken in {@code zoneForLocalTimes}. Daylight saving gaps and overlaps are resolved as
     * {@code ZonedDateTime.of} does: a time in a gap moves forward by the gap length, a time
     * in an overlap takes the earlier offset.
     *
     * @param text              one of the forms listed in the class description
     * @param zoneForLocalTimes zone for text that carries no offset
     * @throws NullPointerException     if an argument is null
     * @throws IllegalArgumentException if the text is not a recognised time stamp
     */
    public SimwTimeStamp(String text, ZoneId zoneForLocalTimes) {
        this.millis = parseMillis(text, zoneForLocalTimes);
    }

    /** @return the current system time */
    public static SimwTimeStamp now() {
        return new SimwTimeStamp();
    }

    /**
     * @param epochMillis milliseconds since 1970-01-01T00:00:00Z
     * @return a time stamp for that instant
     */
    public static SimwTimeStamp ofMillis(long epochMillis) {
        return epochMillis == 0L ? EPOCH : new SimwTimeStamp(epochMillis);
    }

    /**
     * @param instant an instant; sub-millisecond digits are truncated (towards the past)
     * @return a time stamp for that instant
     * @throws ArithmeticException if the instant is outside the epoch-millisecond range
     */
    public static SimwTimeStamp of(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        long ms = Math.addExact(Math.multiplyExact(instant.getEpochSecond(), MILLIS_PER_SECOND),
                                instant.getNano() / 1_000_000);
        return new SimwTimeStamp(ms);
    }

    /**
     * @param date a {@code java.util.Date}
     * @return a time stamp for the same instant
     */
    public static SimwTimeStamp of(Date date) {
        return new SimwTimeStamp(Objects.requireNonNull(date, "date").getTime());
    }

    /**
     * Parses text, taking times without an offset as UTC.
     *
     * @param text one of the forms listed in the class description
     * @return the time stamp
     * @throws IllegalArgumentException if the text is not a recognised time stamp
     */
    public static SimwTimeStamp parse(String text) {
        return new SimwTimeStamp(text);
    }

    /**
     * Parses text, taking times without an offset in {@code zoneForLocalTimes}.
     *
     * @param text              one of the forms listed in the class description
     * @param zoneForLocalTimes zone for text that carries no offset
     * @return the time stamp
     * @throws IllegalArgumentException if the text is not a recognised time stamp
     */
    public static SimwTimeStamp parse(String text, ZoneId zoneForLocalTimes) {
        return new SimwTimeStamp(text, zoneForLocalTimes);
    }

    /**
     * Like {@link #parse(String)}, but returns {@code null} for null or unparsable text.
     *
     * @param text the text, may be null
     * @return the time stamp, or null
     */
    public static SimwTimeStamp tryParse(String text) {
        return tryParse(text, ZoneOffset.UTC);
    }

    /**
     * Like {@link #parse(String, ZoneId)}, but returns {@code null} for null or unparsable text.
     *
     * @param text              the text, may be null
     * @param zoneForLocalTimes zone for text that carries no offset
     * @return the time stamp, or null
     */
    public static SimwTimeStamp tryParse(String text, ZoneId zoneForLocalTimes) {
        if (text == null) {
            return null;
        }
        try {
            return new SimwTimeStamp(text, zoneForLocalTimes);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Access and arithmetic
    // ------------------------------------------------------------------------------------------

    /** @return milliseconds since 1970-01-01T00:00:00Z */
    public long getMillis() {
        return millis;
    }

    /** @return the same instant as a {@code java.time.Instant} */
    public Instant toInstant() {
        return Instant.ofEpochMilli(millis);
    }

    /** @return the same instant as a new {@code java.util.Date} */
    public Date toDate() {
        return new Date(millis);
    }

    /**
     * @param zone a zone, for example {@code ZoneId.systemDefault()}
     * @return the local date and time in that zone
     */
    public LocalDateTime toLocalDateTime(ZoneId zone) {
        return LocalDateTime.ofInstant(toInstant(), zone);
    }

    // ---- calendar fields (UTC)

    /** @return the year in UTC (proleptic Gregorian, year 0 = 1 BC) */
    public int getYear() {
        return (int) new Fields(millis).year;
    }

    /** @return the month in UTC, 1..12 */
    public int getMonth() {
        return new Fields(millis).month;
    }

    /** @return the day of the month in UTC, 1..31 */
    public int getDayOfMonth() {
        return new Fields(millis).day;
    }

    /** @return the day of the week in UTC */
    public DayOfWeek getDayOfWeek() {
        // 1970-01-01 was a Thursday.
        return DayOfWeek.of((int) Math.floorMod(Math.floorDiv(millis, MILLIS_PER_DAY) + 3, 7L) + 1);
    }

    /** @return the hour of the day in UTC, 0..23 */
    public int getHour() {
        return (int) (Math.floorMod(millis, MILLIS_PER_DAY) / MILLIS_PER_HOUR);
    }

    /** @return the minute of the hour, 0..59 */
    public int getMinute() {
        return (int) (Math.floorMod(millis, MILLIS_PER_HOUR) / MILLIS_PER_MINUTE);
    }

    /** @return the second of the minute, 0..59 */
    public int getSecond() {
        return (int) (Math.floorMod(millis, MILLIS_PER_MINUTE) / MILLIS_PER_SECOND);
    }

    /** @return the millisecond of the second, 0..999 */
    public int getMillisecond() {
        return (int) Math.floorMod(millis, MILLIS_PER_SECOND);
    }

    // ---- age

    /**
     * @return milliseconds from this time stamp to the current system time; negative if this
     *         time stamp lies in the future
     */
    public long getAgeMillis() {
        return System.currentTimeMillis() - millis;
    }

    /**
     * @param ageMillis an age in milliseconds
     * @return true if more than {@code ageMillis} have passed since this time stamp
     */
    public boolean isOlderThan(long ageMillis) {
        return getAgeMillis() > ageMillis;
    }

    // ---- comparison

    /**
     * @param other another time stamp
     * @return true if this is strictly before {@code other}
     */
    public boolean before(SimwTimeStamp other) {
        return millis < other.millis;
    }

    /**
     * @param other another time stamp
     * @return true if this is strictly after {@code other}
     */
    public boolean after(SimwTimeStamp other) {
        return millis > other.millis;
    }

    /**
     * @param other another time stamp
     * @return true if both denote the same millisecond
     */
    public boolean same(SimwTimeStamp other) {
        return millis == other.millis;
    }

    // ---- differences
    //
    // All diff methods are signed: a.diffX(b) is a - b, positive when a is after b.
    // Coarser units are truncated towards zero, so -90 s is -1 minute, not -2.

    /**
     * @param other another time stamp
     * @return {@code this - other} in milliseconds
     * @throws ArithmeticException if the difference does not fit in a long
     */
    public long diffMillis(SimwTimeStamp other) {
        return Math.subtractExact(millis, other.millis);
    }

    /**
     * @param other another time stamp
     * @return {@code this - other} in whole seconds, truncated towards zero
     */
    public long diffSeconds(SimwTimeStamp other) {
        return diffMillis(other) / MILLIS_PER_SECOND;
    }

    /**
     * @param other another time stamp
     * @return {@code this - other} in whole minutes, truncated towards zero
     */
    public long diffMinutes(SimwTimeStamp other) {
        return diffMillis(other) / MILLIS_PER_MINUTE;
    }

    /**
     * @param other another time stamp
     * @return {@code this - other} in whole hours, truncated towards zero
     */
    public long diffHours(SimwTimeStamp other) {
        return diffMillis(other) / MILLIS_PER_HOUR;
    }

    /**
     * @param other another time stamp
     * @return {@code this - other} in whole days of 24 hours, truncated towards zero
     */
    public long diffDays(SimwTimeStamp other) {
        return diffMillis(other) / MILLIS_PER_DAY;
    }

    /**
     * The distance to another time stamp as English text, for example
     * {@code "1 day, 14 hours and 3 minutes"}. The text has no sign: use
     * {@link #before(SimwTimeStamp)} or {@link #diffMillis(SimwTimeStamp)} for the direction.
     * See {@link #durationText(long)} for the rules.
     *
     * @param other another time stamp
     * @return the distance as text
     */
    public String diffText(SimwTimeStamp other) {
        return durationText(diffMillis(other));
    }

    /**
     * Like {@link #diffText(SimwTimeStamp)}, limited to the {@code maxUnits} most significant
     * units: with 2, {@code "1 day, 14 hours and 3 minutes"} becomes {@code "1 day and 14 hours"}.
     * The rest is truncated, not rounded.
     *
     * @param other    another time stamp
     * @param maxUnits number of units to show, counted from the largest non-zero one; at least 1
     * @return the distance as text
     */
    public String diffText(SimwTimeStamp other, int maxUnits) {
        return durationText(diffMillis(other), maxUnits);
    }

    /**
     * A duration as English text: days, hours, minutes and seconds, zero units left out,
     * singular where the count is 1, joined as {@code "a, b and c"}. Milliseconds are shown
     * only for durations under one second ({@code "250 milliseconds"}); zero is
     * {@code "0 seconds"}. The sign is ignored.
     *
     * @param durationMillis a duration in milliseconds
     * @return the text, for example {@code "1 day, 14 hours and 3 minutes"}
     */
    public static String durationText(long durationMillis) {
        return durationText(durationMillis, Integer.MAX_VALUE);
    }

    /**
     * Like {@link #durationText(long)}, limited to the {@code maxUnits} most significant units
     * counted from the largest non-zero one (truncated, not rounded).
     *
     * @param durationMillis a duration in milliseconds
     * @param maxUnits       number of units to show; at least 1
     * @return the text
     */
    public static String durationText(long durationMillis, int maxUnits) {
        if (maxUnits < 1) {
            throw new IllegalArgumentException("maxUnits must be at least 1, was " + maxUnits);
        }
        // Long.MIN_VALUE has no positive counterpart; one millisecond less is invisible here.
        long ms = durationMillis == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(durationMillis);
        if (ms < MILLIS_PER_SECOND) {
            return ms == 0 ? "0 seconds" : unit(ms, "millisecond");
        }
        long[] counts = {
            ms / MILLIS_PER_DAY,
            ms % MILLIS_PER_DAY / MILLIS_PER_HOUR,
            ms % MILLIS_PER_HOUR / MILLIS_PER_MINUTE,
            ms % MILLIS_PER_MINUTE / MILLIS_PER_SECOND
        };
        String[] names = {"day", "hour", "minute", "second"};
        int first = 0;
        while (counts[first] == 0) {
            first++;
        }
        int last = (int) Math.min(counts.length - 1L, (long) first + maxUnits - 1);
        List<String> parts = new ArrayList<>(4);
        for (int i = first; i <= last; i++) {
            if (counts[i] != 0) {
                parts.add(unit(counts[i], names[i]));
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(i == parts.size() - 1 ? " and " : ", ");
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static String unit(long count, String name) {
        return count == 1 ? "1 " + name : count + " " + name + "s";
    }

    /**
     * @param delta milliseconds to add, may be negative
     * @return a new time stamp, or this one if {@code delta} is 0
     * @throws ArithmeticException on overflow
     */
    public SimwTimeStamp plusMillis(long delta) {
        return delta == 0L ? this : new SimwTimeStamp(Math.addExact(millis, delta));
    }

    /**
     * @param delta milliseconds to subtract, may be negative
     * @return a new time stamp, or this one if {@code delta} is 0
     * @throws ArithmeticException on overflow
     */
    public SimwTimeStamp minusMillis(long delta) {
        return delta == 0L ? this : new SimwTimeStamp(Math.subtractExact(millis, delta));
    }

    // ------------------------------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------------------------------

    /** @return ISO 8601 UTC with milliseconds, {@code 2026-10-19T11:43:12.345Z} */
    @Override
    public String toString() {
        return toIsoString();
    }

    /** @return ISO 8601 UTC with milliseconds, {@code 2026-10-19T11:43:12.345Z} */
    public String toIsoString() {
        return iso(true, true);
    }

    /** @return ISO 8601 UTC truncated to seconds, {@code 2026-10-19T11:43:12Z} */
    public String toIsoSecondsString() {
        return iso(true, false);
    }

    /**
     * ISO 8601 basic form, {@code 20261019T114312.345Z}. Contains no colons, so it can be used
     * in Windows file names, and it sorts as text. Years outside 0000..9999 have no basic form
     * and fall back to {@link #toIsoString()}.
     *
     * @return the basic-form text
     */
    public String toBasicIsoString() {
        Fields f = new Fields(millis);
        if (f.year < 0 || f.year > 9999) {
            return toIsoString();
        }
        return iso(false, true);
    }

    /** @return military date-time group in UTC, {@code 191143ZOCT26} (minute precision) */
    public String toDtg() {
        return toDtg(false);
    }

    /**
     * @param spaced true for {@code 191143Z OCT 26}, false for {@code 191143ZOCT26}
     * @return military date-time group in UTC (minute precision, two-digit year)
     */
    public String toDtg(boolean spaced) {
        Fields f = new Fields(millis);
        StringBuilder sb = new StringBuilder(14);
        pad2(sb, f.day);
        pad2(sb, f.hour);
        pad2(sb, f.minute);
        sb.append('Z');
        if (spaced) {
            sb.append(' ');
        }
        sb.append(MONTHS[f.month - 1]);
        if (spaced) {
            sb.append(' ');
        }
        pad2(sb, (int) Math.floorMod(f.year, 100L));
        return sb.toString();
    }

    /** @return UTC time of day, {@code 11:43:12Z} */
    public String toZuluTime() {
        return zulu(false);
    }

    /** @return UTC time of day with milliseconds, {@code 11:43:12.345Z} */
    public String toZuluTimeMillis() {
        return zulu(true);
    }

    /**
     * RFC 1123 / HTTP date, {@code Mon, 19 Oct 2026 11:43:12 GMT} (seconds precision).
     *
     * @return the text
     * @throws DateTimeException for years outside 0000..9999, which RFC 1123 cannot express
     */
    public String toRfc1123String() {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(toInstant().atOffset(ZoneOffset.UTC));
    }

    /** @return the default text form, the same as {@link #toString()} */
    public String getText() {
        return toIsoString();
    }

    /**
     * Formats in UTC with a {@link DateTimeFormatter} pattern and English month/day names.
     *
     * @param pattern for example {@code "yyyy-MM-dd HH:mm"}
     * @return the formatted text
     * @throws IllegalArgumentException if the pattern is invalid
     */
    public String getText(String pattern) {
        return getText(pattern, ZoneOffset.UTC);
    }

    /**
     * Formats in a given zone with a {@link DateTimeFormatter} pattern and English names.
     *
     * @param pattern for example {@code "yyyy-MM-dd HH:mm z"}
     * @param zone    the zone to display in, for example {@code ZoneId.systemDefault()}
     * @return the formatted text
     * @throws IllegalArgumentException if the pattern is invalid
     */
    public String getText(String pattern, ZoneId zone) {
        return DateTimeFormatter.ofPattern(pattern, Locale.US).withZone(zone).format(toInstant());
    }

    /**
     * Formats with a ready formatter. A formatter without a zone is used in UTC.
     *
     * @param formatter the formatter
     * @return the formatted text
     */
    public String getText(DateTimeFormatter formatter) {
        DateTimeFormatter f = formatter.getZone() == null ? formatter.withZone(ZoneOffset.UTC) : formatter;
        return f.format(toInstant());
    }

    // ---- JSON

    /**
     * The time stamp as a JSON string value, {@code "2026-10-19T11:43:12.345Z"}, quotes
     * included. This is the form to embed in other JSON (REST replies, SSE events); it is
     * accepted by {@link #parse(String)} as it is.
     *
     * @return the JSON string literal
     */
    public String toJsonValue() {
        return '"' + toIsoString() + '"';
    }

    /**
     * The time stamp as a pretty-printed JSON object, two-space indent, UTC:
     * <pre>
     * {
     *   "iso": "2026-10-19T11:43:12.345Z",
     *   "millis": 1792410192345,
     *   "year": 2026,
     *   "month": 10,
     *   "day": 19,
     *   "hour": 11,
     *   "minute": 43,
     *   "second": 12,
     *   "millisecond": 345,
     *   "dayofweek": "Monday",
     *   "dtg": "191143ZOCT26"
     * }</pre>
     * {@code millis} is a JSON number; a JavaScript client reads it exactly up to
     * 2<sup>53</sup> ms (year 287396), which covers any real time stamp.
     *
     * @return the JSON text
     */
    public String toJson() {
        return toJson(true);
    }

    /**
     * @param pretty true for the indented multi-line form of {@link #toJson()}, false for one
     *               compact line (as required inside an SSE {@code data:} line)
     * @return the JSON object text
     */
    public String toJson(boolean pretty) {
        Fields f = new Fields(millis);
        String dayOfWeek = getDayOfWeek().name();
        dayOfWeek = dayOfWeek.charAt(0) + dayOfWeek.substring(1).toLowerCase(Locale.ROOT);
        JsonObjectWriter w = new JsonObjectWriter(pretty);
        w.string("iso", toIsoString());
        w.number("millis", millis);
        w.number("year", f.year);
        w.number("month", f.month);
        w.number("day", f.day);
        w.number("hour", f.hour);
        w.number("minute", f.minute);
        w.number("second", f.second);
        w.number("millisecond", f.milli);
        w.string("dayofweek", dayOfWeek);
        w.string("dtg", toDtg());
        return w.close();
    }

    /** Writes one flat JSON object. Values written here never need escaping. */
    private static final class JsonObjectWriter {
        private final boolean pretty;
        private final StringBuilder sb = new StringBuilder(256);
        private boolean first = true;

        JsonObjectWriter(boolean pretty) {
            this.pretty = pretty;
            sb.append('{');
        }

        void string(String key, String value) {
            key(key);
            sb.append('"').append(value).append('"');
        }

        void number(String key, long value) {
            key(key);
            sb.append(value);
        }

        private void key(String key) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(pretty ? "\n  \"" : "\"").append(key).append(pretty ? "\": " : "\":");
        }

        String close() {
            return sb.append(pretty ? "\n}" : "}").toString();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Object
    // ------------------------------------------------------------------------------------------

    @Override
    public int compareTo(SimwTimeStamp other) {
        return Long.compare(millis, other.millis);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof SimwTimeStamp && ((SimwTimeStamp) obj).millis == millis;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(millis);
    }

    // ------------------------------------------------------------------------------------------
    // Formatting internals
    // ------------------------------------------------------------------------------------------

    /** Calendar fields of an epoch-millisecond value in UTC (proleptic Gregorian). */
    private static final class Fields {
        final long year;
        final int month;
        final int day;
        final int hour;
        final int minute;
        final int second;
        final int milli;

        Fields(long epochMillis) {
            long days = Math.floorDiv(epochMillis, MILLIS_PER_DAY);
            int tod = (int) Math.floorMod(epochMillis, MILLIS_PER_DAY);
            // H. Hinnant, "civil_from_days": exact for the whole long range used here.
            long z = days + 719_468L;
            long era = Math.floorDiv(z, 146_097L);
            long doe = z - era * 146_097L;
            long yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
            long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
            long mp = (5 * doy + 2) / 153;
            this.day = (int) (doy - (153 * mp + 2) / 5 + 1);
            this.month = (int) (mp < 10 ? mp + 3 : mp - 9);
            this.year = yoe + era * 400 + (month <= 2 ? 1 : 0);
            this.hour = tod / 3_600_000;
            this.minute = tod / 60_000 % 60;
            this.second = tod / 1_000 % 60;
            this.milli = tod % 1_000;
        }
    }

    private String iso(boolean extended, boolean withMillis) {
        Fields f = new Fields(millis);
        StringBuilder sb = new StringBuilder(24);
        appendYear(sb, f.year);
        if (extended) {
            sb.append('-');
        }
        pad2(sb, f.month);
        if (extended) {
            sb.append('-');
        }
        pad2(sb, f.day);
        sb.append('T');
        pad2(sb, f.hour);
        if (extended) {
            sb.append(':');
        }
        pad2(sb, f.minute);
        if (extended) {
            sb.append(':');
        }
        pad2(sb, f.second);
        if (withMillis) {
            sb.append('.');
            pad3(sb, f.milli);
        }
        return sb.append('Z').toString();
    }

    private String zulu(boolean withMillis) {
        Fields f = new Fields(millis);
        StringBuilder sb = new StringBuilder(13);
        pad2(sb, f.hour);
        sb.append(':');
        pad2(sb, f.minute);
        sb.append(':');
        pad2(sb, f.second);
        if (withMillis) {
            sb.append('.');
            pad3(sb, f.milli);
        }
        return sb.append('Z').toString();
    }

    /** ISO 8601: four digits for 0000..9999, otherwise sign plus at least four digits. */
    private static void appendYear(StringBuilder sb, long year) {
        if (year > 9999) {
            sb.append('+').append(year);
            return;
        }
        long abs = year;
        if (year < 0) {
            sb.append('-');
            abs = -year;
        }
        if (abs < 1000) {
            sb.append('0');
        }
        if (abs < 100) {
            sb.append('0');
        }
        if (abs < 10) {
            sb.append('0');
        }
        sb.append(abs);
    }

    private static void pad2(StringBuilder sb, int v) {
        if (v < 10) {
            sb.append('0');
        }
        sb.append(v);
    }

    private static void pad3(StringBuilder sb, int v) {
        if (v < 100) {
            sb.append('0');
        }
        if (v < 10) {
            sb.append('0');
        }
        sb.append(v);
    }

    // ------------------------------------------------------------------------------------------
    // Parsing internals
    // ------------------------------------------------------------------------------------------

    private static long parseMillis(String text, ZoneId zoneForLocalTimes) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(zoneForLocalTimes, "zoneForLocalTimes");
        String s = text.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1).trim();      // a JSON string value
        }
        if (s.isEmpty()) {
            throw fail(text, "empty text");
        }
        if (s.equalsIgnoreCase("now")) {
            return System.currentTimeMillis();
        }
        if (isInteger(s)) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                throw fail(text, "epoch milliseconds out of range");
            }
        }
        if (isDecimal(s)) {
            throw fail(text, "a number with a fraction is not accepted; "
                    + "give epoch milliseconds as an integer, or ISO 8601");
        }
        Matcher dtg = DTG.matcher(s);
        if (dtg.matches()) {
            return parseDtg(text, dtg, zoneForLocalTimes);
        }
        if (Character.isLetter(s.charAt(0)) || RFC1123_WITHOUT_WEEKDAY.matcher(s).matches()) {
            return parseRfc1123(text, s);
        }
        return parseIso(text, s, zoneForLocalTimes);
    }

    private static boolean isInteger(String s) {
        int i = (s.charAt(0) == '+' || s.charAt(0) == '-') ? 1 : 0;
        if (i == s.length()) {
            return false;
        }
        for (; i < s.length(); i++) {
            if (!isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDecimal(String s) {
        int i = (s.charAt(0) == '+' || s.charAt(0) == '-') ? 1 : 0;
        int digitsBefore = 0;
        while (i < s.length() && isDigit(s.charAt(i))) {
            i++;
            digitsBefore++;
        }
        if (digitsBefore == 0 || i == s.length() || (s.charAt(i) != '.' && s.charAt(i) != ',')) {
            return false;
        }
        i++;
        int digitsAfter = 0;
        while (i < s.length() && isDigit(s.charAt(i))) {
            i++;
            digitsAfter++;
        }
        return digitsAfter > 0 && i == s.length();
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static long parseRfc1123(String text, String s) {
        try {
            return DateTimeFormatter.RFC_1123_DATE_TIME.parse(s, Instant::from).toEpochMilli();
        } catch (DateTimeException | ArithmeticException e) {
            throw fail(text, "not a recognised form (ISO 8601, DTG, RFC 1123, epoch milliseconds or 'now')");
        }
    }

    private static long parseDtg(String text, Matcher m, ZoneId zoneForLocalTimes) {
        int day = Integer.parseInt(m.group(1));
        int hour = Integer.parseInt(m.group(2));
        int minute = Integer.parseInt(m.group(3));
        int second = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
        char zoneLetter = Character.toUpperCase(m.group(5).charAt(0));
        String monthName = m.group(6).toUpperCase(Locale.ROOT);
        String yearText = m.group(7);

        int month = 0;
        for (int i = 0; i < MONTHS.length; i++) {
            if (MONTHS[i].equals(monthName)) {
                month = i + 1;
                break;
            }
        }
        if (month == 0) {
            throw fail(text, "unknown month '" + m.group(6) + "' in date-time group");
        }
        long year = Long.parseLong(yearText);
        if (yearText.length() == 2) {
            year += year < 70 ? 2000 : 1900;
        }
        checkRange(text, "day", day, 1, daysInMonth(year, month));
        checkRange(text, "hour", hour, 0, 23);
        checkRange(text, "minute", minute, 0, 59);
        checkRange(text, "second", second, 0, 59);

        int offsetSeconds;
        if (zoneLetter == 'Z') {
            offsetSeconds = 0;
        } else if (zoneLetter == 'J') {
            offsetSeconds = NO_OFFSET;              // "Juliet": local time of the observer
        } else if (zoneLetter >= 'A' && zoneLetter <= 'I') {
            offsetSeconds = (zoneLetter - 'A' + 1) * 3600;
        } else if (zoneLetter >= 'K' && zoneLetter <= 'M') {
            offsetSeconds = (zoneLetter - 'K' + 10) * 3600;
        } else if (zoneLetter >= 'N' && zoneLetter <= 'Y') {
            offsetSeconds = -(zoneLetter - 'N' + 1) * 3600;
        } else {
            throw fail(text, "unknown zone letter '" + zoneLetter + "' in date-time group");
        }
        return resolve(text, year, month, day, hour, minute, second, 0, offsetSeconds, zoneForLocalTimes);
    }

    private static long parseIso(String text, String s, ZoneId zoneForLocalTimes) {
        Cursor c = new Cursor(text, s);

        // ---- date
        long year;
        int month;
        int day;
        char first = c.peek();
        if (first == '+' || first == '-') {
            c.p++;
            int start = c.p;
            c.skipDigits();
            int k = c.p - start;
            if (k < 4 || k > 9) {
                throw fail(text, "a signed year needs 4 to 9 digits");
            }
            year = Long.parseLong(s.substring(start, c.p));
            if (first == '-') {
                year = -year;
            }
            c.expect('-', "'-' after the year");
            month = c.twoDigits("month");
            c.expect('-', "'-' after the month");
            day = c.twoDigits("day");
        } else {
            int start = c.p;
            c.skipDigits();
            int k = c.p - start;
            if (k == 4) {
                year = Long.parseLong(s.substring(start, c.p));
                c.expect('-', "'-' after the year");
                month = c.twoDigits("month");
                c.expect('-', "'-' after the month");
                day = c.twoDigits("day");
            } else if (k == 8) {
                year = Long.parseLong(s.substring(start, start + 4));
                month = Integer.parseInt(s.substring(start + 4, start + 6));
                day = Integer.parseInt(s.substring(start + 6, start + 8));
            } else {
                throw fail(text, "expected a date as yyyy-MM-dd or yyyyMMdd");
            }
        }
        checkRange(text, "month", month, 1, 12);
        checkRange(text, "day", day, 1, daysInMonth(year, month));

        // ---- time
        int hour = 0;
        int minute = 0;
        int second = 0;
        int milli = 0;
        boolean fractionNonZero = false;
        if (!c.atEnd()) {
            char sep = c.peek();
            if (sep == 'T' || sep == 't' || sep == '_') {
                c.p++;
            } else if (sep == ' ') {
                c.skipSpaces();
            } else {
                throw fail(text, "expected 'T' or a space between date and time, found '" + sep + "'");
            }
            hour = c.twoDigits("hour");
            boolean hasSeconds = false;
            if (c.peekIs(':')) {
                c.p++;
                minute = c.twoDigits("minute");
                if (c.peekIs(':')) {
                    c.p++;
                    second = c.twoDigits("second");
                    hasSeconds = true;
                }
            } else if (c.peekDigit()) {
                minute = c.twoDigits("minute");
                if (c.peekDigit()) {
                    second = c.twoDigits("second");
                    hasSeconds = true;
                }
            }
            if (c.peekIs('.') || c.peekIs(',')) {
                if (!hasSeconds) {
                    throw fail(text, "a decimal fraction is only accepted after the seconds");
                }
                c.p++;
                int start = c.p;
                c.skipDigits();
                if (c.p == start) {
                    throw fail(text, "no digits after the decimal sign");
                }
                for (int i = start; i < c.p; i++) {
                    int digit = s.charAt(i) - '0';
                    int pos = i - start;
                    if (pos < 3) {
                        milli = milli * 10 + digit;
                    }
                    fractionNonZero |= digit != 0;
                }
                for (int pos = c.p - start; pos < 3; pos++) {
                    milli *= 10;
                }
            }
            checkRange(text, "hour", hour, 0, 24);
            checkRange(text, "minute", minute, 0, 59);
            checkRange(text, "second", second, 0, 60);
            if (hour == 24 && (minute != 0 || second != 0 || fractionNonZero)) {
                throw fail(text, "hour 24 is only accepted as 24:00:00");
            }
            if (second == 60) {
                if (minute != 59) {
                    throw fail(text, "second 60 (leap second) is only accepted in minute 59");
                }
                second = 59;
            }
        }

        // ---- zone
        int offsetSeconds = NO_OFFSET;
        ZoneId zone = zoneForLocalTimes;
        c.skipSpaces();
        if (!c.atEnd()) {
            char z = c.peek();
            if (z == 'Z' || z == 'z') {
                c.p++;
                offsetSeconds = 0;
            } else if (z == '+' || z == '-') {
                offsetSeconds = parseOffset(c);
            } else if (s.regionMatches(true, c.p, "UTC", 0, 3) || s.regionMatches(true, c.p, "GMT", 0, 3)) {
                c.p += 3;
                offsetSeconds = 0;
            }
            if (c.peekIs('[')) {
                int close = s.indexOf(']', c.p);
                if (close != s.length() - 1) {
                    throw fail(text, "a zone region must be the last part, as [Region/City]");
                }
                String region = s.substring(c.p + 1, close).trim();
                c.p = s.length();
                if (offsetSeconds == NO_OFFSET) {       // with an offset the region does not matter
                    try {
                        zone = ZoneId.of(region);
                    } catch (DateTimeException e) {
                        throw fail(text, "unknown zone '" + region + "'");
                    }
                }
            }
            if (!c.atEnd()) {
                throw fail(text, "unexpected text '" + s.substring(c.p) + "'");
            }
        }
        return resolve(text, year, month, day, hour, minute, second, milli, offsetSeconds, zone);
    }

    private static int parseOffset(Cursor c) {
        boolean negative = c.peek() == '-';
        c.p++;
        int hours = c.twoDigits("offset hours");
        int minutes = 0;
        if (c.peekIs(':')) {
            c.p++;
            minutes = c.twoDigits("offset minutes");
        } else if (c.peekDigit()) {
            minutes = c.twoDigits("offset minutes");
        }
        checkRange(c.text, "offset minutes", minutes, 0, 59);
        int total = hours * 3600 + minutes * 60;
        if (total > MAX_OFFSET_SECONDS) {
            throw fail(c.text, "offset beyond +/-18:00");
        }
        return negative ? -total : total;
    }

    /** Local fields plus an offset (or a zone when the offset is {@link #NO_OFFSET}) to millis. */
    private static long resolve(String text, long year, int month, int day, int hour, int minute,
                                int second, int milli, int offsetSeconds, ZoneId zone) {
        int offset = offsetSeconds;
        if (offset == NO_OFFSET) {
            ZoneId normalized = zone.normalized();
            if (normalized instanceof ZoneOffset) {
                offset = ((ZoneOffset) normalized).getTotalSeconds();
            }
        }
        try {
            if (offset != NO_OFFSET) {
                long days = daysFromCivil(year, month, day);
                long tod = hour * MILLIS_PER_HOUR + minute * MILLIS_PER_MINUTE
                        + second * MILLIS_PER_SECOND + milli - offset * MILLIS_PER_SECOND;
                return combine(days, tod);
            }
            // Region zone: let java.time apply its rules (DST gaps and overlaps).
            boolean endOfDay = hour == 24;
            LocalDateTime local = LocalDateTime.of((int) year, month, day, endOfDay ? 0 : hour, minute,
                                                   second, milli * 1_000_000);
            if (endOfDay) {
                local = local.plusDays(1);
            }
            Instant instant = local.atZone(zone).toInstant();
            return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), MILLIS_PER_SECOND),
                                 instant.getNano() / 1_000_000);
        } catch (ArithmeticException | DateTimeException e) {
            throw fail(text, "outside the epoch-millisecond range");
        }
    }

    /**
     * {@code days * MILLIS_PER_DAY + tod} without a spurious overflow at the ends of the long
     * range: the product is taken one or two days closer to zero and the remainder added back.
     * {@code tod} lies within -18 h .. +42 h.
     */
    private static long combine(long days, long tod) {
        if (days > 0) {
            return Math.addExact(Math.multiplyExact(days - 1, MILLIS_PER_DAY), tod + MILLIS_PER_DAY);
        }
        if (days < 0) {
            return Math.addExact(Math.multiplyExact(days + 2, MILLIS_PER_DAY), tod - 2 * MILLIS_PER_DAY);
        }
        return tod;
    }

    /** H. Hinnant, "days_from_civil": days since 1970-01-01 in the proleptic Gregorian calendar. */
    private static long daysFromCivil(long year, int month, int day) {
        long y = month <= 2 ? year - 1 : year;
        long era = Math.floorDiv(y, 400L);
        long yoe = y - era * 400;
        int mp = month > 2 ? month - 3 : month + 9;
        long doy = (153L * mp + 2) / 5 + day - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146_097L + doe - 719_468L;
    }

    private static boolean isLeapYear(long year) {
        return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
    }

    private static int daysInMonth(long year, int month) {
        switch (month) {
            case 2:
                return isLeapYear(year) ? 29 : 28;
            case 4:
            case 6:
            case 9:
            case 11:
                return 30;
            default:
                return 31;
        }
    }

    private static void checkRange(String text, String what, int value, int min, int max) {
        if (value < min || value > max) {
            throw fail(text, what + " " + value + " out of range " + min + ".." + max);
        }
    }

    private static IllegalArgumentException fail(String text, String reason) {
        String shown = text.length() > 80 ? text.substring(0, 77) + "..." : text;
        return new IllegalArgumentException("Cannot parse time stamp '" + shown + "': " + reason);
    }

    /** Position in the text being parsed. */
    private static final class Cursor {
        final String text;
        final String s;
        int p;

        Cursor(String text, String s) {
            this.text = text;
            this.s = s;
        }

        boolean atEnd() {
            return p >= s.length();
        }

        char peek() {
            return s.charAt(p);
        }

        boolean peekIs(char ch) {
            return p < s.length() && s.charAt(p) == ch;
        }

        boolean peekDigit() {
            return p < s.length() && isDigit(s.charAt(p));
        }

        void skipDigits() {
            while (peekDigit()) {
                p++;
            }
        }

        void skipSpaces() {
            while (peekIs(' ')) {
                p++;
            }
        }

        void expect(char ch, String what) {
            if (!peekIs(ch)) {
                throw fail(text, "expected " + what);
            }
            p++;
        }

        int twoDigits(String what) {
            if (p + 2 > s.length() || !isDigit(s.charAt(p)) || !isDigit(s.charAt(p + 1))) {
                throw fail(text, "expected two digits for the " + what);
            }
            int v = (s.charAt(p) - '0') * 10 + (s.charAt(p + 1) - '0');
            p += 2;
            return v;
        }
    }
}
