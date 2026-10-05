package com.google.gson;

/** 验证用桩件，见 {@link JsonElement}。 */
public class JsonObject extends JsonElement {

    public JsonElement get(String name) {
        throw new UnsupportedOperationException("桩件");
    }
}
