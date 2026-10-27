package com.simweaver.common.logging;

import java.util.logging.LogManager;

/**
 * The LogManager of the server (0.9.22). It differs from the JDK's in one thing: while the server
 * owns the log ({@link LogSetup#install}), the JDK's shutdown clean-up does not close the
 * handlers, so what the server logs while it stops (gateways stopped, server stopped) still
 * reaches the log files and the syslog server. {@link LogSetup#shutdown()} closes them at the end.
 *
 * <p>Picked with {@code -Djava.util.logging.manager=com.simweaver.common.logging.SimwLogManager},
 * which {@link LogSetup#prepare()} sets when nothing else is set. Without it everything works as
 * before, except that the last lines of a shutdown may be missing.
 */
public final class SimwLogManager extends LogManager {

    private static volatile boolean held;

    public SimwLogManager() {
        super();
    }

    static void hold(boolean on) {
        held = on;
    }

    @Override
    public void reset() {
        if (!held) {
            super.reset();
        }
    }
}
