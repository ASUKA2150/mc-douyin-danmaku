package com.douyindanmaku.core.source;

import com.douyindanmaku.core.config.DanmakuConfig;
import com.douyindanmaku.core.model.DanmakuMessage;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 解析外部程序转发过来的弹幕 JSON。
 *
 * <p>字段名做了兼容处理，尽可能宽容——毕竟对面是什么程序我们控制不了。
 * 认这些写法：
 * <pre>
 *   {"nick":"张三","content":"你好"}
 *   {"nickname":"张三","content":"你好","level":12,"fanClubLevel":3,"fanClubName":"某某团"}
 * </pre>
 */
final class ExternalJson {

    private ExternalJson() {
    }

    /**
     * 解一行 JSON。
     *
     * @return 解出来的弹幕；这一行不是合法弹幕时返回 {@code null}
     */
    static DanmakuMessage parse(String line, DanmakuConfig config) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(line.trim());
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject object = parsed.getAsJsonObject();

            // 正文是必须的，没有正文就没有意义
            String content = readString(object, "content", "text", "msg", "danmaku");
            if (content == null || content.isEmpty()) {
                return null;
            }

            String nickname = readString(object, "nick", "nickname", "user", "username", "name");
            int level = readInt(object, "level", "userLevel", "payGradeLevel");
            int fanClubLevel = readInt(object, "fanClubLevel", "fansClubLevel", "medalLevel");
            String fanClubName = readString(object, "fanClubName", "fansClubName", "medalName");

            return new DanmakuMessage(
                    nickname == null ? "" : nickname,
                    content,
                    level,
                    level > 0,
                    fanClubName,
                    fanClubLevel,
                    System.currentTimeMillis());
        } catch (RuntimeException malformed) {
            // 不是 JSON 或者结构不对，丢掉这一行
            return null;
        }
    }

    /** 按顺序找第一个存在的字符串字段。 */
    private static String readString(JsonObject object, String... names) {
        for (String name : names) {
            JsonElement element = object.get(name);
            if (element != null && element.isJsonPrimitive()) {
                return element.getAsString();
            }
        }
        return null;
    }

    /** 按顺序找第一个存在的整数字段。 */
    private static int readInt(JsonObject object, String... names) {
        for (String name : names) {
            JsonElement element = object.get(name);
            if (element != null && element.isJsonPrimitive()) {
                try {
                    return element.getAsInt();
                } catch (RuntimeException notANumber) {
                    // 试试下一个名字
                }
            }
        }
        return 0;
    }
}
