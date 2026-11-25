package com.simweaver.common.mcp;

import com.simweaver.common.rest.Jsons;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An MCP (Model Context Protocol) server's message handling (0.14.0), independent of HTTP: one
 * JSON-RPC message in, one response out. {@link McpHttpHandler} puts it on an HTTP endpoint.
 *
 * <p><b>Two eras</b> (the MCP specification's terms), served side by side and statelessly:
 * <ul>
 *   <li><b>Modern</b>, revision {@code 2026-07-28}: no handshake; every request carries its
 *       protocol version, client info and capabilities in {@code params._meta}, and on HTTP the
 *       headers {@code MCP-Protocol-Version}, {@code Mcp-Method} and (for {@code tools/call}
 *       and the like) {@code Mcp-Name}, which must match the body ({@code HeaderMismatch},
 *       -32020, HTTP 400). Results carry {@code resultType} and the server's identity in
 *       {@code _meta}; {@code server/discover} is answered.</li>
 *   <li><b>Legacy</b>, revisions {@code 2025-11-25}, {@code 2025-06-18} and
 *       {@code 2025-03-26}: an {@code initialize} handshake. No session id is assigned (the
 *       revisions allow that), so a legacy client's later requests are served like any other.</li>
 * </ul>
 *
 * Capabilities: {@code tools} only (the list does not change at run time). Batches are refused.
 * Thread safe and stateless.
 */
public final class McpServer {

    /** The modern revision. */
    public static final String MODERN = "2026-07-28";
    /** The legacy revisions answered, newest first. */
    public static final List<String> LEGACY = Collections.unmodifiableList(Arrays.asList("2025-11-25", "2025-06-18", "2025-03-26"));
    /** Every revision answered, newest first. */
    public static final List<String> SUPPORTED;

    static {
        List<String> s = new ArrayList<>();
        s.add(MODERN);
        s.addAll(LEGACY);
        SUPPORTED = Collections.unmodifiableList(s);
    }

    static final String META_VERSION = "io.modelcontextprotocol/protocolVersion";
    static final String META_CLIENT = "io.modelcontextprotocol/clientInfo";
    static final String META_SERVER = "io.modelcontextprotocol/serverInfo";

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    public static final int HEADER_MISMATCH = -32020;
    public static final int UNSUPPORTED_VERSION = -32022;

    private static final Logger LOG = Logger.getLogger(McpServer.class.getName());

    /** Who may see and call which tool. */
    @FunctionalInterface
    public interface ToolAccess {
        /** @param caller who API authentication let in, or null */
        boolean allowed(McpTool tool, String caller);
    }

    /** Told about every {@code tools/call}. */
    @FunctionalInterface
    public interface Audit {
        /** @param error null when the call succeeded, else the message sent to the model */
        void toolCall(String caller, String tool, Map<String, Object> arguments, long millis, int resultBytes, String error);
    }

    /** One message as the transport received it. */
    public static final class Request {
        final String body;
        final Function<String, String> header;
        final String caller;

        /**
         * @param body   the JSON-RPC message
         * @param header HTTP request header by name (case-insensitive), null when absent
         * @param caller who API authentication let in, or null
         */
        public Request(String body, Function<String, String> header, String caller) {
            this.body = body;
            this.header = header == null ? h -> null : header;
            this.caller = caller;
        }
    }

    /** The answer: an HTTP status and a JSON body, or no body (202). */
    public static final class Response {
        public final int status;
        public final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private final String serverName;
    private final String serverVersion;
    private final String instructions;
    private final List<McpTool> tools;
    private final ToolAccess access;
    private final int maxResultBytes;
    private final Audit audit;

    /**
     * @param tools          in the order {@code tools/list} shows them (deterministic)
     * @param access         null: every read-only tool for everyone, control tools for no one
     * @param maxResultBytes a tool answer larger than this (as JSON) becomes a tool error
     * @param audit          may be null
     */
    public McpServer(String serverName, String serverVersion, String instructions, List<McpTool> tools,
                     ToolAccess access, int maxResultBytes, Audit audit) {
        this.serverName = serverName;
        this.serverVersion = serverVersion;
        this.instructions = instructions;
        this.tools = Collections.unmodifiableList(new ArrayList<>(tools));
        this.access = access != null ? access : (t, c) -> !t.isControl();
        this.maxResultBytes = maxResultBytes;
        this.audit = audit;
        java.util.Set<String> names = new java.util.HashSet<>();
        for (McpTool t : tools) {
            if (!names.add(t.name())) {
                throw new IllegalArgumentException("two tools are called " + t.name());
            }
        }
    }

    /** @return every tool, also those a caller may not see */
    public List<McpTool> tools() {
        return tools;
    }

    // ------------------------------------------------------------------ one message

    /** Handles one message. Never throws. */
    public Response handle(Request req) {
        Object parsed;
        try {
            parsed = Jsons.fromJson(req.body == null ? "" : req.body);
        } catch (RuntimeException e) {
            return error(400, null, PARSE_ERROR, "Parse error: " + e.getMessage(), null);
        }
        if (parsed instanceof List) {
            return error(400, null, INVALID_REQUEST, "JSON-RPC batches are not supported: send one message per request", null);
        }
        if (!(parsed instanceof Map)) {
            return error(400, null, INVALID_REQUEST, "The body must be a JSON-RPC message (an object)", null);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> msg = (Map<String, Object>) parsed;
        Object idObj = msg.get("id");
        boolean hasId = idObj != null;
        Object methodObj = msg.get("method");
        if (!(methodObj instanceof String)) {
            if (msg.containsKey("result") || msg.containsKey("error")) {
                return new Response(202, null);                 // a response from a client: nothing to do
            }
            return error(400, hasId ? idObj : null, INVALID_REQUEST, "A JSON-RPC request needs a method", null);
        }
        String method = (String) methodObj;
        if (!"2.0".equals(msg.get("jsonrpc"))) {
            return error(400, hasId ? idObj : null, INVALID_REQUEST, "jsonrpc must be \"2.0\"", null);
        }
        if (!hasId) {
            return new Response(202, null);                     // notifications (initialized, cancelled ...)
        }
        Map<String, Object> params = map(msg.get("params"));
        Map<String, Object> meta = map(params.get("_meta"));
        Object metaVersion = meta.get(META_VERSION);
        String headerVersion = trimmed(req.header.apply("MCP-Protocol-Version"));

        String era;
        if (metaVersion != null) {                              // modern: per-request metadata
            String mv = String.valueOf(metaVersion);
            if (headerVersion == null) {
                return error(400, idObj, HEADER_MISMATCH, "The MCP-Protocol-Version header is missing", null);
            }
            if (!headerVersion.equals(mv)) {
                return error(400, idObj, HEADER_MISMATCH, "Header mismatch: MCP-Protocol-Version '" + headerVersion
                        + "' does not match _meta " + META_VERSION + " '" + mv + "'", null);
            }
            if (!MODERN.equals(mv)) {
                return unsupported(idObj, mv);
            }
            String hm = trimmed(req.header.apply("Mcp-Method"));
            if (hm == null) {
                return error(400, idObj, HEADER_MISMATCH, "The Mcp-Method header is missing", null);
            }
            if (!hm.equals(method)) {
                return error(400, idObj, HEADER_MISMATCH, "Header mismatch: Mcp-Method '" + hm + "' does not match method '" + method + "'", null);
            }
            if (method.equals("tools/call") || method.equals("resources/read") || method.equals("prompts/get")) {
                Object nv = method.equals("resources/read") ? params.get("uri") : params.get("name");
                String hn = req.header.apply("Mcp-Name");
                if (hn == null) {
                    return error(400, idObj, HEADER_MISMATCH, "The Mcp-Name header is missing", null);
                }
                String decoded = decodeHeaderValue(hn);
                if (decoded == null) {
                    return error(400, idObj, HEADER_MISMATCH, "The Mcp-Name header is not valid", null);
                }
                if (nv == null || !decoded.equals(String.valueOf(nv))) {
                    return error(400, idObj, HEADER_MISMATCH, "Header mismatch: Mcp-Name '" + decoded + "' does not match the body's '" + nv + "'", null);
                }
            }
            era = MODERN;
        } else if (method.equals("initialize")) {
            era = null;                                          // chosen by the handshake
        } else if (headerVersion != null) {
            if (MODERN.equals(headerVersion)) {
                return error(400, idObj, HEADER_MISMATCH, "A " + MODERN + " request carries " + META_VERSION + " in params._meta", null);
            }
            if (!LEGACY.contains(headerVersion)) {
                return unsupported(idObj, headerVersion);
            }
            era = headerVersion;
        } else {
            era = "2025-03-26";                                  // the revision before the header existed
        }

        Map<String, Object> client = map(meta.get(META_CLIENT));
        String clientName = client.get("name") == null ? null : String.valueOf(client.get("name"));
        boolean modern = MODERN.equals(era);
        try {
            switch (method) {
                case "initialize":
                    return initialize(idObj, params);
                case "ping":
                    return result(idObj, new LinkedHashMap<>(), modern);
                case "server/discover":
                    return result(idObj, discover(), modern);
                case "tools/list":
                    return result(idObj, toolsList(req.caller, modern), modern);
                case "tools/call":
                    return toolsCall(idObj, params, req.caller, era, clientName, modern);
                default:
                    return error(modern ? 404 : 200, idObj, METHOD_NOT_FOUND, "Method not found: " + method, null);
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "mcp: " + method + " failed", e);
            return error(500, idObj, INTERNAL_ERROR, "Internal error", null);
        }
    }

    // ------------------------------------------------------------------ methods

    private Response initialize(Object id, Map<String, Object> params) {
        Object requested = params.get("protocolVersion");
        String version = requested != null && LEGACY.contains(String.valueOf(requested)) ? String.valueOf(requested) : LEGACY.get(0);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("protocolVersion", version);
        r.put("capabilities", capabilities());
        r.put("serverInfo", serverInfo());
        if (instructions != null) {
            r.put("instructions", instructions);
        }
        return result(id, r, false);
    }

    private Map<String, Object> discover() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("supportedVersions", SUPPORTED);
        r.put("capabilities", capabilities());
        if (instructions != null) {
            r.put("instructions", instructions);
        }
        r.put("ttlMs", 3_600_000L);
        r.put("cacheScope", "private");
        return r;
    }

    private Map<String, Object> toolsList(String caller, boolean modern) {
        List<Object> list = new ArrayList<>();
        for (McpTool t : tools) {
            if (access.allowed(t, caller)) {
                list.add(t.definition());
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tools", list);
        if (modern) {
            r.put("ttlMs", 300_000L);
            r.put("cacheScope", "private");
        }
        return r;
    }

    private Response toolsCall(Object id, Map<String, Object> params, String caller, String era, String clientName, boolean modern) {
        Object nameObj = params.get("name");
        String name = nameObj == null ? null : String.valueOf(nameObj);
        McpTool tool = null;
        for (McpTool t : tools) {
            if (t.name().equals(name) && access.allowed(t, caller)) {
                tool = t;
                break;
            }
        }
        if (tool == null) {
            return error(200, id, INVALID_PARAMS, "Unknown tool: " + name, null);
        }
        Object argsObj = params.get("arguments");
        if (argsObj != null && !(argsObj instanceof Map)) {
            return error(200, id, INVALID_PARAMS, "arguments must be an object", null);
        }
        Map<String, Object> args = map(argsObj);
        long t0 = System.nanoTime();
        List<String> problems = McpSchema.check(tool.inputSchema(), args);
        if (!problems.isEmpty()) {
            String msg = "Invalid arguments: " + String.join("; ", problems) + ".";
            audit(caller, name, args, t0, 0, msg);
            return result(id, toolError(msg), modern);
        }
        Map<String, Object> structured;
        try {
            structured = tool.handler().call(new McpArgs(args), new McpTool.Call(caller, era, clientName, Instant.now()));
        } catch (McpToolException e) {
            audit(caller, name, args, t0, 0, e.getMessage());
            return result(id, toolError(e.getMessage()), modern);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "mcp: tool " + name + " failed", e);
            String msg = "The tool failed on the server: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            audit(caller, name, args, t0, 0, msg);
            return result(id, toolError(msg), modern);
        }
        if (structured == null) {
            structured = new LinkedHashMap<>();
        }
        String text = Jsons.toJson(structured);
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxResultBytes) {
            String msg = "The answer is too large (" + (bytes / 1024) + " KB, at most " + (maxResultBytes / 1024)
                    + " KB): narrow the filter or lower the limit.";
            audit(caller, name, args, t0, bytes, msg);
            return result(id, toolError(msg), modern);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        List<Object> content = new ArrayList<>();
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        content.add(block);
        r.put("content", content);
        r.put("structuredContent", structured);
        r.put("isError", false);
        audit(caller, name, args, t0, bytes, null);
        return result(id, r, modern);
    }

    private void audit(String caller, String tool, Map<String, Object> args, long t0, int bytes, String error) {
        if (audit != null) {
            try {
                audit.toolCall(caller, tool, args, (System.nanoTime() - t0) / 1_000_000L, bytes, error);
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "mcp: audit failed", e);
            }
        }
    }

    private static Map<String, Object> toolError(String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        List<Object> content = new ArrayList<>();
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", message);
        content.add(block);
        r.put("content", content);
        r.put("isError", true);
        return r;
    }

    private Map<String, Object> capabilities() {
        Map<String, Object> caps = new LinkedHashMap<>();
        Map<String, Object> tl = new LinkedHashMap<>();
        tl.put("listChanged", false);
        caps.put("tools", tl);
        return caps;
    }

    private Map<String, Object> serverInfo() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", serverName);
        s.put("version", serverVersion);
        return s;
    }

    // ------------------------------------------------------------------ JSON-RPC envelopes

    private Response result(Object id, Map<String, Object> result, boolean modern) {
        if (modern) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("resultType", "complete");
            r.putAll(result);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put(META_SERVER, serverInfo());
            r.put("_meta", meta);
            result = r;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return new Response(200, Jsons.toJson(m));
    }

    private Response unsupported(Object id, String requested) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("supported", SUPPORTED);
        data.put("requested", requested);
        return error(400, id, UNSUPPORTED_VERSION, "Unsupported protocol version", data);
    }

    private Response error(int status, Object id, int code, String message, Object data) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        if (data != null) {
            err.put("data", data);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", err);
        return new Response(status, Jsons.toJson(m));
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Collections.<String, Object>emptyMap();
    }

    private static String trimmed(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * @return the header value, decoded when it uses the {@code =?base64?…?=} form; null when that
     *         form is not valid Base64
     */
    static String decodeHeaderValue(String v) {
        String s = v.trim();
        if (s.startsWith("=?base64?") && s.endsWith("?=") && s.length() >= 11) {
            try {
                return new String(Base64.getDecoder().decode(s.substring(9, s.length() - 2)), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return s;
    }
}
