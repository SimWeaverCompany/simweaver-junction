package com.simweaver.common.rest;

import java.util.Map;

/**
 * Handles REST API calls. The original three-argument method is retained for
 * source compatibility with existing applications. Implementations that need
 * request bodies can override the four-argument method or implement
 * {@link ApiCallHandlerWithBody}.
 */
public interface ApiCallHandler {
    XrResultPair handleCallToJson(String method, String requestUri, String apiRoute, Map<String, String> queryMap);

    /**
     * Body-aware entry point. Existing implementations remain compatible:
     * by default the request body is ignored and the original method is called.
     */
    default XrResultPair handleCallToJson(String method, String requestUri, String apiRoute,
                                          Map<String, String> queryMap, String requestBody) {
        return handleCallToJson(method, requestUri, apiRoute, queryMap);
    }
}
