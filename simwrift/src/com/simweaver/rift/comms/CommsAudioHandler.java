package com.simweaver.rift.comms;

import com.simweaver.common.json.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code GET /api/audio/stream?ds=<datasource id>&channels=<id>,<id>…} (0.12.0): the audio of some
 * Comms channels as one long chunked response of binary frames ({@link CommsAudioStream} has the
 * format). The page reads it with {@code fetch()} and a {@code ReadableStream}; to change the
 * channels it opens a new stream and aborts the old one. A keep-alive frame goes out every 2 s
 * without audio, so a closed page is noticed. Mounted under {@code /api}, so the API
 * authentication filter applies (the session cookie travels with {@code fetch}).
 */
public final class CommsAudioHandler implements HttpHandler {

    private static final Logger LOG = Logger.getLogger(CommsAudioHandler.class.getName());
    /** Channels per stream. */
    static final int MAX_CHANNELS = 32;
    /** Streams per datasource (each holds a server thread while open). */
    static final int MAX_STREAMS = 16;
    static final long KEEPALIVE_MS = 2000;

    private final Function<UUID, CommsTracker> trackers;
    private final String route;

    /**
     * @param trackers the Comms tracker of a datasource id, or null
     * @param route    the context path, e.g. {@code /api/audio}
     */
    public CommsAudioHandler(Function<UUID, CommsTracker> trackers, String route) {
        this.trackers = trackers;
        this.route = route;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String rest = path.length() > route.length() ? path.substring(route.length()) : "";
            if (!rest.equals("/stream") && !rest.equals("/stream/")) {
                error(ex, 404, "NOT_FOUND", "No such audio resource: " + path);
                return;
            }
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.getResponseHeaders().set("Allow", "GET");
                error(ex, 405, "METHOD_NOT_ALLOWED", ex.getRequestMethod() + " is not allowed here");
                return;
            }
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            UUID id;
            try {
                id = UUID.fromString(q.getOrDefault("ds", "").trim());
            } catch (IllegalArgumentException e) {
                error(ex, 400, "BAD_REQUEST", "ds must be a datasource id");
                return;
            }
            CommsTracker t = trackers.apply(id);
            if (t == null) {
                error(ex, 404, "DATASOURCE_UNKNOWN", "No datasource " + id);
                return;
            }
            List<String> channels = CommsTracker.list(q.get("channels"));
            if (channels.isEmpty() || channels.size() > MAX_CHANNELS) {
                error(ex, 400, "BAD_REQUEST", "channels must list 1 - " + MAX_CHANNELS + " channel ids");
                return;
            }
            for (String c : channels) {
                if (!c.matches("[A-Za-z0-9_-]{1,64}")) {
                    error(ex, 400, "BAD_REQUEST", "'" + c + "' is not a channel id");
                    return;
                }
            }
            if (t.listenerCount() >= MAX_STREAMS) {
                error(ex, 429, "TOO_MANY_LISTENERS", "This datasource already has " + MAX_STREAMS + " audio streams");
                return;
            }
            stream(ex, t, channels);
        } finally {
            ex.close();
        }
    }

    private void stream(HttpExchange ex, CommsTracker t, List<String> channels) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-SimWeaver-Audio", "1");          // frame format version
        ex.sendResponseHeaders(200, 0);                                    // chunked, open until the page goes
        CommsAudioStream s = t.open(channels, String.valueOf(ex.getRemoteAddress()));
        OutputStream out = ex.getResponseBody();
        try {
            out.write(s.next(0));                                          // a keep-alive at once: the page sees the stream is open
            out.flush();
            while (!s.isClosed()) {
                byte[] f = s.next(KEEPALIVE_MS);
                out.write(f);
                for (int i = 0; i < 32; i++) {                             // what else is waiting, then one flush
                    byte[] more = s.poll();
                    if (more == null) break;
                    out.write(more);
                }
                out.flush();
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "audio stream closed: " + e);              // the page went away
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            t.close(s);
            try {
                out.close();
            } catch (IOException ignored) {
                // gone already
            }
        }
    }

    private static void error(HttpExchange ex, int status, String code, String message) throws IOException {
        Map<String, Object> e = new LinkedHashMap<String, Object>();
        e.put("code", code);
        e.put("message", message);
        byte[] b = Json.object().put("success", false).put("error", e).toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }

    static Map<String, String> query(String raw) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (raw == null || raw.isEmpty()) return m;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            String v = eq < 0 ? "" : part.substring(eq + 1);
            try {
                m.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (java.io.UnsupportedEncodingException | IllegalArgumentException e) {
                // skip a broken pair
            }
        }
        return m;
    }
}
