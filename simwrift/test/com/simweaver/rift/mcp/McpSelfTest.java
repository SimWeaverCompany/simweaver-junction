package com.simweaver.rift.mcp;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.dis.DisEntityType;
import com.simweaver.common.dis.DisWriter;
import com.simweaver.common.geo.Ellipsoid;
import com.simweaver.common.geo.EulerAngles;
import com.simweaver.common.geo.Geodetic;
import com.simweaver.common.geo.LocalFrame;
import com.simweaver.common.geo.Vec3;
import com.simweaver.common.mcp.McpHttpHandler;
import com.simweaver.common.mcp.McpSchema;
import com.simweaver.common.mcp.McpServer;
import com.simweaver.common.mcp.McpTool;
import com.simweaver.common.mcp.McpToolException;
import com.simweaver.common.rest.ApiCallHandlerWithBody;
import com.simweaver.common.rest.Jsons;
import com.simweaver.common.rest.XrResultPair;
import com.simweaver.common.rest.XrSseHub;
import com.simweaver.common.rest.auth.ApiAuth;
import com.simweaver.common.rest.auth.ApiTokens;
import com.simweaver.common.siso.SisoEnumerations;
import com.simweaver.rift.DatasourceConfig;
import com.simweaver.rift.DatasourceManager;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The MCP endpoint (0.14.0): the protocol in both eras (2026-07-28 and the 2025 handshake), the
 * header checks, errors, the HTTP transport (methods, Origin, size, rate limit, API tokens, the
 * session cookie refused), and the tools on a live DIS exercise (objects, geometry, interactions)
 * and on gateway, J2J and Infra answers.
 *
 * <pre>java -cp out/classes:out/test com.simweaver.rift.mcp.McpSelfTest [rtdir]</pre>
 */
public final class McpSelfTest {

    private static int checks;
    private static int failures;
    private static final List<String> AUDIT = Collections.synchronizedList(new ArrayList<>());

    private McpSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File rtdir = new File(args.length > 0 ? args[0] : "simwrift/rtdir").getAbsoluteFile();
        SisoEnumerations.setGlobal(SisoEnumerations.load(rtdir.toPath().resolve("etc/enumerations/SISO-REF-010.xml")), null);

        // a DIS 6 datasource on a free port, fed with a small scenario
        int port;
        try (DatagramSocket probe = new DatagramSocket(0)) {
            port = probe.getLocalPort();
        }
        DatasourceManager dsm = new DatasourceManager(new XrSseHub(64, 250));
        SimwProperties p = new SimwProperties();
        p.set("datasource.name", "MCP test");
        p.set("datasource.standard", "dis6");
        p.set("datasource.hla.foms", new File(rtdir, "etc/fom/ieee1516-2010/RPR_FOM_v2.0_Link16_1516-2010.xml").getPath());
        p.set("datasource.dis.interface", "127.0.0.1");
        p.set("datasource.dis.port", String.valueOf(port));
        DatasourceManager.SetupResult sr = dsm.setupDatasource(new DatasourceConfig(1, new File("mcp-test.datasource.settings"), p), null);
        check("DIS datasource started: " + sr.message, sr.dataSource != null);
        scenario(port);
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end && (sr.dataSource.getDatabase().getInstanceCount() < 5
                || sr.dataSource.getDatabase().getLatestInteractionSeq() < 2)) {
            Thread.sleep(50);
        }
        check("scenario received: " + sr.dataSource.getDatabase().getClassCounts(), sr.dataSource.getDatabase().getInstanceCount() >= 5);

        RiftMcpTools rt = new RiftMcpTools(dsm, new CannedApi());
        McpServer server = new McpServer("SimWeaver Junction & Rift Server", "0.14.0", RiftMcpTools.INSTRUCTIONS, withTestTools(rt.tools()),
                null, 64 * 1024, (caller, tool, a, ms, bytes, error) -> AUDIT.add(caller + "|" + tool + "|" + (error == null ? "ok" : error)));
        protocol(server);
        tools(server);
        http(server);
        sr.dataSource.disconnect();
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ================================================================== protocol

    private static void protocol(McpServer s) {
        // legacy handshake
        McpServer.Response r = call(s, rpc(1, "initialize", obj("protocolVersion", "2025-06-18", "capabilities", obj(),
                "clientInfo", obj("name", "t", "version", "1"))), null);
        Map<String, Object> res = result(r);
        check("legacy initialize: the requested version, tools, server info, instructions", r.status == 200
                && "2025-06-18".equals(res.get("protocolVersion")) && map(res.get("capabilities")).containsKey("tools")
                && "SimWeaver Junction & Rift Server".equals(map(res.get("serverInfo")).get("name"))
                && String.valueOf(res.get("instructions")).contains("server_overview") && !res.containsKey("resultType"));
        res = result(call(s, rpc(1, "initialize", obj("protocolVersion", "2024-11-05")), null));
        check("legacy initialize with an older version: our newest legacy one", "2025-11-25".equals(res.get("protocolVersion")));
        r = call(s, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", null);
        check("a notification: 202 without a body", r.status == 202 && r.body == null);
        r = call(s, rpc(2, "tools/list", obj()), h("MCP-Protocol-Version", "2025-06-18"));
        List<Object> tools = list(result(r).get("tools"));
        check("legacy tools/list: 14 tools plus the 2 test tools, no ttl", tools.size() == 16 && !result(r).containsKey("ttlMs"));
        boolean shapes = true;
        for (Object o : tools) {
            Map<String, Object> t = map(o);
            shapes &= String.valueOf(t.get("name")).matches("[A-Za-z0-9_]+") && "object".equals(map(t.get("inputSchema")).get("type"))
                    && !String.valueOf(t.get("description")).isEmpty() && Boolean.TRUE.equals(map(t.get("annotations")).get("readOnlyHint"));
        }
        check("every tool: a plain name, an object input schema, a description, readOnlyHint", shapes);
        check("no header at all is read as 2025-03-26", call(s, rpc(3, "ping", obj()), null).status == 200);
        r = call(s, rpc(3, "tools/list", obj()), h("MCP-Protocol-Version", "2024-11-05"));
        check("an unknown version in the header: -32022 with the supported ones", r.status == 400 && code(r) == -32022
                && list(map(error(r).get("data")).get("supported")).contains(McpServer.MODERN));

        // modern: per-request metadata and headers
        r = call(s, rpc(4, "tools/list", modern(obj())), modernHeaders("tools/list", null));
        res = result(r);
        check("modern tools/list: resultType, ttlMs, cacheScope, serverInfo in _meta", r.status == 200 && "complete".equals(res.get("resultType"))
                && res.get("ttlMs") instanceof Number && "private".equals(res.get("cacheScope"))
                && map(map(res.get("_meta")).get("io.modelcontextprotocol/serverInfo")).get("version").equals("0.14.0"));
        r = call(s, rpc(5, "server/discover", modern(obj())), modernHeaders("server/discover", null));
        check("server/discover: the versions", list(result(r).get("supportedVersions")).containsAll(McpServer.SUPPORTED));
        r = call(s, rpc(6, "tools/list", modern(obj())), h("Mcp-Method", "tools/list"));
        check("modern without MCP-Protocol-Version: HeaderMismatch, 400", r.status == 400 && code(r) == -32020);
        r = call(s, rpc(6, "tools/list", modern(obj())), h("MCP-Protocol-Version", "2025-11-25", "Mcp-Method", "tools/list"));
        check("header and _meta disagree: HeaderMismatch", r.status == 400 && code(r) == -32020);
        Map<String, Object> future = obj("_meta", obj("io.modelcontextprotocol/protocolVersion", "2099-01-01"));
        r = call(s, rpc(6, "tools/list", future), h("MCP-Protocol-Version", "2099-01-01", "Mcp-Method", "tools/list"));
        check("a version we do not know: UnsupportedProtocolVersion", r.status == 400 && code(r) == -32022
                && "2099-01-01".equals(map(error(r).get("data")).get("requested")));
        r = call(s, rpc(6, "tools/list", modern(obj())), modernHeaders("ping", null));
        check("Mcp-Method is not the method: HeaderMismatch", code(r) == -32020);
        r = call(s, rpc(6, "tools/list", obj()), h("MCP-Protocol-Version", McpServer.MODERN));
        check("the modern header without _meta: HeaderMismatch", code(r) == -32020);
        Map<String, Object> callParams = modern(obj("name", "list_datasources", "arguments", obj()));
        r = call(s, rpc(7, "tools/call", callParams), modernHeaders("tools/call", null));
        check("tools/call without Mcp-Name: HeaderMismatch", code(r) == -32020);
        r = call(s, rpc(7, "tools/call", callParams), modernHeaders("tools/call", "other_tool"));
        check("Mcp-Name not the tool: HeaderMismatch", code(r) == -32020);
        r = call(s, rpc(7, "tools/call", callParams), modernHeaders("tools/call",
                "=?base64?" + Base64.getEncoder().encodeToString("list_datasources".getBytes(StandardCharsets.UTF_8)) + "?="));
        check("Mcp-Name in base64 form", r.status == 200 && Boolean.FALSE.equals(result(r).get("isError")));
        r = call(s, rpc(8, "resources/list", modern(obj())), modernHeaders("resources/list", null));
        check("modern: an unknown method is 404 / -32601", r.status == 404 && code(r) == -32601);
        r = call(s, rpc(8, "resources/list", obj()), null);
        check("legacy: an unknown method is 200 / -32601", r.status == 200 && code(r) == -32601);

        // JSON-RPC errors
        check("a batch is refused", call(s, "[" + rpc(1, "ping", obj()) + "]", null).status == 400);
        r = call(s, "{not json", null);
        check("parse error: -32700", r.status == 400 && code(r) == -32700);
        check("jsonrpc must be 2.0", call(s, "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}", null).status == 400);
        check("a client's response is accepted (202)", call(s, "{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{}}", null).status == 202);

        // tool errors
        r = call(s, rpc(9, "tools/call", obj("name", "no_such_tool", "arguments", obj())), null);
        check("unknown tool: -32602", code(r) == -32602);
        r = callTool(s, "find_objects", obj("limit", "lots"));
        check("invalid arguments: a tool error the model can read: " + text(r), Boolean.TRUE.equals(result(r).get("isError"))
                && text(r).startsWith("Invalid arguments") && text(r).contains("limit"));
        r = callTool(s, "find_objects", obj("limit", 9999));
        check("out of range: a tool error", Boolean.TRUE.equals(result(r).get("isError")) && text(r).contains("limit"));
        r = callTool(s, "test_big", obj());
        check("a too large answer: a tool error asking to narrow: " + text(r), Boolean.TRUE.equals(result(r).get("isError"))
                && text(r).contains("too large") && text(r).contains("narrow"));
        r = callTool(s, "test_boom", obj());
        check("a failing tool: a tool error, not a protocol error", r.status == 200 && Boolean.TRUE.equals(result(r).get("isError"))
                && text(r).contains("IllegalStateException"));
        check("the audit saw the calls", AUDIT.stream().anyMatch(a -> a.contains("|test_big|") && a.contains("too large"))
                && AUDIT.stream().anyMatch(a -> a.endsWith("|list_datasources|ok")));
        try {
            new McpServer("x", "1", null, java.util.Arrays.asList(withTestTools(new ArrayList<>()).get(0), withTestTools(new ArrayList<>()).get(0)), null, 1024, null);
            check("two tools with one name are refused", false);
        } catch (IllegalArgumentException e) {
            check("two tools with one name are refused", true);
        }
    }

    // ================================================================== tools

    private static void tools(McpServer s) {
        Map<String, Object> ov = structured(callTool(s, "server_overview", obj()));
        check("server_overview: the datasource, gateways by state, J2J, Infra: " + Jsons.toJson(ov),
                list(ov.get("datasources")).size() == 1 && "MCP test".equals(map(list(ov.get("datasources")).get(0)).get("config"))
                        && map(map(ov.get("gateways")).get("by_state")).containsKey("DEGRADED") && map(ov.get("j2j")).get("state").equals("running")
                        && map(ov.get("infra")).containsKey("counts") && ov.get("asof") instanceof String);

        Map<String, Object> cl = structured(callTool(s, "list_classes", obj()));
        check("list_classes: Aircraft (2) first, interactions WeaponFire and MunitionDetonation: " + cl,
                "Aircraft".equals(map(list(cl.get("object_classes")).get(0)).get("class"))
                        && ((Number) map(list(cl.get("object_classes")).get(0)).get("count")).intValue() == 2
                        && Jsons.toJson(cl.get("interaction_classes")).contains("WeaponFire"));

        Map<String, Object> f = structured(callTool(s, "find_objects", obj("class", "Aircraft")));
        check("find_objects class Aircraft: 2, sorted by label", ((Number) f.get("total")).intValue() == 2
                && "BANDIT2".equals(map(list(f.get("objects")).get(0)).get("label")));
        Map<String, Object> viper = null;
        for (Object o : list(f.get("objects"))) {
            if ("VIPER1".equals(map(o).get("label"))) {
                viper = map(o);
            }
        }
        check("summary: position, heading, speed in m/s and knots, force, entity type code: " + viper, viper != null
                && near(((Number) viper.get("lat")).doubleValue(), 57.5, 0.01) && near(((Number) viper.get("heading_deg")).doubleValue(), 90, 1)
                && near(((Number) viper.get("speed_mps")).doubleValue(), 200, 2) && near(((Number) viper.get("speed_kt")).doubleValue(), 388.8, 4)
                && "friendly".equals(viper.get("force")) && "1.2.225.1.1.0.0".equals(viper.get("entity_type_code")));
        f = structured(callTool(s, "find_objects", obj("class", "platform.groundvehicle")));
        Map<String, Object> cv = list(f.get("objects")).isEmpty() ? obj() : map(list(f.get("objects")).get(0));
        check("a dotted tail in any case; entity type in words and country: " + cv, "CV9040A".equals(cv.get("entity_type"))
                && String.valueOf(cv.get("country")).contains("SWE") && ((Number) cv.get("heading_deg")).doubleValue() == 0.0);
        f = structured(callTool(s, "find_objects", obj()));
        check("without a class, carried objects are left out: " + f.get("total"), ((Number) f.get("total")).intValue() == 3);
        f = structured(callTool(s, "find_objects", obj("include_carried", true)));
        check("include_carried: the emitter system and its beam too", ((Number) f.get("total")).intValue() == 5);
        f = structured(callTool(s, "find_objects", obj("force", "opposing")));
        check("force opposing: BANDIT2", ((Number) f.get("total")).intValue() == 1 && "BANDIT2".equals(map(list(f.get("objects")).get(0)).get("label")));
        f = structured(callTool(s, "find_objects", obj("text", "bandit")));
        check("text bandit: 1", ((Number) f.get("total")).intValue() == 1);
        f = structured(callTool(s, "find_objects", obj("near", obj("object", "VIPER1"), "radius_m", 40000)));
        List<Object> nearRows = list(f.get("objects"));
        double expect = haversine(57.5, 18.5, 57.5, 19.0);
        Map<String, Object> second = nearRows.size() > 1 ? map(nearRows.get(1)) : obj();
        check("near VIPER1 within 40 km, by range: itself, then BANDIT2 at ~" + Math.round(expect) + " m bearing ~90: " + second,
                nearRows.size() == 2 && "VIPER1".equals(map(nearRows.get(0)).get("label")) && "BANDIT2".equals(second.get("label"))
                        && near(((Number) second.get("range_m")).doubleValue(), expect, expect * 0.01)
                        && near(((Number) second.get("bearing_deg")).doubleValue(), 90, 1) && second.get("range_nm") instanceof Number);
        f = structured(callTool(s, "find_objects", obj("near", obj("lat", 57.0, "lon", 18.0), "radius_m", 1000)));
        check("near a point: the vehicle there", ((Number) f.get("total")).intValue() == 1);
        f = structured(callTool(s, "find_objects", obj("include_carried", true, "limit", 2)));
        check("paging: next_offset", ((Number) f.get("returned")).intValue() == 2 && ((Number) f.get("next_offset")).intValue() == 2);
        McpServer.Response r = callTool(s, "find_objects", obj("class", "Submarine"));
        check("an unknown class: a tool error naming the classes there: " + text(r), Boolean.TRUE.equals(result(r).get("isError"))
                && text(r).contains("Aircraft"));
        r = callTool(s, "find_objects", obj("radius_m", 5));
        check("radius without near: a tool error", Boolean.TRUE.equals(result(r).get("isError")));

        Map<String, Object> g = structured(callTool(s, "get_object", obj("object", "viper1")));
        Map<String, Object> attrs = map(g.get("attributes"));
        check("get_object by marking (any case): attributes compact: " + attrs.keySet(), "VIPER1".equals(attrs.get("Marking"))
                && "Friendly".equals(attrs.get("ForceIdentifier")) && attrs.get("Spatial") instanceof Map);
        check("get_object: it carries its emitter system", Jsons.toJson(g.get("carries")).contains("\"relation\":\"hosted\""));
        String vid = String.valueOf(map(g.get("object")).get("id"));
        Map<String, Object> g2 = structured(callTool(s, "get_object", obj("object", vid, "attributes", java.util.Arrays.asList("Marking", "Nope"))));
        check("get_object by id, some attributes; the missing one named", map(g2.get("attributes")).size() == 1
                && list(g2.get("attributes_not_received")).contains("Nope"));
        check("get_object by entity id 1:1:1", "VIPER1".equals(map(structured(callTool(s, "get_object", obj("object", "1:1:1"))).get("object")).get("label")));
        Map<String, Object> em = structured(callTool(s, "find_objects", obj("class", "EmitterSystem")));
        Map<String, Object> emRow = list(em.get("objects")).isEmpty() ? obj() : map(list(em.get("objects")).get(0));
        check("a carried object: carried_by, and the carrier's position: " + emRow, map(emRow.get("carried_by")).get("label") != null
                && "hosted".equals(map(emRow.get("carried_by")).get("relation")) && emRow.get("lat") instanceof Number && emRow.containsKey("position_of"));
        r = callTool(s, "get_object", obj("object", "NOBODY"));
        check("an unknown object: a tool error", Boolean.TRUE.equals(result(r).get("isError")) && text(r).contains("No object"));
        r = callTool(s, "get_object", obj("object", "1"));
        check("an ambiguous part: the candidates with ids: " + text(r), Boolean.TRUE.equals(result(r).get("isError")) && text(r).contains("use the id"));

        Map<String, Object> rel = structured(callTool(s, "relation", obj("from", "VIPER1", "to", "BANDIT2")));
        Map<String, Object> cpa = map(rel.get("cpa"));
        double sec = expect / 400.0;
        check("relation: range ~" + Math.round(expect) + " m, bearing 90, relative 0, closing ~400 m/s, CPA in ~" + Math.round(sec) + " s: " + rel,
                near(((Number) rel.get("range_m")).doubleValue(), expect, expect * 0.01) && near(((Number) rel.get("bearing_deg")).doubleValue(), 90, 1)
                        && near(((Number) rel.get("relative_bearing_deg")).doubleValue(), 0, 1)
                        && near(((Number) rel.get("closing_mps")).doubleValue(), 400, 8) && near(((Number) cpa.get("in_s")).doubleValue(), sec, sec * 0.03)
                        && ((Number) cpa.get("range_m")).doubleValue() < 1500 && cpa.get("at") instanceof String && rel.get("closing_kt") instanceof Number);
        rel = structured(callTool(s, "relation", obj("from", "BANDIT2", "to_point", obj("lat", 57.5, "lon", 18.5, "alt_m", 3000))));
        check("relation to a point: bearing ~270, closing ~200 m/s: " + rel, near(((Number) rel.get("bearing_deg")).doubleValue(), 270, 1)
                && near(((Number) rel.get("closing_mps")).doubleValue(), 200, 5));
        rel = structured(callTool(s, "relation", obj("from", "CV90", "to", "VIPER1")));
        check("relation: elevation up to an aircraft", ((Number) rel.get("elevation_deg")).doubleValue() > 0);

        Map<String, Object> ri = structured(callTool(s, "recent_interactions", obj()));
        List<Object> ix = list(ri.get("interactions"));
        check("recent_interactions: fire and detonation, newest first: " + ri, ix.size() == 2
                && "MunitionDetonation".equals(map(ix.get(0)).get("class")) && "WeaponFire".equals(map(ix.get(1)).get("class")));
        Map<String, Object> fire = map(ix.get(1));
        check("parameters in short form, references with labels: " + fire.get("parameters"),
                Jsons.toJson(fire.get("parameters")).contains("VIPER1") && Jsons.toJson(fire.get("parameters")).contains("BANDIT2"));
        ri = structured(callTool(s, "recent_interactions", obj("involving", "CV90")));
        check("involving an object not in them: none", list(ri.get("interactions")).isEmpty());
        ri = structured(callTool(s, "recent_interactions", obj("class", "WeaponFire")));
        check("by class", list(ri.get("interactions")).size() == 1);
        Map<String, Object> gi = structured(callTool(s, "get_interaction", obj("interaction", fire.get("id"))));
        check("get_interaction: every parameter, entity types in words", map(map(gi.get("interaction")).get("parameters")).size() > 4
                && map(gi.get("interaction")).get("class_qualified") != null);
        r = callTool(s, "get_interaction", obj("interaction", "i999999"));
        check("an interaction not kept: a tool error", Boolean.TRUE.equals(result(r).get("isError")));

        Map<String, Object> ld = structured(callTool(s, "list_datasources", obj()));
        check("list_datasources", list(ld.get("datasources")).size() == 1 && ld.containsKey("configurations"));
        r = callTool(s, "list_classes", obj("datasource", "nope"));
        check("an unknown datasource: a tool error naming the running ones", Boolean.TRUE.equals(result(r).get("isError")) && text(r).contains("MCP test"));

        // gateways, J2J, Infra (canned answers of the REST API)
        Map<String, Object> lg = structured(callTool(s, "list_gateways", obj()));
        check("list_gateways: 2, names with instance", ((Number) lg.get("total")).intValue() == 2
                && Jsons.toJson(lg).contains("\"name\":\"dis-hla-2\""));
        check("list_gateways by state", ((Number) structured(callTool(s, "list_gateways", obj("state", "DEGRADED"))).get("total")).intValue() == 1);
        Map<String, Object> gg = structured(callTool(s, "get_gateway", obj("gateway", "dis-hla-2")));
        String ggs = Jsons.toJson(gg);
        check("get_gateway: no zero counters, no history: " + ggs, ggs.contains("queuedrops") && !ggs.contains("\"transcoded\":0")
                && !ggs.contains("history"));
        r = callTool(s, "get_gateway", obj("gateway", "zzz"));
        check("an unknown gateway: the names", Boolean.TRUE.equals(result(r).get("isError")) && text(r).contains("dis-hla-2"));
        Map<String, Object> hc = structured(callTool(s, "health_check", obj()));
        String hcs = Jsons.toJson(hc);
        check("health_check: degraded gateway, lost endpoint, send errors, queue drops, J2J member, DIS send address: " + hcs,
                hcs.contains("DEGRADED") && hcs.contains("send errors") && hcs.contains("router queue") && hcs.contains("J2J member got-2")
                        && hcs.contains("10.9.9.9") && Boolean.FALSE.equals(hc.get("ok")) && ((Number) map(hc.get("counts")).get("warning")).intValue() > 0);
        hc = structured(callTool(s, "health_check", obj("scope", "datasources")));
        check("health_check datasources only: fine", Boolean.TRUE.equals(hc.get("ok")));
        Map<String, Object> js = structured(callTool(s, "j2j_status", obj()));
        check("j2j_status: members and sessions", list(js.get("members")).size() == 1 && list(js.get("sessions")).size() == 1);
        Map<String, Object> il = structured(callTool(s, "infra_list", obj("kind", "dis")));
        check("infra_list dis: the exercise", ((Number) il.get("returned")).intValue() == 1 && Jsons.toJson(il).contains("dis7-0123456789ab"));
        Map<String, Object> ii = structured(callTool(s, "infra_item", obj("item", "dis7-0123456789ab")));
        check("infra_item: senders, no history", Jsons.toJson(ii).contains("sender_list") && !Jsons.toJson(ii).contains("history"));
        Map<String, Object> ih = structured(callTool(s, "infra_item", obj("item", "Blue")));
        check("infra_item by name: federates with rates", Jsons.toJson(ih).contains("rates_per_s"));
    }

    // ================================================================== HTTP

    private static void http(McpServer s) throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/open/mcp", new McpHttpHandler(s, McpHttpHandler.standardGate(null, false), 0));
        http.createContext("/limited/mcp", new McpHttpHandler(s, McpHttpHandler.standardGate(null, false), 3));
        Path dir = Files.createTempDirectory("simw-mcp");
        Path tf = dir.resolve("api-tokens.settings");
        String tok = ApiTokens.addToFile(tf, "agent", null, null);
        String ui = ApiTokens.addToFile(tf, "ui", null, null);
        ApiAuth auth = new ApiAuth(true, new ApiTokens(tf), 12, "/api", Collections.<String>emptyList());
        List<HttpContext> under = new ArrayList<>();
        under.add(http.createContext("/api/mcp", new McpHttpHandler(s, McpHttpHandler.standardGate(auth, false), 0)));
        under.add(http.createContext("/api/auth", auth.handler()));
        for (HttpContext c : under) {
            c.getFilters().add(auth.filter());
        }
        http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        http.start();
        int port = http.getAddress().getPort();
        HttpClient c = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + port;
        try {
            String ping = rpc(1, "ping", obj());
            HttpResponse<String> r = post(c, base + "/open/mcp", ping, null);
            check("POST: 200 JSON, no-store", r.statusCode() == 200 && r.headers().firstValue("Content-Type").orElse("").startsWith("application/json")
                    && "no-store".equals(r.headers().firstValue("Cache-Control").orElse("")));
            r = c.send(HttpRequest.newBuilder(URI.create(base + "/open/mcp")).GET().build(), HttpResponse.BodyHandlers.ofString());
            check("GET: 405, Allow: POST", r.statusCode() == 405 && "POST".equals(r.headers().firstValue("Allow").orElse("")));
            r = c.send(HttpRequest.newBuilder(URI.create(base + "/open/mcp")).DELETE().build(), HttpResponse.BodyHandlers.ofString());
            check("DELETE: 405 (no sessions)", r.statusCode() == 405);
            r = post(c, base + "/open/mcp", ping, "Origin", "https://evil.example");
            check("another site's page: 403", r.statusCode() == 403);
            r = post(c, base + "/open/mcp", ping, "Origin", "https://127.0.0.1:" + port);
            check("this server's own page: 200", r.statusCode() == 200);
            r = post(c, base + "/open/mcp", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", null);
            check("notification: 202, empty", r.statusCode() == 202 && r.body().isEmpty());
            StringBuilder big = new StringBuilder("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"x\":\"");
            while (big.length() < 1_100_000) {
                big.append("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
            }
            big.append("\"}}");
            r = post(c, base + "/open/mcp", big.toString(), null);
            check("over 1 MB: 413", r.statusCode() == 413);
            Map<String, String> mh = new LinkedHashMap<>();
            mh.put("MCP-Protocol-Version", McpServer.MODERN);
            mh.put("Mcp-Method", "tools/call");
            mh.put("Mcp-Name", "health_check");
            r = post(c, base + "/open/mcp", rpc(2, "tools/call", modern(obj("name", "health_check", "arguments", obj()))), mh);
            check("modern tools/call over HTTP with the headers", r.statusCode() == 200 && r.body().contains("\"structuredContent\""));
            int last = 0;
            for (int i = 0; i < 4; i++) {
                r = post(c, base + "/limited/mcp", ping, null);
                last = r.statusCode();
            }
            check("rate limit: the 4th of 3 per minute is 429 with Retry-After", last == 429 && r.headers().firstValue("Retry-After").isPresent());

            // API tokens
            r = post(c, base + "/api/mcp", ping, null);
            check("auth on, no token: 401", r.statusCode() == 401);
            r = post(c, base + "/api/mcp", ping, "Authorization", "Bearer swt_wrong");
            check("a wrong token: 401", r.statusCode() == 401);
            AUDIT.clear();
            r = post(c, base + "/api/mcp", rpc(3, "tools/call", obj("name", "list_datasources", "arguments", obj())), "Authorization", "Bearer " + tok);
            check("the token: 200, the audit names it: " + AUDIT, r.statusCode() == 200 && AUDIT.contains("token agent|list_datasources|ok"));
            HttpResponse<String> in = c.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/session")).header("Content-Type", "application/json")
                    .header("Origin", "https://127.0.0.1:" + port)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"" + ui + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
            String cookie = in.headers().firstValue("Set-Cookie").orElse(";");
            cookie = cookie.substring(0, cookie.indexOf(';'));
            Map<String, String> ch = new LinkedHashMap<>();
            ch.put("Cookie", cookie);
            ch.put("Origin", "https://127.0.0.1:" + port);
            r = post(c, base + "/api/mcp", ping, ch);
            check("the web UI's session cookie is refused: 401 Bearer (signed in: " + in.statusCode() + ")", in.statusCode() == 200
                    && r.statusCode() == 401 && r.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Bearer"));
        } finally {
            http.stop(0);
        }
    }

    // ================================================================== the scenario

    private static void scenario(int port) throws Exception {
        try (DatagramSocket udp = new DatagramSocket()) {
            List<byte[]> pdus = new ArrayList<>();
            pdus.add(entity(1, 1, 1, new DisEntityType(1, 2, 225, 1, 1, 0, 0), "VIPER1", 57.5, 18.5, 3000, 90, 200));
            pdus.add(entity(1, 1, 2, new DisEntityType(1, 2, 222, 1, 1, 0, 0), "BANDIT2", 57.5, 19.0, 3000, 270, 200));
            pdus.add(entity(1, 1, 3, new DisEntityType(1, 1, 205, 2, 1, 5, 0), "CV90", 57.0, 18.0, 10, 0, 0));
            pdus.add(emission(1, 1));
            pdus.add(fire());
            pdus.add(detonation());
            for (byte[] pdu : pdus) {
                udp.send(new DatagramPacket(pdu, pdu.length, InetAddress.getLoopbackAddress(), port));
                Thread.sleep(20);
            }
        }
    }

    private static byte[] entity(int site, int app, int number, DisEntityType type, String marking, double lat, double lon, double alt,
                                 double headingDeg, double speed) {
        int force = marking.startsWith("BANDIT") ? 2 : 1;
        Geodetic g = Geodetic.ofDegrees(lat, lon, alt);
        Vec3 pos = Ellipsoid.WGS84.toEcef(lat, lon, alt);
        LocalFrame f = LocalFrame.at(g);
        double h = Math.toRadians(headingDeg);
        Vec3 v = f.fromNed(Vec3.of(speed * Math.cos(h), speed * Math.sin(h), 0));
        EulerAngles o = f.toEcef(EulerAngles.ofDegrees(headingDeg, 0, 0));
        DisWriter w = new DisWriter(144).header(6, 1, 1, 1, 0, 0);
        w.entityId(site, app, number).u8(force).u8(0).entityType(type).zeros(8);
        w.f32((float) v.getX()).f32((float) v.getY()).f32((float) v.getZ());
        w.f64(pos.getX()).f64(pos.getY()).f64(pos.getZ());
        w.f32((float) o.getPsi()).f32((float) o.getTheta()).f32((float) o.getPhi());
        w.u32(0).u8(2).zeros(15).zeros(24).u8(1).text(marking, 11).u32(0);     // dead reckoning FPW
        return w.finish();
    }

    /** One emitter system with one search beam on entity 1-1-1. */
    private static byte[] emission(int site, int number) {
        int sysLen = 20 + 52;
        DisWriter w = new DisWriter(28 + sysLen).header(6, 1, 23, 6, 0, 0);
        w.entityId(site, 1, number).entityId(site, 1, 77);
        w.u8(0).u8(1).u16(0);
        w.u8(sysLen / 4).u8(1).u16(0);
        w.u16(9200).u8(2).u8(1).f32x3(new float[] {0, 0, -5});
        w.u8(52 / 4).u8(1).u16(0);
        w.f32(3.1e9f).f32(0).f32(70).f32(1000).f32(1);
        w.f32(0).f32((float) Math.toRadians(30)).f32(0).f32((float) Math.toRadians(20)).f32(0);
        w.u8(1).u8(0).u8(0).u8(0).u32(0);
        return w.finish();
    }

    private static final DisEntityType MISSILE = new DisEntityType(2, 2, 225, 1, 1, 0, 0);

    private static byte[] fire() {
        Vec3 p = Ellipsoid.WGS84.toEcef(57.5, 18.5, 3000);
        DisWriter w = new DisWriter(96).header(6, 1, 2, 2, 0, 0);
        w.entityId(1, 1, 1).entityId(1, 1, 2).entityId(1, 1, 500).entityId(1, 1, 42).u32(0);
        w.f64(p.getX()).f64(p.getY()).f64(p.getZ());
        w.entityType(MISSILE).u16(1000).u16(1000).u16(1).u16(0);
        w.f32(300).f32(0).f32(0).f32(20_000);
        return w.finish();
    }

    private static byte[] detonation() {
        Vec3 p = Ellipsoid.WGS84.toEcef(57.5, 19.0, 3000);
        DisWriter w = new DisWriter(104).header(6, 1, 3, 2, 0, 0);
        w.entityId(1, 1, 1).entityId(1, 1, 2).entityId(1, 1, 500).entityId(1, 1, 42);
        w.f32(300).f32(0).f32(0);
        w.f64(p.getX()).f64(p.getY()).f64(p.getZ());
        w.entityType(MISSILE).u16(1000).u16(1000).u16(1).u16(0);
        w.f32(0).f32(0).f32(0).u8(1).u8(0).u16(0);         // result 1: entity impact
        return w.finish();
    }

    // ================================================================== canned REST answers

    /** The REST API's answers for gateways, J2J and Infra, as the documentation shows them. */
    private static final class CannedApi implements ApiCallHandlerWithBody {
        @Override
        public XrResultPair handleCallToJson(String method, String uri, String route, Map<String, String> q, String body) {
            switch (uri) {
                case "/api/junction/gateways":
                    return new XrResultPair("{\"gateways\":[" + GW1 + "," + GW2 + "]}", 200);
                case "/api/j2j":
                    return new XrResultPair("{\"state\":\"running\",\"problems\":[],\"self\":{\"id\":\"sto-1\",\"name\":\"Stockholm\"},"
                            + "\"network\":{\"id\":\"net\",\"name\":\"N\",\"members\":2,\"known\":2,\"agree\":2},\"listen\":{\"tcp\":8844,\"problems\":[]},"
                            + "\"counts\":{\"direct\":0,\"routed\":0,\"notreachable\":1,\"sessions\":1}}", 200);
                case "/api/j2j/members":
                    return new XrResultPair("{\"members\":[{\"id\":\"sto-1\",\"self\":true,\"state\":\"self\"},"
                            + "{\"id\":\"got-2\",\"name\":\"Gothenburg\",\"self\":false,\"state\":\"waiting\",\"statemessage\":\"connection refused\",\"retryseconds\":4,"
                            + "\"memberlist\":\"same\",\"rttms\":null}]}", 200);
                case "/api/j2j/sessions":
                    return new XrResultPair("{\"sessions\":[{\"name\":\"north\",\"kind\":\"pdu\",\"model\":\"DIS 7, exercise 1\",\"members\":["
                            + "{\"node\":\"sto-1\",\"gateway\":\"dis-j2j\",\"instance\":\"\",\"endpoint\":\"j2j\",\"model\":\"DIS 7, exercise 1\",\"state\":\"active\",\"local\":true}],"
                            + "\"since\":\"2026-11-23T08:00:00Z\"}]}", 200);
                case "/api/infra":
                    return new XrResultPair("{\"enabled\":true,\"now\":" + System.currentTimeMillis() + ",\"dis6\":[],\"dis7\":[" + DIS_ITEM + "],\"cdis\":[],"
                            + "\"hla3\":[" + HLA_ITEM + "],\"hla4\":[],\"counts\":{\"dis6\":0,\"dis7\":1,\"cdis\":0,\"hla3\":1,\"hla4\":0},\"listeners\":1,\"federates\":1,\"sources\":2}", 200);
                case "/api/infra/items/dis7-0123456789ab":
                    return new XrResultPair("{\"item\":" + DIS_ITEM.substring(0, DIS_ITEM.length() - 1)
                            + ",\"senderlist\":[{\"address\":\"192.0.2.10\",\"port\":3000,\"pdus\":12,"
                            + "\"siteapps\":[{\"site\":1,\"app\":1,\"pdus\":12}],\"history\":[1,2,3]}],\"pdutypes\":[{\"type\":1,\"name\":\"Entity State\",\"pdus\":12}]}}", 200);
                case "/api/infra/items/hla3-0123456789ab":
                    return new XrResultPair("{\"item\":" + HLA_ITEM.substring(0, HLA_ITEM.length() - 1)
                            + ",\"federatelist\":[{\"name\":\"BlueSim\",\"type\":\"Tester\",\"host\":\"h\",\"state\":\"active\",\"momrates\":{\"updatessent\":11.0},"
                            + "\"observed\":{\"updates\":62,\"history\":[1]}}]}}", 200);
                default:
                    return new XrResultPair("{\"error\":\"not found\"}", 404);
            }
        }
    }

    private static final String DIS_ITEM = "{\"key\":\"dis7-0123456789ab\",\"family\":\"dis7\",\"protocol\":\"DIS 7\",\"name\":\"Exercise 1\","
            + "\"source\":{\"kind\":\"rift\",\"label\":\"Rift: DIS7\"},\"rate\":10.0,\"history\":[1,2],\"last\":" + System.currentTimeMillis()
            + ",\"status\":\"active\",\"exercise\":1,\"port\":3000,\"senders\":1,\"entities\":3}";
    private static final String HLA_ITEM = "{\"key\":\"hla3-0123456789ab\",\"family\":\"hla3\",\"protocol\":\"HLA Evolved\",\"name\":\"Blue\","
            + "\"source\":{\"kind\":\"junction\",\"label\":\"Junction: dis-hla / hla\"},\"rate\":3.0,\"history\":[1],\"last\":0,\"status\":\"joined\","
            + "\"federation\":\"Blue\",\"federates\":2,\"mom\":\"ok\"}";
    private static final String GW1 = "{\"id\":\"g1\",\"configid\":\"dis-hla\",\"name\":\"DIS - HLA\",\"state\":\"RUNNING\",\"message\":null,"
            + "\"created\":\"2026-11-23T08:00:00Z\",\"router\":\"iem\",\"group\":\"\",\"instance\":\"1\",\"vars\":{},"
            + "\"endpoints\":[{\"id\":\"dis\",\"standardlabel\":\"DIS 7\",\"target\":\"1\",\"state\":\"JOINED\",\"flowingin\":true,\"flowingout\":true,"
            + "\"stats\":{\"in\":{\"messages\":5,\"rate\":2.0},\"out\":{\"messages\":5,\"rate\":2.0},\"senderrors\":0}}],"
            + "\"objects\":{\"bridged\":3,\"copies\":3,\"pending\":0,\"failed\":0},\"routerstats\":{\"updatesrouted\":10,\"transcoded\":0,\"queuedrops\":0,\"problems\":[]},\"queue\":0}";
    private static final String GW2 = "{\"id\":\"g2\",\"configid\":\"dis-hla\",\"name\":\"DIS - HLA\",\"state\":\"DEGRADED\",\"message\":null,"
            + "\"created\":\"2026-11-23T08:00:00Z\",\"router\":\"iem\",\"group\":\"\",\"instance\":\"2\",\"vars\":{\"GROUPVAR1\":\"Blue\"},"
            + "\"endpoints\":[{\"id\":\"dis\",\"standardlabel\":\"DIS 7\",\"target\":\"2\",\"state\":\"JOINED\",\"flowingin\":true,\"flowingout\":false,"
            + "\"library\":\"/x\",\"stats\":{\"in\":{\"messages\":5,\"rate\":2.0,\"bytes\":0},\"out\":{\"messages\":0,\"rate\":0.0},\"senderrors\":7,\"registrationfailures\":0},"
            + "\"dis\":{\"sendaddresses\":[{\"address\":\"10.9.9.9:3000\",\"sent\":0,\"errors\":7,\"lasterror\":\"No route to host\"}]}},"
            + "{\"id\":\"hla\",\"standardlabel\":\"HLA Evolved\",\"target\":\"Blue\",\"state\":\"RECONNECTING\",\"message\":\"connection lost\","
            + "\"flowingin\":false,\"flowingout\":false,\"stats\":{\"in\":{},\"out\":{}}}],"
            + "\"objects\":{\"bridged\":0,\"copies\":0,\"pending\":0,\"failed\":0},"
            + "\"routerstats\":{\"updatesrouted\":3,\"transcoded\":0,\"queuedrops\":12,\"conversionfailures\":0,\"problems\":[],\"history\":[1]},\"queue\":0}";

    // ================================================================== helpers

    private static List<McpTool> withTestTools(List<McpTool> tools) {
        List<McpTool> l = new ArrayList<>(tools);
        l.add(McpTool.named("test_big").description("A large answer").readOnly().handler((a, c) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            StringBuilder b = new StringBuilder();
            while (b.length() < 100_000) {
                b.append("0123456789");
            }
            m.put("x", b.toString());
            return m;
        }).build());
        l.add(McpTool.named("test_boom").description("Fails").input(McpSchema.object()).readOnly().handler((a, c) -> {
            if (a != null) {
                throw new IllegalStateException("boom");
            }
            throw new McpToolException("never");
        }).build());
        return l;
    }

    private static McpServer.Response call(McpServer s, String body, Map<String, String> headers) {
        Map<String, String> lower = new HashMap<>();
        if (headers != null) {
            headers.forEach((k, v) -> lower.put(k.toLowerCase(java.util.Locale.ROOT), v));
        }
        Function<String, String> h = name -> lower.get(name.toLowerCase(java.util.Locale.ROOT));
        return s.handle(new McpServer.Request(body, h, null));
    }

    private static McpServer.Response callTool(McpServer s, String name, Map<String, Object> args) {
        return call(s, rpc(100, "tools/call", obj("name", name, "arguments", args)), h("MCP-Protocol-Version", "2025-11-25"));
    }

    private static HttpResponse<String> post(HttpClient c, String url, String body, String headerName, String headerValue) throws Exception {
        Map<String, String> h = new LinkedHashMap<>();
        if (headerName != null) {
            h.put(headerName, headerValue);
        }
        return post(c, url, body, h);
    }

    private static HttpResponse<String> post(HttpClient c, String url, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream").POST(HttpRequest.BodyPublishers.ofString(body));
        if (headers != null) {
            headers.forEach(b::header);
        }
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, String> modernHeaders(String method, String name) {
        Map<String, String> m = h("MCP-Protocol-Version", McpServer.MODERN, "Mcp-Method", method);
        if (name != null) {
            m.put("Mcp-Name", name);
        }
        return m;
    }

    private static Map<String, Object> modern(Map<String, Object> params) {
        params.put("_meta", obj("io.modelcontextprotocol/protocolVersion", McpServer.MODERN,
                "io.modelcontextprotocol/clientInfo", obj("name", "selftest", "version", "1"), "io.modelcontextprotocol/clientCapabilities", obj()));
        return params;
    }

    private static Map<String, String> h(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String rpc(Object id, String method, Map<String, Object> params) {
        return Jsons.toJson(obj("jsonrpc", "2.0", "id", id, "method", method, "params", params));
    }

    private static Map<String, Object> body(McpServer.Response r) {
        return r.body == null ? obj() : map(Jsons.fromJson(r.body));
    }

    private static Map<String, Object> result(McpServer.Response r) {
        return map(body(r).get("result"));
    }

    private static Map<String, Object> error(McpServer.Response r) {
        return map(body(r).get("error"));
    }

    private static int code(McpServer.Response r) {
        Object c = error(r).get("code");
        return c instanceof Number ? ((Number) c).intValue() : 0;
    }

    private static String text(McpServer.Response r) {
        List<Object> content = list(result(r).get("content"));
        return content.isEmpty() ? "" : String.valueOf(map(content.get(0)).get("text"));
    }

    private static Map<String, Object> structured(McpServer.Response r) {
        Map<String, Object> res = result(r);
        if (Boolean.TRUE.equals(res.get("isError"))) {
            System.out.println("  (tool error: " + text(r) + ")");
        }
        return map(res.get("structuredContent"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<>();
    }

    private static boolean near(double a, double b, double tol) {
        return Math.abs(a - b) <= tol;
    }

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000 * 1.0010;     // about the WGS84 radius at 57.5° N (between the mean radius and the semi-major axis)
        double dl = Math.toRadians(lat2 - lat1);
        double dn = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dl / 2) * Math.sin(dl / 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dn / 2) * Math.sin(dn / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (ok) {
            System.out.println("  ok   " + what);
        } else {
            failures++;
            System.out.println("  FAIL " + what);
        }
    }
}
