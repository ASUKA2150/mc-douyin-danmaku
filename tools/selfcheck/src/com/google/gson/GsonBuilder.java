package com.google.gson;

/** 验证用桩件，见 {@link JsonElement}。 */
public final class GsonBuilder {

    public GsonBuilder setPrettyPrinting() {
        return this;
    }

    public GsonBuilder disableHtmlEscaping() {
        return this;
    }

    public Gson create() {
        return new Gson();
    }
}
