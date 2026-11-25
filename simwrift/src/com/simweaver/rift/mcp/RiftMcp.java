package com.simweaver.rift.mcp;

import com.simweaver.common.config.SimwProperties;
import com.simweaver.common.logging.Events;
import com.simweaver.common.mcp.McpHttpHandler;
import com.simweaver.common.mcp.McpServer;
import com.simweaver.common.mcp.McpTool;
import com.simweaver.common.rest.ApiCallHandlerWithBody;
import com.simweaver.common.rest.Jsons;
import com.simweaver.common.rest.auth.ApiAuth;
import com.simweaver.rift.DatasourceManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 0.14.0: the server's MCP endpoint, {@code POST /api/mcp}, from the settings:
 *
 * <pre>
 * mcp.enabled=false                  # on: the endpoint answers
 * mcp.tools=                         # the tools offered, ';' or ',' separated; empty = all
 * mcp.ratelimit.perminute=120        # requests per caller (token, or address) and minute; 0 = no limit
 * mcp.maxresultkb=64                 # a larger tool answer becomes a tool error
 * mcp.allowremotewithoutauth=false   # without api.auth.enabled, also answer other machines
 * </pre>
 *
 * Every tool call is logged as event {@code mcp.call} (tool, caller, milliseconds, size, error).
 */
public final class RiftMcp {

    private static final Logger LOG = Logger.getLogger(RiftMcp.class.getName());

    private final McpHttpHandler handler;
    private final String summary;
    private final List<String> problems;

    private RiftMcp(McpHttpHandler handler, String summary, List<String> problems) {
        this.handler = handler;
        this.summary = summary;
        this.problems = problems;
    }

    /** @param api the server's REST API (in process), for the gateway, J2J and Infra tools */
    public static RiftMcp fromSettings(SimwProperties cfg, String serverName, String version, DatasourceManager datasources,
                                       ApiCallHandlerWithBody api, ApiAuth auth) {
        List<String> problems = new ArrayList<>();
        if (!cfg.getBoolean("mcp.enabled", false)) {
            return new RiftMcp(null, "off (mcp.enabled)", problems);
        }
        List<McpTool> all = new RiftMcpTools(datasources, api).tools();
        Set<String> wanted = new LinkedHashSet<>();
        for (String s : cfg.getString("mcp.tools", "").split("[;,]")) {
            if (!s.trim().isEmpty()) {
                wanted.add(s.trim().toLowerCase(Locale.ROOT));
            }
        }
        List<McpTool> tools = new ArrayList<>();
        for (McpTool t : all) {
            if (wanted.isEmpty() || wanted.remove(t.name().toLowerCase(Locale.ROOT))) {
                tools.add(t);
            }
        }
        for (String unknown : wanted) {
            problems.add("mcp.tools: no tool '" + unknown + "'");
        }
        int perMinute = Math.max(0, cfg.getInt("mcp.ratelimit.perminute", 120));
        int maxKb = Math.max(4, cfg.getInt("mcp.maxresultkb", 64));
        boolean remote = cfg.getBoolean("mcp.allowremotewithoutauth", false);
        McpServer server = new McpServer(serverName, version, RiftMcpTools.INSTRUCTIONS, tools, null, maxKb * 1024, RiftMcp::audit);
        McpHttpHandler h = new McpHttpHandler(server, McpHttpHandler.standardGate(auth, remote), perMinute);
        boolean authOn = auth != null && auth.isEnabled();
        String who = authOn ? "API tokens" : remote ? "anyone (no authentication: mcp.allowremotewithoutauth)" : "this machine only (no API authentication)";
        return new RiftMcp(h, "POST /api/mcp, " + tools.size() + " tools, " + who, problems);
    }

    /** @return the handler for /api/mcp, or null when MCP is off */
    public McpHttpHandler handler() {
        return handler;
    }

    /** One line for the console. */
    public String summary() {
        return summary;
    }

    public List<String> problems() {
        return problems;
    }

    private static void audit(String caller, String tool, java.util.Map<String, Object> args, long millis, int bytes, String error) {
        String a = Jsons.toJson(args);
        if (a.length() > 300) {
            a = a.substring(0, 300) + "…";
        }
        Events.log(LOG, error == null ? Level.INFO : Level.WARNING, "mcp.call",
                "MCP " + tool + (error == null ? " (" + millis + " ms, " + bytes + " bytes)" : " failed: " + error),
                "tool", tool, "caller", caller == null ? "" : caller, "arguments", a, "ms", millis, "bytes", bytes,
                "error", error == null ? "" : error);
    }
}
