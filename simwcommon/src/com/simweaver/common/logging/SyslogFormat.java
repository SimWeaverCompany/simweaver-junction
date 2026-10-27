package com.simweaver.common.logging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Turns a log record into one syslog message (0.9.22), RFC 5424 or RFC 3164, without framing.
 *
 * <pre>
 * RFC 5424  &lt;PRI&gt;1 2026-10-26T14:03:11.250+01:00 HOST simweaver 4711 datasource.created [origin software="SimWeaver" swVersion="0.9.22"] BOM text  [event=… by=…]
 * RFC 3164  &lt;PRI&gt;Oct 26 14:03:11 HOST simweaver[4711]: text  [event=… by=…]
 * </pre>
 *
 * PRI is facility × 8 + severity; SEVERE is 3 (err), WARNING 4, NOTICE 5, INFO 6, CONFIG and
 * below 7 (debug). Line breaks in the text become {@code " | "}; a message longer than the limit
 * is cut at a character boundary.
 */
final class SyslogFormat {

    private static final DateTimeFormatter RFC5424_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    private static final DateTimeFormatter RFC3164_TIME = DateTimeFormatter.ofPattern("MMM ppd HH:mm:ss", Locale.ENGLISH);
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final SyslogSettings settings;
    private final String header5424Tail;     // " HOST APP PROCID "
    private final String origin;             // [origin …] or "-"
    private final String tag3164;            // "app[pid]: "
    private final ZoneId zone;

    SyslogFormat(SyslogSettings settings, String swVersion, ZoneId zone) {
        this.settings = settings;
        this.zone = zone;
        String pid = String.valueOf(ProcessHandle.current().pid());
        this.header5424Tail = " " + settings.hostName() + " " + settings.appName() + " " + pid + " ";
        String ver = swVersion == null || swVersion.trim().isEmpty() ? null : swVersion.trim();
        this.origin = "[origin software=\"SimWeaver\"" + (ver == null ? "" : " swVersion=\"" + sdValue(ver) + "\"") + "]";
        String tag = tagName(settings.appName());
        this.tag3164 = tag + "[" + pid + "]: ";
    }

    /** syslog severity of a JUL level. */
    static int severity(Level level) {
        int v = level.intValue();
        if (v >= Level.SEVERE.intValue()) {
            return 3;
        }
        if (v >= Level.WARNING.intValue()) {
            return 4;
        }
        if (v >= Events.NOTICE.intValue()) {
            return 5;
        }
        if (v >= Level.INFO.intValue()) {
            return 6;
        }
        return 7;
    }

    /** @return the message as UTF-8 bytes, at most {@link SyslogSettings#maxLength()} long */
    byte[] format(LogRecord r) {
        return format(r.getLevel(), Instant.ofEpochMilli(r.getMillis()), Events.tagOf(r), ConsoleLineHandler.text(r));
    }

    byte[] format(Level level, Instant when, Events.Tag tag, String text) {
        int pri = settings.facility() * 8 + severity(level);
        String msg = oneLine(text);
        ZonedDateTime t = when.atZone(zone);
        StringBuilder head = new StringBuilder(160).append('<').append(pri).append('>');
        boolean bom = false;
        if (settings.format() == SyslogSettings.Format.RFC5424) {
            String msgId = tag == null ? "-" : SyslogSettings.printable(tag.event(), 32);
            head.append("1 ").append(RFC5424_TIME.format(t)).append(header5424Tail)
                    .append(msgId.isEmpty() ? "-" : msgId).append(' ').append(origin);
            if (!msg.isEmpty()) {
                head.append(' ');
                bom = settings.bom();
            }
        } else {
            head.append(RFC3164_TIME.format(t)).append(' ').append(settings.hostName()).append(' ').append(tag3164);
        }
        byte[] h = head.toString().getBytes(StandardCharsets.UTF_8);
        byte[] m = msg.getBytes(StandardCharsets.UTF_8);
        int room = settings.maxLength() - h.length - (bom ? BOM.length : 0);
        int mlen = cut(m, Math.max(0, room));
        byte[] out = new byte[h.length + (bom ? BOM.length : 0) + mlen];
        System.arraycopy(h, 0, out, 0, h.length);
        int at = h.length;
        if (bom) {
            System.arraycopy(BOM, 0, out, at, BOM.length);
            at += BOM.length;
        }
        System.arraycopy(m, 0, out, at, mlen);
        return out;
    }

    /** @return how many of {@code b}'s bytes fit in {@code max} without splitting a character */
    static int cut(byte[] b, int max) {
        if (b.length <= max) {
            return b.length;
        }
        int n = max;
        while (n > 0 && (b[n] & 0xC0) == 0x80) {    // b[n] continues the character before it
            n--;
        }
        return n;
    }

    static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace("\r\n", " | ").replace('\r', '\n').replace("\n", " | ").replace('\t', ' ');
        StringBuilder b = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            b.append(c < 0x20 || c == 0x7F ? ' ' : c);
        }
        String r = b.toString();
        while (r.endsWith(" | ")) {
            r = r.substring(0, r.length() - 3);
        }
        return r.trim();
    }

    /** RFC 5424 PARAM-VALUE: {@code "}, {@code \} and {@code ]} escaped. */
    static String sdValue(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("]", "\\]");
    }

    /** RFC 3164 TAG: letters and digits (and {@code -_.}), at most 32. */
    static String tagName(String app) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < app.length() && b.length() < 32; i++) {
            char c = app.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.') {
                b.append(c);
            }
        }
        return b.length() == 0 ? "simweaver" : b.toString();
    }
}
