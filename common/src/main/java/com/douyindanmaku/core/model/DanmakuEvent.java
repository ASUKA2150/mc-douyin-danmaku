package com.douyindanmaku.core.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 从直播间收到的一条事件。
 *
 * <h2>为什么用「统一事件 + 可选载荷」而不是继承体系</h2>
 * 四种事件的字段差异很大：弹幕有正文、礼物有连击数、点赞有增量、
 * 进房有在线人数。如果做成继承体系（{@code ChatEvent extends Event} 之类），
 * 协议层要写四个类型、显示层要写四个 {@code instanceof}，代码会散开。
 *
 * <p>这里反过来：一个事件对象带一个「类型」标记，再挂一个和类型对应的载荷。
 * 好处是<b>新增一种事件只改一处</b>，而且显示层可以统一用
 * 「取类型 → 查模板 → 填占位符」这一条路径处理所有类型。
 *
 * <p>调用方要按 {@link #kind()} 取对应载荷。取错会返回 {@code null}
 * 而不是抛异常——网络数据不可信，出错不该把收弹幕的线程搞挂。
 *
 * @param kind       事件类型
 * @param nickname   发送者昵称（进房、点赞、礼物都有；系统消息可能为空）
 * @param userLevel  抖音消费等级，取不到为 0
 * @param hasUserLevel 是否真的取到了消费等级
 * @param fanClubName 粉丝团名称，没有为 {@code null}
 * @param fanClubLevel 粉丝团等级，没有为 0
 * @param content    弹幕正文（只有 {@link Kind#CHAT} 有）
 * @param gift       礼物信息（只有 {@link Kind#GIFT} 有）
 * @param like       点赞信息（只有 {@link Kind#LIKE} 有）
 * @param member     进房信息（只有 {@link Kind#MEMBER} 有）
 * @param receivedAt 收到时间
 */
public record DanmakuEvent(
        Kind kind,
        String nickname,
        int userLevel,
        boolean hasUserLevel,
        String fanClubName,
        int fanClubLevel,
        String content,
        Gift gift,
        Like like,
        Member member,
        long receivedAt
) {

    /** 事件类型。名字对应抖音的 method 字符串含义。 */
    public enum Kind {
        /** 聊天弹幕。 */
        CHAT("聊天", "chat"),
        /** 送礼物。 */
        GIFT("礼物", "gift"),
        /** 点赞。 */
        LIKE("点赞", "like"),
        /** 进入直播间。 */
        MEMBER("入场", "member");

        private final String displayName;
        private final String configKey;

        Kind(String displayName, String configKey) {
            this.displayName = displayName;
            this.configKey = configKey;
        }

        /** 给人看的名字。 */
        public String displayName() {
            return displayName;
        }

        /** 配置里用的键名，例如模板配置项 {@code templateGift}。 */
        public String configKey() {
            return configKey;
        }
    }

    /**
     * 礼物信息。
     *
     * @param name         礼物名，例如「小心心」
     * @param repeatCount  这次送了多少个（单个礼物时为 1）
     * @param comboCount   连击数（主播看到直播间里那个「x5」的累计）
     * @param repeatEnd    是否是连击的最后一击。抖音会为一次连击推多条消息，
     *                     只有最后一条的这个标记是 true
     * @param diamondCount 单价（抖币）
     */
    public record Gift(String name, int repeatCount, int comboCount, boolean repeatEnd, int diamondCount) {
    }

    /**
     * 点赞信息。
     *
     * <p><b>重要</b>：点赞增量消息里<b>没有累计总数</b>，只有「这一次点了几下」。
     *
     * <p>而且实测确认：抖音的弹幕通道里<b>根本没有「累计获赞」这个数据</b>。
     * 统计消息里只有一个累计量（实测 {@code f7/f8/f11} = 63133 / "6万+" / "6.3万"），
     * 它和直播间面板上的获赞数<b>对不上</b>（实测面板 4 万、它是 6.3 万），
     * 所以不能拿它当获赞显示。详见 {@code DanmakuConfig.likeFormat} 的说明。
     *
     * @param increment 本次点赞增量
     * @param total     模组自己累计出来的「本场收到多少」；0 表示还没算出来
     * @param online    直播间当前在线人数；0 表示拿不到
     * @param watched   直播间的累计量（抖音给的原文，如「6万+」）。
     *                  <b>含义不确定</b>——抖音没给标签，实测和获赞对不上，
     *                  可能是在线人次的累计
     */
    public record Like(long increment, long total, long online, String watched) {

        /** 只有增量的构造（普通点赞消息用这个）。 */
        public Like(long increment) {
            this(increment, 0L, 0L, null);
        }

        /** 带自己累加值的构造（汇总消息用这个）。 */
        public Like(long increment, long total) {
            this(increment, total, 0L, null);
        }
    }

    /**
     * 进房信息。
     *
     * @param memberCount 当前直播间人数（抖音推的「看过/在线」口径，取不到为 0）
     */
    public record Member(long memberCount) {
    }

    /** 修正 {@code null}，并把空字符串的粉丝团名归一成 {@code null}。 */
    public DanmakuEvent {
        kind = kind == null ? Kind.CHAT : kind;
        nickname = nickname == null ? "" : nickname;
        content = content == null ? "" : content;
        fanClubName = fanClubName == null || fanClubName.isBlank() ? null : fanClubName;
    }

    /** 是否来自粉丝团成员。 */
    public boolean isFanClubMember() {
        return fanClubLevel > 0;
    }

    /**
     * 构造一条聊天弹幕（最常用，所以单独给个方便的方法）。
     */
    public static DanmakuEvent chat(DanmakuMessage message) {
        return new DanmakuEvent(Kind.CHAT, message.nickname(), message.userLevel(),
                message.hasUserLevel(), message.fanClubName(), message.fanClubLevel(),
                message.content(), null, null, null, message.receivedAt());
    }

    /** 把事件转回聊天弹幕。不是聊天事件时返回 {@code null}。 */
    public DanmakuMessage toChatMessage() {
        if (kind != Kind.CHAT) {
            return null;
        }
        return new DanmakuMessage(nickname, content, userLevel, hasUserLevel,
                fanClubName, fanClubLevel, receivedAt);
    }

    /**
     * 这个事件能提供的所有占位符值。
     *
     * <p>模板渲染用的就是这张表。放在这里而不是渲染器里，
     * 是为了让「事件有哪些字段」和「模板能写什么」始终对得上——
     * 加了新字段只要往这里加一行，模板立刻就能用。
     */
    public Map<String, String> placeholders() {
        Map<String, String> values = new LinkedHashMap<>(16);
        values.put("kind", kind.displayName());
        values.put("nick", nickname);
        values.put("content", content);

        if (hasUserLevel) {
            values.put("level.value", String.valueOf(userLevel));
        }
        if (isFanClubMember()) {
            values.put("fanclub.level", String.valueOf(fanClubLevel));
            values.put("fanclub.name", fanClubName == null ? "" : fanClubName);
        }

        if (gift != null) {
            values.put("gift.name", gift.name() == null ? "" : gift.name());
            values.put("gift.count", String.valueOf(Math.max(1, gift.repeatCount())));
            values.put("gift.combo", gift.comboCount() > 1 ? "x" + gift.comboCount() : "");
            values.put("gift.diamond", String.valueOf(gift.diamondCount()));
        }
        if (like != null) {
            values.put("like.count", String.valueOf(like.increment()));
        }
        if (member != null && member.memberCount() > 0) {
            values.put("member.count", String.valueOf(member.memberCount()));
        }
        return values;
    }

    /** 去重指纹。按类型区分，免得礼物和弹幕撞到一起。 */
    public String fingerprint() {
        return kind.configKey() + '\u0000' + nickname + '\u0000' + content
                + '\u0000' + (gift == null ? "" : gift.name());
    }
}
