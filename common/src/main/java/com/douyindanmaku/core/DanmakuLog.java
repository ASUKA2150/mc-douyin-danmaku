package com.douyindanmaku.core;

import java.util.function.Consumer;

/**
 * 核心代码的日志出口。
 *
 * <p>核心代码没有依赖任何加载器的日志 API（SLF4J 在 Fabric 和 NeoForge 上
 * 是不同的实现），所以这里留一个可以替换的输出口，
 * 由各加载器在启动时接到自己的日志系统上。
 *
 * <p>这样做的另一个好处是：核心代码可以脱离 Minecraft 单独跑测试。
 */
public final class DanmakuLog {

    /** 真正干活的输出口。默认打到标准输出，方便脱离游戏做调试。 */
    private static volatile Consumer<String> sink = message -> System.out.println("[DouyinDanmaku] " + message);

    private DanmakuLog() {
    }

    /** 让加载器把自己的日志器接进来。 */
    public static void setSink(Consumer<String> newSink) {
        if (newSink != null) {
            sink = newSink;
        }
    }

    /** 记一条普通信息。 */
    public static void info(String message) {
        write(message);
    }

    /**
     * 记一条调试信息。
     *
     * <p>抖音推的消息类型非常多，逐条记会把日志刷爆，所以这类信息
     * 只应该记录「没见过的东西」，用来发现协议变动。
     */
    public static void debug(String message) {
        write(message);
    }

    /** 记一条错误。 */
    public static void error(String message, Throwable cause) {
        FileLog.error(message, cause);
        write(message + (cause == null ? "" : "：" + cause));
    }

    private static void write(String message) {
        // 除了给加载器的日志器，也写一份到独立的日志文件。
        // 排查「连不上」这类问题时，用户直接把那个文件发过来就行。
        FileLog.info(message);
        try {
            sink.accept(message);
        } catch (RuntimeException ignored) {
            // 日志失败不能影响主流程
        }
    }
}
