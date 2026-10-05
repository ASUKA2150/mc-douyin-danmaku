package com.google.gson;

/** 验证用桩件，见 {@link JsonElement}。 */
public final class JsonParser {

    private JsonParser() {
    }

    public static JsonElement parseString(String json) {
        throw new UnsupportedOperationException("桩件");
    }
}
