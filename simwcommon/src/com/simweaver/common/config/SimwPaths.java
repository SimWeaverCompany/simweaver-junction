package com.simweaver.common.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Where the server's configuration folder is (0.11.4): {@code etcdir}, by default {@code etc} in the
 * installation folder. Every default that names a configuration file or folder (datasources,
 * junctions, FOMs, J2J, API tokens, enumerations, list profiles) is taken from here, so moving the
 * configuration out of the installation is one setting. The server sets it once at start
 * ({@link #setEtc}); until then, and in tests, it is {@code etc}.
 */
public final class SimwPaths {

    /** The default: {@code etc}, relative to the installation (working) folder. */
    public static final Path DEFAULT_ETC = Paths.get("etc");

    private static volatile Path etc = DEFAULT_ETC;

    private SimwPaths() {
    }

    /** @return the configuration folder, as set (relative to the installation folder, or absolute) */
    public static Path etc() {
        return etc;
    }

    /** @return a file or folder in the configuration folder, e.g. {@code etc("j2j", "network.key")} */
    public static Path etc(String first, String... more) {
        Path p = etc.resolve(first);
        for (String m : more) {
            p = p.resolve(m);
        }
        return p;
    }

    /** The same as a string, for defaults of settings. */
    public static String etcString(String first, String... more) {
        return etc(first, more).toString();
    }

    private static volatile boolean moved;

    /** Sets the configuration folder (null: back to {@code etc}). */
    public static void setEtc(Path p) {
        setEtc(p, p != null && !p.normalize().equals(DEFAULT_ETC));
    }

    /** Sets the configuration folder, and whether it is another than the installation's {@code etc}. */
    public static void setEtc(Path p, boolean isMoved) {
        etc = p == null ? DEFAULT_ETC : p;
        moved = p != null && isMoved;
    }

    /** @return true when the configuration folder is not the installation's {@code etc} (etcdir) */
    public static boolean isMoved() {
        return moved;
    }
}
