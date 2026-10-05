package com.douyindanmaku.core.text;

import com.douyindanmaku.core.config.DanmakuConfig;
import com.douyindanmaku.core.model.DanmakuEvent;
import com.douyindanmaku.core.model.DanmakuMessage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 决定一条弹幕「要不要显示」。
 *
 * <p>做三件事：关键词黑白名单、屏蔽指定用户、以及限流。
 */
public final class DanmakuFilter {

    /**
     * 记录最近出现过的弹幕，用来去重。
     *
     * <p>抖音在重连后、以及同一帧被重复推送时会把同一条弹幕发多次，
     * 不去重的话聊天栏里会出现连着好几条一模一样的话。
     *
     * <p>用 {@link LinkedHashMap} 而不是普通 {@code HashMap}，是为了能按
     * 「插入顺序」淘汰最旧的记录——这就是最简单的 LRU。
     */
    private final Map<String, Long> recentFingerprints = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_REMEMBERED;
        }
    };

    /** 最多记住多少条弹幕的指纹。 */
    private static final int MAX_REMEMBERED = 512;

    /** 多久以内出现同样的「昵称 + 正文」就认为是重复。 */
    private static final long DEDUPE_WINDOW_MILLIS = 5_000L;

    /** 限流用：本秒内已经放行了几条。 */
    private long currentSecond = -1L;
    private int shownThisSecond = 0;

    /**
     * 判断一个事件能不能显示。
     *
     * <p>各种消息类型共用一张去重表，但指纹里带了类型，
     * 所以「同一个人的弹幕」和「同一个人的礼物」不会被误判成重复。
     *
     * @return {@code true} 表示应该显示
     */
    public synchronized boolean shouldShow(DanmakuEvent event, DanmakuConfig config) {
        if (isDuplicate(event)) {
            return false;
        }
        if (isBlockedUser(event.nickname(), config)) {
            return false;
        }
        // 关键词过滤只对弹幕有意义：礼物名、进房这些不是用户自由输入的内容，
        // 拿弹幕的屏蔽词去套它们会误伤。
        if (event.kind() == DanmakuEvent.Kind.CHAT
                && !passesKeywordFilter(event.content(), config)) {
            return false;
        }
        return passesRateLimit(config);
    }

    /** 兼容入口：只处理弹幕。 */
    public synchronized boolean shouldShow(DanmakuMessage danmaku, DanmakuConfig config) {
        return shouldShow(DanmakuEvent.chat(danmaku), config);
    }

    /** 清空状态（重新连接时调用，免得旧记录影响判断）。 */
    public synchronized void reset() {
        recentFingerprints.clear();
        currentSecond = -1L;
        shownThisSecond = 0;
    }

    // ------------------------------------------------------------------

    private boolean isDuplicate(DanmakuEvent event) {
        long now = event.receivedAt();
        String fingerprint = event.fingerprint();

        Long previous = recentFingerprints.get(fingerprint);
        if (previous != null && now - previous < DEDUPE_WINDOW_MILLIS) {
            return true;
        }
        recentFingerprints.put(fingerprint, now);
        return false;
    }

    private boolean isBlockedUser(String nickname, DanmakuConfig config) {
        if (nickname == null || nickname.isEmpty()) {
            return false;
        }
        for (String blocked : config.blockedUsers) {
            if (blocked != null && blocked.equalsIgnoreCase(nickname)) {
                return true;
            }
        }
        return false;
    }

    private boolean passesKeywordFilter(String content, DanmakuConfig config) {
        DanmakuConfig.FilterMode mode = config.filterMode;
        if (mode == DanmakuConfig.FilterMode.DISABLED || config.filterKeywords.isEmpty()) {
            return true;
        }

        boolean hit = false;
        for (String keyword : config.filterKeywords) {
            if (keyword == null || keyword.isEmpty()) {
                continue;
            }
            if (content.toLowerCase(java.util.Locale.ROOT)
                    .contains(keyword.toLowerCase(java.util.Locale.ROOT))) {
                hit = true;
                break;
            }
        }

        return switch (mode) {
            case BLACKLIST -> !hit;   // 黑名单：命中就丢掉
            case WHITELIST -> hit;    // 白名单：没命中就丢掉
            case DISABLED -> true;
        };
    }

    /**
     * 限流。
     *
     * <p>热门直播间一秒钟几十条弹幕很常见，逐条渲染会明显掉帧。
     * 超过配额的弹幕直接丢掉——直播场景下丢几条不影响观看体验，
     * 但掉帧主播和观众都能立刻感觉到。
     */
    private boolean passesRateLimit(DanmakuConfig config) {
        int limit = config.maxDanmakuPerSecond;
        if (limit <= 0) {
            return true;
        }

        long second = System.currentTimeMillis() / 1000L;
        if (second != currentSecond) {
            currentSecond = second;
            shownThisSecond = 0;
        }
        if (shownThisSecond >= limit) {
            return false;
        }
        shownThisSecond++;
        return true;
    }
}
