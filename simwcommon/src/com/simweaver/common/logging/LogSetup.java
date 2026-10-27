package com.simweaver.common.logging;

import com.simweaver.common.config.SimwProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Sets up java.util.logging for the server (0.9.22):
 * <ul>
 * <li>the JDK's console handler (everything to stderr, two lines per record) is replaced by
 *     {@link ConsoleLineHandler} (one line; INFO to stdout, WARNING and SEVERE to stderr), so
 *     {@code <name>.err.log} holds only problems;</li>
 * <li>with {@code syslog.enabled=true} a {@link SyslogHandler} sends the log to a syslog server
 *     too ({@link SyslogSettings});</li>
 * <li>the handlers stay open until {@link #shutdown()} (see {@link SimwLogManager}).</li>
 * </ul>
 *
 * <pre>
 * public static void main(String[] args) {
 *     LogSetup.prepare();                                // first: before anything logs
 *     … read settings, ConsoleTee.install(…, false) …
 *     for (String line : LogSetup.install(cfg, Version.getVersion())) System.out.println("Syslog:  " + line);
 *     …
 *     // last in the shutdown hook:
 *     LogSetup.shutdown(); ConsoleTee.close();
 * }
 * </pre>
 */
public final class LogSetup {

    private static volatile SyslogHandler syslog;
    private static volatile ConsoleLineHandler console;

    private LogSetup() {
    }

    /** Picks {@link SimwLogManager} unless {@code java.util.logging.manager} is set; call first in main. */
    public static void prepare() {
        if (System.getProperty("java.util.logging.manager") == null) {
            System.setProperty("java.util.logging.manager", SimwLogManager.class.getName());
        }
    }

    /**
     * Installs the console handler and, if configured, the syslog handler on the root logger.
     *
     * @param cfg       the server settings ({@code syslog.*})
     * @param swVersion the version for RFC 5424's {@code [origin swVersion=…]}
     * @return lines for the start-up banner: what syslog does, or what is wrong with its settings
     */
    public static synchronized List<String> install(SimwProperties cfg, String swVersion) {
        Logger root = Logger.getLogger("");
        for (Handler h : root.getHandlers()) {
            if (h instanceof ConsoleHandler || h instanceof ConsoleLineHandler || h instanceof SyslogHandler) {
                root.removeHandler(h);
                if (h instanceof SyslogHandler) {
                    h.close();
                }
            }
        }
        Level rootLevel = root.getLevel() == null ? Level.INFO : root.getLevel();
        console = new ConsoleLineHandler();
        console.setLevel(rootLevel);                 // the console shows what it showed before
        root.addHandler(console);
        if (LogManager.getLogManager() instanceof SimwLogManager) {
            SimwLogManager.hold(true);
        }

        List<String> lines = new ArrayList<String>();
        SyslogSettings s = new SyslogSettings(cfg);
        syslog = null;
        if (s.isEnabled()) {
            SyslogHandler h = new SyslogHandler(s, swVersion);
            root.addHandler(h);
            if (s.level().intValue() < rootLevel.intValue()) {
                root.setLevel(s.level());            // e.g. syslog.level=FINE: the console stays at INFO
            }
            syslog = h;
            lines.add(s.describe());
        } else if (!s.problems().isEmpty()) {
            for (String p : s.problems()) {
                lines.add("WARNING: " + p + "; syslog is off");
            }
        } else {
            lines.add("off (syslog.enabled=false)");
        }
        return lines;
    }

    /** @return the syslog state for /api/serverinfo: {"enabled":false} or {@link SyslogHandler#status()} */
    public static Map<String, Object> syslogStatus() {
        SyslogHandler h = syslog;
        if (h == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("enabled", Boolean.FALSE);
            return Collections.unmodifiableMap(m);
        }
        return h.status();
    }

    /** @return the syslog handler, or null when syslog is off */
    public static SyslogHandler syslog() {
        return syslog;
    }

    /** Sends what syslog still has queued (at most 3 s) and closes the handlers; call last. */
    public static synchronized void shutdown() {
        SimwLogManager.hold(false);
        Logger root = Logger.getLogger("");
        SyslogHandler h = syslog;
        if (h != null) {
            root.removeHandler(h);
            h.close();
            syslog = null;
        }
        if (console != null) {
            console.flush();
        }
    }
}
