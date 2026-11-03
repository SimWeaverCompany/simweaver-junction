package com.simweaver.rift.api;

import com.simweaver.common.rest.ApiCallHandlerWithBody;
import com.simweaver.common.rest.JsonParser;
import com.simweaver.common.rest.Jsons;
import com.simweaver.common.rest.XrResultPair;
import com.simweaver.rift.DatasourceManager;
import com.simweaver.rift.db.RiftDatabase;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Rift REST API (everything under the API route, e.g. "/api"):
 *
 * <pre>
 *   GET    /serverinfo                                      version, CPU, memory (top bar)
 *   GET    /datasourceconfigs                               config list
 *   GET    /datasourceconfig/{configid}/autofederations     federations found for a config
 *   GET    /datasources                                     running datasources
 *   POST   /datasources          {"configid":..,"federation":".."}   create (federation optional, e.g. DIS)
 *   GET    /datasources/{id}                                header of one datasource (+ "stats")
 *   DELETE /datasources/{id}                                resign, disconnect, remove
 *   GET    /datasources/{id}/classes | objects | objects/{iid} | interactions | interactions/{ixid}
 *                                                           datasource page (RiftDatasourcePageApi)
 * </pre>
 *
 * Errors use the envelope {@code {"success":false,"error":{"code","message"}}} with a real HTTP
 * status (see {@link RiftDatasourcePageApi#error}): 400 BAD_REQUEST, 404 NOT_FOUND /
 * DATASOURCE_UNKNOWN, 405 METHOD_NOT_ALLOWED, 500 INTERNAL_ERROR. Ids in paths keep their case.
 *
 * <p>Called concurrently by the HTTP server threads; it keeps no request state.
 */
public class ApiRequestEntryPoint implements ApiCallHandlerWithBody {

    private static final Logger LOG = Logger.getLogger(ApiRequestEntryPoint.class.getName());

    private final DatasourceManager datasourceManager;
    private final RiftDatasourcePageApi pageApi;
    // NEW: other APIs under this route, by their first path segment (e.g. "junction")
    private final Map<String, ApiCallHandlerWithBody> mounts = new java.util.concurrent.ConcurrentHashMap<>();

    /** Uses the manager's list profiles, so REST rows match the live (SSE) rows. */
    public ApiRequestEntryPoint(DatasourceManager datasourceManager) {
        this(datasourceManager, datasourceManager.getListProfiles());
    }

    /** @param profiles list-view profiles; use the same ones as the datasources' RiftEventPublishers */
    public ApiRequestEntryPoint(DatasourceManager datasourceManager, RiftListProfiles profiles) {
        if (datasourceManager == null) throw new IllegalArgumentException("datasourceManager must not be null");
        this.datasourceManager = datasourceManager;
        this.pageApi = new RiftDatasourcePageApi(this::findDatabase, profiles, "/datasources/");
    }

    /**
     * NEW: hands every call under {@code /<segment>/...} (and {@code /<segment>}) to another API,
     * e.g. Junction's {@code /api/junktion/...}. The handler gets the call unchanged. The Rift
     * routes keep precedence: a segment Rift uses itself cannot be mounted.
     */
    /** NEW (map): server setting map.enabled, for GET /api/datasources/{dsid}/map. */
    public void setMapEnabled(boolean enabled) {
        pageApi.setMapEnabled(enabled);
    }

    public void mount(String segment, ApiCallHandlerWithBody handler) {
        String s = segment.toLowerCase(Locale.ROOT);
        switch (s) {
            case "serverinfo":
            case "datasourceconfigs":
            case "datasourceconfig":
            case "datasources":
            case "events":
                throw new IllegalArgumentException("'" + segment + "' is a Rift route");
            default:
                mounts.put(s, handler);
        }
    }

    @Override
    public XrResultPair handleCallToJson(String requestMethod, String requestUri, String apiRoute,
                                         Map<String, String> queryMap, String requestBody) {
        String method = requestMethod == null ? "GET" : requestMethod.toUpperCase(Locale.ROOT);
        String path = apiPath(requestUri, apiRoute);
        List<String> seg = path == null ? null : segments(path);
        if (seg == null) return notFound(method, requestUri);
        if (LOG.isLoggable(Level.FINE)) LOG.fine(method + " " + path);
        try {
            String first = seg.isEmpty() ? "" : seg.get(0).toLowerCase(Locale.ROOT);
            ApiCallHandlerWithBody mounted = mounts.get(first);            // NEW
            if (mounted != null) {
                return mounted.handleCallToJson(requestMethod, requestUri, apiRoute, queryMap, requestBody);
            }
            switch (first) {
                case "serverinfo":
                    if (seg.size() != 1) break;
                    if (!isGet(method)) return methodNotAllowed(method, path, "GET");
                    return ok(datasourceManager.jsonGetServerInfo());

                case "datasourceconfigs":
                    if (seg.size() != 1) break;
                    if (!isGet(method)) return methodNotAllowed(method, path, "GET");
                    return ok(datasourceManager.jsonGetDatasourceConfigs());

                case "datasourceconfig":
                    if (seg.size() != 3 || !"autofederations".equalsIgnoreCase(seg.get(2))) break;
                    if (!isGet(method)) return methodNotAllowed(method, path, "GET");
                    String probeLsd = queryMap == null ? null : queryMap.get("lsd");      // 0.9.15
                    if (probeLsd != null && com.simweaver.common.hla.session.LsdOptions.problem(probeLsd) != null) {
                        throw new BadRequest(com.simweaver.common.hla.session.LsdOptions.problem(probeLsd));
                    }
                    return ok(datasourceManager.jsonGetDatasourceConfigAutoFederations(seg.get(1), probeLsd));

                case "datasources":
                    if (seg.size() == 1) {
                        if (isGet(method)) return ok(datasourceManager.jsonGetDatasources());
                        if ("POST".equals(method)) return createDatasource(requestBody);
                        return methodNotAllowed(method, path, "GET, POST");
                    }
                    if (seg.size() == 2) {
                        if (isGet(method)) return getDatasource(seg.get(1));
                        if ("DELETE".equals(method)) return datasourceManager.jsonTeardownDatasource(seg.get(1));
                        return methodNotAllowed(method, path, "GET, DELETE");
                    }
                    if ("comms".equalsIgnoreCase(seg.get(2))) {                 // 0.12.0: the Comms view
                        return comms(method, path, seg, queryMap);
                    }
                    XrResultPair page = pageApi.handle(method, path, queryMap);
                    if (page != null) return page;
                    break;

                default:
                    break;
            }
            return notFound(method, path);
        } catch (BadRequest e) {
            return RiftDatasourcePageApi.error(400, "BAD_REQUEST", e.getMessage());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "API call failed: " + method + " " + path, e);
            return RiftDatasourcePageApi.error(500, "INTERNAL_ERROR", e.toString());
        }
    }

    // ------------------------------------------------------------------ comms (0.12.0)

    /**
     * {@code GET /api/datasources/{id}/comms}: channels, senders and activity;
     * {@code GET …/comms/chat?channel=<id>&limit=200}: a chat transcript (oldest first).
     * The audio itself is {@code GET /api/audio/stream} (CommsAudioHandler).
     */
    private XrResultPair comms(String method, String path, List<String> seg, Map<String, String> q) {
        if (!isGet(method)) return methodNotAllowed(method, path, "GET");
        DataSource ds = findDatasource(seg.get(1));
        com.simweaver.rift.comms.CommsTracker t = ds == null ? null : datasourceManager.getComms(ds.getId());
        if (t == null) {
            return RiftDatasourcePageApi.error(404, "DATASOURCE_UNKNOWN", "No datasource " + seg.get(1));
        }
        if (seg.size() == 3) {
            return ok(com.simweaver.common.json.Json.toJson(t.snapshot()));
        }
        if (seg.size() == 4 && "chat".equalsIgnoreCase(seg.get(3))) {
            String channel = q == null ? null : q.get("channel");
            if (channel == null || channel.trim().isEmpty()) throw new BadRequest("channel is required");
            int limit = 200;
            String l = q.get("limit");
            if (l != null) {
                try {
                    limit = Integer.parseInt(l.trim());
                } catch (NumberFormatException e) {
                    throw new BadRequest("limit must be a number");
                }
                if (limit < 1 || limit > 500) throw new BadRequest("limit must be 1 - 500");
            }
            Map<String, Object> chat = t.chat(channel.trim(), limit);
            if (chat == null) return RiftDatasourcePageApi.error(404, "CHANNEL_UNKNOWN", "No channel " + channel.trim());
            return ok(com.simweaver.common.json.Json.toJson(chat));
        }
        return notFound(method, path);
    }

    // ------------------------------------------------------------------ datasources

    /** POST /datasources {"configid": .., "federation": ".."}; federation may be left out (DIS). */
    private XrResultPair createDatasource(String requestBody) {
        Map<String, Object> body = jsonBody(requestBody);
        String configId = text(body.get("configid"));
        if (configId == null || configId.isEmpty()) throw new BadRequest("configid is required");
        String federation = null;
        if (body.containsKey("federation") && body.get("federation") != null) {
            federation = text(body.get("federation"));
            if (federation.isEmpty()) throw new BadRequest("federation must not be empty");
        }
        String lsd = null;                                                   // 0.9.15: another LSD than the default
        if (body.containsKey("lsd") && body.get("lsd") != null) {
            if (!(body.get("lsd") instanceof String)) throw new BadRequest("lsd must be a string");
            lsd = ((String) body.get("lsd")).trim();
            String why = com.simweaver.common.hla.session.LsdOptions.problem(lsd);
            if (why != null) throw new BadRequest(why);
        }
        return datasourceManager.jsonSetupDatasource(configId, federation, lsd);
    }

    /**
     * GET /datasources/{id}: {"datasource": {...}}. Takes the datasource's own JSON (with or without
     * a "datasource" wrapper) and adds "state"/"statemessage" from the manager and the database
     * "stats" when the datasource does not send them.
     */
    private XrResultPair getDatasource(String id) {
        DataSource ds = findDatasource(id);
        if (ds == null) return RiftDatasourcePageApi.error(404, "DATASOURCE_UNKNOWN", "No datasource " + id);
        Map<String, Object> d = new LinkedHashMap<>(datasourceObject(ds));
        if (!d.containsKey("state")) {
            d.put("state", datasourceManager.getDatasourceState(ds.getId()));
            d.put("statemessage", datasourceManager.getDatasourceStateMessage(ds.getId()));
        }
        if (!d.containsKey("stats")) {
            RiftDatabase db = ds.getDatabase();
            if (db != null) d.put("stats", db.getStats());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("datasource", d);
        return new XrResultPair(Jsons.toJson(m), 200);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> datasourceObject(DataSource ds) {
        String own = String.valueOf(ds.toJson());                         // Json.JsonObject -> JSON text
        Map<String, Object> parsed = JsonParser.parseObject(own);
        Object inner = parsed.get("datasource");
        return inner instanceof Map ? (Map<String, Object>) inner : parsed;
    }

    /** The datasource with this id (a UUID in the path), or null. */
    private DataSource findDatasource(String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
        return datasourceManager.getDatasource(uuid);
    }

    private RiftDatabase findDatabase(String id) {
        DataSource ds = findDatasource(id);
        return ds == null ? null : ds.getDatabase();
    }

    // ------------------------------------------------------------------ helpers

    /** The path after the API route, without query, fragment and trailing slash; null if not under the route. */
    static String apiPath(String requestUri, String apiRoute) {
        if (requestUri == null) return null;
        String path = requestUri;
        int cut = path.length();
        int q = path.indexOf('?'), h = path.indexOf('#');
        if (q >= 0) cut = q;
        if (h >= 0 && h < cut) cut = h;
        path = path.substring(0, cut);
        String route = apiRoute == null ? "" : apiRoute;
        if (route.endsWith("/")) route = route.substring(0, route.length() - 1);
        if (!path.startsWith(route)) return null;
        path = path.substring(route.length());
        if (!path.isEmpty() && path.charAt(0) != '/') return null;          // "/apix/..." is not under "/api"
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path.isEmpty() ? "/" : path;
    }

    /** Percent-decoded, non-empty path segments; null if one is malformed or empty ("//"). */
    static List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        if ("/".equals(path)) return out;
        for (String s : path.substring(1).split("/", -1)) {
            String d = RiftDatasourcePageApi.percentDecode(s);
            if (d == null || d.isEmpty()) return null;
            out.add(d);
        }
        return out;
    }

    private static Map<String, Object> jsonBody(String requestBody) {
        if (requestBody == null || requestBody.isBlank()) throw new BadRequest("A JSON body is required");
        try {
            return Jsons.fromJsonObject(requestBody);
        } catch (RuntimeException e) {
            throw new BadRequest("The body is not a JSON object: " + e.getMessage());
        }
    }

    /** JSON value as text: numbers without ".0" / exponent (config id 3 -> "3"), strings trimmed. */
    private static String text(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal) return ((BigDecimal) v).stripTrailingZeros().toPlainString();
        return v.toString().trim();
    }

    private static boolean isGet(String method) {
        return "GET".equals(method) || "HEAD".equals(method);
    }

    private static XrResultPair ok(String json) {
        return new XrResultPair(json, 200);
    }

    private static XrResultPair notFound(String method, String path) {
        return RiftDatasourcePageApi.error(404, "NOT_FOUND", "There is no API function " + method + " " + path);
    }

    private static XrResultPair methodNotAllowed(String method, String path, String allowed) {
        return RiftDatasourcePageApi.error(405, "METHOD_NOT_ALLOWED", method + " is not supported on " + path + "; use " + allowed);
    }

    private static final class BadRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BadRequest(String message) {
            super(message, null, false, false);
        }
    }
}
