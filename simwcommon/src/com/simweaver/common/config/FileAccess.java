package com.simweaver.common.config;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Why a configured file cannot be used (0.14.0). {@code Files.exists} is false both for a missing
 * file and for one the process may not look at (a folder on the way without search permission), so a
 * plain "not found" sent people looking for a file that was there.
 */
public final class FileAccess {

    private FileAccess() {
    }

    /**
     * @param path a file the caller could not find
     * @return a short reason with the absolute path, e.g. {@code file not found: /x/y.xml} or
     *         {@code not accessible to user simwusr (permission denied: the folder /x lacks search
     *         permission, x): /x/y.xml}
     */
    public static String whyMissing(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        try {
            Files.readAttributes(abs, BasicFileAttributes.class);
            return "not a readable file: " + abs;                      // it is there after all
        } catch (NoSuchFileException e) {
            Path missing = firstMissingFolder(abs);
            return "file not found: " + abs + (missing == null ? "" : " (no folder " + missing + ")");
        } catch (AccessDeniedException e) {
            Path blocker = firstUnsearchableFolder(abs);
            return "not accessible to user " + System.getProperty("user.name", "?") + " (permission denied"
                    + (blocker == null ? "" : ": the folder " + blocker + " lacks search permission, x") + "): " + abs;
        } catch (IOException | SecurityException e) {
            return "cannot be checked (" + e + "): " + abs;
        }
    }

    /** @return the outermost folder of the path that does not exist, or null when all do */
    private static Path firstMissingFolder(Path abs) {
        Path p = abs.getRoot();
        if (p == null) {
            return null;
        }
        for (int i = 0; i < abs.getNameCount() - 1; i++) {
            p = p.resolve(abs.getName(i));
            if (!Files.isDirectory(p)) {
                return Files.exists(p) ? null : p;
            }
        }
        return null;
    }

    /** @return the outermost folder of the path this process cannot search, or null */
    private static Path firstUnsearchableFolder(Path abs) {
        Path p = abs.getRoot();
        if (p == null) {
            return null;
        }
        for (int i = 0; i < abs.getNameCount() - 1; i++) {
            p = p.resolve(abs.getName(i));
            if (!Files.isDirectory(p)) {
                return null;
            }
            if (!Files.isExecutable(p)) {
                return p;
            }
        }
        return null;
    }
}
