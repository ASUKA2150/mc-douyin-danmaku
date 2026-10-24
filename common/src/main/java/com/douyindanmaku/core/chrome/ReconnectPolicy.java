package com.douyindanmaku.core.chrome;

/**
 * 重连策略：连续失败几次就该放弃，以及每次等多久。
 *
 * <h2>为什么单独抽一个类</h2>
 * 这段逻辑原来写在 {@link ChromeDanmakuSource} 的重连循环里，没法单独测——
 * 要测就得真的开浏览器。抽出来之后它变成了一个纯计算：给一串
 * 「成功 / 没找到通道 / 其它错误」，算出该不该继续。
 *
 * <h2>为什么按失败原因分两档</h2>
 * 「页面打开了但找不到弹幕通道」是个很明确的信号：<b>主播没在播</b>。
 * 这种失败重试再多次也没用，所以上限压得很低（5 次）。
 *
 * <p>其它错误（浏览器起不来、网络抖动）常常是暂时的，重试往往就好了，
 * 所以给的上限宽一些（10 次）。
 *
 * <p>实测背景：以前是无条件无限重试的，结果主播没开播时它能转一整晚，
 * 每 5~60 秒开一次浏览器去看一眼，日志里堆满重复的失败记录。
 *
 * <p><b>这个类不是线程安全的</b>，只在重连循环那一个线程里用。
 */
final class ReconnectPolicy {

    /**
     * 「没找到弹幕通道」这种失败，连续几次就放弃。
     *
     * <p><b>这个值从 5 提到了 20，因为实测打脸了。</b>
     * 原本的想法是：找不到通道 = 主播没开播，重试再多次也没用，早点停。
     *
     * <p>但直播实测发现：<b>主播明明在播，也会连续好几次找不到通道。</b>
     * 原因不是没开播，而是页面加载太慢（机器一边推流一边跑 Minecraft），
     * 或者上一次的浏览器还占着配置目录。这种情况下模组几次之后就永久放弃，
     * 主播得手动 {@code /dy connect}——正在直播时非常难受。
     *
     * <p>20 次配合退避（最长 60 秒）大约是 15~20 分钟的持续尝试，
     * 足够覆盖开机、推流软件启动、页面卡顿这些暂时状况。
     */
    static final int MAX_CHANNEL_NOT_FOUND_FAILURES = 20;

    /** 其它失败连续几次就放弃。 */
    static final int MAX_OTHER_FAILURES = 10;

    /** 第一次失败后等多久（秒）。等得短一点，因为最常见的情况是网页自己刷新了一下。 */
    private static final long FIRST_BACKOFF_SECONDS = 5L;

    /** 退避的上限（秒）。再久就没必要了，用户会以为模组死了。 */
    private static final long MAX_BACKOFF_SECONDS = 60L;

    /** 连续失败了多少次。连上一次就清零。 */
    private int consecutiveFailures = 0;

    /** 上一次失败是不是「没找到弹幕通道」。 */
    private boolean lastFailureWasNoChannel = false;

    /** 连上一次了，清空所有失败记录。 */
    void recordSuccess() {
        consecutiveFailures = 0;
        lastFailureWasNoChannel = false;
    }

    /** 记一次「页面打开了但没找到弹幕通道」。 */
    void recordChannelNotFound() {
        consecutiveFailures++;
        lastFailureWasNoChannel = true;
    }

    /** 记一次其它失败（浏览器起不来、网络断了等）。 */
    void recordOtherFailure() {
        consecutiveFailures++;
        lastFailureWasNoChannel = false;
    }

    /** 连续失败了几次。 */
    int consecutiveFailures() {
        return consecutiveFailures;
    }

    /**
     * 还该不该继续重试。
     *
     * @return {@code false} 表示到上限了，应该停下来并告诉用户
     */
    boolean shouldRetry() {
        if (lastFailureWasNoChannel) {
            return consecutiveFailures < MAX_CHANNEL_NOT_FOUND_FAILURES;
        }
        return consecutiveFailures < MAX_OTHER_FAILURES;
    }

    /**
     * 放弃的原因，用来给用户一句有用的话。
     *
     * <p>只在 {@link #shouldRetry()} 返回 {@code false} 时有意义。
     */
    boolean stoppedBecauseChannelNotFound() {
        return lastFailureWasNoChannel;
    }

    /**
     * 下一次重试前等多久（秒）。
     *
     * <p>指数退避：5 → 10 → 20 → 40 → 60 → 60…
     */
    long backoffSeconds() {
        int shift = Math.min(consecutiveFailures, 4);
        long backoff = FIRST_BACKOFF_SECONDS * (1L << shift) / 2L;
        return Math.min(MAX_BACKOFF_SECONDS, Math.max(FIRST_BACKOFF_SECONDS, backoff));
    }
}
