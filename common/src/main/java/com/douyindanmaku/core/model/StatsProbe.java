package com.douyindanmaku.core.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 直播间统计字段的观测记录器。
 *
 * <h2>为什么需要它</h2>
 * 直播间统计消息里的字段号<b>会变，而且含义光看数字猜不出来</b>——
 * 实测踩过：把「累计看过」当成了「累计获赞」，显示出来差了四成。
 * 社区资料也互相矛盾。
 *
 * <p>所以这里把每次收到的统计字段<b>原样记下来，并跟踪每个字段的变化</b>，
 * 用户用 {@code /dy stats} 就能一眼对上号：
 *
 * <pre>
 *   f7  50042 → 56315  变了  12 次，一直在涨   ← 累计值
 *   f9   1824 →   2010  变了 240 次，来回波动   ← 瞬时值（在线人数）
 * </pre>
 *
 * <p><b>怎么用它定位获赞</b>：抖音面板上的「累计获赞」一定满足两个条件——
 * 数值对得上、而且只增不减。把面板上的数和你看到的字段值一比就知道了。
 *
 * <h2>线程安全</h2>
 * 收弹幕的线程写、命令线程读，所以用并发容器。
 */
public final class StatsProbe {

    /** 每种统计消息各自的字段跟踪表。 */
    private static final Map<String, Map<String, FieldTrack>> TRACKS = new ConcurrentHashMap<>();

    /** 各类统计消息收到过多少次。 */
    private static final Map<String, Integer> COUNTS = new ConcurrentHashMap<>();

    private StatsProbe() {
    }

    /** 一个字段被观察到的历史。 */
    private static final class FieldTrack {
        /** 显示用的最新值，例如 {@code f7=56315}。 */
        String latest;
        /** 数值形式的最新值；非数字字段为 {@code null}。 */
        Long latestNumber;
        /** 第一次看到的值。 */
        String first;
        /** 这个字段出现过多少次。 */
        int seen;
        /** 数值变化的次数（用来区分「累计值」和「瞬时值」）。 */
        int changes;
        /** 观察期间是否一直在增长（累计计数的特征）。 */
        boolean monotonicIncrease = true;
    }

    /**
     * 记一次统计消息。
     *
     * @param kind   消息类型名（如 {@code RoomUserSeq}）
     * @param fields 这一帧里出现的字段，形如 {@code "f3=1941"}；
     *               数值字段用 {@code "f9#1941"} 表示同时可读作数字
     */
    public static void record(String kind, List<String> fields) {
        COUNTS.merge(kind, 1, Integer::sum);
        Map<String, FieldTrack> tracks = TRACKS.computeIfAbsent(kind,
                ignored -> new ConcurrentHashMap<>());

        for (String field : fields) {
            // 格式："f7=56315" 或 "f7#56315"（后者表示还能读成数字）
            int split = field.indexOf('=');
            int hashSplit = field.indexOf('#');
            String key;
            Long number = null;
            String display;

            if (hashSplit > 0 && (split < 0 || hashSplit < split)) {
                key = field.substring(0, hashSplit);
                String raw = field.substring(hashSplit + 1);
                try {
                    number = Long.parseLong(raw);
                } catch (NumberFormatException ignored) {
                    number = null;
                }
                display = key + "=" + raw;
            } else if (split > 0) {
                key = field.substring(0, split);
                display = field;
                String raw = field.substring(split + 1);
                try {
                    number = Long.parseLong(raw);
                } catch (NumberFormatException ignored) {
                    number = null;
                }
            } else {
                continue;
            }

            FieldTrack track = tracks.computeIfAbsent(key, ignored -> new FieldTrack());
            synchronized (track) {
                if (track.seen == 0) {
                    track.first = display;
                } else if (number != null && track.latestNumber != null
                        && number < track.latestNumber) {
                    // 变小了 → 不是单调递增的累计值
                    track.monotonicIncrease = false;
                }
                if (number != null && track.latestNumber != null
                        && !number.equals(track.latestNumber)) {
                    track.changes++;
                }
                track.latest = display;
                track.latestNumber = number;
                track.seen++;
            }
        }
    }

    /** 某类统计消息收到过几次。 */
    public static int countOf(String kind) {
        return COUNTS.getOrDefault(kind, 0);
    }

    /** 一共收到过多少条统计消息（所有类型加起来）。 */
    public static int totalCount() {
        int total = 0;
        for (Integer value : COUNTS.values()) {
            total += value;
        }
        return total;
    }

    /**
     * 生成一份给人看的报告。
     *
     * @return 多行文本，每行一段信息
     */
    public static List<String> report() {
        List<String> lines = new ArrayList<>();

        if (COUNTS.isEmpty()) {
            lines.add("&c没有收到任何直播间统计消息");
            lines.add("&7抖音没推统计消息（或者消息类型名变了），");
            lines.add("&7所以拿不到面板上的获赞数。");
            return lines;
        }

        lines.add("&7收到的统计消息：");
        for (Map.Entry<String, Integer> entry : COUNTS.entrySet()) {
            lines.add("  &f" + entry.getKey() + " &7收到 &f" + entry.getValue() + " &7次");
        }
        lines.add("");
        lines.add("&7各字段（&f一直在涨&7 的那个才可能是累计获赞）：");

        for (Map.Entry<String, Map<String, FieldTrack>> entry : TRACKS.entrySet()) {
            lines.add("  &f" + entry.getKey() + "&7：");
            List<Map.Entry<String, FieldTrack>> sorted =
                    new ArrayList<>(entry.getValue().entrySet());
            sorted.sort((left, right) -> compareFieldNames(left.getKey(), right.getKey()));

            for (Map.Entry<String, FieldTrack> field : sorted) {
                FieldTrack track = field.getValue();
                synchronized (track) {
                    StringBuilder line = new StringBuilder("    &7").append(track.latest);
                    if (track.seen > 1) {
                        if (track.monotonicIncrease && track.changes > 0) {
                            line.append(" &a↑一直在涨&7（变了 ").append(track.changes).append(" 次）");
                        } else if (track.changes > 0) {
                            line.append(" &e波动&7（变了 ").append(track.changes).append(" 次）");
                        } else {
                            line.append(" &7没变过");
                        }
                    }
                    lines.add(line.toString());
                }
            }
        }

        lines.add("");
        lines.add("&7拿面板上的「累计获赞」和上面的数字对一下——");
        lines.add("&7数值对得上、而且一直在涨的那个，就是它。");
        return lines;
    }

    /** 按字段号排序（f2 排在 f10 前面，而不是按字符串比）。 */
    private static int compareFieldNames(String left, String right) {
        return Integer.compare(fieldNumber(left), fieldNumber(right));
    }

    private static int fieldNumber(String name) {
        try {
            return Integer.parseInt(name.replaceAll("\\D", ""));
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    /** 清空（重新连接时调用）。 */
    public static void reset() {
        TRACKS.clear();
        COUNTS.clear();
    }
}
