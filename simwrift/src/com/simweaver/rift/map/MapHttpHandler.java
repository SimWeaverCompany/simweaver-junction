package com.simweaver.rift.map;

import com.simweaver.common.rest.Jsons;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code /api/maps}: a handler of its own because tiles are binary (the REST handler answers JSON).
 *
 * <pre>
 *   GET /api/maps                              {"enabled", "maps":[{id, name, bounds, minzoom, maxzoom, tiles, ...}], "default", "problems"}
 *   GET /api/maps/{id}/tiles/{z}/{x}/{y}.jpg   image/jpeg (cached by the browser for a day)
 *   GET /api/maps/overlays/{id}/tiles/{z}/{x}/{y}.png        image/png, transparent (a 1 x 1 PNG where it has nothing)
 *   GET /api/maps/overlays/{id}/labels?bbox=w,s,e,n&amp;zoom=z  {"overlay","zoom","labels":[{t, lat, lon, k}],"truncated"}
 * </pre>
 * Errors use Rift's envelope {@code {"success":false,"error":{"code","message"}}}: 404
 * MAP_UNKNOWN / TILE_UNKNOWN / MAP_DISABLED / OVERLAY_UNKNOWN, 400 BAD_REQUEST, 405
 * METHOD_NOT_ALLOWED, 500 TILE_FAILED, 503 OVERLAY_NOT_READY.
 */
public final class MapHttpHandler implements HttpHandler {

    private static final Pattern TILE = Pattern.compile("/([A-Za-z0-9_.-]+)/tiles/(\\d{1,2})/(\\d{1,6})/(\\d{1,6})\\.jpe?g");
    private static final Pattern OVERLAY_TILE = Pattern.compile("/overlays/([A-Za-z0-9_.-]+)/tiles/(\\d{1,2})/(\\d{1,6})/(\\d{1,6})\\.png");
    private static final Pattern OVERLAY_LABELS = Pattern.compile("/overlays/([A-Za-z0-9_.-]+)/labels");

    private final MapService maps;
    private final String route;

    /** @param route the context path, e.g. "/api/maps" */
    public MapHttpHandler(MapService maps, String route) {
        this.maps = maps;
        this.route = route.endsWith("/") ? route.substring(0, route.length() - 1) : route;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            boolean head = "HEAD".equalsIgnoreCase(method);
            if (!head && !"GET".equalsIgnoreCase(method)) {
                json(ex, 405, error("METHOD_NOT_ALLOWED", method + " is not supported here; use GET"), false);
                return;
            }
            String path = ex.getRequestURI().getPath();
            String rest = path.length() > route.length() ? path.substring(route.length()) : "";
            if (rest.isEmpty() || rest.equals("/")) {
                json(ex, 200, Jsons.toJson(maps.listing()), head);
                return;
            }
            if (!maps.isEnabled()) {
                json(ex, 404, error("MAP_DISABLED", "The map is turned off on this server (map.enabled=false)"), head);
                return;
            }
            Matcher ot = OVERLAY_TILE.matcher(rest);
            if (ot.matches()) {
                overlayTile(ex, ot, head);
                return;
            }
            Matcher ol = OVERLAY_LABELS.matcher(rest);
            if (ol.matches()) {
                overlayLabels(ex, ol.group(1), head);
                return;
            }
            Matcher m = TILE.matcher(rest);
            if (!m.matches()) {
                json(ex, 404, error("NOT_FOUND", "No such map resource: " + path), head);
                return;
            }
            String id = m.group(1);
            if (!maps.hasMap(id)) {
                json(ex, 404, error("MAP_UNKNOWN", "No map " + id), head);
                return;
            }
            int z = Integer.parseInt(m.group(2)), x = Integer.parseInt(m.group(3)), y = Integer.parseInt(m.group(4));
            byte[] b;
            try {
                b = maps.tile(id, z, x, y);
            } catch (IOException e) {
                json(ex, 500, error("TILE_FAILED", e.getMessage()), head);
                return;
            }
            if (b == null) {
                json(ex, 404, error("TILE_UNKNOWN", "No tile " + z + "/" + x + "/" + y + " (zoom 0-" + maps.maxZoom(id) + ")"), head);
                return;
            }
            ex.getResponseHeaders().set("Content-Type", "image/jpeg");
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            if (head) {
                ex.sendResponseHeaders(200, -1);
            } else {
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream o = ex.getResponseBody()) {
                    o.write(b);
                }
            }
        } finally {
            ex.close();
        }
    }

    private void overlayTile(HttpExchange ex, Matcher m, boolean head) throws IOException {
        String id = m.group(1);
        if (!maps.hasOverlay(id)) {
            json(ex, 404, error("OVERLAY_UNKNOWN", "No overlay " + id), head);
            return;
        }
        int z = Integer.parseInt(m.group(2)), x = Integer.parseInt(m.group(3)), y = Integer.parseInt(m.group(4));
        byte[] b;
        try {
            b = maps.overlayTile(id, z, x, y);
        } catch (IOException e) {
            json(ex, 503, error("OVERLAY_NOT_READY", e.getMessage()), head);
            return;
        }
        if (b == null) {
            json(ex, 404, error("TILE_UNKNOWN", "No overlay tile " + z + "/" + x + "/" + y + " (zoom 0-" + VectorOverlay.MAX_ZOOM + ")"), head);
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "image/png");
        ex.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (head) {
            ex.sendResponseHeaders(200, -1);
        } else {
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            }
        }
    }

    private void overlayLabels(HttpExchange ex, String id, boolean head) throws IOException {
        if (!maps.hasOverlay(id)) {
            json(ex, 404, error("OVERLAY_UNKNOWN", "No overlay " + id), head);
            return;
        }
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        double[] bbox = new double[4];
        int zoom;
        try {
            String[] p = q.getOrDefault("bbox", "").split(",");
            if (p.length != 4) {
                throw new NumberFormatException();
            }
            for (int i = 0; i < 4; i++) {
                bbox[i] = Double.parseDouble(p[i].trim());
            }
            zoom = Integer.parseInt(q.getOrDefault("zoom", "").trim());
        } catch (NumberFormatException e) {
            json(ex, 400, error("BAD_REQUEST", "labels need bbox=west,south,east,north (degrees) and zoom=<0-18>"), head);
            return;
        }
        Map<String, Object> r;
        try {
            r = maps.overlayLabels(id, bbox, zoom);
        } catch (IOException e) {
            json(ex, 503, error("OVERLAY_NOT_READY", e.getMessage()), head);
            return;
        }
        json(ex, 200, Jsons.toJson(r), head);
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null) {
            return m;
        }
        for (String kv : raw.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) {
                m.put(java.net.URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    private static String error(String code, String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("code", code);
        e.put("message", message);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", Boolean.FALSE);
        m.put("error", e);
        return Jsons.toJson(m);
    }

    private static void json(HttpExchange ex, int status, String body, boolean head) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (head) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }
}
