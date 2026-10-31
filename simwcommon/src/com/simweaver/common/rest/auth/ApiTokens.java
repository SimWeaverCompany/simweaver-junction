package com.simweaver.common.rest.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The API tokens of this server (0.11.0): the file {@code etc/auth/api-tokens.settings}, which
 * holds only the SHA-256 of each token, never the token itself.
 *
 * <pre>
 * token.ops-dashboard.hash=sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
 * token.ops-dashboard.created=2026-10-29T14:12:00Z
 * token.ops-dashboard.expires=2027-06-30          (optional: valid through that day, UTC)
 * token.ops-dashboard.description=Ops wall screen  (optional)
 * </pre>
 *
 * A token is {@code swt_} and 43 characters (32 random octets, base64url without padding). It is
 * made with {@link #addToFile} (the server's {@code --new-token} switch), which shows it once. The
 * file is read again when it changes ({@link #refresh}), so deleting a token's lines revokes it.
 * There is one access level: a valid token may do everything the API offers.
 */
public final class ApiTokens {

    /** The prefix of every token, so a leaked one is easy to recognise. */
    public static final String PREFIX = "swt_";

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern KEY = Pattern.compile("token\\.([^.]+(?:\\.[^.]+)*?)\\.(hash|created|expires|description)");
    private static final Pattern HASH = Pattern.compile("sha256:([0-9a-fA-F]{64})");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long CHECK_INTERVAL_MS = 1000;

    /** One token as the file describes it. */
    public static final class Token {
        private final String name;
        private final byte[] hash;
        private final String created;
        private final LocalDate expires;
        private final String description;

        Token(String name, byte[] hash, String created, LocalDate expires, String description) {
            this.name = name;
            this.hash = hash;
            this.created = created;
            this.expires = expires;
            this.description = description;
        }

        public String name() {
            return name;
        }

        /** @return when it was made, as written in the file, or null */
        public String created() {
            return created;
        }

        /** @return the last day it is valid (UTC), or null for no end */
        public LocalDate expires() {
            return expires;
        }

        public String description() {
            return description;
        }

        /** @return true when it has expired at {@code now} */
        public boolean expiredAt(Instant now) {
            return expires != null && now.isAfter(expires.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1));
        }

        boolean sameHash(byte[] other) {
            return MessageDigest.isEqual(hash, other);
        }
    }

    private final Path file;
    private volatile List<Token> tokens = Collections.emptyList();
    private volatile List<String> problems = Collections.emptyList();
    private volatile long seenModified = Long.MIN_VALUE;
    private volatile long seenSize = -1;
    private volatile long lastCheck;
    private final Map<String, Instant> lastUsed = new ConcurrentHashMap<>();

    /** @param file the token file; it may be missing (then there are no tokens) */
    public ApiTokens(Path file) {
        this.file = file;
        refresh(true);
    }

    public Path file() {
        return file;
    }

    /** @return the tokens as last read */
    public List<Token> tokens() {
        return tokens;
    }

    /** @return what was wrong in the file when it was last read (those lines are ignored) */
    public List<String> problems() {
        return problems;
    }

    /** @return when a token was last accepted since the server started, or null */
    public Instant lastUsed(String name) {
        return lastUsed.get(name);
    }

    /**
     * Reads the file again when it changed (looked at once a second at most, unless forced).
     *
     * @return true when the tokens were read again
     */
    public synchronized boolean refresh(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastCheck < CHECK_INTERVAL_MS) {
            return false;
        }
        lastCheck = now;
        long modified;
        long size;
        try {
            modified = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : -1;
            size = modified == -1 ? -1 : Files.size(file);
        } catch (IOException e) {
            modified = -2;
            size = -2;
        }
        if (!force && modified == seenModified && size == seenSize) {
            return false;
        }
        seenModified = modified;
        seenSize = size;
        List<String> p = new ArrayList<>();
        List<Token> t = new ArrayList<>();
        if (modified >= 0) {
            try {
                parse(Files.readAllLines(file, StandardCharsets.UTF_8), t, p);
            } catch (IOException e) {
                p.add(file + ": cannot be read (" + e.getMessage() + ")");
            }
        }
        tokens = Collections.unmodifiableList(t);
        problems = Collections.unmodifiableList(p);
        return true;
    }

    /**
     * @param presented the token a client sent
     * @return its token when it is one of this server's and has not expired, else null
     */
    public Token match(String presented) {
        if (presented == null || presented.isEmpty() || presented.length() > 200) {
            return null;
        }
        refresh(false);
        byte[] h = sha256(presented);
        Token found = null;
        for (Token t : tokens) {                       // no early exit: the time does not tell which one
            if (t.sameHash(h) && found == null) {
                found = t;
            }
        }
        if (found == null || found.expiredAt(Instant.now())) {
            return null;
        }
        lastUsed.put(found.name, Instant.now());
        return found;
    }

    /** @return the token of that name, when it is still in the file and has not expired, else null */
    public Token current(String name, byte[] hash) {
        refresh(false);
        for (Token t : tokens) {
            if (t.name.equals(name) && t.sameHash(hash)) {
                if (t.expiredAt(Instant.now())) {
                    return null;
                }
                lastUsed.put(name, Instant.now());
                return t;
            }
        }
        return null;
    }

    static byte[] hashOf(Token t) {
        return t.hash.clone();
    }

    // ------------------------------------------------------------------ the file

    static void parse(List<String> lines, List<Token> out, List<String> problems) {
        Map<String, Map<String, String>> byName = new LinkedHashMap<>();
        Map<String, Integer> lineOf = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                problems.add("line " + (i + 1) + ": not key=value");
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            Matcher m = KEY.matcher(key);
            if (!m.matches()) {
                problems.add("line " + (i + 1) + ": " + key + " is not token.<name>.hash, .created, .expires or .description");
                continue;
            }
            String name = m.group(1);
            byName.computeIfAbsent(name, n -> new LinkedHashMap<>()).put(m.group(2), value);
            lineOf.putIfAbsent(name, i + 1);
        }
        List<byte[]> hashes = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> e : byName.entrySet()) {
            String name = e.getKey();
            Map<String, String> v = e.getValue();
            String where = "line " + lineOf.get(name) + ": token " + name;
            if (!NAME.matcher(name).matches()) {
                problems.add(where + ": a name is letters, digits, '.', '_' or '-' (at most 64), first a letter or digit");
                continue;
            }
            String hash = v.get("hash");
            Matcher hm = hash == null ? null : HASH.matcher(hash);
            if (hm == null || !hm.matches()) {
                problems.add(where + ": token." + name + ".hash must be sha256:<64 hex digits>");
                continue;
            }
            LocalDate expires = null;
            String ex = v.get("expires");
            if (ex != null && !ex.isEmpty()) {
                try {
                    expires = LocalDate.parse(ex);
                } catch (DateTimeParseException x) {
                    problems.add(where + ": expires must be a date, e.g. 2027-06-30");
                    continue;
                }
            }
            byte[] h = unhex(hm.group(1));
            boolean dup = false;
            for (byte[] other : hashes) {
                dup |= MessageDigest.isEqual(other, h);
            }
            if (dup) {
                problems.add(where + ": the same hash as another token (ignored)");
                continue;
            }
            hashes.add(h);
            out.add(new Token(name, h, v.get("created"), expires, v.get("description")));
        }
    }

    /**
     * Makes a new token and adds its hash to the file (creating the file and its folder when they
     * are missing). The token itself is returned once and stored nowhere.
     *
     * @param expires     the last valid day, or null
     * @param description a note for people, or null
     * @return the new token
     * @throws IllegalArgumentException when the name is not valid or already in the file
     */
    public static String addToFile(Path file, String name, LocalDate expires, String description) throws IOException {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("a token name is letters, digits, '.', '_' or '-' (at most 64), first a letter or digit: "
                    + (name == null ? "(none)" : "'" + name + "'"));
        }
        List<String> lines = Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : new ArrayList<>();
        List<Token> existing = new ArrayList<>();
        parse(lines, existing, new ArrayList<>());
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("token." + name + ".")) {
                throw new IllegalArgumentException("there is already a token named '" + name + "' in " + file
                        + " (delete its lines to revoke it, or choose another name)");
            }
        }
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        List<String> out = new ArrayList<>(lines);
        if (out.isEmpty()) {
            out.add("# SimWeaver API tokens: only their SHA-256 is kept here, never the token itself.");
            out.add("# Make one with run.sh --new-token=<name> (run.bat on Windows); delete a token's lines to revoke it.");
            out.add("# The server reads this file again when it changes.");
        }
        out.add("");
        out.add("token." + name + ".hash=sha256:" + hex(sha256(token)));
        out.add("token." + name + ".created=" + Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        if (expires != null) {
            out.add("token." + name + ".expires=" + expires);
        }
        if (description != null && !description.trim().isEmpty()) {
            out.add("token." + name + ".description=" + description.trim().replace('\n', ' ').replace('\r', ' '));
        }
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = (dir == null ? file.toAbsolutePath() : dir).resolve(file.getFileName() + ".new");
        Files.write(tmp, out, StandardCharsets.UTF_8);
        ownerOnly(tmp);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return token;
    }

    /** rw for the owner only, where the file system knows POSIX permissions (not on Windows) */
    private static void ownerOnly(Path p) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows: the folder's ACL applies
        }
    }

    // ------------------------------------------------------------------ helpers

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java implementation", e);
        }
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) {
            s.append(String.format(Locale.ROOT, "%02x", x & 0xFF));
        }
        return s.toString();
    }

    static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    /** @return a random session id (32 octets, base64url) */
    static String randomId() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
