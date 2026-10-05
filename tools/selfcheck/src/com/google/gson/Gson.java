package com.google.gson;

/** 验证用桩件，见 {@link JsonElement}。 */
public class Gson {

    public String toJson(Object value) {
        throw new UnsupportedOperationException("桩件");
    }

    public <T> T fromJson(String json, Class<T> type) {
        throw new UnsupportedOperationException("桩件");
    }
}
