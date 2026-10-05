package com.douyindanmaku.core.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 点赞累计器。
 *
 * <h2>为什么要自己算</h2>
 * 抖音只推「这一次点了几下」的<b>增量</b>，<b>不推累计总数</b>。
 * 这是平台行为，不是接口没找对——社区里所有实现都拿不到总数。
 * 所以总数只能自己把增量加起来。
 *
 * <h2>怎么决定「什么时候报一次」</h2>
 * 点赞是超高频事件（有人能一口气点几百下），逐条显示会把聊天栏刷满。
 * 这里用「<b>按人攒、停顿后报</b>」的策略：
 * <ol>
 *   <li><b>攒 5 秒</b>：一个人开始点赞后，等他不点了（或间隔超过窗口）
 *       就把这段时间的累计报一次。这样一口气点 100 下只出一条消息。</li>
 *   <li><b>每人 30 秒冷却</b>：同一个人 30 秒内最多被播报一次。
 *       这样有人一直点也只会偶尔冒一条，不会刷屏。</li>
 *   <li><b>拿不到昵称的不单独播报</b>：抖音的点赞消息可能不带用户信息。
 *       那种情况下按人分不了，如果也报一次，就会和按人播报<b>重复</b>——
 *       表现为同一个直播间同时冒出「几万」和「从零慢慢涨」两个数字。
 *       所以匿名的那部分<b>只计入总数、不单独出一条消息</b>。</li>
 * </ol>
 *
 * <h2>两个「总数」不要混</h2>
 * <ul>
 *   <li>{@link #total()} —— 模组自己加出来的「本场收到多少」。
 *       <b>只是本次连接期间收到的量</b>，和直播间面板上的累计获赞差得很远。</li>
 *   <li>抖音的累计获赞 —— 在直播间统计消息里，见 {@code RoomStats}。
 *       要显示面板上那个数，用那个。</li>
 * </ul>
 * 早期版本把这两个塞进同一个占位符，结果数字互相矛盾，看起来像 bug。
 *
 * <h2>重要提醒</h2>
 * 这个数字是<b>本模组自己算的</b>，不是抖音给的：
 * <ul>
 *   <li>断线重连后会重置（断开期间有多少赞我们不知道）</li>
 *   <li>只会比真实值小，不会大</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * 收弹幕的线程和定时检查的线程都会碰它，所以方法都加了同步。
 */
public final class LikeCounter {

    /** 攒多久算「这一波点完了」。 */
    private static final long BURST_WINDOW_MILLIS = 5_000L;

    /** 同一个人两次播报之间至少隔多久。 */
    public static final long PER_USER_COOLDOWN_MILLIS = 30_000L;

    /** 全场合计。 */
    private long grandTotal;

    /** 每个人当前正在攒的这一波。 */
    private final Map<String, Burst> bursts = new ConcurrentHashMap<>();

    /**
     * 一条可以播报的点赞汇总。
     *
     * @param nickname 昵称；空字符串表示「没有昵称，这是全局汇总」
     * @param amount   本条要显示的赞数
     * @param total    全场合计（本模组自己算的）
     */
    public record Report(String nickname, long amount, long total) {
    }

    /** 一个人在攒的这一波。 */
    private static final class Burst {
        long amount;
        long lastLikeAt;
        long lastReportAt;
    }

    /**
     * 记一次点赞。
     *
     * @param nickname  昵称；空表示拿不到用户信息
     * @param increment 本次增量
     */
    public synchronized void add(String nickname, long increment) {
        if (increment <= 0) {
            return;
        }
        grandTotal += increment;

        if (nickname == null || nickname.isBlank()) {
            // 拿不到昵称，按人分不了——只记进总数，不单独播报
            // （原因见类注释：那样会和按人播报重复，冒出两个矛盾的数字）
            return;
        }

        long now = System.currentTimeMillis();
        Burst burst = bursts.computeIfAbsent(nickname, ignored -> {
            Burst fresh = new Burst();
            fresh.lastReportAt = now - PER_USER_COOLDOWN_MILLIS;  // 允许第一次立刻播报
            return fresh;
        });
        burst.amount += increment;
        burst.lastLikeAt = now;
    }

    /**
     * 取出「现在该播报的」汇总。
     *
     * <p>由外层定期调用（几百毫秒一次即可）。
     *
     * @return 该播报的汇总列表，没有就返回空列表
     */
    public synchronized List<Report> drainReports() {
        long now = System.currentTimeMillis();
        List<Report> reports = new ArrayList<>(2);

        // ---- 按人：这一波停下来了 + 冷却时间过了 ----
        for (Map.Entry<String, Burst> entry : bursts.entrySet()) {
            Burst burst = entry.getValue();
            if (burst.amount <= 0) {
                continue;
            }
            boolean burstSettled = now - burst.lastLikeAt >= BURST_WINDOW_MILLIS;
            boolean cooldownPassed = now - burst.lastReportAt >= PER_USER_COOLDOWN_MILLIS;
            if (burstSettled && cooldownPassed) {
                reports.add(new Report(entry.getKey(), burst.amount, grandTotal));
                burst.amount = 0;
                burst.lastReportAt = now;
            }
        }

        // 匿名点赞<b>不在这里播报</b>——它们只计入 total()。
        // 原因：按人播报的那些已经覆盖了同一批数据，匿名再报一次就会出现
        // 两个数字（一个权威的「几万」、一个自己累加的「慢慢涨」），
        // 用户看到的就是「同一个直播间两个累计数」，像 bug。

        // 顺手清掉已经没用的条目，免得直播间人多了内存一直涨
        bursts.entrySet().removeIf(entry ->
                entry.getValue().amount <= 0 && now - entry.getValue().lastLikeAt > 600_000L);

        return reports;
    }

    /** 全场合计（本模组自己算的）。 */
    public synchronized long total() {
        return grandTotal;
    }

    /** 重置。重新连接直播间时调用——断开期间的赞我们收不到，计数没意义了。 */
    public synchronized void reset() {
        grandTotal = 0;
        bursts.clear();
    }

    /**
     * 把数字念成中文习惯的样子。
     *
     * <p>实现挪到了 {@link NumberText#format(long)}——房间统计里的
     * 「累计观看 1.3万」也要用同一套规则，放一起免得两边不一致。
     */
    public static String formatCount(long value) {
        return NumberText.format(value);
    }
}
