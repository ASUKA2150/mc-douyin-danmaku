package com.douyindanmaku.core.text;

/**
 * 数字的读写：把「1.3万」这种中文写法解析成数字，反过来也能格式化。
 *
 * <h2>为什么需要解析</h2>
 * 抖音在直播间统计消息里推的有些数字<b>不是纯数字</b>——
 * 比如「累计观看人数」服务端已经格式化成了 {@code "1.3万"}。
 * 想拿它做比较或者参与运算就得先解析回数字。
 *
 * <h2>为什么需要格式化</h2>
 * 反过来，显示的时候几百万的赞写成 {@code "1234567"} 很难一眼读出来，
 * 换成 {@code "123.5万"} 更自然。
 */
public final class NumberText {

    private NumberText() {
    }

    /**
     * 把抖音给的中文数字写法解析成数值。
     *
     * <pre>
     *   "12345"  -> 12345
     *   "1.3万"  -> 13000
     *   "12万"   -> 120000
     *   "1.5亿"  -> 150000000
     *   "3.2w"   -> 32000      （有些人用 w 代替「万」）
     *   "1,234"  -> 1234       （带千分位）
     *   ""       -> 0
     *   "未知"   -> 0          （解不出来就当 0，不抛异常）
     * </pre>
     *
     * <p>解不出来一律返回 0 而不抛异常——网络数据不可信，
     * 一个解析失败不该把收弹幕的线程搞挂。
     */
    public static long parse(String raw) {
        if (raw == null) {
            return 0L;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return 0L;
        }

        // 去掉千分位逗号和空格
        text = text.replace(",", "").replace(" ", "").replace("\u00A0", "");

        double multiplier = 1.0;
        if (text.endsWith("亿")) {
            multiplier = 100_000_000.0;
            text = text.substring(0, text.length() - 1);
        } else if (text.endsWith("万") || text.endsWith("w") || text.endsWith("W")) {
            multiplier = 10_000.0;
            text = text.substring(0, text.length() - 1);
        }

        if (text.isEmpty()) {
            return 0L;
        }

        try {
            double value = Double.parseDouble(text);
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return 0L;
            }
            return (long) (value * multiplier);
        } catch (NumberFormatException notANumber) {
            return 0L;
        }
    }

    /**
     * 把数值写成中文习惯的样子。
     *
     * <pre>
     *   999       -> 999
     *   9999      -> 9999
     *   10000     -> 1万
     *   25000     -> 2.5万
     *   50000     -> 5万        （整数不带 .0）
     *   1234567   -> 123.5万
     *   1亿        -> 1亿
     * </pre>
     */
    public static String format(long value) {
        if (value < 0) {
            return "0";
        }
        if (value < 10_000L) {
            return String.valueOf(value);
        }
        if (value < 100_000_000L) {
            return trimDecimal(value / 10_000.0) + "万";
        }
        return trimDecimal(value / 100_000_000.0) + "亿";
    }

    /** 保留一位小数；本来就是整数就不带小数点。 */
    private static String trimDecimal(double value) {
        double rounded = Math.round(value * 10.0) / 10.0;
        if (rounded == Math.floor(rounded) && !Double.isInfinite(rounded)) {
            return String.valueOf((long) rounded);
        }
        return String.valueOf(rounded);
    }
}
