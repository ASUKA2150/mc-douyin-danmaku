package com.douyindanmaku.core.chrome;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 找浏览器装在哪（Chrome 或 Edge 都行）。
 *
 * <p>用户大概率不知道自己浏览器的完整路径，所以这里按常见位置挨个找。
 * 找不到也没关系——配置里可以手工填 {@code chromePath}。
 *
 * <h2>为什么 Edge 也能用</h2>
 * Edge 是 Chromium 内核的，Chrome DevTools Protocol 是同一套实现，
 * 本模组用到的能力（旁观 WebSocket 帧、模拟焦点、控制页面生命周期）
 * Edge 全都支持。已实测通过。
 *
 * <h2>探测顺序</h2>
 * 先 Chrome 后 Edge。装了两个的话用 Chrome —— 换句话说，
 * 想强制用 Edge 就把配置里的 {@code chromePath} 填成 {@code msedge.exe} 的完整路径。
 */
public final class ChromeFinder {

    private ChromeFinder() {
    }

    /**
     * 找 Chrome 可执行文件。
     *
     * @param configuredPath 配置里填的路径，留空表示自动查找
     * @param baseDirectory  相对路径的基准目录（游戏目录），可为 {@code null}
     * @return 可执行文件路径；找不到返回 {@code null}
     */
    public static Path find(String configuredPath, Path baseDirectory) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path configured = Path.of(configuredPath);
            if (!configured.isAbsolute() && baseDirectory != null) {
                configured = baseDirectory.resolve(configured);
            }
            // 用户明确指定了就用它，哪怕我们检查不出来（可能是绿色版、便携版）
            return Files.isRegularFile(configured) ? configured : null;
        }

        for (Path candidate : defaultCandidates()) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 各平台常见的 Chrome 安装位置。 */
    private static Path[] defaultCandidates() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        String localAppData = System.getenv("LOCALAPPDATA");
        String userHome = System.getProperty("user.home");

        if (os.contains("win")) {
            return new Path[]{
                    pathOf(programFiles, "Google\\Chrome\\Application\\chrome.exe"),
                    pathOf(programFilesX86, "Google\\Chrome\\Application\\chrome.exe"),
                    pathOf(localAppData, "Google\\Chrome\\Application\\chrome.exe"),
                    // 一些国内发行版也会装在这些位置
                    pathOf(programFiles, "Microsoft\\Edge\\Application\\msedge.exe"),
                    pathOf(programFilesX86, "Microsoft\\Edge\\Application\\msedge.exe"),
            };
        }
        if (os.contains("mac")) {
            return new Path[]{
                    Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"),
                    Path.of("/Applications/Chromium.app/Contents/MacOS/Chromium"),
                    Path.of("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"),
                    userHome == null ? null
                            : Path.of(userHome, "Applications/Google Chrome.app/Contents/MacOS/Google Chrome"),
            };
        }
        // Linux 之类
        return new Path[]{
                Path.of("/usr/bin/google-chrome"),
                Path.of("/usr/bin/google-chrome-stable"),
                Path.of("/usr/bin/chromium"),
                Path.of("/usr/bin/chromium-browser"),
                Path.of("/snap/bin/chromium"),
                Path.of("/usr/bin/microsoft-edge"),
        };
    }

    private static Path pathOf(String directory, String relative) {
        return directory == null || directory.isBlank() ? null : Path.of(directory, relative);
    }
}
