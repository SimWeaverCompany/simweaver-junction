package com.simweaver.common.rest.auth;

import com.simweaver.common.config.SimwArgs;
import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.logging.Events;
import com.simweaver.common.rest.ApiCallHandler;
import com.simweaver.common.rest.RequestContext;
import com.simweaver.common.rest.XrRequestHandler;
import com.simweaver.common.rest.XrResultPair;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * API token authentication (0.11.0): the token file, --new-token, and the filter and /api/auth on a
 * plain HTTP server (the filter does not care about TLS): Bearer, sign-in, the session cookie,
 * Origin checks, revoking by editing the file, too many wrong tokens, signing out, the log.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.common.rest.auth.ApiAuthSelfTest</pre>
 */
public final class ApiAuthSelfTest {

    private static int checks;
    private static int failures;
    private static final Logger AUTH_LOG = Logger.getLogger("com.simweaver.common.rest.auth");   // held: a collected logger loses its handler
    private static final List<LogRecord> EVENTS = Collections.synchronizedList(new ArrayList<>());

    private ApiAuthSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        AUTH_LOG.addHandler(new Handler() {
            @Override public void publish(LogRecord r) { EVENTS.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        Path dir = Files.createTempDirectory("simw-auth");
        tokenFile(dir);
        command(dir);
        off(dir);
        on(dir);
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ the file

    static void tokenFile(Path dir) throws Exception {
        List<ApiTokens.Token> t = new ArrayList<>();
        List<String> p = new ArrayList<>();
        String h = "sha256:" + ApiTokens.hex(ApiTokens.sha256("swt_a"));
        ApiTokens.parse(java.util.Arrays.asList(
                "# comment", "",
                "token.ops.v2.hash=" + h, "token.ops.v2.expires=2027-06-30", "token.ops.v2.description=Wall screen",
                "token.bad.hash=sha256:1234",
                "token.-x.hash=" + "sha256:" + ApiTokens.hex(ApiTokens.sha256("swt_b")),
                "token.when.hash=" + "sha256:" + ApiTokens.hex(ApiTokens.sha256("swt_c")), "token.when.expires=30/06/2027",
                "token.twin.hash=" + h,
                "token.ops.v2.colour=blue",
                "no equals sign"), t, p);
        check("one good token (a dotted name, expiry, description)", t.size() == 1 && t.get(0).name().equals("ops.v2")
                && t.get(0).expires().equals(LocalDate.of(2027, 6, 30)) && "Wall screen".equals(t.get(0).description()));
        check("problems: bad hash, bad name, bad date, same hash, unknown key, not key=value: " + p, p.size() == 6);

        Path f = dir.resolve("etc/auth/api-tokens.settings");
        String tok = ApiTokens.addToFile(f, "cli", null, "the test");
        check("a token is swt_ + 43 base64url characters: " + tok, tok.matches("swt_[A-Za-z0-9_-]{43}"));
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        check("the file holds the hash, never the token", lines.contains("token.cli.hash=sha256:" + ApiTokens.hex(ApiTokens.sha256(tok)))
                && lines.stream().noneMatch(l -> l.contains(tok)) && lines.get(0).startsWith("#"));
        try {
            check("only the owner may read it", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)).equals("rw-------"));
        } catch (UnsupportedOperationException e) {
            check("(no POSIX permissions here)", true);
        }
        check("the same name again is refused", throwsIae(() -> ApiTokens.addToFile(f, "cli", null, null)));
        check("a bad name is refused", throwsIae(() -> ApiTokens.addToFile(f, "a b", null, null)) && throwsIae(() -> ApiTokens.addToFile(f, ".x", null, null)));
        ApiTokens tokens = new ApiTokens(f);
        check("it matches", tokens.match(tok) != null && tokens.match(tok).name().equals("cli") && tokens.lastUsed("cli") != null);
        check("others do not", tokens.match("swt_" + tok.substring(5)) == null && tokens.match("") == null && tokens.match(null) == null);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String last = ApiTokens.addToFile(f, "today", today, null);
        String old = ApiTokens.addToFile(f, "yesterday", today.plusDays(2), null);
        List<String> l2 = new ArrayList<>(Files.readAllLines(f, StandardCharsets.UTF_8));
        l2.replaceAll(s -> s.startsWith("token.yesterday.expires=") ? "token.yesterday.expires=" + today.minusDays(1) : s);
        Files.write(f, l2, StandardCharsets.UTF_8);
        tokens.refresh(true);
        check("valid through its expiry day, not after", tokens.match(last) != null && tokens.match(old) == null);
    }

    static void command(Path dir) throws Exception {
        Path runtime = dir.resolve("cmd");
        Files.createDirectories(runtime);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = ApiAuth.newTokenCommand(args("--new-token=wall", "--expires=2099-01-31", "--description=Ops wall"), runtime,
                new PrintStream(out, true, "UTF-8"), new PrintStream(err, true, "UTF-8"));
        String o = out.toString("UTF-8");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("swt_[A-Za-z0-9_-]{43}").matcher(o);
        check("--new-token=wall prints the token once and says auth is off", code == 0 && m.find() && o.contains("api.auth.enabled=false")
                && o.contains("valid through 2099-01-31"));
        ApiTokens t = new ApiTokens(runtime.resolve("etc/auth/api-tokens.settings"));
        check("...and its hash is in etc/auth/api-tokens.settings", t.match(m.group()) != null && t.match(m.group()).name().equals("wall"));
        code = ApiAuth.newTokenCommand(args("--new-token", "desk"), runtime, new PrintStream(out), new PrintStream(err));
        check("--new-token desk (name as the next word) works too", code == 0 && t.refresh(true) && t.tokens().size() == 2);
        check("no name, a past or bad date, a used name: exit 2", ApiAuth.newTokenCommand(args("--new-token"), runtime, nul(), nul()) == 2
                && ApiAuth.newTokenCommand(args("--new-token=x", "--expires=2001-01-01"), runtime, nul(), nul()) == 2
                && ApiAuth.newTokenCommand(args("--new-token=x", "--expires=tomorrow"), runtime, nul(), nul()) == 2
                && ApiAuth.newTokenCommand(args("--new-token=wall"), runtime, nul(), nul()) == 2);
        SimwProperties other = args("--new-token=elsewhere", "api.auth.tokens=" + dir.resolve("other.settings"));
        check("api.auth.tokens names the file", ApiAuth.newTokenCommand(other, runtime, nul(), nul()) == 0 && Files.exists(dir.resolve("other.settings")));
    }

    // ------------------------------------------------------------------ the server

    static void off(Path dir) throws Exception {
        ApiAuth auth = new ApiAuth(false, new ApiTokens(dir.resolve("none.settings")), 12, "/api", Collections.emptyList());
        Server s = new Server(auth);
        try {
            check("off: everything passes", s.get("/api/thing", null).statusCode() == 200 && s.get("/api/events", null).statusCode() == 200);
            HttpResponse<String> w = s.get("/api/auth/whoami", null);
            check("off: whoami says so", w.statusCode() == 200 && w.body().equals("{\"auth\":{\"enabled\":false}}"));
            check("off: sign-in is 409", s.post("/api/auth/session", "{\"token\":\"x\"}", null, s.origin()).statusCode() == 409);
            check("off: the banner line", auth.describe().startsWith("off"));
        } finally {
            s.stop();
        }
    }

    static void on(Path dir) throws Exception {
        Path f = dir.resolve("server/api-tokens.settings");
        String cli = ApiTokens.addToFile(f, "cli", null, null);
        String ui = ApiTokens.addToFile(f, "ui", null, null);
        ApiAuth auth = new ApiAuth(true, new ApiTokens(f), 12, "/api", Collections.singletonList("https://rift.example.org/"));
        check("the banner line counts the tokens", auth.describe().startsWith("on, 2 tokens"));
        Server s = new Server(auth);
        try {
            HttpResponse<String> r = s.get("/api/thing", null);
            check("no token: 401, WWW-Authenticate: Bearer", r.statusCode() == 401
                    && r.headers().firstValue("WWW-Authenticate").orElse("").equals("Bearer realm=\"SimWeaver\"")
                    && r.body().contains("AUTH_REQUIRED"));
            r = s.get("/api/thing", "Bearer swt_nope");
            check("a wrong token: 401 invalid_token", r.statusCode() == 401 && r.headers().firstValue("WWW-Authenticate").orElse("").contains("invalid_token"));
            r = s.get("/api/thing", "Basic dXNlcjpwYXNz");
            check("not Bearer: 401 invalid_request", r.statusCode() == 401 && r.headers().firstValue("WWW-Authenticate").orElse("").contains("invalid_request"));
            r = s.get("/api/thing", "Bearer " + cli);
            check("the token: 200, and the API knows which token asked: " + r.body(), r.statusCode() == 200 && r.body().contains("(token cli)"));
            check("lower-case bearer is fine", s.get("/api/thing", "bearer " + cli).statusCode() == 200);
            check("events and maps are behind it too", s.get("/api/events", null).statusCode() == 401 && s.get("/api/maps/x", null).statusCode() == 401
                    && s.get("/api/events", "Bearer " + cli).statusCode() == 200 && s.get("/api/maps/x", "Bearer " + cli).statusCode() == 200);
            check("the pages are not", s.get("/index.html", null).statusCode() == 200);
            check("whoami with the token", s.get("/api/auth/whoami", "Bearer " + cli).body().equals("{\"auth\":{\"enabled\":true,\"name\":\"cli\",\"via\":\"bearer\"}}"));
            check("whoami without: 401", s.get("/api/auth/whoami", null).statusCode() == 401);

            // the browser
            check("sign-in with a wrong token: 401", s.post("/api/auth/session", "{\"token\":\"swt_x\"}", null, s.origin()).statusCode() == 401);
            check("sign-in from another site's page: 403", s.post("/api/auth/session", "{\"token\":\"" + ui + "\"}", null, "https://evil.example").statusCode() == 403);
            check("sign-in without a token field: 400", s.post("/api/auth/session", "{\"tok\":1}", null, s.origin()).statusCode() == 400
                    && s.post("/api/auth/session", "not json", null, s.origin()).statusCode() == 400);
            int beforeIn = count("api.auth.signedin");
            r = s.post("/api/auth/session", "{\"token\":\"" + ui + "\"}", null, s.origin());
            String setCookie = r.headers().firstValue("Set-Cookie").orElse("");
            check("sign-in: 200 and a session cookie: " + setCookie, r.statusCode() == 200
                    && setCookie.matches(ApiAuth.COOKIE + "=[A-Za-z0-9_-]{43}; Path=/api; Max-Age=43200; HttpOnly; Secure; SameSite=Strict")
                    && r.body().contains("\"name\":\"ui\"") && count("api.auth.signedin") == beforeIn + 1);
            String cookie = setCookie.substring(0, setCookie.indexOf(';'));
            check("the cookie lets GETs in, as token ui", s.get("/api/thing", null, cookie).body().contains("(token ui)")
                    && s.get("/api/events", null, cookie).statusCode() == 200 && s.get("/api/maps/t.png", null, cookie).statusCode() == 200);
            check("whoami by session", s.get("/api/auth/whoami", null, cookie).body().contains("\"via\":\"session\",\"expires\""));
            check("a change with the cookie from this server's page: 200", s.post("/api/thing", "{}", cookie, s.origin()).statusCode() == 200);
            check("...from an allowed proxy origin: 200", s.post("/api/thing", "{}", cookie, "https://rift.example.org").statusCode() == 200);
            check("...from another site: 403", s.post("/api/thing", "{}", cookie, "https://evil.example").statusCode() == 403);
            check("...cross-site without Origin (Sec-Fetch-Site): 403", s.send(HttpRequest.newBuilder(s.uri("/api/thing"))
                    .header("Cookie", cookie).header("Sec-Fetch-Site", "cross-site").POST(HttpRequest.BodyPublishers.ofString("{}"))).statusCode() == 403);
            check("...from a program (neither header): 200", s.post("/api/thing", "{}", cookie, null).statusCode() == 200);
            check("a made-up cookie: 401", s.get("/api/thing", null, ApiAuth.COOKIE + "=" + ApiTokens.randomId()).statusCode() == 401);

            // too many wrong tokens from one address
            for (int i = 0; i < 12; i++) {
                s.get("/api/thing", "Bearer swt_wrong" + i);
            }
            r = s.get("/api/thing", "Bearer " + cli);
            check("after 10 wrong tokens in a minute: 429 even for a good one, Retry-After", r.statusCode() == 429
                    && r.headers().firstValue("Retry-After").orElse("").equals("60"));
            check("sign-in is blocked too", s.post("/api/auth/session", "{\"token\":\"" + ui + "\"}", null, s.origin()).statusCode() == 429);
            check("a signed-in browser is not", s.get("/api/thing", null, cookie).statusCode() == 200);
            long refusals = EVENTS.stream().filter(e -> "api.auth.refused".equals(tag(e))).count();
            check("the refusals are logged once a minute per address, not each: " + refusals, refusals >= 1 && refusals <= 2);

            // revoke: delete the token's lines
            ApiAuth auth2 = new ApiAuth(true, new ApiTokens(f), 12, "/api", Collections.emptyList());
            Server s2 = new Server(auth2);
            try {
                r = s2.post("/api/auth/session", "{\"token\":\"" + ui + "\"}", null, s2.origin());
                String c2 = r.headers().firstValue("Set-Cookie").orElse(";");
                c2 = c2.substring(0, c2.indexOf(';'));
                check("(another server: signed in)", r.statusCode() == 200 && s2.get("/api/thing", null, c2).statusCode() == 200);
                List<String> lines = new ArrayList<>(Files.readAllLines(f, StandardCharsets.UTF_8));
                lines.removeIf(l -> l.startsWith("token.ui."));
                Files.write(f, lines, StandardCharsets.UTF_8);
                Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 5000));
                Thread.sleep(1100);
                check("revoked by deleting its lines: the token and its sessions end", s2.get("/api/thing", "Bearer " + ui).statusCode() == 401
                        && s2.get("/api/thing", null, c2).statusCode() == 401 && s2.get("/api/thing", "Bearer " + cli).statusCode() == 200);

                ApiTokens.addToFile(f, "ui2", null, null);
                String ui3 = ApiTokens.addToFile(f, "ui3", null, null);
                Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 10000));
                Thread.sleep(1100);
                r = s2.post("/api/auth/session", "{\"token\":\"" + ui3 + "\"}", null, s2.origin());
                String c3 = r.headers().firstValue("Set-Cookie").orElse(";");
                c3 = c3.substring(0, c3.indexOf(';'));
                check("a token made while it runs works at once", r.statusCode() == 200);
                int beforeOut = count("api.auth.signedout");
                r = s2.send(HttpRequest.newBuilder(s2.uri("/api/auth/session")).header("Cookie", c3).DELETE());
                check("sign out: 204, the cookie cleared, the session gone", r.statusCode() == 204
                        && r.headers().firstValue("Set-Cookie").orElse("").startsWith(ApiAuth.COOKIE + "=; Path=/api; Max-Age=0")
                        && s2.get("/api/thing", null, c3).statusCode() == 401 && count("api.auth.signedout") == beforeOut + 1
                        && auth2.sessionCount() == 0);
            } finally {
                s2.stop();
            }
        } finally {
            s.stop();
        }
    }

    /** A plain HTTP server with the contexts Rift has: /api (a handler that answers who asked), events, maps, auth, pages. */
    static final class Server {
        final HttpServer http;
        final HttpClient client = HttpClient.newHttpClient();

        Server(ApiAuth auth) throws Exception {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ApiCallHandler api = new ApiCallHandler() {
                @Override
                public XrResultPair handleCallToJson(String method, String requestUri, String apiRoute, Map<String, String> q) {
                    return new XrResultPair("{\"actor\":\"" + RequestContext.actor() + "\"}", 200);
                }
            };
            List<HttpContext> under = new ArrayList<>();
            under.add(http.createContext("/api", new XrRequestHandler(api, "/api")));
            under.add(http.createContext("/api/events", ex -> { ex.sendResponseHeaders(200, -1); ex.close(); }));
            under.add(http.createContext("/api/maps", ex -> { ex.sendResponseHeaders(200, -1); ex.close(); }));
            under.add(http.createContext("/api/auth", auth.handler()));
            http.createContext("/", ex -> { ex.sendResponseHeaders(200, -1); ex.close(); });
            for (HttpContext c : under) {
                c.getFilters().add(auth.filter());
            }
            http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            http.start();
        }

        String origin() {
            return "https://127.0.0.1:" + http.getAddress().getPort();      // https: what a browser sends for the real server
        }

        URI uri(String path) {
            return URI.create("http://127.0.0.1:" + http.getAddress().getPort() + path);
        }

        HttpResponse<String> get(String path, String authz) throws Exception {
            return get(path, authz, null);
        }

        HttpResponse<String> get(String path, String authz, String cookie) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).GET();
            if (authz != null) b.header("Authorization", authz);
            if (cookie != null) b.header("Cookie", cookie);
            return send(b);
        }

        HttpResponse<String> post(String path, String body, String cookie, String origin) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (cookie != null) b.header("Cookie", cookie);
            if (origin != null) b.header("Origin", origin);
            return send(b);
        }

        HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
            return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        }

        void stop() {
            http.stop(0);
        }
    }

    // ------------------------------------------------------------------ helpers

    interface Io {
        void run() throws Exception;
    }

    static boolean throwsIae(Io io) {
        try {
            io.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static SimwProperties args(String... a) {
        SimwProperties p = new SimwProperties();
        new SimwArgs().parseInto(p, a);
        return p;
    }

    static PrintStream nul() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    static String tag(LogRecord r) {
        Events.Tag t = Events.tagOf(r);
        return t == null ? null : t.event();
    }

    static int count(String event) {
        synchronized (EVENTS) {
            return (int) EVENTS.stream().filter(e -> event.equals(tag(e))).count();
        }
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
