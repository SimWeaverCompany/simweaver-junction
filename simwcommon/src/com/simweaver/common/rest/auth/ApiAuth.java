package com.simweaver.common.rest.auth;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.json.Json;
import com.simweaver.common.logging.Events;
import com.simweaver.common.rest.Jsons;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Token authentication for the REST API (0.11.0). Off unless {@code api.auth.enabled=true}.
 *
 * <ul>
 *   <li>{@link #filter()} goes in front of every context under {@code /api} ({@code /api},
 *       {@code /api/events}, {@code /api/maps}): a request needs {@code Authorization: Bearer <token>}
 *       or the session cookie of a signed-in browser, else 401 with {@code WWW-Authenticate: Bearer}
 *       (RFC 6750). With the cookie, a request that changes something (not GET / HEAD) must come
 *       from this server's own pages (the {@code Origin} header), else 403.</li>
 *   <li>{@link #handler()} answers {@code /api/auth}: {@code POST /api/auth/session}
 *       {@code {"token":"swt_…"}} signs a browser in (the login page), {@code DELETE} signs it out,
 *       {@code GET /api/auth/whoami} tells who the caller is (and whether auth is on at all).</li>
 *   <li>An address that sends 10 wrong tokens within a minute gets 429 until the minute is over.
 *       Refusals are logged as {@code api.auth.refused}, once per address and minute with a count.</li>
 *   <li>Sessions live in memory: a restart signs every browser out. A session ends when its token
 *       is revoked or expires, or after {@code api.auth.sessionhours}.</li>
 * </ul>
 *
 * The token of a request is handed on to the API by {@link #current()} ("token &lt;name&gt;"), so the
 * log's events say which token asked.
 */
public final class ApiAuth {

    /** Who the filter let in for the request this thread is handling: {@code token <name>}. */
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    /** The session cookie. */
    public static final String COOKIE = "simweaver_session";

    private static final Logger LOG = Logger.getLogger("com.simweaver.common.rest.auth");
    private static final int MAX_FAILURES_PER_MINUTE = 10;
    private static final long MINUTE_MS = 60_000;
    private static final int MAX_SESSIONS = 10_000;
    private static final int MAX_BODY = 4096;

    private final boolean enabled;
    private final ApiTokens tokens;
    private final long sessionMs;
    private final String route;
    private final Set<String> allowedOrigins;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Refusals> refusals = new ConcurrentHashMap<>();

    private static final class Session {
        final String name;
        final byte[] hash;
        final long expiresAt;

        Session(String name, byte[] hash, long expiresAt) {
            this.name = name;
            this.hash = hash;
            this.expiresAt = expiresAt;
        }
    }

    /** Wrong tokens and refusals of one address, per minute. */
    private static final class Refusals {
        long windowStart;
        int failures;
        long logWindowStart = Long.MIN_VALUE;
        int unlogged;
    }

    /**
     * @param enabled        false: everything passes, as before 0.11.0
     * @param tokens         the token file
     * @param sessionHours   how long a browser stays signed in
     * @param route          the API's route, normally {@code /api}
     * @param allowedOrigins more origins whose pages may change things with the cookie (e.g. a
     *                       reverse proxy's {@code https://rift.example.org}); this server's own is always allowed
     */
    public ApiAuth(boolean enabled, ApiTokens tokens, double sessionHours, String route, List<String> allowedOrigins) {
        this.enabled = enabled;
        this.tokens = tokens;
        this.sessionMs = Math.max(60_000L, (long) (sessionHours * 3_600_000L));
        this.route = route.endsWith("/") ? route.substring(0, route.length() - 1) : route;
        Set<String> o = new LinkedHashSet<>();
        for (String s : allowedOrigins) {
            if (!s.trim().isEmpty()) {
                o.add(trimSlash(s.trim().toLowerCase(Locale.ROOT)));
            }
        }
        this.allowedOrigins = Collections.unmodifiableSet(o);
    }

    /**
     * From the server settings: {@code api.auth.enabled} (false), {@code api.auth.tokens}
     * ({@code etc/auth/api-tokens.settings}), {@code api.auth.sessionhours} (12),
     * {@code api.auth.origins} (none).
     */
    public static ApiAuth fromSettings(SimwProperties cfg, Path runtimeDir, String route) {
        Path file = tokenFile(cfg, runtimeDir);
        List<String> origins = new ArrayList<>();
        for (String s : cfg.getString("api.auth.origins", "").split("[;,]")) {
            origins.add(s);
        }
        return new ApiAuth(cfg.getBoolean("api.auth.enabled", false), new ApiTokens(file),
                cfg.getDouble("api.auth.sessionhours", 12), route, origins);
    }

    /** @return the token file the settings name ({@code api.auth.tokens}) */
    public static Path tokenFile(SimwProperties cfg, Path runtimeDir) {
        Path p = java.nio.file.Paths.get(cfg.getString("api.auth.tokens", com.simweaver.common.config.SimwPaths.etcString("auth", "api-tokens.settings")));
        return p.isAbsolute() ? p : runtimeDir.resolve(p).normalize();
    }

    /**
     * @return who API authentication let in for the request the current thread is handling
     *         ({@code token <name>}), or null (auth off, or not inside a request)
     */
    public static String current() {
        return CURRENT.get();
    }

    /**
     * The server's {@code --new-token} switch: {@code run.sh --new-token=<name> [--expires=2027-06-30]
     * [--description="…"]} (also {@code --new-token <name>}). Adds the token's hash to the token
     * file and prints the token once.
     *
     * @return the exit code (0: made)
     */
    public static int newTokenCommand(SimwProperties cfg, Path runtimeDir, java.io.PrintStream out, java.io.PrintStream err) {
        String name = cfg.getString("new-token", "").trim();
        if (name.isEmpty() || name.equals("true")) {
            name = cfg.getString("arg.0", "").trim();
        }
        if (name.isEmpty()) {
            err.println("--new-token needs a name: run.sh --new-token=<name>   (letters, digits, '.', '_', '-')");
            return 2;
        }
        java.time.LocalDate expires = null;
        String ex = cfg.getString("expires", "").trim();
        if (!ex.isEmpty()) {
            try {
                expires = java.time.LocalDate.parse(ex);
            } catch (java.time.format.DateTimeParseException e) {
                err.println("--expires must be a date, e.g. --expires=2027-06-30 (the last day the token is valid, UTC)");
                return 2;
            }
            if (expires.isBefore(java.time.LocalDate.now(java.time.ZoneOffset.UTC))) {
                err.println("--expires=" + ex + " is in the past");
                return 2;
            }
        }
        Path file = tokenFile(cfg, runtimeDir);
        String token;
        try {
            token = ApiTokens.addToFile(file, name, expires, cfg.getString("description", null));
        } catch (IllegalArgumentException e) {
            err.println("No token made: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("No token made: " + file + " could not be written (" + e.getMessage() + ")");
            return 1;
        }
        out.println();
        out.println("New API token '" + name + "'" + (expires == null ? "" : ", valid through " + expires)
                + " (its hash was added to " + file + "):");
        out.println();
        out.println("    " + token);
        out.println();
        out.println("Copy it now: it is not stored anywhere and cannot be shown again.");
        out.println("Clients send it as  Authorization: Bearer " + token.substring(0, 8) + "...");
        out.println("and the web UI's login page takes it as it is. A running server picks it up at once.");
        out.println("To revoke it, delete its lines (token." + name + ".*) from that file.");
        if (!cfg.getBoolean("api.auth.enabled", false)) {
            out.println();
            out.println("Note: API authentication is off (api.auth.enabled=false), so no token is needed yet.");
            out.println("Set api.auth.enabled=true in etc/server.root.settings and restart to turn it on.");
        }
        out.println();
        return 0;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public ApiTokens tokens() {
        return tokens;
    }

    /** @return one line for the server's start-up banner */
    public String describe() {
        if (!enabled) {
            return "off (api.auth.enabled=false)";
        }
        int n = tokens.tokens().size();
        return "on, " + n + (n == 1 ? " token" : " tokens") + " in " + tokens.file()
                + (n == 0 ? " - nothing gets in: make one with run.sh --new-token=<name>" : "")
                + (tokens.problems().isEmpty() ? "" : " (" + tokens.problems().size() + " problems, see the log)");
    }

    /** Logs that auth is on, and the token file's problems (at start). */
    public void logStart() {
        if (!enabled) {
            return;
        }
        Events.notice(LOG, "api.auth.on", "API authentication is on: " + tokens.tokens().size() + (tokens.tokens().size() == 1 ? " token" : " tokens") + " in " + tokens.file(),
                "tokens", tokens.tokens().size(), "file", tokens.file().toString());
        logProblems();
    }

    private void logProblems() {
        for (String p : tokens.problems()) {
            Events.warning(LOG, "api.auth.tokens.invalid", "API token file " + tokens.file() + ": " + p, "problem", p);
        }
    }

    // ------------------------------------------------------------------ the filter

    /** @return the filter for every context under the API's route */
    public Filter filter() {
        return new Filter() {
            @Override
            public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
                if (!enabled || isAuthPath(exchange.getRequestURI().getPath())) {
                    chain.doFilter(exchange);
                    return;
                }
                String who = admit(exchange);
                if (who == null) {
                    exchange.close();
                    return;
                }
                CURRENT.set(who);              // not an exchange attribute: in Java 11 those are shared by the whole context
                try {
                    chain.doFilter(exchange);
                } finally {
                    CURRENT.remove();
                }
            }

            @Override
            public String description() {
                return "SimWeaver API token authentication";
            }
        };
    }

    private boolean isAuthPath(String path) {
        return path.equals(route + "/auth") || path.startsWith(route + "/auth/");
    }

    /** @return "token <name>" when the request may pass; null when it was answered (refused) */
    private String admit(HttpExchange ex) throws IOException {
        String ip = address(ex);
        String authz = ex.getRequestHeaders().getFirst("Authorization");
        if (authz != null) {
            if (blocked(ip)) {                   // only token checks: a signed-in browser's session is not guessed
                tooMany(ex);
                return null;
            }
            String t = bearer(authz);
            ApiTokens.Token tok = t == null ? null : tokens.match(t);
            if (tok == null) {
                failed(ip);
                refused(ip, t == null ? "not a Bearer token" : "a token this server does not know (or expired)");
                unauthorized(ex, t == null ? "invalid_request" : "invalid_token",
                        t == null ? "Authorization must be: Bearer <token>" : "The token is not valid on this server");
                return null;
            }
            return "token " + tok.name();
        }
        Session s = session(ex);
        if (s != null) {
            if (!safe(ex.getRequestMethod()) && !sameOrigin(ex)) {
                refused(ip, "a change from another site's page (Origin " + ex.getRequestHeaders().getFirst("Origin") + ")");
                send(ex, 403, error("ORIGIN_REFUSED", "Changes with the session cookie are only accepted from this server's own pages"), null);
                return null;
            }
            return "token " + s.name;
        }
        refused(ip, cookie(ex) != null ? "an ended session" : "no token");
        unauthorized(ex, null, "Sign in on the login page, or send Authorization: Bearer <token>");
        return null;
    }

    private Session session(HttpExchange ex) {
        String id = cookie(ex);
        if (id == null) {
            return null;
        }
        Session s = sessions.get(id);
        if (s == null) {
            return null;
        }
        if (System.currentTimeMillis() > s.expiresAt || tokens.current(s.name, s.hash) == null) {
            sessions.remove(id);
            return null;
        }
        return s;
    }

    // ------------------------------------------------------------------ /api/auth

    /** @return the handler of {@code /api/auth} (session and whoami); it is not behind the filter */
    public HttpHandler handler() {
        return ex -> {
            try {
                String path = ex.getRequestURI().getPath();
                String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
                if (path.equals(route + "/auth/whoami") || path.equals(route + "/auth/session") && method.equals("GET")) {
                    if (!method.equals("GET")) {
                        send(ex, 405, error("METHOD_NOT_ALLOWED", "GET only"), null);
                    } else {
                        whoami(ex);
                    }
                } else if (path.equals(route + "/auth/session") && method.equals("POST")) {
                    signIn(ex);
                } else if (path.equals(route + "/auth/session") && method.equals("DELETE")) {
                    signOut(ex);
                } else {
                    send(ex, 404, error("NOT_FOUND", "Not an /api/auth call: GET whoami, POST / DELETE session"), null);
                }
            } finally {
                ex.close();
            }
        };
    }

    private void whoami(HttpExchange ex) throws IOException {
        if (!enabled) {
            send(ex, 200, Json.toJson(Json.object().putObject("auth", a -> a.put("enabled", false))), null);
            return;
        }
        String authz = ex.getRequestHeaders().getFirst("Authorization");
        String name = null;
        String via = null;
        Long expires = null;
        if (authz != null) {
            String ip = address(ex);
            if (blocked(ip)) {
                tooMany(ex);
                return;
            }
            String t = bearer(authz);
            ApiTokens.Token tok = t == null ? null : tokens.match(t);
            if (tok == null) {
                failed(ip);
                refused(ip, "a token this server does not know (or expired)");
                unauthorized(ex, "invalid_token", "The token is not valid on this server");
                return;
            }
            name = tok.name();
            via = "bearer";
        } else {
            Session s = session(ex);
            if (s != null) {
                name = s.name;
                via = "session";
                expires = s.expiresAt;
            }
        }
        if (name == null) {
            unauthorized(ex, null, "Not signed in");
            return;
        }
        final String n = name;
        final String v = via;
        final Long e = expires;
        send(ex, 200, Json.toJson(Json.object().putObject("auth", a -> {
            a.put("enabled", true).put("name", n).put("via", v);
            if (e != null) {
                a.put("expires", Instant.ofEpochMilli(e).toString());
            }
        })), null);
    }

    private void signIn(HttpExchange ex) throws IOException {
        if (!enabled) {
            send(ex, 409, error("AUTH_OFF", "API authentication is off on this server: no sign-in is needed"), null);
            return;
        }
        String ip = address(ex);
        if (blocked(ip)) {
            tooMany(ex);
            return;
        }
        if (!sameOrigin(ex)) {
            refused(ip, "a sign-in from another site's page (Origin " + ex.getRequestHeaders().getFirst("Origin") + ")");
            send(ex, 403, error("ORIGIN_REFUSED", "Sign in on this server's own login page"), null);
            return;
        }
        String token = null;
        try {
            Map<String, Object> body = Jsons.fromJsonObject(readBody(ex.getRequestBody()));
            Object t = body.get("token");
            token = t instanceof String ? ((String) t).trim() : null;
        } catch (IllegalArgumentException | IOException e) {
            send(ex, 400, error("BAD_REQUEST", "Send {\"token\": \"swt_...\"}"), null);
            return;
        }
        if (token == null || token.isEmpty()) {
            send(ex, 400, error("BAD_REQUEST", "Send {\"token\": \"swt_...\"}"), null);
            return;
        }
        ApiTokens.Token tok = tokens.match(token);
        if (tok == null) {
            failed(ip);
            refused(ip, "a sign-in with a token this server does not know (or expired)");
            send(ex, 401, error("TOKEN_INVALID", "That token is not valid on this server"), null);
            return;
        }
        long now = System.currentTimeMillis();
        prune(now);
        String id = ApiTokens.randomId();
        Session s = new Session(tok.name(), ApiTokens.hashOf(tok), now + sessionMs);
        sessions.put(id, s);
        Events.notice(LOG, "api.auth.signedin", "Web UI signed in with token " + tok.name() + " from " + ip,
                "token", tok.name(), "by", ip);
        String cookie = COOKIE + "=" + id + "; Path=" + route + "; Max-Age=" + (sessionMs / 1000)
                + "; HttpOnly; Secure; SameSite=Strict";
        send(ex, 200, Json.toJson(Json.object().putObject("auth", a -> a.put("enabled", true).put("name", s.name)
                .put("via", "session").put("expires", Instant.ofEpochMilli(s.expiresAt).toString()))), cookie);
    }

    private void signOut(HttpExchange ex) throws IOException {
        String id = cookie(ex);
        Session s = id == null ? null : sessions.remove(id);
        if (s != null) {
            Events.notice(LOG, "api.auth.signedout", "Web UI signed out (token " + s.name + ") from " + address(ex),
                    "token", s.name, "by", address(ex));
        }
        send(ex, 204, null, COOKIE + "=; Path=" + route + "; Max-Age=0; HttpOnly; Secure; SameSite=Strict");
    }

    /** Ends every session of a token name (for tests and a future revoke call). */
    void endSessions(String name) {
        sessions.values().removeIf(s -> s.name.equals(name));
    }

    int sessionCount() {
        return sessions.size();
    }

    private void prune(long now) {
        sessions.values().removeIf(s -> now > s.expiresAt);
        if (sessions.size() >= MAX_SESSIONS) {                        // the oldest go first
            List<Map.Entry<String, Session>> all = new ArrayList<>(sessions.entrySet());
            all.sort((a, b) -> Long.compare(a.getValue().expiresAt, b.getValue().expiresAt));
            Iterator<Map.Entry<String, Session>> it = all.iterator();
            while (sessions.size() >= MAX_SESSIONS && it.hasNext()) {
                sessions.remove(it.next().getKey());
            }
        }
    }

    // ------------------------------------------------------------------ refusals

    private boolean blocked(String ip) {
        Refusals r = refusals.get(ip);
        if (r == null) {
            return false;
        }
        synchronized (r) {
            return System.currentTimeMillis() - r.windowStart < MINUTE_MS && r.failures >= MAX_FAILURES_PER_MINUTE;
        }
    }

    private void failed(String ip) {
        Refusals r = refusals.computeIfAbsent(ip, k -> new Refusals());
        synchronized (r) {
            long now = System.currentTimeMillis();
            if (now - r.windowStart >= MINUTE_MS) {
                r.windowStart = now;
                r.failures = 0;
            }
            r.failures++;
        }
    }

    /** Logs a refusal: the first of a minute at once, the others counted into the next line. */
    private void refused(String ip, String why) {
        if (refusals.size() > 10_000) {
            refusals.clear();
        }
        Refusals r = refusals.computeIfAbsent(ip, k -> new Refusals());
        int count;
        synchronized (r) {
            long now = System.currentTimeMillis();
            r.unlogged++;
            if (r.logWindowStart != Long.MIN_VALUE && now - r.logWindowStart < MINUTE_MS) {
                return;
            }
            r.logWindowStart = now;
            count = r.unlogged;
            r.unlogged = 0;
        }
        Events.warning(LOG, "api.auth.refused", "API request refused from " + ip + ": " + why
                        + (count > 1 ? " (" + count + " refusals since the last report)" : ""),
                "address", ip, "reason", why, "count", count, "by", ip);
    }

    // ------------------------------------------------------------------ helpers

    private static boolean safe(String method) {
        String m = method.toUpperCase(Locale.ROOT);
        return m.equals("GET") || m.equals("HEAD") || m.equals("OPTIONS");
    }

    /**
     * @return true when the request comes from this server's own pages: its Origin is
     *         https://&lt;Host&gt; (or one of api.auth.origins); without Origin, Sec-Fetch-Site must
     *         not name another site (requests from programs carry neither)
     */
    boolean sameOrigin(HttpExchange ex) {
        Headers h = ex.getRequestHeaders();
        String origin = h.getFirst("Origin");
        if (origin != null) {
            String o = trimSlash(origin.trim().toLowerCase(Locale.ROOT));
            String host = h.getFirst("Host");
            if (host != null && o.equals("https://" + host.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
            return allowedOrigins.contains(o);
        }
        String site = h.getFirst("Sec-Fetch-Site");
        return site == null || site.equalsIgnoreCase("same-origin") || site.equalsIgnoreCase("none");
    }

    static String bearer(String header) {
        String h = header.trim();
        if (h.length() < 7 || !h.substring(0, 7).equalsIgnoreCase("Bearer ")) {
            return null;
        }
        String t = h.substring(7).trim();
        return t.isEmpty() ? null : t;
    }

    static String cookie(HttpExchange ex) {
        List<String> all = ex.getRequestHeaders().get("Cookie");
        if (all == null) {
            return null;
        }
        for (String header : all) {
            for (String part : header.split(";")) {
                String p = part.trim();
                if (p.startsWith(COOKIE + "=")) {
                    String v = p.substring(COOKIE.length() + 1).trim();
                    return v.isEmpty() ? null : v;
                }
            }
        }
        return null;
    }

    private static String address(HttpExchange ex) {
        InetSocketAddress a = ex.getRemoteAddress();
        return a == null || a.getAddress() == null ? "unknown client" : a.getAddress().getHostAddress();
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String error(String code, String message) {
        return Json.toJson(Json.object().put("success", false).putObject("error", e -> e.put("code", code).put("message", message)));
    }

    private void unauthorized(HttpExchange ex, String error, String message) throws IOException {
        ex.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"SimWeaver\""
                + (error == null ? "" : ", error=\"" + error + "\""));
        send(ex, 401, error("AUTH_REQUIRED", message), null);
    }

    private void tooMany(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Retry-After", "60");
        send(ex, 429, error("TOO_MANY_ATTEMPTS", "Too many wrong tokens from this address: wait a minute"), null);
    }

    private static void send(HttpExchange ex, int status, String json, String setCookie) throws IOException {
        if (setCookie != null) {
            ex.getResponseHeaders().add("Set-Cookie", setCookie);
        }
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        byte[] b = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
        if (b.length > 0) {
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        }
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        }
    }

    private static String readBody(InputStream in) throws IOException {
        try (InputStream i = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = i.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BODY) {
                    throw new IOException("too large");
                }
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
