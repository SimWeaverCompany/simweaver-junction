package com.simweaver.junction.api;

import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.json.Json;
import com.simweaver.common.rest.ApiCallHandlerWithBody;
import com.simweaver.common.rest.Jsons;
import com.simweaver.common.rest.XrResultPair;
import com.simweaver.junction.Gateway;
import com.simweaver.junction.GatewayException;
import com.simweaver.junction.Inspector;
import com.simweaver.junction.JunctionManager;
import com.simweaver.junction.TargetProbe;
import com.simweaver.junction.config.EndpointConfig;
import com.simweaver.junction.config.GatewayConfig;
import com.simweaver.junction.router.ModelTools;
import com.simweaver.junction.router.RouteCompiler;
import com.simweaver.junction.router.RouteTable;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Junction REST API, mounted at {@code /api/junktion} (see {@code doc/junktion-rest-api.md}):
 *
 * <pre>
 *   GET    /junktion/configs                                         configurations and kits
 *   GET    /junktion/configs/{cfgid}                                 one configuration
 *   GET    /junktion/configs/{cfgid}/compatibility                   routes and drops, from the FOMs
 *   GET    /junktion/configs/{cfgid}/endpoints/{epid}/targets        federations / exercises / Talk channels (?federation=)
 *   GET    /junktion/gateways                                        running gateways
 *   POST   /junktion/gateways   {"configid":"..","targets":{"ep":".."},"vars":{"GROUPVAR1":".."},"instance":"..","lsds":{"ep":".."}}   start
 *   GET    /junktion/groups                                          groups, their gateways, variables and running instances
 *   POST   /junktion/groups/{group}/start   {"vars":{"GROUPVAR1":".."},"instance":"..","lsds":{"cfg":{"ep":".."}}}   start every gateway of the group
 *                                          as one instance (201 all, 207 some: see "results"; 400 GROUP_START_FAILED none;
 *                                          409 INSTANCE_RUNNING)
 *   POST   /junktion/groups/{group}/stop    {"instance":".."} (optional)   stop the group's running gateways (of one instance)
 *   GET    /junktion/gateways/{gid}                                  one gateway: state and statistics
 *   GET    /junktion/gateways/{gid}/compatibility                    its route tables
 *   DELETE /junktion/gateways/{gid}                                  stop and remove
 *   POST   /junktion/gateways/{gid}/endpoints/{epid}/inspect         a Rift datasource for that endpoint
 * </pre>
 *
 * Errors use Rift's envelope {@code {"success":false,"error":{"code","message"}}} with a real HTTP
 * status. Called concurrently by the HTTP threads; keeps no request state.
 */
public final class JunktionApi implements ApiCallHandlerWithBody {

    private static final Logger LOG = Logger.getLogger(JunktionApi.class.getName());

    private final JunctionManager manager;

    /** @param manager the manager */
    public JunktionApi(JunctionManager manager) {
        this.manager = manager;
    }

    @Override
    public XrResultPair handleCallToJson(String requestMethod, String requestUri, String apiRoute,
                                         Map<String, String> queryMap, String requestBody) {
        String method = requestMethod == null ? "GET" : requestMethod.toUpperCase(Locale.ROOT);
        List<String> seg = segments(requestUri, apiRoute);
        // "junction"; the old spelling "junktion" is still answered (clients of 0.9.0)
        if (seg == null || seg.isEmpty() || !("junction".equalsIgnoreCase(seg.get(0)) || "junktion".equalsIgnoreCase(seg.get(0)))) {
            return error(404, "NOT_FOUND", "There is no API function " + method + " " + requestUri);
        }
        boolean get = "GET".equals(method) || "HEAD".equals(method);
        try {
            if (seg.size() == 1) {
                return get ? ok(Json.object().put("name", "SimWeaver Junction").put("version", com.simweaver.junction.Version.getVersion()))
                        : notAllowed(method, "GET");
            }
            String area = seg.get(1).toLowerCase(Locale.ROOT);
            if (area.equals("configs")) {
                if (seg.size() == 2) {
                    return get ? ok(manager.jsonConfigs()) : notAllowed(method, "GET");
                }
                GatewayConfig cfg = manager.reload().gateway(seg.get(2));
                if (cfg == null) {
                    return error(404, "CONFIG_NOT_FOUND", "No gateway configuration '" + seg.get(2) + "'");
                }
                if (seg.size() == 3) {
                    return get ? ok(Json.object().put("config", cfg)) : notAllowed(method, "GET");
                }
                if (seg.size() == 4 && seg.get(3).equalsIgnoreCase("compatibility")) {
                    return get ? ok(compatibility(cfg)) : notAllowed(method, "GET");
                }
                if (seg.size() == 6 && seg.get(3).equalsIgnoreCase("endpoints") && seg.get(5).equalsIgnoreCase("targets")) {
                    if (!get) {
                        return notAllowed(method, "GET");
                    }
                    EndpointConfig ec = cfg.getEndpoints().get(seg.get(4));
                    if (ec == null) {
                        return error(404, "ENDPOINT_UNKNOWN", "The gateway has no endpoint '" + seg.get(4) + "'");
                    }
                    String fed = queryMap == null ? null : queryMap.get("federation");
                    String lsd = queryMap == null ? null : queryMap.get("lsd");      // 0.9.15: another LSD than the default
                    if (lsd != null && com.simweaver.common.hla.session.LsdOptions.problem(lsd) != null) {
                        throw new BadRequest(com.simweaver.common.hla.session.LsdOptions.problem(lsd));
                    }
                    TargetProbe.Result r = TargetProbe.probe(ec, manager.getRuntimeDir(), fed, lsd);
                    return ok(Json.object().put("endpoint", ec.getId()).put("result", r)
                            .put("default", ec.getDefaultTarget()));
                }
            } else if (area.equals("groups")) {
                if (seg.size() == 2) {
                    return get ? ok(Json.object().put("groups", manager.jsonGroups())) : notAllowed(method, "GET");
                }
                if (seg.size() == 4 && seg.get(3).equalsIgnoreCase("start")) {
                    return "POST".equals(method) ? startGroup(seg.get(2), requestBody) : notAllowed(method, "POST");
                }
                if (seg.size() == 4 && seg.get(3).equalsIgnoreCase("stop")) {
                    if (!"POST".equals(method)) {
                        return notAllowed(method, "POST");
                    }
                    if (manager.groupConfigs(seg.get(2)).isEmpty()) {
                        return error(404, "GROUP_NOT_FOUND", "No gateway configuration is in group '" + seg.get(2) + "'");
                    }
                    Map<String, Object> b = requestBody == null || requestBody.trim().isEmpty() ? new LinkedHashMap<String, Object>() : jsonBody(requestBody);
                    String inst = text(b.get("instance"));
                    if ((inst == null || inst.trim().isEmpty()) && queryMap != null) {
                        inst = queryMap.get("instance");
                    }
                    if (inst != null && !inst.trim().isEmpty()) {
                        if (!manager.groupInstanceRuns(seg.get(2), inst)) {
                            return error(404, "INSTANCE_NOT_FOUND", "No instance '" + inst.trim() + "' of group '" + seg.get(2) + "' is running");
                        }
                        return ok(Json.object().put("group", seg.get(2)).put("instance", inst.trim())
                                .put("stopped", manager.stopGroup(seg.get(2), inst)));
                    }
                    return ok(Json.object().put("group", seg.get(2)).put("stopped", manager.stopGroup(seg.get(2))));
                }
            } else if (area.equals("gateways")) {
                if (seg.size() == 2) {
                    if (get) {
                        return ok(manager.jsonGateways());
                    }
                    if ("POST".equals(method)) {
                        return startGateway(requestBody);
                    }
                    return notAllowed(method, "GET, POST");
                }
                Gateway g = manager.gateway(seg.get(2));
                if (seg.size() == 3) {
                    if (get) {
                        return g == null ? unknownGateway(seg.get(2)) : ok(Json.object().put("gateway", g));
                    }
                    if ("DELETE".equals(method)) {
                        return manager.stop(seg.get(2)) ? new XrResultPair("", 204) : unknownGateway(seg.get(2));
                    }
                    return notAllowed(method, "GET, DELETE");
                }
                if (g == null) {
                    return unknownGateway(seg.get(2));
                }
                if (seg.size() == 4 && seg.get(3).equalsIgnoreCase("compatibility")) {
                    return get ? ok(Json.object().put("routes", g.getRouter().compatibility())) : notAllowed(method, "GET");
                }
                if (seg.size() == 6 && seg.get(3).equalsIgnoreCase("endpoints") && seg.get(5).equalsIgnoreCase("inspect")) {
                    if (!"POST".equals(method)) {
                        return notAllowed(method, "POST");
                    }
                    try {
                        JunctionManager.InspectResult r = manager.inspect(seg.get(2), seg.get(4));
                        return new XrResultPair(Json.object().put("success", true).put("inspect", r).toString(), r.created ? 201 : 200);
                    } catch (Inspector.InspectException e) {
                        return error(e.getStatus(), e.getCode(), e.getMessage());
                    }
                }
            }
            return error(404, "NOT_FOUND", "There is no API function " + method + " " + requestUri);
        } catch (BadRequest e) {
            return error(400, "BAD_REQUEST", e.getMessage());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Junction API call failed: " + method + " " + requestUri, e);
            return error(500, "INTERNAL_ERROR", e.toString());
        }
    }

    // ------------------------------------------------------------------ calls

    @SuppressWarnings("unchecked")
    private XrResultPair startGateway(String body) {
        Map<String, Object> b = jsonBody(body);
        String configId = text(b.get("configid"));
        if (configId == null || configId.isEmpty()) {
            throw new BadRequest("configid is required");
        }
        Map<String, String> targets = new LinkedHashMap<String, String>();
        Object t = b.get("targets");
        if (t != null && !(t instanceof Map)) {
            throw new BadRequest("targets must be an object: {\"endpoint id\": \"federation or exercise\"}");
        }
        if (t != null) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) t).entrySet()) {
                String v = text(e.getValue());
                if (v != null && !v.isEmpty()) {
                    targets.put(e.getKey(), v);
                }
            }
        }
        Map<String, String> vars = vars(b.get("vars"));
        Map<String, String> lsds = lsds(b.get("lsds"), "lsds");
        try {
            Gateway g = manager.start(configId, targets, vars, text(b.get("instance")), lsds);
            Json.JsonObject r = Json.object().put("success", true).put("gateway", g);
            String warning = manager.sharedTargetWarning(g);
            r.putIfNotNull("warning", warning);
            return new XrResultPair(r.toString(), 201);
        } catch (GatewayException e) {
            int status;
            switch (e.getCode()) {
                case "CONFIG_NOT_FOUND":
                    status = 404;
                    break;
                case "CONFIG_INVALID":
                case "VARIABLES_REQUIRED":
                case "INSTANCE_INVALID":
                case "LSD_INVALID":
                    status = 400;
                    break;
                case "TARGET_CONFLICT":
                case "INSTANCE_RUNNING":
                    status = 409;
                    break;
                case "RTI_NOT_AVAILABLE":
                    status = 503;
                    break;
                default:
                    status = 500;
            }
            return error(status, e.getCode(), e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> vars(Object v) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (v == null) {
            return out;
        }
        if (!(v instanceof Map)) {
            throw new BadRequest("vars must be an object: {\"GROUPVAR1\": \"value\"}");
        }
        for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
            String t = text(e.getValue());
            if (t != null && !t.trim().isEmpty()) {
                out.put(e.getKey().trim(), t.trim());
            }
        }
        return out;
    }

    /**
     * {"endpoint id": "lsd"}: kept as given, "" included (the RTI's default).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> lsds(Object v, String what) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (v == null) {
            return out;
        }
        if (!(v instanceof Map)) {
            throw new BadRequest(what + " must be an object: {\"endpoint id\": \"LSD\"}");
        }
        for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
            if (e.getValue() != null && !(e.getValue() instanceof String)) {
                throw new BadRequest(what + "." + e.getKey() + " must be a string");
            }
            out.put(e.getKey().trim(), e.getValue() == null ? "" : ((String) e.getValue()).trim());
        }
        return out;
    }

    private XrResultPair startGroup(String group, String body) {
        Map<String, Object> b = body == null || body.trim().isEmpty() ? new LinkedHashMap<String, Object>() : jsonBody(body);
        Map<String, String> vars = vars(b.get("vars"));
        Map<String, Map<String, String>> lsds = null;
        Object lo = b.get("lsds");
        if (lo != null) {
            if (!(lo instanceof Map)) {
                throw new BadRequest("lsds must be an object: {\"gateway configuration id\": {\"endpoint id\": \"LSD\"}}");
            }
            lsds = new LinkedHashMap<String, Map<String, String>>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) lo).entrySet()) {
                lsds.put(String.valueOf(e.getKey()).trim(), lsds(e.getValue(), "lsds." + e.getKey()));
            }
        }
        List<JunctionManager.GroupStart> results;
        try {
            results = manager.startGroup(group, vars, text(b.get("instance")), lsds);
        } catch (GatewayException e) {
            return error("INSTANCE_RUNNING".equals(e.getCode()) ? 409 : 400, e.getCode(), e.getMessage());
        }
        if (results.isEmpty()) {
            return error(404, "GROUP_NOT_FOUND", "No gateway configuration is in group '" + group + "'");
        }
        Json.JsonArray a = Json.array();
        int started = 0;
        for (JunctionManager.GroupStart r : results) {
            a.add(r);
            if (r.error == null) {
                started++;
            }
        }
        if (started == 0) {
            StringBuilder sb = new StringBuilder("No gateway of group " + group + " instance " + results.get(0).instance + " started:");
            for (JunctionManager.GroupStart r : results) {
                sb.append(' ').append(r.configId).append(": ").append(r.error.getMessage()).append(';');
            }
            return error(400, "GROUP_START_FAILED", sb.toString());
        }
        Json.JsonObject o = Json.object().put("success", true).put("allstarted", started == results.size()).put("group", group)
                .put("instance", results.get(0).instance)
                .put("started", started).put("total", results.size()).put("results", a);
        return new XrResultPair(o.toString(), started == results.size() ? 201 : 207);
    }

    /** The route tables a gateway of this configuration would have, from its FOMs only. */
    private Json.JsonObject compatibility(GatewayConfig cfg) {
        Map<String, ModelTools> tools = new LinkedHashMap<String, ModelTools>();
        Json.JsonObject errors = Json.object();
        for (EndpointConfig e : cfg.getEndpoints().values()) {
            if (e.getType() != null && e.getType().isJ2j() && !e.isJ2jObjects()) {
                errors.put(e.getId(), "a J2J session passes DIS PDUs as they are: it has no object model");
                continue;
            }
            if (e.getType() != null && e.getType().isKafka() && !e.isJ2jObjects()) {
                errors.put(e.getId(), "a Kafka endpoint in a PDU relay passes DIS PDUs as they are: it has no object model");
                continue;
            }
            if (e.isDynamicFom() && e.getCreateFomPaths().isEmpty()) {
                errors.put(e.getId(), "takes its FOM from the federation when the gateway starts (endpoint.hla.foms=dynamic):"
                        + " open Compatibility on the running gateway");
                continue;
            }
            // a dynamic endpoint with endpoint.hla.createfoms (0.9.21): those modules stand for the federation's FOM
            List<String> foms = e.isDynamicFom() ? e.getCreateFomPaths() : e.getType() != null && (e.getType().isJ2j() || e.getType().isKafka()) ? e.getJ2jFomPaths() : e.getFomPaths();
            try {
                InformationExchangeModel m = e.isHla4() ? InformationExchangeModel.fromHla4(foms)
                        : InformationExchangeModel.fromHlaEvolved(foms);
                tools.put(e.getId(), new ModelTools(m, e.getReferenceTypes()));
            } catch (Exception ex) {
                errors.put(e.getId(), ex.getMessage());
            }
        }
        RouteCompiler compiler = new RouteCompiler(cfg.getRouter().getFilter(), cfg.getRouter().isPromote(), manager.conversions());
        Map<String, com.simweaver.junction.router.ValueMap> values = new LinkedHashMap<String, com.simweaver.junction.router.ValueMap>();
        for (Map.Entry<String, ModelTools> e : tools.entrySet()) {
            try {
                values.put(e.getKey(), com.simweaver.junction.router.ValueMap.compile(cfg.getRouter().getValueRules(), e.getValue(), e.getKey()));
            } catch (IllegalArgumentException ex) {
                errors.put(e.getKey(), "value rules: " + ex.getMessage());
            }
        }
        Json.JsonArray routes = Json.array();
        for (EndpointConfig s : cfg.getEndpoints().values()) {
            for (EndpointConfig d : cfg.getEndpoints().values()) {
                if (s == d || !tools.containsKey(s.getId()) || !tools.containsKey(d.getId())) {
                    continue;
                }
                RouteTable t = compiler.compile(new RouteCompiler.Side(s.getId(), tools.get(s.getId()), s.getInFilter(), s.getOutFilter(),
                                values.get(s.getId())),
                        new RouteCompiler.Side(d.getId(), tools.get(d.getId()), d.getInFilter(), d.getOutFilter()));
                routes.add(t);
            }
        }
        return Json.object().put("config", cfg.getId()).put("routes", routes).put("errors", errors);
    }

    // ------------------------------------------------------------------ helpers

    private static XrResultPair unknownGateway(String id) {
        return error(404, "GATEWAY_UNKNOWN", "No running gateway " + id);
    }

    private static XrResultPair ok(Object json) {
        return new XrResultPair(Json.toJson(json), 200);
    }

    private static XrResultPair notAllowed(String method, String allowed) {
        return error(405, "METHOD_NOT_ALLOWED", method + " is not supported here; use " + allowed);
    }

    /** Rift's error envelope. */
    public static XrResultPair error(int status, String code, String message) {
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
        int h = path.indexOf('#');
        if (h >= 0) {
            path = path.substring(0, h);
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

    private static Map<String, Object> jsonBody(String body) {
        if (body == null || body.trim().isEmpty()) {
            throw new BadRequest("A JSON body is required");
        }
        try {
            return Jsons.fromJsonObject(body);
        } catch (RuntimeException e) {
            throw new BadRequest("The body is not a JSON object: " + e.getMessage());
        }
    }

    private static String text(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal) {
            return ((BigDecimal) v).stripTrailingZeros().toPlainString();
        }
        return v.toString().trim();
    }

    private static final class BadRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BadRequest(String message) {
            super(message, null, false, false);
        }
    }
}
