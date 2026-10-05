package com.douyindanmaku.core.model;

/**
 * 直播间的统计数据。
 *
 * <p>抖音会周期性推送这类消息（不是弹幕，是「房间状态」）。
 *
 * <h2>为什么这里没有「累计获赞」</h2>
 * 实测把所有统计字段 dump 出来对过：<b>弹幕通道里根本没有获赞这个数据</b>。
 * 能读出来的只有两种：
 * <ul>
 *   <li><b>当前在线人数</b>（可靠，见 {@link #online}）</li>
 *   <li>一个累计量（{@link #watched}）。实测样本是
 *       {@code f7/f8/f11 = 63133 / "6万+" / "6.3万"}，
 *       而当时直播间面板上的获赞是 4 万——<b>对不上</b>。
 *       所以它不是获赞，抖音也没给标签，含义不确定。</li>
 * </ul>
 * 早期版本把那个累计量当成获赞显示，结果是一个稳定错误的数字
 * （面板 4 万、模组显示 6.3 万）。与其显示错的，不如不显示。
 *
 * @param online  当前在线人数；取不到为 0
 * @param watched 那个含义不确定的累计量（<b>抖音给的原文</b>，如「6万+」）；
 *                取不到为 {@code null}
 */
public record RoomStats(long online, String watched) {

    /** 一条内容都没有的空统计。 */
    public static RoomStats empty() {
        return new RoomStats(0L, null);
    }

    /** 有没有任何有效内容。 */
    public boolean isEmpty() {
        return online <= 0 && isBlank(watched);
    }

    /**
     * 和另一份统计合并：<b>有值才覆盖，没值不动</b>。
     *
     * <p>各字段的来源是确定的——在线人数两种统计消息都有，
     * 累计量只有 {@code RoomUserSeqMessage} 提供。
     */
    public RoomStats merge(RoomStats newer) {
        if (newer == null) {
            return this;
        }
        return new RoomStats(
                newer.online > 0 ? newer.online : online,
                !isBlank(newer.watched) ? newer.watched : watched);
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
