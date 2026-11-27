package com.simweaver.common.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 0.14.5: what the server does with its site folder at start. Making a new configuration folder from the
 * program's templates ({@link #seed}), the lock that keeps a second server off the same site ({@link #lock}),
 * and the list of site files that hide delivered ones ({@link #shadowed}).
 */
public final class SiteFolders {

    /** The program folder's templates for a new configuration folder. */
    public static final String TEMPLATES = "etc.dist";

    /** The lock file in the site root. */
    public static final String LOCK_FILE = ".lock";

    private static FileChannel lockChannel;
    private static FileLock lock;

    private SiteFolders() {
    }

    /**
     * Makes {@code etc} from {@code <program>/etc.dist} when {@code etc} does not exist yet: every file and
     * folder, recursively. An existing {@code etc} is never touched, not even for a missing file, so a sample
     * deleted on purpose stays deleted.
     *
     * @return the number of files copied; 0 when {@code etc} existed; -1 when it did not and the program
     *         folder has no templates (then only the folder is made)
     */
    public static int seed(Path program, Path etc) throws IOException {
        if (Files.exists(etc)) {
            return 0;
        }
        Path src = program.resolve(TEMPLATES);
        if (!Files.isDirectory(src)) {
            Files.createDirectories(etc);
            return -1;
        }
        Path tmp = etc.resolveSibling(etc.getFileName() + ".new-" + ProcessHandle.current().pid());
        int[] n = {0};
        Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(tmp.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, tmp.resolve(src.relativize(file).toString()));
                n[0]++;
                return FileVisitResult.CONTINUE;
            }
        });
        try {
            move(tmp, etc);                             // all or nothing: a half-made etc is never left behind
        } catch (FileAlreadyExistsException e) {
            deleteTree(tmp);                            // another made it meanwhile: theirs stays
            return 0;
        } catch (IOException e) {
            // The rename stayed refused. On Windows a sync client (Dropbox, OneDrive), a virus scanner or the
            // search indexer may keep the new folder open for a while: copy into etc instead.
            try {
                Files.createDirectory(etc);             // fails when another made it meanwhile
            } catch (FileAlreadyExistsException e2) {
                deleteTree(tmp);
                return 0;
            }
            try {
                copyTree(tmp, etc);
            } catch (IOException e2) {
                deleteTree(etc);                        // ours, half made
                deleteTree(tmp);
                e2.addSuppressed(e);
                throw e2;
            }
            deleteTree(tmp);
        }
        return n[0];
    }

    /**
     * Files.move, tried again for up to about 10 s while it is refused (not when the target exists). On Windows a
     * sync client, a virus scanner or the search indexer may hold a file or folder that was just written open for
     * a moment, and a rename of it is then refused with "access denied".
     */
    public static void move(Path from, Path to, CopyOption... options) throws IOException {
        IOException last = null;
        for (int i = 0; i < 40; i++) {
            try {
                Files.move(from, to, options);
                return;
            } catch (FileAlreadyExistsException | AtomicMoveNotSupportedException | java.nio.file.NoSuchFileException e) {
                throw e;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last;
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file).toString()));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Takes the site's lock ({@code <root>/.lock}, held until the process ends) and writes this process's id
     * into it.
     *
     * @return null when taken; else why not (another server holds it, naming its process id when known)
     */
    public static synchronized String lock(Path root) {
        if (lock != null) {
            return null;
        }
        Path f = root.resolve(LOCK_FILE);
        try {
            Files.createDirectories(root);
            FileChannel ch = FileChannel.open(f, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileLock l;
            try {
                l = ch.tryLock();
            } catch (OverlappingFileLockException e) {
                l = null;
            }
            if (l == null) {
                String pid = "";
                try {
                    ByteBuffer b = ByteBuffer.allocate(64);
                    ch.read(b, 0);
                    pid = new String(b.array(), 0, b.position(), StandardCharsets.US_ASCII).trim();
                } catch (IOException ignored) {
                    // the other process's id is only a courtesy
                }
                ch.close();
                return "the site " + root + " is in use by another server" + (pid.matches("\\d+") ? " (pid " + pid + ")" : "");
            }
            ch.truncate(0);
            ch.write(ByteBuffer.wrap((ProcessHandle.current().pid() + "\n").getBytes(StandardCharsets.US_ASCII)), 0);
            ch.force(false);
            lockChannel = ch;
            lock = l;
            return null;
        } catch (IOException e) {
            return "the site " + root + " cannot be locked (" + f + ": " + e.getMessage() + ")";
        }
    }

    /** Lets go of the lock (tests; the server keeps it until it ends). */
    public static synchronized void unlock() {
        try {
            if (lock != null) {
                lock.release();
            }
            if (lockChannel != null) {
                lockChannel.close();
            }
        } catch (IOException ignored) {
            // nothing to do
        }
        lock = null;
        lockChannel = null;
    }

    /** A site file with the same relative path as a delivered one, which it hides. */
    public static final class Shadow {
        public final Path site;
        public final Path delivered;
        public final boolean identical;

        Shadow(Path site, Path delivered, boolean identical) {
            this.site = site;
            this.delivered = delivered;
            this.identical = identical;
        }

        @Override
        public String toString() {
            return site + (identical ? " is the same as the delivered " + delivered + ": delete it to get updates"
                    : " differs from the delivered " + delivered + " and is used");
        }
    }

    /**
     * Site files that hide delivered ones: every file under {@code siteDir} that also exists under
     * {@code deliveredDir} with the same relative path. Nothing when the two folders are one.
     */
    public static List<Shadow> shadowed(Path siteDir, Path deliveredDir) {
        List<Shadow> out = new ArrayList<>();
        if (!Files.isDirectory(siteDir) || !Files.isDirectory(deliveredDir)) {
            return out;
        }
        try {
            if (Files.isSameFile(siteDir, deliveredDir)) {
                return out;
            }
            Files.walkFileTree(siteDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path d = deliveredDir.resolve(siteDir.relativize(file).toString());
                    if (Files.isRegularFile(d)) {
                        out.add(new Shadow(file, d, sameContent(file, d)));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // a folder that cannot be read hides nothing we can tell about
        }
        out.sort((a, b) -> a.site.compareTo(b.site));
        return out;
    }

    static boolean sameContent(Path a, Path b) {
        try {
            if (Files.size(a) != Files.size(b)) {
                return false;
            }
            try (InputStream x = Files.newInputStream(a); InputStream y = Files.newInputStream(b)) {
                byte[] bx = new byte[65536];
                byte[] by = new byte[65536];
                while (true) {
                    int nx = x.readNBytes(bx, 0, bx.length);
                    int ny = y.readNBytes(by, 0, by.length);
                    if (nx != ny || !Arrays.equals(bx, 0, nx, by, 0, ny)) {
                        return false;
                    }
                    if (nx == 0) {
                        return true;
                    }
                }
            }
        } catch (IOException e) {
            return false;
        }
    }

    static void deleteTree(Path p) {
        for (int i = 0; i < 20 && Files.exists(p); i++) {       // a sync client may hold a file for a moment
            deleteTreeOnce(p);
            if (Files.exists(p)) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static void deleteTreeOnce(Path p) {
        try {
            if (!Files.exists(p)) {
                return;
            }
            Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
