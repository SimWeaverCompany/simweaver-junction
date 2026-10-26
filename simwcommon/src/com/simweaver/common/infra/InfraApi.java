package com.simweaver.common.infra;

import com.simweaver.common.json.Json;
import com.simweaver.common.rest.ApiCallHandlerWithBody;
import com.simweaver.common.rest.Jsons;
import com.simweaver.common.rest.XrResultPair;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Infra REST API (0.9.20), mounted under Rift's API route as {@code /infra}:
 *
 * <pre>
 *   GET  /infra                                    every item by family (dis6, dis7, cdis, hla3, hla4)
 *   GET  /infra/items?keys=k1,k2                   the full view of each (missing ones flagged)
 *   GET  /infra/items/{key}                        the full view of one
 *   POST /infra/items/{key}/refresh                HLA: ask the MOM for fresh values now
 *   POST /infra/items/{key}/declarations {"federate":"&lt;handle hex&gt;"}
 *                                                  HLA: ask the MOM for a federate's publications and subscriptions
 * </pre>
 *
 * Errors: {@code {"success":false,"error":{"code","message"}}} with 400 BAD_REQUEST,
 * 404 NOT_FOUND / ITEM_UNKNOWN, 405 METHOD_NOT_ALLOWED, 409 MOM_REQUEST_FAILED.
 */
public final class InfraApi implements ApiCallHandlerWithBody {

    private static final int MAX_KEYS = 64;

    private final InfraRegistry registry;

    public InfraApi(InfraRegistry registry) {
        this.registry = registry;
    }

    @Override
    public XrResultPair handleCallToJson(String method, String requestUri, String apiRoute, Map<String, String> queryMap,
                                         String requestBody) {
        String m = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        boolean get = "GET".equals(m) || "HEAD".equals(m);
        List<String> seg = segments(requestUri, apiRoute);
        if (seg == null || seg.isEmpty() || !"infra".equalsIgnoreCase(seg.get(0))) {
            return error(404, "NOT_FOUND", "There is no API function " + m + " " + requestUri);
        }
        if (seg.size() == 1) {
            return get ? ok(registry.overview()) : notAllowed(m, "GET");
        }
        if (!"items".equalsIgnoreCase(seg.get(1))) {
            return error(404, "NOT_FOUND", "There is no API function " + m + " " + requestUri);
        }
        if (seg.size() == 2) {
            if (!get) {
                return notAllowed(m, "GET");
            }
            String keys = queryMap == null ? null : queryMap.get("keys");
            List<String> list = new ArrayList<String>();
            if (keys != null) {
                for (String k : keys.split(",")) {
                    if (!k.trim().isEmpty() && list.size() < MAX_KEYS) {
                        list.add(k.trim());
                    }
                }
            }
            if (list.isEmpty()) {
                return error(400, "BAD_REQUEST", "keys is required, e.g. ?keys=dis7-0a1b2c3d4e5f,hla3-...");
            }
            return ok(registry.details(list));
        }
        String key = seg.get(2);
        if (seg.size() == 3) {
            if (!get) {
                return notAllowed(m, "GET");
            }
            Json.JsonObject d = registry.detail(key);
            return d == null ? error(404, "ITEM_UNKNOWN", "No exercise or federation " + key + " now") : ok(Json.object().put("item", d));
        }
        if (seg.size() == 4 && "refresh".equalsIgnoreCase(seg.get(3))) {
            if (!"POST".equals(m)) {
                return notAllowed(m, "POST");
            }
            return registry.refresh(key) ? new XrResultPair(Json.object().put("success", true).toString(), 202)
                    : error(404, "ITEM_UNKNOWN", "No HLA federation " + key + " now");
        }
        if (seg.size() == 4 && "declarations".equalsIgnoreCase(seg.get(3))) {
            if (!"POST".equals(m)) {
                return notAllowed(m, "POST");
            }
            String federate;
            try {
                Map<String, Object> body = Jsons.fromJsonObject(requestBody == null || requestBody.isBlank() ? "{}" : requestBody);
                Object f = body.get("federate");
                federate = f == null ? null : f.toString().trim().toLowerCase(Locale.ROOT);
            } catch (RuntimeException e) {
                return error(400, "BAD_REQUEST", "The body is not a JSON object: " + e.getMessage());
            }
            if (federate == null || federate.isEmpty() || !federate.matches("[0-9a-f]{2,128}")) {
                return error(400, "BAD_REQUEST", "federate (the federate's handle in hex, from the item's federatelist) is required");
            }
            if (registry.hlaTap(key) == null) {
                return error(404, "ITEM_UNKNOWN", "No HLA federation " + key + " now");
            }
            String why = registry.requestDeclarations(key, federate);
            return why == null ? new XrResultPair(Json.object().put("success", true).toString(), 202)
                    : error(409, "MOM_REQUEST_FAILED", why);
        }
        return error(404, "NOT_FOUND", "There is no API function " + m + " " + requestUri);
    }

    private static XrResultPair ok(Object json) {
        return new XrResultPair(Json.toJson(json), 200);
    }

    private static XrResultPair notAllowed(String method, String allowed) {
        return error(405, "METHOD_NOT_ALLOWED", method + " is not supported here; use " + allowed);
    }

    static XrResultPair error(int status, String code, String message) {
        return new XrResultPair(Json.object().put("success", false)
                .put("error", Json.object().put("code", code).put("message", message)).toString(), status);
    }

    /** Path segments after the API route, percent-decoded; null when malformed. */
    static List<String> segments(String requestUri, String apiRoute) {
        if (requestUri == null) {
            return null;
        }
        String path = requestUri;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        String route = apiRoute == null ? "" : apiRoute;
        if (route.endsWith("/")) {
            route = route.substring(0, route.length() - 1);
        }
        if (!path.startsWith(route)) {
            return null;
        }
        path = path.substring(route.length());
        List<String> out = new ArrayList<String>();
        for (String s : path.split("/")) {
            if (s.isEmpty()) {
                continue;
            }
            try {
                out.add(URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8.name()));
            } catch (Exception e) {
                return null;
            }
        }
        return out;
    }
}
