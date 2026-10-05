package com.douyindanmaku.core;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 往文件里记日志。
 *
 * <h2>为什么要这个东西</h2>
 * 模组的日志本来只打到游戏日志里，而游戏日志又长又吵，用户很难找到关键信息。
 * 「浏览器意外退出了」这类问题如果只提示一句话，用户和我都无从下手——
 * 真正有用的信息是<b>浏览器自己输出了什么、退出码是多少</b>。
 *
 * <p>所以这里单独写一个日志文件，把最关键的诊断信息记下来，
 * 用户遇到问题直接把文件发过来就行。
 *
 * <h2>几个刻意的设计</h2>
 * <ul>
 *   <li><b>有大小上限</b>：直播间弹幕量很大，日志不设限会涨到几百 MB。</li>
 *   <li><b>写失败绝不影响功能</b>：磁盘满、文件被占用等情况都吞掉，
 *       日志本来就只是辅助手段。</li>
 *   <li><b>带时间戳</b>：排查「是不是过一段时间才出问题」时必须有时间线。</li>
 * </ul>
 */
public final class FileLog {

    /** 单个日志文件的大小上限。超过了就轮转一份，只保留一份备份。 */
    private static final long MAX_BYTES = 1024L * 1024L;

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final Object LOCK = new Object();

    private static Path logFile;

    private FileLog() {
    }

    /** 设置日志文件位置。加载器启动时调用一次。 */
    public static void init(Path file) {
        synchronized (LOCK) {
            logFile = file;
        }
        info("=== 日志开始 ===");
    }

    /** 日志文件路径；没初始化时返回 {@code null}。 */
    public static Path file() {
        return logFile;
    }

    /** 记一条普通信息。 */
    public static void info(String message) {
        write("INFO", message, null);
    }

    /** 记一条错误，带异常堆栈。 */
    public static void error(String message, Throwable cause) {
        write("ERROR", message, cause);
    }

    private static void write(String level, String message, Throwable cause) {
        Path target = logFile;
        if (target == null) {
            return;
        }

        StringBuilder line = new StringBuilder(160);
        line.append(LocalDateTime.now().format(TIMESTAMP))
                .append(' ').append(level).append(' ')
                .append(message);
        if (cause != null) {
            StringWriter stack = new StringWriter();
            cause.printStackTrace(new PrintWriter(stack));
            line.append(System.lineSeparator()).append(stack);
        }
        line.append(System.lineSeparator());

        synchronized (LOCK) {
            try {
                rotateIfNeeded(target);
                Files.writeString(target, line.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException | RuntimeException ignored) {
                // 日志写不了不能影响功能
            }
        }
    }

    /** 文件太大就把它挪成 .old，然后重新开始写。 */
    private static void rotateIfNeeded(Path target) {
        try {
            if (Files.isRegularFile(target) && Files.size(target) > MAX_BYTES) {
                Path backup = target.resolveSibling(target.getFileName() + ".old");
                Files.move(backup, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // 轮转失败就算了，继续往原文件追加
        }
    }
}
