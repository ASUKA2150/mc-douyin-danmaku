package com.google.gson;

/** 验证用桩件，见 {@link JsonElement}。 */
public class JsonArray extends JsonElement {

    public boolean isEmpty() {
        throw new UnsupportedOperationException("桩件");
    }

    public int size() {
        throw new UnsupportedOperationException("桩件");
    }

    public JsonElement get(int index) {
        throw new UnsupportedOperationException("桩件");
    }
}
