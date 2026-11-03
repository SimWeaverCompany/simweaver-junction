package com.simweaver.common.hla;

import com.simweaver.common.logging.ConsoleTee;

import java.util.logging.Logger;

/**
 * The connection state of one federation probe, reported without repeating itself (0.11.5).
 *
 * <p>Before, every failed connect printed two lines on the console, and a probe tried to connect
 * whenever a configuration was read: at start-up (twice) and on every listing of the
 * configurations. Now the first failure is one WARNING, later failures go to the log file only
 * (at most one line per ten minutes, or when the message changes), and a connect after failures
 * is one INFO line. A failed probe is not tried again within {@link #RETRY_MS}, so a burst of
 * requests does not wait for the RTI's connect time-out over and over.
 */
public final class ProbeStatus {

    private static final Logger LOG = Logger.getLogger(FederationProbe.class.getName());

    /** A failed probe is not tried again sooner than this. */
    public static final long RETRY_MS = 3000;
    /** Repeated failures: at most one line in the log file per this period. */
    static final long REPEAT_LOG_MS = 10 * 60 * 1000L;

    private final String what;
    private boolean failing;
    private int failures;
    private long lastAttempt;
    private long lastLogged;
    private String lastMessage = "";

    /**
     * @param standard e.g. "IEEE 1516-2010"
     * @param lsdLabel "LSD" or "RTI configuration"
     * @param lsd      the value, "" for the RTI's default
     */
    public ProbeStatus(String standard, String lsdLabel, String lsd) {
        String l = lsd == null || lsd.trim().isEmpty() ? "the RTI's default " + lsdLabel.replaceFirst("^RTI ", "") : lsdLabel + " '" + lsd.trim() + "'";
        this.what = "Federation probe (" + standard + ", " + l + ")";
    }

    /** @return false while a failed probe should wait before it tries again; true records an attempt */
    public synchronized boolean mayTry() {
        long now = System.currentTimeMillis();
        if (failing && now - lastAttempt < RETRY_MS) {
            return false;
        }
        lastAttempt = now;
        return true;
    }

    /** A connect failed. */
    public synchronized void failed(Throwable e) {
        long now = System.currentTimeMillis();
        String m = message(e);
        failures++;
        if (!failing) {
            failing = true;
            LOG.warning(what + ": cannot connect to the RTI (" + m + "); no federations are listed until it can. "
                    + "Repeats go to the log file only");
            lastLogged = now;
        } else if (!m.equals(lastMessage) || now - lastLogged >= REPEAT_LOG_MS) {
            ConsoleTee.logOnly("INFO: " + what + ": still cannot connect to the RTI (" + m + "), "
                    + failures + " attempts");
            lastLogged = now;
        }
        lastMessage = m;
    }

    /** A connect succeeded. */
    public synchronized void connected() {
        if (failing) {
            LOG.info(what + ": connected to the RTI (after " + failures
                    + (failures == 1 ? " failed attempt)" : " failed attempts)"));
        } else {
            LOG.fine(what + ": connected to the RTI");
        }
        failing = false;
        failures = 0;
        lastMessage = "";
    }

    /** @return true after a failed connect, until one succeeds */
    public synchronized boolean isFailing() {
        return failing;
    }

    /** @return the number of failed connects in a row */
    public synchronized int failures() {
        return failures;
    }

    @Override
    public String toString() {
        return what;
    }

    static String message(Throwable e) {
        if (e == null) {
            return "unknown error";
        }
        String m = e.getMessage();
        String n = e.getClass().getSimpleName();
        return m == null || m.trim().isEmpty() ? n : n + ": " + m.trim();
    }
}
