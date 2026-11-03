package com.simweaver.common.logging;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Mirrors System.out / System.err to size-rotating log files, one log record
 * per printed line, while still writing everything to the original console.
 *
 * Java 11, no third-party dependencies, Windows-safe rotation.
 *
 * Usage (once, early in main):
 *
 *   ConsoleTee.install(Paths.get(mainLogDir), "bridgeservice", 1024 * 1024, 5);
 *
 * Produces bridgeservice.log / bridgeservice.log.1 .. .4
 *      and bridgeservice.err.log / bridgeservice.err.log.1 .. .4
 */
public final class ConsoleTee {

    private ConsoleTee() {
    }

    private static volatile Runnable closer;

    /** The sink of System.out's log file while installed (0.11.5: {@link #logOnly}). */
    private static volatile LineSink outLog;

    /**
     * @param dir       directory the logs are written to (created if missing)
     * @param baseName  file name stem, e.g. "bridgeservice"
     * @param maxBytes  approximate size at which a file is rotated
     * @param maxFiles  total files kept per stream, including the current one
     */
    public static void install(Path dir, String baseName, long maxBytes, int maxFiles)
            throws IOException {
        install(dir, baseName, maxBytes, maxFiles, true);
    }

    /**
     * As {@link #install(Path, String, long, int)}; with {@code closeOnShutdown=false} the files
     * stay open until {@link #close()} is called, so a shutdown hook of the application can still
     * log its last lines (0.9.22).
     */
    public static void install(Path dir, String baseName, long maxBytes, int maxFiles, boolean closeOnShutdown)
            throws IOException {

        RotatingSink outSink = new RotatingSink(dir.resolve(baseName + ".log"), maxBytes, maxFiles);
        RotatingSink errSink = new RotatingSink(dir.resolve(baseName + ".err.log"), maxBytes, maxFiles);

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        PrintStream teeOut = tee(originalOut, outSink);
        PrintStream teeErr = tee(originalErr, errSink);
        System.setOut(teeOut);
        System.setErr(teeErr);
        outLog = outSink;

        closer = () -> {
            outLog = null;
            close(teeOut);       // flushes any unterminated partial line
            close(teeErr);
            System.setOut(originalOut);
            System.setErr(originalErr);
            close(outSink);
            close(errSink);
        };
        if (closeOnShutdown) {
            Runtime.getRuntime().addShutdownHook(new Thread(ConsoleTee::close, "console-tee-shutdown"));
        }
    }

    /**
     * Writes a line to the log file of System.out only, not to the console (0.11.5): details that
     * belong in the log but would clutter the console, e.g. every map layer read at start-up.
     * Without the tee installed (tests, tools) the line goes to System.out as before.
     */
    public static void logOnly(String line) {
        LineSink sink = outLog;
        if (sink != null) {
            System.out.flush();
            sink.accept(line);
        } else {
            System.out.println(line);
        }
    }

    /** @return true while System.out / System.err are mirrored to log files */
    public static boolean isInstalled() {
        return outLog != null;
    }

    /** Writes the last partial lines and closes the files; the console keeps working. */
    public static synchronized void close() {
        Runnable c = closer;
        closer = null;
        if (c != null) {
            c.run();
        }
    }

    /** Wraps an OutputStream that echoes to {@code original} and emits whole lines to {@code sink}. */
    private static PrintStream tee(PrintStream original, LineSink sink) {
        OutputStream os = new OutputStream() {

            private final ByteArrayOutputStream line = new ByteArrayOutputStream(256);

            @Override
            public synchronized void write(int b) throws IOException {
                original.write(b);
                if (b == '\n') {
                    emit();
                } else if (b != '\r') {          // drop the CR of a CRLF
                    line.write(b);
                }
            }

            @Override
            public synchronized void write(byte[] b, int off, int len) throws IOException {
                for (int i = 0; i < len; i++) {
                    write(b[off + i]);
                }
            }

            @Override
            public synchronized void flush() throws IOException {
                // deliberately does NOT emit a partial line: autoflush fires after
                // every print(), which would split one line into several records
                original.flush();
            }

            @Override
            public synchronized void close() throws IOException {
                emit();
                original.flush();
            }

            private void emit() {
                if (line.size() > 0) {
                    sink.accept(new String(line.toByteArray(), StandardCharsets.UTF_8));
                    line.reset();
                }
            }
        };
        try {
            return new PrintStream(os, true, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 missing", e);   // cannot happen
        }
    }

    private interface LineSink {
        void accept(String line);
    }

    /** Appends timestamped lines to a file, rotating it by size. */
    private static final class RotatingSink implements LineSink, Closeable {

        private static final DateTimeFormatter TS =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

        private final Path base;
        private final long maxBytes;
        private final int maxFiles;

        private OutputStream out;
        private long written;

        RotatingSink(Path base, long maxBytes, int maxFiles) throws IOException {
            this.base = base.toAbsolutePath();
            this.maxBytes = maxBytes;
            this.maxFiles = Math.max(1, maxFiles);
            Path parent = this.base.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            open();
        }

        @Override
        public synchronized void accept(String line) {
            byte[] bytes = (LocalDateTime.now().format(TS) + ' ' + line + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8);
            try {
                if (written > 0 && written + bytes.length > maxBytes) {
                    rotate();
                }
                out.write(bytes);
                out.flush();
                written += bytes.length;
            } catch (IOException e) {
                // Nothing sane to do here: writing to System.err would recurse
                // back into this sink. Drop the line rather than kill the caller.
            }
        }

        private void open() throws IOException {
            out = new BufferedOutputStream(Files.newOutputStream(base,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND));
            written = Files.size(base);
        }

        private void rotate() throws IOException {
            out.close();                                   // Windows: cannot rename an open file
            try {
                if (maxFiles > 1) {
                    Files.deleteIfExists(indexed(maxFiles - 1));
                    for (int i = maxFiles - 2; i >= 1; i--) {
                        Path src = indexed(i);
                        if (Files.exists(src)) {
                            Files.move(src, indexed(i + 1), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    Files.move(base, indexed(1), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(base);
                }
            } finally {
                open();                                    // always get a usable stream back
            }
        }

        private Path indexed(int i) {
            return base.resolveSibling(base.getFileName().toString() + "." + i);
        }

        @Override
        public synchronized void close() throws IOException {
            out.close();
        }
    }

    private static void close(Closeable c) {
        try {
            c.close();
        } catch (Exception ignored) {
            // shutting down anyway
        }
    }

    // --- self-test ---------------------------------------------------------
    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args.length > 0 ? args[0] : "logs");
        install(dir, "bridgeservice", 4096, 3);

        System.out.println("plain line");
        System.out.print("built ");
        System.out.print("from ");
        System.out.println("three prints");
        System.out.printf("formatted %s %d%n", "value", 42);
        System.out.println("åäö unicode + tab\tinside");
        System.err.println("this goes to the err log only");
        new RuntimeException("stack trace test").printStackTrace();
        for (int i = 0; i < 300; i++) {
            System.out.println("filler line " + i + " ------------------------------");
        }
        System.out.print("unterminated tail");   // flushed by the shutdown hook
    }
}
