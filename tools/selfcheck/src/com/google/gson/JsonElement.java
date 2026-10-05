package com.google.gson;

/**
 * 验证用桩件（不是真实现）。
 *
 * <p>它存在的唯一目的：让不依赖 Gson 的核心代码（protobuf 解码、签名、
 * 过滤、渲染）能脱离 Gradle 和 Minecraft 单独编译运行，从而快速验证协议解析。
 * 正式构建用的是 Minecraft 自带的真 Gson。
 */
public class JsonElement {

    public boolean isJsonObject() {
        throw new UnsupportedOperationException("桩件");
    }

    public boolean isJsonArray() {
        throw new UnsupportedOperationException("桩件");
    }

    public boolean isJsonPrimitive() {
        throw new UnsupportedOperationException("桩件");
    }

    public boolean isJsonNull() {
        throw new UnsupportedOperationException("桩件");
    }

    public JsonObject getAsJsonObject() {
        throw new UnsupportedOperationException("桩件");
    }

    public JsonArray getAsJsonArray() {
        throw new UnsupportedOperationException("桩件");
    }

    public String getAsString() {
        throw new UnsupportedOperationException("桩件");
    }

    public int getAsInt() {
        throw new UnsupportedOperationException("桩件");
    }
}
