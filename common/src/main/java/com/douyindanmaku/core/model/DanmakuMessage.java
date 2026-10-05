package com.douyindanmaku.core.model;

/**
 * 一条弹幕消息（聊天）。
 *
 * <p>这是「协议层」和「Minecraft 显示层」之间唯一的约定：
 * 抖音那边怎么变都好，只要最终能产出这个对象，聊天栏的显示逻辑就不用动。
 *
 * @param nickname     发送者昵称，不会为 {@code null}
 * @param content      弹幕正文，不会为 {@code null}
 * @param userLevel    抖音消费等级（也就是常说的「抖音等级」）。取不到时为 0
 * @param hasUserLevel 是否真的取到了等级。为 {@code false} 时显示层不应该显示「Lv.0」
 * @param fanClubName  粉丝团名称（例如「某某的粉丝团」）。没有则为 {@code null}
 * @param fanClubLevel 粉丝团等级。没有粉丝团时为 0
 * @param receivedAt   收到这条消息的时间戳（{@link System#currentTimeMillis()}），用于去重
 */
public record DanmakuMessage(
        String nickname,
        String content,
        int userLevel,
        boolean hasUserLevel,
        String fanClubName,
        int fanClubLevel,
        long receivedAt
) {

    /** 修正传入的 {@code null}，保证下游不用做空判断。 */
    public DanmakuMessage {
        nickname = nickname == null ? "" : nickname;
        content = content == null ? "" : content;
        fanClubName = fanClubName == null || fanClubName.isBlank() ? null : fanClubName;
    }

    /** 是否来自粉丝团成员。 */
    public boolean isFanClubMember() {
        return fanClubLevel > 0;
    }

    /**
     * 去重用的指纹。
     *
     * <p>抖音在重连、以及同一帧重复推送时会把同一条弹幕发多次，
     * 不去重的话聊天栏会出现连续重复的同一句话。
     * 昵称 + 正文的组合已经足够，不需要再引入 message id。
     */
    public String fingerprint() {
        return nickname + '\u0000' + content;
    }
}
