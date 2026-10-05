package com.douyindanmaku.core;

import java.nio.file.Path;

/**
 * 记着日志文件在哪，并保证它只被初始化一次。
 *
 * <p>两个加载器各自在启动时调用一次 {@link #ensure(Path)}，
 * 之后 {@link DanmakuLog} 就会把日志同时写进这个文件。
 */
public final class LogFile {

    private static volatile Path path;

    private LogFile() {
    }

    /**
     * 初始化日志文件。重复调用只有第一次生效。
     *
     * @param candidate 期望的日志文件位置（一般是配置目录下的一个文件）
     * @return 实际使用的路径
     */
    public static Path ensure(Path candidate) {
        Path current = path;
        if (current != null) {
            return current;
        }
        synchronized (LogFile.class) {
            if (path == null) {
                FileLog.init(candidate);
                path = candidate;
            }
            return path;
        }
    }

    /** 日志文件路径；还没初始化时返回 {@code null}。 */
    public static Path current() {
        return path;
    }
}
