package com.simweaver.common.mcp;

import com.simweaver.common.rest.RequestContext;
import com.simweaver.common.rest.auth.ApiAuth;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The MCP endpoint over HTTP (0.14.0): the Streamable HTTP transport, answered with
 * {@code application/json} only (no SSE streams).
 *
 * <ul>
 *   <li>POST only; GET and DELETE (the legacy stream and session end) get 405.</li>
 *   <li>Before the message is read, a {@link Gate} decides who is asking (or answers itself).</li>
 *   <li>A rate limit per caller (or address): 429 with {@code Retry-After}.</li>
 *   <li>Bodies over 1 MB: 413.</li>
 *   <li>The request runs with {@link RequestContext} set, so events name the caller.</li>
 * </ul>
 */
public final class McpHttpHandler implements HttpHandler {

    private static final Logger LOG = Logger.getLogger(McpHttpHandler.class.getName());
    private static final int MAX_BODY = 1024 * 1024;

    /** Decides who a request is from, before its body is read. */
    @FunctionalInterface
    public interface Gate {
        /**
         * @return the caller: who API authentication let in, "" for an anonymous caller that may
         *         pass, or null when the gate has answered the request itself (refused)
         */
        String admit(HttpExchange exchange) throws IOException;
    }

    private final McpServer server;
    private final Gate gate;
    private final int perMinute;
    private final Map<String, long[]> windows = new ConcurrentHashMap<>();

    /** @param perMinute requests per caller (or address) and minute; 0 = no limit */
    public McpHttpHandler(McpServer server, Gate gate, int perMinute) {
        this.server = server;
        this.gate = gate;
        this.perMinute = perMinute;
    }

    /**
     * The gate SimWeaver uses: refuses pages of other sites ({@code Origin}, DNS rebinding); with
     * API authentication on, takes only {@code Authorization: Bearer} (the web UI's session cookie
     * is refused here: a cookie on an MCP request means a browser); with it off, takes only
     * clients on this machine unless {@code allowRemoteWithoutAuth}.
     *
     * @param auth the server's API authentication (its filter has already checked the token); may be null
     */
    public static Gate standardGate(ApiAuth auth, boolean allowRemoteWithoutAuth) {
        return ex -> {
            String origin = ex.getRequestHeaders().getFirst("Origin");
            String host = ex.getRequestHeaders().getFirst("Host");
            boolean originOk = origin == null || (auth != null ? auth.isAllowedOrigin(origin, host)
                    : host != null && origin.trim().equalsIgnoreCase("https://" + host.trim()));
            if (!originOk) {
                send(ex, 403, rpcError(-32600, "Requests from other sites' pages are refused (Origin " + origin + ")"), null);
                return null;
            }
            if (auth != null && auth.isEnabled()) {
                if (ex.getRequestHeaders().getFirst("Authorization") == null) {
                    send(ex, 401, rpcError(-32600, "The MCP endpoint needs Authorization: Bearer <API token>"), "Bearer");
                    return null;
                }
                String who = ApiAuth.current();
                return who == null ? "" : who;
            }
            if (!allowRemoteWithoutAuth && !isLocal(ex.getRemoteAddress())) {
                send(ex, 403, rpcError(-32600, "Without API authentication (api.auth.enabled) the MCP endpoint only answers"
                        + " clients on this machine (mcp.allowremotewithoutauth)"), null);
                return null;
            }
            return "";
        };
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            if (!"POST".equalsIgnoreCase(method)) {
                ex.getResponseHeaders().set("Allow", "POST");
                send(ex, 405, rpcError(-32600, "The MCP endpoint takes POST only (no GET stream, no sessions)"), null);
                return;
            }
            String caller = gate.admit(ex);
            if (caller == null) {
                return;
            }
            String remote = remoteIp(ex.getRemoteAddress());
            String key = caller.isEmpty() ? "addr " + remote : caller;
            long retry = limited(key);
            if (retry > 0) {
                ex.getResponseHeaders().set("Retry-After", String.valueOf(retry));
                send(ex, 429, rpcError(-32000, "Too many requests: at most " + perMinute + " per minute"), null);
                return;
            }
            String body = readBody(ex.getRequestBody());
            if (body == null) {
                send(ex, 413, rpcError(-32600, "The body is larger than 1 MB"), null);
                return;
            }
            RequestContext.enterRequest(remote, ex.getRequestHeaders().getFirst("X-Forwarded-For"), caller.isEmpty() ? null : caller);
            McpServer.Response r;
            try {
                r = server.handle(new McpServer.Request(body, ex.getRequestHeaders()::getFirst, caller.isEmpty() ? null : caller));
            } finally {
                RequestContext.leaveRequest();
            }
            if (r.body == null) {
                ex.sendResponseHeaders(r.status, -1);
            } else {
                send(ex, r.status, r.body, null);
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "mcp: request failed", e);
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        } finally {
            ex.close();
        }
    }

    /** @return seconds to wait when over the limit, else 0 */
    private long limited(String key) {
        if (perMinute <= 0) {
            return 0;
        }
        long now = System.currentTimeMillis();
        long[] w = windows.computeIfAbsent(key, k -> new long[] {now, 0});
        synchronized (w) {
            if (now - w[0] >= 60_000) {
                w[0] = now;
                w[1] = 0;
            }
            w[1]++;
            if (w[1] > perMinute) {
                return Math.max(1, (60_000 - (now - w[0]) + 999) / 1000);
            }
        }
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> now - e.getValue()[0] > 120_000);
        }
        return 0;
    }

    private static String readBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BODY) {
                return null;
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    static void send(HttpExchange ex, int status, String json, String wwwAuthenticate) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (wwwAuthenticate != null) {
            ex.getResponseHeaders().set("WWW-Authenticate", wwwAuthenticate);
        }
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    static String rpcError(int code, String message) {
        return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":" + code + ",\"message\":"
                + com.simweaver.common.json.Json.quote(message) + "}}";
    }

    private static boolean isLocal(InetSocketAddress a) {
        if (a == null || a.getAddress() == null) {
            return false;
        }
        InetAddress ad = a.getAddress();
        if (ad.isLoopbackAddress()) {
            return true;
        }
        try {
            return java.net.NetworkInterface.getByInetAddress(ad) != null;   // one of this machine's own addresses
        } catch (java.net.SocketException e) {
            return false;
        }
    }

    private static String remoteIp(InetSocketAddress a) {
        return a == null || a.getAddress() == null ? null : a.getAddress().getHostAddress();
    }
}
