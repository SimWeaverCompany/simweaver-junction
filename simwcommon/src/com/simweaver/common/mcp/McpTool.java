package com.simweaver.common.mcp;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One MCP tool (0.14.0): its definition as {@code tools/list} shows it, and the code that runs it.
 *
 * <pre>
 * McpTool t = McpTool.named("list_datasources")
 *     .title("Datasources")
 *     .description("The running datasources ...")
 *     .input(McpSchema.object())
 *     .output(McpSchema.object().prop("asof", McpSchema.string("UTC time of the answer")))
 *     .readOnly()
 *     .handler((args, call) -&gt; ...)
 *     .build();
 * </pre>
 *
 * The handler returns the structured result (a JSON-ready map); the server adds the text copy.
 * Handlers must be thread safe: calls arrive on the web server's threads.
 */
public final class McpTool {

    /** What a tool does with a call. */
    @FunctionalInterface
    public interface Handler {
        /**
         * @return the structured result: a map of JSON-ready values
         * @throws McpToolException for errors the model can fix (sent as {@code isError: true})
         */
        Map<String, Object> call(McpArgs args, Call call) throws McpToolException;
    }

    /** The context of one call. */
    public static final class Call {
        private final String caller;
        private final String protocolVersion;
        private final String clientName;
        private final Instant now;

        public Call(String caller, String protocolVersion, String clientName, Instant now) {
            this.caller = caller;
            this.protocolVersion = protocolVersion;
            this.clientName = clientName;
            this.now = now;
        }

        /** @return who API authentication let in ({@code token <name>}), or null (authentication off) */
        public String caller() {
            return caller;
        }

        public String protocolVersion() {
            return protocolVersion;
        }

        /** @return the client's self-reported name, or null */
        public String clientName() {
            return clientName;
        }

        /** @return the time of the call: every answer is as of this moment */
        public Instant now() {
            return now;
        }
    }

    private final String name;
    private final String title;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final Map<String, Object> outputSchema;
    private final Map<String, Object> annotations;
    private final boolean control;
    private final Handler handler;

    private McpTool(Builder b) {
        this.name = b.name;
        this.title = b.title;
        this.description = b.description;
        this.inputSchema = b.input == null ? McpSchema.object().toMap() : b.input.toMap();
        this.outputSchema = b.output == null ? null : b.output.toMap();
        this.annotations = new LinkedHashMap<>(b.annotations);
        this.control = b.control;
        this.handler = b.handler;
    }

    /** @return a builder; names are letters, digits and '_' only (the set every LLM API accepts) */
    public static Builder named(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("tool name must be 1-64 letters, digits or '_': " + name);
        }
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public Map<String, Object> inputSchema() {
        return inputSchema;
    }

    /** @return true for tools that change something (not offered unless allowed) */
    public boolean isControl() {
        return control;
    }

    public Handler handler() {
        return handler;
    }

    /** @return the definition as {@code tools/list} lists it */
    public Map<String, Object> definition() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        if (title != null) {
            m.put("title", title);
        }
        m.put("description", description);
        m.put("inputSchema", inputSchema);
        if (outputSchema != null) {
            m.put("outputSchema", outputSchema);
        }
        if (!annotations.isEmpty()) {
            m.put("annotations", annotations);
        }
        return m;
    }

    /** Builder of {@link McpTool}. */
    public static final class Builder {
        private final String name;
        private String title;
        private String description = "";
        private McpSchema input;
        private McpSchema output;
        private final Map<String, Object> annotations = new LinkedHashMap<>();
        private boolean control;
        private Handler handler;

        private Builder(String name) {
            this.name = name;
        }

        public Builder title(String t) {
            this.title = t;
            return this;
        }

        public Builder description(String d) {
            this.description = Objects.requireNonNull(d);
            return this;
        }

        public Builder input(McpSchema s) {
            this.input = s;
            return this;
        }

        public Builder output(McpSchema s) {
            this.output = s;
            return this;
        }

        /** A tool that only reads: {@code readOnlyHint: true, openWorldHint: false}. */
        public Builder readOnly() {
            annotations.put("readOnlyHint", true);
            annotations.put("openWorldHint", false);
            control = false;
            return this;
        }

        /** A tool that changes something: offered only to callers allowed to control. */
        public Builder control(boolean destructive, boolean idempotent) {
            annotations.put("readOnlyHint", false);
            annotations.put("destructiveHint", destructive);
            annotations.put("idempotentHint", idempotent);
            annotations.put("openWorldHint", false);
            control = true;
            return this;
        }

        public Builder handler(Handler h) {
            this.handler = h;
            return this;
        }

        public McpTool build() {
            if (handler == null) {
                throw new IllegalStateException("tool " + name + " has no handler");
            }
            if (!annotations.containsKey("readOnlyHint")) {
                readOnly();
            }
            return new McpTool(this);
        }
    }
}
