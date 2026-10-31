package com.simweaver.common.rest;

/**
 * Already serialized JSON text that {@link Jsons#toJson(Object)} inserts as is, instead of
 * quoting or reflecting it. Use it to put JSON built elsewhere (another serializer, a cache)
 * into maps, lists or {@link XrSseHub#publishPatch} fields without re-serializing.
 *
 * The text is not validated here; it must be one complete, valid JSON value.
 */
public final class XrRawJson {
    private final String json;

    public XrRawJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("raw JSON must not be empty");
        }
        this.json = json;
    }

    /** Wraps text that has been checked with {@link JsonParser#parse(String)}. */
    public static XrRawJson ofValidated(String json) {
        JsonParser.parse(json);   // throws IllegalArgumentException if invalid
        return new XrRawJson(json);
    }

    public String getJson() {
        return json;
    }

    @Override
    public String toString() {
        return json;
    }
}
