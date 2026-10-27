package com.simweaver.common.logging;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * The console handler of the server (0.9.22): one line per record, {@code LEVEL: text}, plus the
 * event's tags; INFO and below go to {@code System.out}, WARNING and SEVERE to {@code System.err}.
 * {@link ConsoleTee} timestamps the lines and writes them to {@code <name>.log} and
 * {@code <name>.err.log}, so ordinary messages no longer land in the error log.
 *
 * <p>{@code System.out} / {@code System.err} are looked up for every record, so the tee installed
 * before or after this handler is used.
 */
public final class ConsoleLineHandler extends Handler {

    private static final Formatter PLAIN = new Formatter() {
        @Override
        public String format(LogRecord r) {
            return formatMessage(r);
        }
    };

    @Override
    public void publish(LogRecord r) {
        if (r == null || !isLoggable(r)) {
            return;
        }
        String line = line(r);
        PrintStream out = r.getLevel().intValue() >= Level.WARNING.intValue() ? System.err : System.out;
        synchronized (this) {
            out.println(line);
            if (r.getThrown() != null) {
                StringWriter sw = new StringWriter();
                r.getThrown().printStackTrace(new PrintWriter(sw));
                out.print(sw);
            }
        }
    }

    /** {@code LEVEL: text  [event=… k=v]} (also used by tests). */
    static String line(LogRecord r) {
        return r.getLevel().getName() + ": " + text(r);
    }

    /** {@code text  [event=… k=v] (exception)}: the record without its level (syslog uses it too). */
    static String text(LogRecord r) {
        Events.Tag tag = Events.tagOf(r);
        String text = tag != null ? r.getMessage() : PLAIN.formatMessage(r);
        StringBuilder b = new StringBuilder(text == null ? "" : text);
        if (tag != null) {
            b.append("  [").append(tag.text()).append(']');
        }
        if (r.getThrown() != null && (text == null || !text.contains(String.valueOf(r.getThrown().getMessage())))) {
            b.append(" (").append(r.getThrown()).append(')');
        }
        return b.toString();
    }

    @Override
    public void flush() {
        System.out.flush();
        System.err.flush();
    }

    @Override
    public void close() {
        flush();
    }
}
