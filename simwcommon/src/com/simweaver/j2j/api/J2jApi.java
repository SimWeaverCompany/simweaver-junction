package com.simweaver.j2j.api;

import com.simweaver.common.json.Json;
import com.simweaver.common.rest.ApiCallHandlerWithBody;
import com.simweaver.common.rest.XrResultPair;
import com.simweaver.common.rest.XrSseHub;
import com.simweaver.j2j.J2jNode;
import com.simweaver.j2j.J2jService;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The J2J REST API (design §13), mounted under Rift's API route as {@code /j2j}:
 *
 * <pre>
 *   GET  /j2j                       this server's J2J state (also when off)
 *   GET  /j2j/members               every configured member: state, how reached, ping, users
 *   GET  /j2j/members/{id}          one member, with attempts, traffic per class, its status
 *   POST /j2j/members/{id}/ping     the path RTT now
 *   POST /j2j/members/reload        read etc/j2j/network.settings and the key again
 *   GET  /j2j/topology              every server and link (the diagram)
 *   GET  /j2j/sessions              the sessions this server knows of
 *   GET  /j2j/events?limit=100      the latest J2J events
 *   POST /j2j/key/new               a random network key text to hand out (not installed)
 * </pre>
 *
 * Live updates on SSE topic {@code j2j}: {@code j2j.status} and {@code j2j.members} every 2 s,
 * {@code j2j.topology} when it changes, {@code j2j.event} as events are logged.
 */
public final class J2jApi implements ApiCallHandlerWithBody {

    private final J2jService service;
    private final J2jViews views;
    private ScheduledExecutorService live;
    private volatile String lastTopology;

    public J2jApi(J2jService service) {
        this.service = service;
        this.views = new J2jViews(service);
        this.views.setUsage(service.sessions());
    }

    public J2jViews views() {
        return views;
    }

    /** Publishes live updates on topic "j2j" of {@code hub}. */
    public synchronized void startLive(XrSseHub hub) {
        if (live != null || hub == null) {
            return;
        }
        live = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "j2j-live");
            t.setDaemon(true);
            return t;
        });
        live.scheduleWithFixedDelay(() -> {
            try {
                hub.publish("j2j", "j2j.status", views.status().toString());
                J2jNode n = service.node();
                if (n != null && n.config().isConfigured()) {
                    hub.publish("j2j", "j2j.members", views.members().toString());
                    String topo = views.topology().toString();
                    if (!topo.equals(lastTopology)) {
                        lastTopology = topo;
                        hub.publish("j2j", "j2j.topology", topo);
                    }
                }
            } catch (RuntimeException ignored) {
                // the next round
            }
        }, 1, 2, TimeUnit.SECONDS);
        service.addEventListener(r -> {
            try {
                hub.publish("j2j", "j2j.event", J2jViews.event(r).toString());
            } catch (RuntimeException ignored) {
                // nobody listening
            }
        });
    }

    public synchronized void stopLive() {
        if (live != null) {
            live.shutdownNow();
            live = null;
        }
    }

    @Override
    public XrResultPair handleCallToJson(String method, String requestUri, String apiRoute, Map<String, String> queryMap,
                                         String requestBody) {
        String m = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        boolean get = "GET".equals(m) || "HEAD".equals(m);
        boolean post = "POST".equals(m);
        List<String> seg = segments(requestUri, apiRoute);
        if (seg == null || seg.isEmpty() || !"j2j".equalsIgnoreCase(seg.get(0))) {
            return error(404, "NOT_FOUND", "There is no API function " + m + " " + requestUri);
        }
        if (seg.size() == 1) {
            return get ? ok(views.status()) : notAllowed(m, "GET");
        }
        String what = seg.get(1).toLowerCase(Locale.ROOT);
        J2jNode n = service.node();
        boolean configured = n != null && n.config().isConfigured();
        if (what.equals("members") && seg.size() == 3 && seg.get(2).equalsIgnoreCase("reload")) {
            if (!post) {
                return notAllowed(m, "POST");
            }
            J2jService.Reload r = service.reload();
            Json.JsonObject o = Json.object().put("success", r.applied).put("added", r.added).put("removed", r.removed)
                    .put("changed", r.changed).put("state", r.state).put("problems", r.problems);
            if (!r.applied) {
                return new XrResultPair(Json.object().put("success", false).put("error", Json.object().put("code", "MEMBERS_INVALID")
                        .put("message", "The member list has problems; the running list stays: " + String.join("; ", r.problems)))
                        .put("problems", r.problems).toString(), 422);
            }
            return ok(o);
        }
        if (what.equals("key") && seg.size() == 3 && seg.get(2).equalsIgnoreCase("new")) {
            return post ? ok(views.newKey()) : notAllowed(m, "POST");
        }
        if (!configured) {
            return error(409, "J2J_OFF", "J2J is off: etc/j2j/network.settings lists no members");
        }
        switch (what) {
            case "members":
                if (seg.size() == 2) {
                    return get ? ok(views.members()) : notAllowed(m, "GET");
                }
                String id = seg.get(2);
                if (n.config().member(id) == null) {
                    return error(404, "UNKNOWN_MEMBER", "No member " + id + " in the member list");
                }
                if (seg.size() == 3) {
                    return get ? ok(views.member(id)) : notAllowed(m, "GET");
                }
                if (seg.size() == 4 && seg.get(3).equalsIgnoreCase("ping")) {
                    return post ? ping(n, id) : notAllowed(m, "POST");
                }
                break;
            case "topology":
                if (seg.size() == 2) {
                    return get ? ok(views.topology()) : notAllowed(m, "GET");
                }
                break;
            case "sessions":
                if (seg.size() == 2) {
                    return get ? ok(views.sessions()) : notAllowed(m, "GET");
                }
                break;
            case "events":
                if (seg.size() == 2) {
                    int limit = 100;
                    try {
                        limit = Math.max(1, Math.min(500, Integer.parseInt(queryMap == null || queryMap.get("limit") == null ? "100" : queryMap.get("limit"))));
                    } catch (NumberFormatException e) {
                        return error(400, "BAD_REQUEST", "limit must be a number 1-500");
                    }
                    return get ? ok(views.events(limit)) : notAllowed(m, "GET");
                }
                break;
            default:
                break;
        }
        return error(404, "NOT_FOUND", "There is no API function " + m + " " + requestUri);
    }

    private XrResultPair ping(J2jNode n, String id) {
        if (!n.isRunning()) {
            return error(409, "J2J_OFF", "J2J does not run: " + String.join("; ", n.config().problems()));
        }
        if (id.equals(n.config().selfId())) {
            return error(409, "UNREACHABLE", "This is this server");
        }
        if (n.router().route(id) == null && n.links().link(id) == null) {
            return error(409, "UNREACHABLE", "No path to " + id);
        }
        List<Json.JsonObject> path = new ArrayList<Json.JsonObject>();
        List<String> hops = n.router().route(id) == null ? java.util.Collections.singletonList(id) : n.router().route(id).path;
        Double total = null;
        for (String hop : hops) {
            Double rtt;
            try {
                rtt = n.router().echo(hop, 5000).get(6, TimeUnit.SECONDS);
            } catch (Exception e) {
                rtt = null;
            }
            path.add(Json.object().put("id", hop).put("rttms", rtt == null ? null : J2jViews.round1(rtt)));
            if (hop.equals(id)) {
                total = rtt;
            }
        }
        if (total == null) {
            return error(504, "PING_TIMEOUT", "No answer from " + id + " within 5 s");
        }
        return ok(Json.object().put("success", true).put("rttms", J2jViews.round1(total)).put("path", path));
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
