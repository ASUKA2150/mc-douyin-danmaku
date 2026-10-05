package com.douyindanmaku.core.chrome;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 浏览器启动诊断（不属于模组本体）。
 *
 * <h2>解决的问题</h2>
 * 模组启动浏览器时把它的输出丢掉了，所以浏览器一退出就只看到
 * 「意外退出了」，完全不知道原因。这个程序用<b>完全相同的参数</b>启动浏览器，
 * 但把退出码和错误输出都打出来。
 *
 * <p>常见原因对照：
 * <ul>
 *   <li><b>退出码 21</b> —— 配置目录被另一个浏览器实例占用。
 *       同一个 {@code --user-data-dir} 不能同时被两个浏览器使用。</li>
 *   <li><b>退出码 0 / 很快退出</b> —— 参数问题，或者浏览器把自己
 *       转交给了已有的实例（不认这个 user-data-dir）。</li>
 *   <li><b>一直不退出但读不到端口</b> —— 用户数据目录没有写权限，
 *       浏览器写不出 {@code DevToolsActivePort} 文件。</li>
 * </ul>
 *
 * <h2>用法</h2>
 * <pre>
 *   java ... BrowserLaunchProbe                       # 自动找浏览器 + 自动找配置目录
 *   java ... BrowserLaunchProbe &lt;浏览器exe&gt;
 *   java ... BrowserLaunchProbe &lt;浏览器exe&gt; &lt;配置目录&gt;
 *   java ... BrowserLaunchProbe &lt;浏览器exe&gt; &lt;配置目录&gt; --no-antithrottle
 * </pre>
 *
 * <p>它比模组的诊断强的地方：会先检查配置目录里有没有上一个实例留下的锁文件，
 * 并且能把「加防节流参数」和「不加」两种情况分开试，用来判断是不是参数引起的。
 */
public final class BrowserLaunchProbe {

    public static void main(String[] args) throws Exception {
        String browserArg = args.length > 0 ? args[0] : "";
        String profileArg = args.length > 1 ? args[1] : "";
        boolean antiThrottle = true;
        for (String arg : args) {
            if ("--no-antithrottle".equals(arg)) {
                antiThrottle = false;
            }
        }

        Path browser = browserArg.isBlank() ? ChromeFinder.find("", null) : Path.of(browserArg);
        if (browser == null || !Files.isRegularFile(browser)) {
            System.out.println("找不到浏览器。请把 chrome.exe / msedge.exe 的完整路径作为第一个参数传进来。");
            System.exit(1);
        }
        System.out.println("浏览器   : " + browser);

        Path profile = profileArg.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "dy-browser-probe-" + System.nanoTime())
                : Path.of(profileArg);
        Files.createDirectories(profile);
        System.out.println("配置目录 : " + profile);
        System.out.println("防节流   : " + (antiThrottle ? "开" : "关"));
        System.out.println();

        // ---- 检查有没有上一个实例留下的锁 ----
        System.out.println("=== 配置目录里的锁文件 ===");
        boolean hasLock = false;
        for (String lockName : new String[]{"SingletonLock", "SingletonCookie", "SingletonSocket", "lockfile"}) {
            Path lock = profile.resolve(lockName);
            if (Files.exists(lock, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                System.out.println("  发现 " + lockName + "  -> " + describeLink(lock));
                hasLock = true;
            }
        }
        if (!hasLock) {
            System.out.println("  （没有锁文件，干净）");
        }
        System.out.println();

        // ---- 看看现在有哪些浏览器进程在跑 ----
        System.out.println("=== 当前浏览器进程 ===");
        long browserCount = ProcessHandle.allProcesses()
                .filter(p -> p.info().command().map(c -> {
                    String lower = c.toLowerCase(java.util.Locale.ROOT);
                    return lower.endsWith("chrome.exe") || lower.endsWith("msedge.exe");
                }).orElse(false))
                .count();
        System.out.println("  chrome.exe / msedge.exe 进程数 = " + browserCount);
        System.out.println();

        // ---- 用和模组完全一样的参数启动 ----
        List<String> command = new ArrayList<>();
        command.add(browser.toString());
        command.add("--remote-debugging-port=0");
        command.add("--user-data-dir=" + profile);
        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        command.add("--disable-extensions");
        command.add("--mute-audio");
        command.add("--window-size=1024,700");
        command.add("--window-position=-32000,-32000");
        if (antiThrottle) {
            command.add("--disable-background-timer-throttling");
            command.add("--disable-backgrounding-occluded-windows");
            command.add("--disable-renderer-backgrounding");
            command.add("--disable-features="
                    + "CalculateNativeWinOcclusion,"
                    + "IntensiveWakeUpThrottling,"
                    + "FreezeUserAgent,"
                    + "MemorySaver,"
                    + "MemorySaverModeAggressiveness,"
                    + "HighEfficiencyModeAvailable,"
                    + "MediaEngagementBypassAutoplayPolicies");
            command.add("--disable-tab-discarding");
            command.add("--disable-background-mode");
        }
        command.add("about:blank");

        System.out.println("=== 启动命令 ===");
        System.out.println("  " + String.join(" \\\n    ", command));
        System.out.println();

        ProcessBuilder builder = new ProcessBuilder(command);
        // 关键区别：这里【不】丢掉输出，而是合并到标准输出让我们看到
        builder.redirectErrorStream(true);
        Process process = builder.start();
        System.out.println("已启动，pid = " + process.pid());
        System.out.println();

        // 一边读输出一边等端口文件
        Thread outputReader = new Thread(() -> {
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("  [浏览器输出] " + line);
                }
            } catch (Exception ignored) {
                // 进程结束了
            }
        }, "browser-output");
        outputReader.setDaemon(true);
        outputReader.start();

        Path activePortFile = profile.resolve("DevToolsActivePort");
        int port = -1;
        for (int attempt = 0; attempt < 200; attempt++) {
            if (!process.isAlive()) {
                System.out.println();
                System.out.println("*** 浏览器退出了！退出码 = " + process.exitValue() + " ***");
                System.out.println(explainExitCode(process.exitValue()));
                System.exit(1);
            }
            try {
                List<String> lines = Files.readAllLines(activePortFile);
                if (!lines.isEmpty()) {
                    port = Integer.parseInt(lines.get(0).trim());
                    break;
                }
            } catch (Exception notReadyYet) {
                // 继续等
            }
            Thread.sleep(100);
        }

        System.out.println();
        if (port > 0) {
            System.out.println("*** 成功：调试端口 = " + port + " ***");
            System.out.println("  浏览器还在跑: " + process.isAlive());
            System.out.println();
            System.out.println("再等 5 秒看看它会不会自己退出…");
            Thread.sleep(5000);
            if (process.isAlive()) {
                System.out.println("  5 秒后仍然存活 —— 启动参数没问题。");
            } else {
                System.out.println("  *** 5 秒后它退出了，退出码 = " + process.exitValue() + " ***");
                System.out.println(explainExitCode(process.exitValue()));
            }
        } else {
            System.out.println("*** 失败：等 20 秒也没拿到调试端口 ***");
            System.out.println("  浏览器还在跑: " + process.isAlive());
            System.out.println("  检查：" + activePortFile + " 是否存在/可读");
            if (Files.exists(activePortFile)) {
                System.out.println("  文件内容: " + Files.readAllLines(activePortFile));
            }
        }

        process.destroy();
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
        System.out.println();
        System.out.println("=== 结束 ===");
    }

    private static String describeLink(Path path) {
        try {
            if (Files.isSymbolicLink(path)) {
                return "符号链接 -> " + Files.readSymbolicLink(path);
            }
        } catch (Exception ignored) {
            // 读不了就算了
        }
        try {
            return "大小 " + Files.size(path) + " 字节";
        } catch (Exception ignored) {
            return "（无法读取）";
        }
    }

    private static String explainExitCode(int code) {
        return switch (code) {
            case 21 -> "  退出码 21 = 配置目录被占用。\n"
                    + "  同一个配置文件目录不能同时被两个浏览器使用。\n"
                    + "  解决办法：把配置里的 chromeProfileDir 换成一个新目录，\n"
                    + "  或者把上面列出的锁文件删掉再试。";
            case 0 -> "  退出码 0 = 正常退出。\n"
                    + "  通常意味着浏览器把这次启动「转交」给了已经打开的实例。\n"
                    + "  检查：是不是有别的浏览器进程也用了同一个 --user-data-dir。";
            default -> "  请把上面的 [浏览器输出] 一起反馈，那是定位问题的关键信息。";
        };
    }
}
