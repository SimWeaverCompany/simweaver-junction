package com.simweaver.common.mcp;

/**
 * A tool execution error the model can act on (0.14.0): an unknown datasource, a class that does
 * not exist, a value out of range. It becomes a tool result with {@code isError: true} and this
 * message, so the model can correct the call. Protocol errors are not thrown this way.
 */
public class McpToolException extends Exception {

    private static final long serialVersionUID = 1L;

    public McpToolException(String message) {
        super(message, null, false, false);
    }
}
