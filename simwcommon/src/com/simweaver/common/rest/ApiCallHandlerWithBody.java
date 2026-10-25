package com.simweaver.common.rest;

import java.util.Map;

/**
 * Convenience interface for applications that want the HTTP request body.
 * Implement this interface instead of ApiCallHandler when body-aware handling
 * is needed; the legacy three-argument method delegates to the body-aware one.
 */
public interface ApiCallHandlerWithBody extends ApiCallHandler {
    XrResultPair handleCallToJson(String method, String requestUri, String apiRoute,
                                  Map<String, String> queryMap, String requestBody);

    @Override
    default XrResultPair handleCallToJson(String method, String requestUri, String apiRoute,
                                          Map<String, String> queryMap) {
        return handleCallToJson(method, requestUri, apiRoute, queryMap, null);
    }
}
