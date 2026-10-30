package com.simweaver.common.rest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class XrRequestHandler implements HttpHandler {

    /** Default maximum request body size: 1 MiB. */
    public static final int DEFAULT_MAX_REQUEST_BODY_BYTES = 1024 * 1024;

    /**
     * Before 0.0.7 the request URI was passed to the handler in lower case, which broke
     * case-sensitive ids in paths. Set the system property {@code simweaver.rest.lowercaseUri=true}
     * to get the old behaviour back for handlers that rely on it.
     */
    public static final String LOWERCASE_URI_PROPERTY = "simweaver.rest.lowercaseUri";

    private final ApiCallHandler apiCallHandler;
    private final String apiRoute;
    private final int maxRequestBodyBytes;
    private final boolean lowerCaseUri;

    public XrRequestHandler(ApiCallHandler apiCallHandler, String apiRoute) {
        this(apiCallHandler, apiRoute, DEFAULT_MAX_REQUEST_BODY_BYTES);
    }

    /**
     * @param maxRequestBodyBytes maximum accepted body size in bytes; must be positive
     */
    public XrRequestHandler(ApiCallHandler apiCallHandler, String apiRoute,
                           int maxRequestBodyBytes) {
        if (apiCallHandler == null) {
            throw new IllegalArgumentException("apiCallHandler must not be null");
        }
        if (maxRequestBodyBytes < 1) {
            throw new IllegalArgumentException("maxRequestBodyBytes must be positive");
        }
        this.apiCallHandler = apiCallHandler;
        this.apiRoute = apiRoute;
        this.maxRequestBodyBytes = maxRequestBodyBytes;
        this.lowerCaseUri = Boolean.getBoolean(LOWERCASE_URI_PROPERTY);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String requestMethod = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            // The URI keeps its case: ids such as UUIDs, instance names or file names may be
            // case sensitive. (Use Locale.ROOT: toLowerCase() alone is locale dependent.)
            String requestUri = exchange.getRequestURI().toString();
            if (lowerCaseUri) {
                requestUri = requestUri.toLowerCase(Locale.ROOT);
            }

            Map<String, String> queryMap;
            try {
                queryMap = queryToMap(exchange.getRequestURI().getRawQuery());
            } catch (IllegalArgumentException e) {       // malformed %-encoding
                sendResponse(exchange, 400, "{\"error\":\"Malformed query string\"}");
                return;
            }

            final String requestBody;
            try {
                // Do not read the request stream when HTTP framing says there is no
                // body. In particular, ordinary GET requests usually have neither
                // Content-Length nor Transfer-Encoding. Reading their stream anyway
                // can stall dispatch on some clients / server implementations.
                if (!requestHasBody(exchange)) {
                    requestBody = "";
                } else {
                    requestBody = readRequestBody(exchange.getRequestBody(), maxRequestBodyBytes);
                }
            } catch (RequestBodyTooLargeException e) {
                sendResponse(exchange, 413,
                        "{\"error\":\"Request body too large\",\"maxBytes\":"
                                + maxRequestBodyBytes + "}");
                return;
            }

            XrResultPair resultPair;
            java.net.InetSocketAddress remote = exchange.getRemoteAddress();
            RequestContext.enter(remote == null || remote.getAddress() == null ? null : remote.getAddress().getHostAddress(),
                    exchange.getRequestHeaders().getFirst("X-Forwarded-For"),        // 0.9.22: who asked, for the log
                    credential(exchange));                                            // 0.11.0: with which token
            try {
                resultPair = apiCallHandler.handleCallToJson(
                        requestMethod, requestUri, apiRoute, queryMap, requestBody);
            } catch (RuntimeException e) {
                // Without this the exchange is never answered: the client sees a reset
                // connection or waits for a reply that does not come.
                java.util.logging.Logger.getLogger(XrRequestHandler.class.getName()).log(java.util.logging.Level.SEVERE,
                        "rest: API handler failed for " + requestMethod + " " + requestUri, e);
                sendResponse(exchange, 500, "{\"error\":\"Internal server error\"}");
                return;
            }
            if (resultPair == null) {
                sendResponse(exchange, 500, "{\"error\":\"API handler returned no response\"}");
                return;
            }
            sendResponse(exchange, resultPair.getValue(), resultPair.getText());
        } finally {
            RequestContext.leave();
            exchange.close();   // idempotent; guarantees the connection is released
        }
    }


    /** @return who API authentication let in, or null */
    private static String credential(HttpExchange exchange) {
        return com.simweaver.common.rest.auth.ApiAuth.current();     // set by the filter, on this thread
    }

    private static boolean requestHasBody(HttpExchange exchange) {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            try {
                if (Long.parseLong(contentLength.trim()) > 0L) {
                    return true;
                }
            } catch (NumberFormatException e) {
                // Let the HTTP implementation / subsequent body read handle
                // malformed framing rather than treating it as a known empty body.
                return true;
            }
        }
        String transferEncoding = exchange.getRequestHeaders().getFirst("Transfer-Encoding");
        return transferEncoding != null && !transferEncoding.trim().isEmpty()
                && !"identity".equalsIgnoreCase(transferEncoding.trim());
    }

    private static String readRequestBody(InputStream input, int maxBytes)
            throws IOException, RequestBodyTooLargeException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > maxBytes) {
                    throw new RequestBodyTooLargeException();
                }
                out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String responseText)
            throws IOException {
        // 204 No Content, 304 Not Modified and 1xx must not carry a body. For the JDK server,
        // a length of 0 means "chunked, unknown length"; -1 means "no body".
        boolean noBodyStatus = statusCode == 204 || statusCode == 304 || (statusCode >= 100 && statusCode < 200);
        byte[] responseBytes = (responseText == null || noBodyStatus)
                ? new byte[0] : responseText.getBytes(StandardCharsets.UTF_8);
        if (responseBytes.length > 0) {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        }
        exchange.sendResponseHeaders(statusCode, responseBytes.length == 0 ? -1 : responseBytes.length);
        if (responseBytes.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }

    /**
     * Query string to a map. A key that occurs several times (a=1&a=2) keeps all values,
     * joined with commas ("1,2"); before 0.0.7 only the last value was kept.
     *
     * @throws IllegalArgumentException on malformed %-encoding
     */
    static Map<String, String> queryToMap(String query) {
        Map<String, String> result = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return result;
        }
        for (String param : query.split("&")) {
            if (param.isEmpty()) continue;
            int equalsIndex = param.indexOf('=');
            String key = equalsIndex >= 0 ? param.substring(0, equalsIndex) : param;
            String value = equalsIndex >= 0 ? param.substring(equalsIndex + 1) : "";
            try {
                key = URLDecoder.decode(key, "UTF-8");
                value = URLDecoder.decode(value, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                // UTF-8 is required by every Java implementation; this is unreachable.
                throw new IllegalStateException("UTF-8 is not supported", e);
            }
            String previous = result.get(key);
            result.put(key, previous == null ? value : previous + "," + value);
        }
        return result;
    }

    private static final class RequestBodyTooLargeException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
