package com.douyindanmaku.core.chrome;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * 一个很小的 Chrome DevTools Protocol（CDP）客户端。
 *
 * <h2>CDP 是什么</h2>
 * Chrome 带了一个调试接口。启动时加 {@code --remote-debugging-port=0}，
 * 它就会在本机开一个端口，允许别的程序用 WebSocket 连上来「看」和「控制」浏览器。
 * 我们要用的只有「看」：<b>旁观页面自己收到了哪些 WebSocket 数据</b>。
 *
 * <h2>协议长什么样</h2>
 * 所有消息都是 JSON，分两种：
 * <pre>
 *   命令（我们发给 Chrome）：{"id":1, "method":"Network.enable", "params":{}}
 *   响应（Chrome 回我们）：  {"id":1, "result":{...}}    ← 靠 id 对上
 *   事件（Chrome 主动推）：  {"method":"Network.webSocketFrameReceived", "params":{...}}
 *                          ← 没有 id，所以用「有没有 id」来区分响应和事件
 * </pre>
 *
 * <h2>为什么可以不带 sessionId</h2>
 * 连「浏览器级」的 WebSocket 时，操作某个标签页需要先 attach 拿到 sessionId。
 * 但 {@code /json/list} 接口会直接给出每个标签页自己的 WebSocket 地址，
 * 连那个地址就不用 sessionId 了——少一层，更不容易出错。本类走的就是后者。
 *
 * <h2>踩过的坑（都在这份代码里处理了）</h2>
 * <ol>
 *   <li><b>回调里绝对不能抛异常</b>。JDK 的 WebSocket 只要 listener 抛一次异常，
 *       整个连接就被永久关闭，之后连发消息都会失败。所以每个回调都包了 try/catch。</li>
 *   <li><b>默认消息大小限制只有 64KB</b>，而弹幕帧可能有几十 KB、base64 之后更大。
 *       必须显式调大，否则大帧会被截断。</li>
 *   <li><b>{@code onOpen} 里必须 {@code request(1)}</b>，
 *       否则一条消息都收不到（JDK 的背压机制）。</li>
 *   <li><b>不要在回调里做重活</b>。protobuf + gzip 解压很吃 CPU，
 *       直接在网络回调里做会拖慢甚至卡住整个连接。本类把数据丢进队列，
 *       由另一个线程处理。</li>
 * </ol>
 */
final class CdpConnection {

    private final HttpClient httpClient;
    private final Path profileDir;

    private Process chromeProcess;
    private int port = -1;

    /** 浏览器级连接的地址（{@code /json/version} 里拿）。 */
    private String browserWsUrl;

    private CdpWebSocket browserSocket;
    private CdpWebSocket pageSocket;

    /**
     * 是不是「浏览器把启动转交给了已存在的实例」。
     *
     * <p>那种情况下我们启动的进程已经退出，真正在跑的是别人的进程，
     * 所以收尾时<b>不能</b>去关它——那会把用户自己的浏览器关掉。
     */
    private boolean handedOffToExistingBrowser;

    /** 事件监听器，由外部设置。 */
    private volatile BiConsumer<String, JsonObject> eventListener;

    /** 调试用：收到的消息统计。排查「为什么收不到事件」时很有用。 */
    final java.util.concurrent.atomic.AtomicInteger messagesReceived =
            new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger responsesReceived =
            new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger eventsReceived =
            new java.util.concurrent.atomic.AtomicInteger();
    final java.util.Set<String> eventMethodsSeen = ConcurrentHashMap.newKeySet();
    /** 监听器实际被调用的次数（用来区分「事件没到」和「事件到了但没发出去」）。 */
    final java.util.concurrent.atomic.AtomicInteger listenerInvocations =
            new java.util.concurrent.atomic.AtomicInteger();

    CdpConnection(Path profileDir) {
        this.profileDir = profileDir;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 设置事件监听器。
     *
     * <p><b>必须能把监听器传播给已经建立的连接</b>。
     * 否则就会出现这个很难查的现象：调用方先连上、后设监听器，
     * 事件确实到了 {@code handleMessage}，但因为连接上没挂监听器而被静默丢弃——
     * 表现为「什么都收不到」，而且完全不报错。
     */
    void setEventListener(BiConsumer<String, JsonObject> listener) {
        this.eventListener = listener;
        if (pageSocket != null) {
            pageSocket.debugOwner = this;
            pageSocket.setEventListener(listener);
        }
        if (browserSocket != null) {
            browserSocket.debugOwner = this;
            browserSocket.setEventListener(listener);
        }
    }

    /** 调试用：描述页面连接上的监听器状态。 */
    /** 调试用：描述页面连接的监听器状态。 */
    String debugDescribePageListener() {
        CdpWebSocket current = pageSocket;
        return current == null ? "null" : current.debugDescribeListener();
    }

    // ==================================================================
    //  启动 / 关闭
    // ==================================================================

    /**
     * 启动 Chrome 并连上它的调试接口。
     *
     * <h2>为什么有这么多 --disable 参数</h2>
     * 你全屏玩 Minecraft 的时候，这个 Chrome 窗口会被系统判定为「被遮挡」，
     * Chrome 就会开始省电：后台标签页的定时器降到一分钟一次、渲染进程降级、
     * 极端情况下直接把整个标签页「丢弃」（销毁页面）。<b>页面一没，我们旁观的
     * WebSocket 就断了。</b>
     *
     * <p>下面这些参数就是用来关掉这些省电行为的。它们都不会影响别的 Chrome
     * 实例，因为只作用在我们自己启动的这个进程上。
     *
     * @param chromeExecutable Chrome 的完整路径
     * @param windowSize       窗口尺寸，形如 {@code 1280,800}
     * @param offscreen        是否把窗口挪到屏幕外
     * @return 调试端口
     */
    int launchChrome(Path chromeExecutable, String windowSize, boolean offscreen) throws IOException {
        // 上一次运行如果没退干净，会在配置目录里留下锁文件，
        // 导致这一次启动被浏览器直接拒绝（退出码 21）。先清掉。
        removeStaleLocks();
        // 同样，上一次留下的调试端口文件也要清掉，
        // 否则会读到一个已经失效的端口，然后连接被拒绝。
        deleteStaleActivePortFile();

        List<String> command = new ArrayList<>(List.of(
                chromeExecutable.toString(),
                // 端口写 0 表示「随便挑一个空闲端口」，Chrome 会把实际端口
                // 写到 <profileDir>/DevToolsActivePort 文件里，我们去读就行。
                // 这样不会和别的程序抢端口。
                "--remote-debugging-port=0",
                "--user-data-dir=" + profileDir,
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-extensions",
                "--mute-audio",
                "--window-size=" + windowSize,

                // ---- 防止「被遮挡的后台标签页」被省电机制搞死 ----
                // 这几条是长时间直播能稳住的关键，缺一条都可能中途断连。

                // 关闭后台标签页的定时器节流
                "--disable-background-timer-throttling",
                // 关闭「窗口被遮挡就降低渲染优先级」
                "--disable-backgrounding-occluded-windows",
                // 关闭整个后台渲染进程的降级
                "--disable-renderer-backgrounding",
                // 不根据窗口遮挡计算原生窗口可见性（全屏玩 MC 时最相关的一条），
                // 以及关掉各种「省电/省内存」的自动行为。
                //
                // 注意：--disable-features 只能出现一次！写两次的话 Chrome 只用最后一个，
                // 前面的会被静默忽略。所以所有特性名必须合并成一条。
                "--disable-features="
                        + "CalculateNativeWinOcclusion,"         // 遮挡检测（全屏 MC 时最关键）
                        + "IntensiveWakeUpThrottling,"            // 长时间不用就把定时器降到 1 分钟
                        + "FreezeUserAgent,"                      // 标签页冻结
                        + "MemorySaver,"                          // 内存节省器会丢弃不活跃标签页
                        + "MemorySaverModeAggressiveness,"
                        + "HighEfficiencyModeAvailable,"
                        + "MediaEngagementBypassAutoplayPolicies",
                // 防止标签页被「丢弃」（丢弃 = 直接销毁页面，连接必断）
                "--disable-tab-discarding",
                // 让后台标签页也保持正常优先级
                "--disable-background-mode",

                // 注意：这里【故意】不加 --headless。
                // 实测抖音在无头模式下根本不会建立弹幕连接，
                // 所以必须让 Chrome 以有窗口的方式运行。
                "about:blank"));

        if (offscreen) {
            // 挪到屏幕外，眼不见为净。注意仍然是有窗口模式。
            // 配合上面的 --disable-backgrounding-occluded-windows，
            // 被挪到屏幕外也不会被当成后台。
            command.add(command.size() - 1, "--window-position=-32000,-32000");
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        // 把实际要执行的命令记下来——排查启动问题时这是第一手信息
        com.douyindanmaku.core.DanmakuLog.info("启动浏览器，配置目录：" + profileDir);
        com.douyindanmaku.core.DanmakuLog.info("浏览器路径：" + chromeExecutable);
        // 关键：把浏览器的输出合并到标准输出并读出来记进日志。
        // 之前这里是 DISCARD（直接丢掉），结果浏览器一退出就只知道「退出了」，
        // 完全不知道原因——那种日志对排查毫无帮助。
        builder.redirectErrorStream(true);
        try {
            chromeProcess = builder.start();
        } catch (IOException startFailed) {
            throw new IOException("启动浏览器进程失败：" + startFailed.getMessage()
                    + "。请确认路径正确、且有权限执行它。", startFailed);
        }
        com.douyindanmaku.core.DanmakuLog.info("浏览器进程已启动，pid=" + chromeProcess.pid());
        startReadingBrowserOutput(chromeProcess);

        port = waitForDebugPort();
        com.douyindanmaku.core.DanmakuLog.info("浏览器调试端口就绪：" + port);
        return port;
    }

    /**
     * 把浏览器的输出读出来记进日志。
     *
     * <p>浏览器在启动失败时会往 stderr 打原因（比如配置目录被占用），
     * 这是排查启动问题最有价值的信息。
     *
     * <p>只记前若干行：正常运行中的浏览器会一直输出各种警告，
     * 全记下来会把日志冲爆。
     */
    private void startReadingBrowserOutput(Process process) {
        Thread outputThread = new Thread(() -> {
            int linesLogged = 0;
            try (var buffered = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(),
                            java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = buffered.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    if (linesLogged < MAX_BROWSER_LOG_LINES) {
                        linesLogged++;
                        com.douyindanmaku.core.DanmakuLog.info("[浏览器] " + line);
                    } else if (linesLogged == MAX_BROWSER_LOG_LINES) {
                        linesLogged++;
                        com.douyindanmaku.core.DanmakuLog.info("[浏览器] （后续输出已省略）");
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // 进程结束了，正常
            }
        }, "douyin-danmaku-browser-output");
        outputThread.setDaemon(true);
        outputThread.start();
    }

    /** 浏览器输出最多记多少行。 */
    private static final int MAX_BROWSER_LOG_LINES = 40;

    /**
     * 清掉配置目录里上一个实例留下的锁文件。
     *
     * <p>浏览器会在这个目录放 {@code SingletonLock} 之类的文件来保证
     * 「同一个配置目录只被一个实例使用」。如果上一个实例是被强杀的
     * （游戏崩溃、任务管理器结束进程），锁会留下来，导致下一次启动
     * <b>直接被拒绝（退出码 21）</b>——表现就是「浏览器意外退出了」。
     *
     * <p>只在确认没有其它实例占用这个目录时才删，避免误删正在用的锁。
     */
    private void removeStaleLocks() {
        String[] lockNames = {"SingletonLock", "SingletonCookie", "SingletonSocket", "lockfile"};
        boolean removedAny = false;

        for (String lockName : lockNames) {
            Path lock = profileDir.resolve(lockName);
            if (!Files.exists(lock, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try {
                Files.deleteIfExists(lock);
                removedAny = true;
                com.douyindanmaku.core.DanmakuLog.info("清掉了上次残留的锁文件 " + lockName);
            } catch (IOException cannotDelete) {
                // 删不掉说明可能真有实例在用，交给后续的启动失败去报错
                com.douyindanmaku.core.DanmakuLog.info(
                        "发现锁文件 " + lockName + " 但删不掉：" + cannotDelete.getMessage());
            }
        }

        if (removedAny) {
            com.douyindanmaku.core.DanmakuLog.info("已清理配置目录里的残留锁：" + profileDir);
        }
    }

    /**
     * 给「浏览器不能用了」配一句能指导下一步的说明。
     *
     * <p>光说「退出了」用户没法处理，必须把退出码翻译成人话。
     */
    String explainExit() {
        Process process = chromeProcess;
        if (process == null) {
            return "浏览器进程对象不存在";
        }
        if (process.isAlive()) {
            return "浏览器进程仍在运行";
        }
        int code;
        try {
            code = process.exitValue();
        } catch (IllegalThreadStateException stillRunning) {
            return "浏览器进程仍在运行";
        }
        return switch (code) {
            case 0 -> "启动被转交给了已有的浏览器实例（退出码 0），"
                    + "但那个实例的调试通道也连不上。"
                    + "请关掉之前所有由本模组启动的浏览器进程（配置目录见日志），再重试。";
            case 21 -> "配置目录被另一个浏览器实例占用（退出码 21）。"
                    + "模组下次会自动换一个临时目录重试。";
            default -> "退出码 " + code + "（详细原因见日志文件里的 [浏览器] 行）";
        };
    }

    /**
     * 等浏览器把实际调试端口写出来。
     *
     * <h2>三个必须处理的坑</h2>
     * <ol>
     *   <li><b>旧文件陷阱</b>：{@code DevToolsActivePort} 是浏览器写在配置目录里的，
     *       而配置目录现在是固定的（为了保住登录状态）。所以这个文件
     *       <b>可能是上一次运行留下的</b>——文件一存在就立刻返回的话，
     *       拿到的是一个<b>已经没人监听的端口</b>，后面连接会直接
     *       {@code ConnectException}。所以启动前必须先把它删掉。</li>
     *   <li><b>端口要真的能连上</b>：文件写出来了不代表监听已经就绪
     *       （中间有个短暂窗口）。所以拿到端口之后要实际探测一次。</li>
     *   <li><b>「转交」不写新文件</b>：如果配置目录被一个<b>还活着的</b>
     *       浏览器实例占着，新进程会把启动转交过去并立刻退出，
     *       <b>不会写新的端口文件</b>。那种情况下我们永远等不到文件，
     *       所以进程一退出就要立刻判定失败，别傻等 40 秒。</li>
     * </ol>
     */
    private int waitForDebugPort() throws IOException {
        Path activePortFile = profileDir.resolve("DevToolsActivePort");
        // 记录上一次读到的端口，用来处理「转交」那种情况
        int lastSeenPort = -1;

        // 最多等 40 秒（冷启动 + 杀毒软件首次扫描可能很慢）
        for (int attempt = 0; attempt < 400; attempt++) {
            try {
                List<String> lines = Files.readAllLines(activePortFile, StandardCharsets.UTF_8);
                if (!lines.isEmpty()) {
                    int parsed = Integer.parseInt(lines.get(0).trim());
                    if (parsed > 0) {
                        lastSeenPort = parsed;
                        if (isPortAccepting(parsed)) {
                            return parsed;
                        }
                    }
                }
            } catch (IOException | NumberFormatException notReadyYet) {
                // 文件还没写出来，继续等
            }

            if (!chromeProcess.isAlive()) {
                // 进程退出了。有两种可能，要分清楚：
                //
                //   1. 真的启动失败（比如配置目录被占、参数有问题）→ 报错
                //   2. 「转交给了已经活着的实例」——那种情况下新进程
                //      故意立刻退出，但浏览器本体还在服务调试端口。
                //      这不是错误，接着用那个端口就行。
                if (lastSeenPort > 0 && isPortAccepting(lastSeenPort)) {
                    handedOffToExistingBrowser = true;
                    com.douyindanmaku.core.DanmakuLog.info(
                            "浏览器把启动转交给了已在运行的实例（端口 " + lastSeenPort + "），继续使用它");
                    return lastSeenPort;
                }
                int exitCode;
                try {
                    exitCode = chromeProcess.exitValue();
                } catch (IllegalThreadStateException race) {
                    exitCode = -1;
                }
                // 浏览器为什么退出，日志里的 [浏览器] 行会有它的原话
                throw new IOException("浏览器启动后立刻退出了（退出码 " + exitCode + "）"
                        + explainExitCode(exitCode)
                        + "。详细原因见日志文件里以 [浏览器] 开头的行。");
            }
            sleepQuietly(100);
        }
        throw new IOException("等了 40 秒还没拿到可用的浏览器调试端口。"
                + "请确认浏览器能正常启动，或换一个 chromeProfileDir。"
                + "（浏览器仍在运行：" + chromeProcess.isAlive() + "）");
    }

    /**
     * 探测端口是不是真的在监听。
     *
     * <p>只要 HTTP 有响应就算通——哪怕是错误码，也说明端口后面有东西在跑。
     * 连接被拒绝就说明还没就绪。
     */
    private boolean isPortAccepting(int candidatePort) {
        try {
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + candidatePort + "/json/version"))
                            .timeout(Duration.ofSeconds(2))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() > 0;
        } catch (IOException | RuntimeException notReady) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 启动前删掉上一次留下的端口文件。
     *
     * <p>不删的话会读到旧端口，然后拿着一个没人监听的端口去连接，
     * 表现就是莫名其妙的 {@code ConnectException}。
     */
    private void deleteStaleActivePortFile() {
        Path activePortFile = profileDir.resolve("DevToolsActivePort");
        try {
            if (Files.deleteIfExists(activePortFile)) {
                com.douyindanmaku.core.DanmakuLog.info(
                        "清掉了上次残留的调试端口文件（否则会读到已经失效的端口）");
            }
        } catch (IOException cannotDelete) {
            com.douyindanmaku.core.DanmakuLog.info(
                    "残留的调试端口文件删不掉：" + cannotDelete.getMessage());
        }
    }

    /** 把退出码翻译成人话。 */
    private static String explainExitCode(int exitCode) {
        return switch (exitCode) {
            case 21 -> "。退出码 21 = 配置目录被另一个浏览器实例占用，"
                    + "同一个配置目录不能同时被两个浏览器使用。"
                    + "把配置里的 chromeProfileDir 换成别的目录试试。";
            case 0 -> "。退出码 0 = 浏览器把这次启动转交给了已存在的实例，"
                    + "通常也是配置目录重复使用导致的。";
            default -> "";
        };
    }

    /**
     * 连上浏览器级调试接口，并打开一个新的标签页。
     *
     * @return 新标签页的标识
     */
    String openBlankPage() throws IOException {
        browserWsUrl = readBrowserWebSocketUrl();
        // 监听器在建立连接时就一起装好，避免出现「事件到了但还没挂监听器」的窗口
        browserSocket = CdpWebSocket.connect(httpClient, browserWsUrl, eventListener);

        // 新建一个标签页。注意不要传 forTab —— 新版本 Chrome 会因此返回
        // 一个没有 Page/Network 能力的「tab」类型目标，后面全都用不了。
        JsonObject result = browserSocket.call("Target.createTarget",
                "{\"url\":\"about:blank\",\"background\":true}");

        JsonElement targetId = result.get("targetId");
        if (targetId == null || targetId.isJsonNull()) {
            throw new IOException("Chrome 没有返回新标签页的标识：" + result);
        }
        return targetId.getAsString();
    }

    /**
     * 连上某个标签页自己的调试接口。
     *
     * <p>注意必须在导航<b>之前</b>调用 {@code Network.enable}——Chrome 不会
     * 补发已经发生过的网络事件，迟了就拿不到弹幕通道的地址了。
     */
    void attachToPage(String targetId) throws IOException {
        String pageWsUrl = findPageWebSocketUrl(targetId);
        // 同样：监听器和 debugOwner 都在 connect 里一次性装好，最后才发布到字段
        CdpWebSocket created = CdpWebSocket.connect(httpClient, pageWsUrl, eventListener);
        created.debugOwner = this;
        this.pageSocket = created;
    }

    /**
     * 调试用：描述各连接的状态与监听器情况。
     *
     * <p>排查「事件收不到」这类问题时很有用——`pageSocket=已挂上(...)`
     * 说明监听器挂好了，`null` 说明调用方还没设置。
     */
    String debugDescribeSockets() {
        return "pageSocket=" + (pageSocket == null ? "null" : pageSocket.debugDescribeListener())
                + ", browserSocket=" + (browserSocket == null ? "null" : browserSocket.debugDescribeListener());
    }

    /** 打开网络事件监听。必须在导航前调用。 */
    void enableNetwork() throws IOException {
        // 参数故意传空对象：三个 size 限制参数只管 XHR/fetch，和 WebSocket 无关，
        // 实测即使 1MB 的帧也能完整送达，不需要调。
        pageSocket.call("Network.enable", "{}");
    }

    /**
     * 把页面「钉」在活跃状态，防止 Chrome 的省电机制把它降级或冻结。
     *
     * <p>必须在导航<b>之前</b>调用，之后还要靠 {@link #keepPageActive()}
     * 定期续命。
     */
    void prepareAntiThrottle() throws IOException {
        // 让页面以为自己是有焦点的（即使窗口被挪到屏幕外或者被游戏挡住）。
        // 页面失去焦点时，很多站点会主动降低刷新率甚至暂停播放。
        pageSocket.call("Emulation.setFocusEmulationEnabled", "{\"enabled\":true}");
    }

    /**
     * 保活一次：确认页面仍然是「活跃」状态，并给一个轻微的交互信号。
     *
     * <p>抖音页面上有「长时间无操作自动暂停」的逻辑，所以除了告诉 Chrome
     * 「这个标签页是活跃的」，还要周期性给页面一点输入事件，让页面自己的
     * 无操作计时器一直归零。
     *
     * <p>所有失败都被吞掉——保活失败不该影响正常的收弹幕。
     */
    void keepPageActive() {
        try {
            pageSocket.call("Page.setWebLifecycleState", "{\"state\":\"active\"}");
        } catch (RuntimeException | IOException ignored) {
            // 保活失败不影响收弹幕，忽略
        }
        try {
            // 一个「无害的鼠标移动」，坐标在窗口内，不点任何东西。
            // 目的是让页面的无操作计时器重置。
            pageSocket.call("Input.dispatchMouseEvent",
                    "{\"type\":\"mouseMoved\",\"x\":10,\"y\":10,\"modifiers\":0}");
        } catch (RuntimeException | IOException ignored) {
            // 同上
        }
    }

    /** 页面当前是否还在（用来判断浏览器有没有被关掉或页面被丢弃）。 */
    boolean isPageAlive() {
        if (pageSocket == null) {
            return false;
        }
        try {
            // 发一个极轻量的命令，能返回就说明页面还在
            pageSocket.call("Runtime.evaluate",
                    "{\"expression\":\"1\",\"returnByValue\":true}");
            return true;
        } catch (RuntimeException | IOException gone) {
            return false;
        }
    }

    /**
     * 浏览器是否还在工作。
     *
     * <p><b>注意：这里不能只看我们自己启动的那个进程是否存活。</b>
     * Chrome 有一个「把启动转交给已有实例」的行为：如果同一个配置目录
     * 已经被另一个 Chrome 占用，新启动的进程会把请求转交过去、
     * <b>自己立刻退出（退出码 0）</b>，而浏览器本体还在正常服务调试端口。
     *
     * <p>之前正是把这个「转交后退出」误判成了「浏览器死了」，
     * 结果陷入「连不上 → 重连 → 又转交 → 又判定死了」的死循环。
     *
     * <p>所以判断标准改成：<b>调试通道还能不能应答</b>。
     * 只要 {@code Browser.getVersion} 有回应，浏览器就是活的，
     * 我们启动的那个进程是不是还在完全无所谓。
     */
    boolean isBrowserReachable() {
        CdpWebSocket socket = browserSocket;
        if (socket == null) {
            return false;
        }
        try {
            socket.call("Browser.getVersion", "{}");
            return true;
        } catch (RuntimeException | IOException gone) {
            return false;
        }
    }

    /**
     * 「浏览器还活着吗」的完整判断。
     *
     * <p>先看调试通道（最可靠），通道不通时才退回到看进程。
     * 两者取「或」：进程退出了但通道还通，说明是正常的转交行为，不算死。
     */
    boolean isBrowserUsable() {
        if (isBrowserReachable()) {
            return true;
        }
        // 通道不通，再看进程：进程还在的话可能是通道刚断，给个机会
        return isAlive();
    }

    /** 让页面开始加载。 */
    void navigate(String url) throws IOException {
        CdpWebSocket socket = pageSocket;
        if (socket == null) {
            // 调用顺序错了：必须先 openBlankPage() 再 attachToPage()，
            // 否则这里没有页面连接可用。给个明确提示，别让它变成 NPE。
            throw new IOException("还没有连接到页面，无法导航（调用顺序错误："
                    + "必须先 openBlankPage 再 attachToPage）");
        }
        socket.call("Page.navigate", "{\"url\":" + jsonString(url) + "}");
    }

    /**
     * 打开抖音直播首页，让用户登录。
     *
     * <p>用首页而不是某个直播间：登录是站点级别的，首页有登录入口，
     * 用户操作起来最直接。
     */
    void navigateLoginPage() throws IOException {
        navigate("https://live.douyin.com/");
    }

    /**
     * 在页面里执行一段 JavaScript 并取回结果。
     *
     * <p>当前功能用不到它（我们是纯旁观，不注入），留着是为了排查问题：
     * 比如读一下页面标题、确认页面到底加载成了什么样。
     *
     * @return 表达式的结果转成字符串；取不到返回 {@code null}
     */
    String evaluate(String expression) throws IOException {
        JsonObject result = pageSocket.call("Runtime.evaluate",
                "{\"expression\":" + jsonString(expression) + ",\"returnByValue\":true}");
        JsonElement value = result.get("result");
        if (value == null || !value.isJsonObject()) {
            return null;
        }
        JsonElement inner = value.getAsJsonObject().get("value");
        return inner == null || inner.isJsonNull() ? null : inner.getAsString();
    }

    /** 关闭一切并杀掉 Chrome。 */
    void close() {
        // 先请浏览器自己退出。这一步是必要的：如果之前发生过「启动转交」，
        // 那么真正在跑的是另一个进程，我们手里这个进程对象早就退出了，
        // 光 destroy() 它是关不掉浏览器的。
        //
        // 但反过来也要小心：如果这次本身就是「转交到别人的实例」，
        // 那关掉它等于把用户自己的浏览器关了——所以那种情况跳过。
        CdpWebSocket socketToClose = browserSocket;
        if (socketToClose != null && !handedOffToExistingBrowser) {
            try {
                // Browser.close 会让整个浏览器退出（包括转交过来的那个实例）
                socketToClose.call("Browser.close", "{}");
                socketToClose.close();
            } catch (RuntimeException | IOException ignored) {
                // 已经不可达了，走下面的强杀
            }
        } else if (socketToClose != null) {
            // 只是断开调试连接，不动浏览器
            socketToClose.close();
        }

        if (pageSocket != null) {
            pageSocket.close();
            pageSocket = null;
        }
        browserSocket = null;

        if (chromeProcess != null) {
            chromeProcess.destroy();
            try {
                // 超时故意设得短：这一步是在 Minecraft 主线程上调用的
                // （切换直播间时），等太久游戏会明显卡住。
                // 正常收到 Browser.close 之后浏览器退得很快；
                // 真退不掉就强杀，反正每次连接用的都是新的配置目录。
                if (!chromeProcess.waitFor(2, TimeUnit.SECONDS)) {
                    chromeProcess.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                chromeProcess.destroyForcibly();
            }
            chromeProcess = null;
        }
    }

    boolean isAlive() {
        return chromeProcess != null && chromeProcess.isAlive();
    }

    // ==================================================================
    //  HTTP 接口（/json/...）
    // ==================================================================

    /** 读 {@code /json/version}，取浏览器级 WebSocket 地址。 */
    private String readBrowserWebSocketUrl() throws IOException {
        String body = httpGet("http://127.0.0.1:" + port + "/json/version");
        JsonElement url = JsonParser.parseString(body).getAsJsonObject().get("webSocketDebuggerUrl");
        if (url == null || url.isJsonNull()) {
            throw new IOException("Chrome 的 /json/version 里没有调试地址：" + body);
        }
        return url.getAsString();
    }

    /** 在 {@code /json/list} 里按标识找到标签页自己的 WebSocket 地址。 */
    private String findPageWebSocketUrl(String targetId) throws IOException {
        String body = httpGet("http://127.0.0.1:" + port + "/json/list");
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonArray()) {
            throw new IOException("Chrome 的 /json/list 返回了意外的内容：" + body);
        }
        for (JsonElement element : root.getAsJsonArray()) {
            JsonObject target = element.getAsJsonObject();
            JsonElement id = target.get("id");
            if (id != null && !id.isJsonNull() && targetId.equals(id.getAsString())) {
                JsonElement url = target.get("webSocketDebuggerUrl");
                if (url != null && !url.isJsonNull()) {
                    return url.getAsString();
                }
            }
        }
        throw new IOException("在 Chrome 的目标列表里找不到标签页 " + targetId
                + "。这通常说明 Chrome 版本行为和预期不一致。");
    }

    private String httpGet(String url) throws IOException {
        try {
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("请求 " + url + " 返回了 " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("请求 Chrome 调试接口被中断", interrupted);
        }
    }

    // ==================================================================
    //  小工具
    // ==================================================================

    static String jsonString(String raw) {
        StringBuilder builder = new StringBuilder(raw.length() + 2);
        builder.append('"');
        for (int index = 0; index < raw.length(); index++) {
            char current = raw.charAt(index);
            switch (current) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (current < 0x20) {
                        builder.append(String.format("\\u%04x", (int) current));
                    } else {
                        builder.append(current);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================================================================
    //  WebSocket 包装：把「发命令 / 收响应 / 收事件」这套逻辑收在一起
    // ==================================================================

    /**
     * 包一层 JDK 的 WebSocket，让它用起来像「发一个命令等一个结果」。
     */
    static final class CdpWebSocket implements WebSocket.Listener {

        private final AtomicInteger nextId = new AtomicInteger(1);
        private final Map<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();

        /** 收消息时要拼，因为 JDK 的分片回调不保证一次给全。 */
        private final StringBuilder buffer = new StringBuilder();

        /** 底层连接。由 connect() 填好，其它地方只读。 */
        private volatile WebSocket socket;

        /** 事件监听器。由 connect() 在发起连接之前就填好，保证不会漏事件。 */
        private volatile BiConsumer<String, JsonObject> eventListener;

        /** 指向外层对象，用于把调试计数汇总上去。 */
        volatile CdpConnection debugOwner;

        /** 调试用：描述监听器是否已挂上。 */
        String debugDescribeListener() {
            return eventListener == null ? "null" : "已挂上(" + eventListener.getClass().getName() + ")";
        }

        /**
         * 补挂事件监听器。
         *
         * <p>用途：调用方可能在连接建立之后才设置监听器。
         * 这种顺序是合法的，所以必须支持补挂——否则事件会到了却被静默丢掉。
         */
        void setEventListener(BiConsumer<String, JsonObject> listener) {
            this.eventListener = listener;
        }

        private CdpWebSocket() {
            // 字段由 connect() 统一填好后再交给调用方，避免出现「半初始化」的对象
        }

        /**
         * 建立连接。
         *
         * <p>关键点：<b>对象必须在返回给调用方之前就完全初始化好</b>
         * （socket 和事件监听器都挂上）。之前这里用「先建空对象、连上后再补字段」
         * 的写法，结果出现了「事件明明收到了、监听器却还是 null」的问题——
         * 调用方拿到的是半成品。
         *
         * <p>做法：先建对象，再把「已经建好的对象」登记到一个
         * {@link CompletableFuture} 里，最后才真正发起连接。
         * 这样 WebSocket 的回调无论什么时候来，都能找到正确的对象。
         */
        static CdpWebSocket connect(HttpClient client,
                                    String url,
                                    BiConsumer<String, JsonObject> listener) throws IOException {
            CdpWebSocket connection = new CdpWebSocket();
            connection.eventListener = listener;

            try {
                connection.socket = client.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .buildAsync(URI.create(url), connection)
                        .get(20, TimeUnit.SECONDS);
                return connection;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("连接 Chrome 调试接口被中断", interrupted);
            } catch (ExecutionException | TimeoutException failed) {
                throw new IOException("连接 Chrome 调试接口失败：" + failed.getMessage(), failed);
            }
        }

        /** 发一个命令并等它的结果。 */
        JsonObject call(String method, String paramsJson) throws IOException {
            int id = nextId.getAndIncrement();
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            pending.put(id, future);

            String message = "{\"id\":" + id + ",\"method\":" + jsonString(method)
                    + ",\"params\":" + (paramsJson == null ? "{}" : paramsJson) + "}";

            try {
                socket.sendText(message, true).get(10, TimeUnit.SECONDS);
                return future.get(20, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("等待 " + method + " 的结果时被中断", interrupted);
            } catch (ExecutionException | TimeoutException failed) {
                throw new IOException("执行 " + method + " 失败：" + failed.getMessage(), failed);
            } finally {
                pending.remove(id);
            }
        }

        void close() {
            WebSocket current = socket;
            if (current == null) {
                return;
            }
            try {
                current.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (RuntimeException ignored) {
                // 已经断了
            }
        }

        // ------------------------------------------------------------------
        //  WebSocket.Listener 回调。每个都必须吞掉异常 ——
        //  JDK 的实现里 listener 抛一次异常就会把整个 WebSocket 永久关闭，
        //  之后连 sendText 都会失败。
        // ------------------------------------------------------------------

        @Override
        public void onOpen(WebSocket webSocket) {
            // 必须显式要消息，否则一条都收不到
            try {
                webSocket.request(1);
            } catch (RuntimeException ignored) {
                // 忽略
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            try {
                onMessageChunk(data, last);
            } catch (RuntimeException ignored) {
                // 单条消息处理失败不能拖垮连接
            } finally {
                try {
                    webSocket.request(1);
                } catch (RuntimeException ignored) {
                    // 忽略
                }
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            try {
                onConnectionError(error);
            } catch (RuntimeException ignored) {
                // 忽略
            }
        }

        private void onMessageChunk(CharSequence data, boolean last) {
            try {
                buffer.append(data);
                if (last) {
                    String message = buffer.toString();
                    buffer.setLength(0);
                    handleMessage(message);
                }
            } catch (RuntimeException ignored) {
                // 单条消息处理失败不能拖垮连接
            }
        }

        private void onConnectionError(Throwable error) {
            // 连接已断，把所有等待中的命令叫醒，免得它们一直挂到超时
            IOException failure = new IOException("调试连接出错：" + error);
            for (CompletableFuture<JsonObject> future : pending.values()) {
                future.completeExceptionally(failure);
            }
            pending.clear();
        }

        private void handleMessage(String message) {
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(message);
            } catch (RuntimeException notJson) {
                return;
            }
            if (!parsed.isJsonObject()) {
                return;
            }
            JsonObject object = parsed.getAsJsonObject();

            CdpConnection owner = debugOwner;
            if (owner != null) {
                owner.messagesReceived.incrementAndGet();
            }

            JsonElement idElement = object.get("id");
            if (idElement != null && !idElement.isJsonNull()) {
                // 有 id 的 = 某个命令的响应
                if (owner != null) {
                    owner.responsesReceived.incrementAndGet();
                }
                CompletableFuture<JsonObject> future = pending.remove(idElement.getAsInt());
                if (future == null) {
                    return;
                }
                JsonElement result = object.get("result");
                future.complete(result != null && result.isJsonObject()
                        ? result.getAsJsonObject()
                        : new JsonObject());
                return;
            }

            // 没有 id 的 = 事件
            JsonElement methodElement = object.get("method");
            if (methodElement == null || methodElement.isJsonNull()) {
                return;
            }
            String method = methodElement.getAsString();
            if (owner != null) {
                owner.eventsReceived.incrementAndGet();
                owner.eventMethodsSeen.add(method);
            }

            BiConsumer<String, JsonObject> listener = eventListener;
            if (listener == null) {
                return;
            }
            if (owner != null) {
                owner.listenerInvocations.incrementAndGet();
            }
            JsonElement paramsElement = object.get("params");
            JsonObject params = paramsElement != null && paramsElement.isJsonObject()
                    ? paramsElement.getAsJsonObject()
                    : new JsonObject();
            listener.accept(method, params);
        }
    }
}
