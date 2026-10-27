package com.simweaver.common.logging;

import com.simweaver.common.rest.RequestContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Lifecycle and audit events (0.9.22): a log record with an event id and key / value pairs, e.g.
 * {@code event=datasource.created id=… federation=Blue by=10.0.0.5}. The local log shows them after
 * the text; syslog uses the event id as the RFC 5424 MSGID too, so a collector can filter on it.
 *
 * <p>Events of normal operation are logged at {@link #NOTICE} (syslog severity 5, between INFO
 * and WARNING); problems at WARNING or SEVERE.
 */
public final class Events {

    /** Normal but significant: syslog severity 5 (notice). */
    public static final Level NOTICE = new Notice();

    private Events() {
    }

    private static final class Notice extends Level {
        private static final long serialVersionUID = 1L;

        Notice() {
            super("NOTICE", 850);       // between INFO (800) and WARNING (900)
        }
    }

    /** The event of a record: its id and fields (in order). */
    public static final class Tag {
        final String event;
        final Map<String, String> fields;

        Tag(String event, Map<String, String> fields) {
            this.event = event;
            this.fields = fields;
        }

        public String event() {
            return event;
        }

        public Map<String, String> fields() {
            return Collections.unmodifiableMap(fields);
        }

        /** @return {@code event=x k=v …}, values with spaces quoted */
        public String text() {
            StringBuilder b = new StringBuilder("event=").append(event);
            for (Map.Entry<String, String> e : fields.entrySet()) {
                b.append(' ').append(e.getKey()).append('=').append(quote(e.getValue()));
            }
            return b.toString();
        }
    }

    /** @return the event tag of a record, or null */
    public static Tag tagOf(LogRecord r) {
        Object[] p = r.getParameters();
        if (p != null) {
            for (Object o : p) {
                if (o instanceof Tag) {
                    return (Tag) o;
                }
            }
        }
        return null;
    }

    /**
     * Logs an event. {@code by} (who asked, from {@link RequestContext#actor()}) is added unless
     * the pairs give one.
     *
     * @param keyValues key, value, key, value … (null values are left out)
     */
    public static void log(Logger logger, Level level, String event, String text, Object... keyValues) {
        if (!logger.isLoggable(level)) {
            return;
        }
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                m.put(String.valueOf(keyValues[i]), String.valueOf(keyValues[i + 1]));
            }
        }
        if (!m.containsKey("by")) {
            m.put("by", RequestContext.actor());
        }
        LogRecord r = new LogRecord(level, text);
        r.setLoggerName(logger.getName());
        r.setParameters(new Object[] {new Tag(event, m)});
        logger.log(r);
    }

    /** Logs an event at {@link #NOTICE}. */
    public static void notice(Logger logger, String event, String text, Object... keyValues) {
        log(logger, NOTICE, event, text, keyValues);
    }

    /** Logs an event at WARNING. */
    public static void warning(Logger logger, String event, String text, Object... keyValues) {
        log(logger, Level.WARNING, event, text, keyValues);
    }

    static String quote(String v) {
        if (v.isEmpty()) {
            return "\"\"";
        }
        boolean plain = true;
        for (int i = 0; i < v.length() && plain; i++) {
            char c = v.charAt(i);
            plain = c > ' ' && c != '"' && c != '=' && c < 0x7F;
        }
        return plain ? v : "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ") + "\"";
    }
}
