package com.simweaver.junction.api;


import com.simweaver.common.json.Json;
import com.simweaver.common.json.JsonFactory;
import com.simweaver.common.rest.ApiCallHandler;
import com.simweaver.common.rest.XrResultPair;

import java.util.Map;

public class ApiRequestEntryPoint implements ApiCallHandler {


    public XrResultPair handleCallToJson(String requestUri, String apiRoute, Map<String, String> queryMap) {
        return this.handleCallToJson(apiRoute,requestUri,queryMap);
    }

    @Override
    public XrResultPair handleCallToJson(String method, String requestUri, String apiRoute, Map<String, String> queryMap) {
        return this.handleCallToJson(apiRoute,requestUri,queryMap);
    }

    @Override
    public XrResultPair handleCallToJson(String method, String requestUri, String apiRoute, Map<String, String> queryMap, String requestBody) {
        System.out.println(requestUri);
        String apiRequestUri = requestUri.substring(apiRoute.length());
        System.out.println(apiRequestUri);
        int headerResultCode = 200;


        if (apiRequestUri.equalsIgnoreCase("/nisse")){
            return new XrResultPair(Json.object().put("test","testvalue").toString(),headerResultCode);
        }
        else{
            return new XrResultPair(JsonFactory.rfc9457(requestUri,"Bad request",400,"There is no API function with that URL",requestUri),400);
        }


    }
}
