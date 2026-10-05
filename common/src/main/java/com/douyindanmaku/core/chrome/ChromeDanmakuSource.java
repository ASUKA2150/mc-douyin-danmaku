package com.douyindanmaku.core.chrome;

import com.douyindanmaku.core.DanmakuLog;
import com.douyindanmaku.core.config.DanmakuConfig;
import com.douyindanmaku.core.douyin.DouyinProtocol;
import com.douyindanmaku.core.model.DanmakuEvent;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 用「旁观 Chrome」的方式拿抖音弹幕。
 *
 * <h2>怎么做</h2>
 * <ol>
 *   <li>启动一个独立的 Chrome（用它自己的配置目录，不碰你平时那个），
 *       打开 {@code live.douyin.com/<直播间号>}</li>
 *   <li>真实浏览器会自己去跟抖音弹幕服务器握手——<b>签名和风控都由浏览器处理</b>，
 *       我们一行相关代码都不用写</li>
 *   <li>通过 Chrome 的调试接口「旁观」页面收到了哪些 WebSocket 数据</li>
 *   <li>那些数据就是弹幕，用和直连模式完全一样的解码器解出来</li>
 * </ol>
 *
 * <h2>为什么这是最推荐的方案</h2>
 * <ul>
 *   <li><b>不需要你准备任何东西</b>——不用 Node.js，不用找签名脚本，
 *       不用管抖音什么时候换签名算法</li>
 *   <li><b>不需要登录</b>，匿名即可</li>
 *   <li><b>不注入、不修改页面</b>，只是被动地看，对抖音来说这就是一个普通观众</li>
 *   <li>抖音改了协议也不影响——帧照样能收到，只有解码那一步需要跟着更新</li>
 * </ul>
 *
 * <h2>为什么 Chrome 必须有窗口</h2>
 * 实测（同一台机器、同一个房间、同样的代码）：
 * <pre>
 *   --headless=new（无头）  → 页面建立了 0 个 WebSocket
 *   有窗口                  → 正常握手 101，持续收到帧
 * </pre>
 * 排除了 CDP 观测失效和「连接建在 worker 里」两种可能之后，结论是
 * <b>抖音自己的 JavaScript 在无头环境下决定不建立弹幕连接</b>。
 * 所以这里必须用有窗口模式，配置里提供了
 * {@code chromeOffscreen} 把窗口挪到屏幕外来减少干扰。
 *
 * <h2>线程模型</h2>
 * CDP 的消息回调在网络线程上。而「base64 解码 + gzip 解压 + protobuf 解析」
 * 很吃 CPU，直接在回调里做会拖慢整个连接，所以这里把原始帧丢进一个有界队列，
 * 由专门的解码线程处理。
 */
public final class ChromeDanmakuSource {

    /** 事件回调。 */
    public interface Listener {
        /** 状态变化（都是给人看的一句话）。 */
        void onState(String detail);

        /** 收到一个事件（弹幕 / 礼物 / 点赞 / 进房）。 */
        void onEvent(DanmakuEvent event);

        /** 收到直播间统计（在线人数、累计获赞）。可能很频繁，实现方自己决定要不要每次都处理。 */
        void onRoomStats(com.douyindanmaku.core.model.RoomStats stats);
    }

    /** 判断一个 WebSocket 地址是不是抖音的弹幕通道。 */
    private static final String MARKER_HOST = "webcast";
    private static final String MARKER_PATH = "/im/push";

    /** 保活间隔。不要太短（没必要），也不要太长（页面可能已经被冻结了）。 */
    private static final long KEEP_ALIVE_INTERVAL_MILLIS = 20_000L;

    /** 队列容量。满了就丢最旧的——弹幕丢几条无所谓，卡住连接才是大问题。 */
    private static final int QUEUE_CAPACITY = 512;

    /** 页面加载后等多久再判断是否连上了弹幕通道。 */
    private static final long PAGE_SETTLE_MILLIS = 12_000L;

    private final DanmakuConfig config;
    private final Listener listener;
    private final Path baseDirectory;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private CdpConnection cdp;
    private Thread decoderThread;
    private ArrayBlockingQueue<byte[]> frameQueue;

    /** 记录 WebSocket 的 requestId -> 地址，用来判断哪些帧是弹幕。
     *  这一步不能省：帧事件本身不带地址。 */
    private final Map<String, String> socketUrls = new ConcurrentHashMap<>();

    /** 是否已经找到了弹幕通道。 */
    private volatile String danmakuSocketId;

    public ChromeDanmakuSource(DanmakuConfig config, Listener listener, Path baseDirectory) {
        this.config = config;
        this.listener = listener;
        this.baseDirectory = baseDirectory;
    }

    /** 当前是否在运行。 */
    public boolean isRunning() {
        return running.get();
    }

    // ==================================================================
    //  启动 / 停止
    // ==================================================================

    /**
     * 开始旁观一个直播间。立刻返回，工作在后台线程进行。
     *
     * @param roomInput 直播间号或链接
     */
    public void connect(String roomInput) {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        String webRid = com.douyindanmaku.core.douyin.DouyinRoomResolver.extractWebRid(roomInput);
        if (webRid == null) {
            listener.onState("无法识别直播间号：「" + roomInput + "」。请填数字短号或直播间链接。");
            running.set(false);
            return;
        }

        Thread worker = new Thread(() -> run(webRid), "douyin-danmaku-chrome");
        worker.setDaemon(true);
        worker.start();
    }

    /** 停止旁观并关掉 Chrome。 */
    public void stop() {
        running.set(false);
        if (cdp != null) {
            cdp.close();
            cdp = null;
        }
        Thread decoder = decoderThread;
        if (decoder != null) {
            decoder.interrupt();
            decoderThread = null;
        }
        socketUrls.clear();
        danmakuSocketId = null;
    }

    // ==================================================================
    //  主流程
    // ==================================================================

    /**
     * 主循环：连上 → 保活 → 断了就自动重连。
     *
     * <p>这个循环会一直转到用户主动断开为止。中途任何失败都只记录并退避重试，
     * 不会让功能永久失效——直播场景下中途断连是很常见的
     * （页面被省电机制冻结、网页自己刷新、网络抖动、主播重新开播等）。
     */
    private void run(String webRid) {
        try {
            Path chromeExecutable = ChromeFinder.find(config.chromePath, baseDirectory);
            if (chromeExecutable == null) {
                listener.onState("找不到 Chrome。请安装 Chrome，"
                        + "或在配置文件里把 chromePath 填成 chrome.exe 的完整路径。");
                running.set(false);
                return;
            }

            int consecutiveFailures = 0;

            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    boolean ok = runOnce(chromeExecutable, webRid);
                    if (!running.get()) {
                        return;
                    }
                    if (ok) {
                        consecutiveFailures = 0;
                    } else {
                        consecutiveFailures++;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception failed) {
                    consecutiveFailures++;
                    DanmakuLog.error("Chrome 旁观失败（第 " + consecutiveFailures + " 次）", failed);
                    listener.onState("连接失败：" + failed.getMessage());
                } finally {
                    closeChrome();
                }

                if (!running.get()) {
                    return;
                }

                // 指数退避：5 秒起，最多 60 秒。
                // 第一次失败等的时间短一点，因为最常见的情况是网页自己刷新了一下。
                long backoffSeconds = Math.min(60L, 5L * (1L << Math.min(consecutiveFailures, 4)) / 2L);
                listener.onState("弹幕连接已断开，" + backoffSeconds + " 秒后自动重连…");
                Thread.sleep(backoffSeconds * 1000L);
                if (!running.get()) {
                    return;
                }
                listener.onState("正在重新连接直播间 " + webRid + " …");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            stop();
        }
    }

    /**
     * 连一次（启动浏览器 → 打开直播间 → 保活直到断开）。
     *
     * @return 正常跑完返回 {@code true}；连不上弹幕通道返回 {@code false}
     */
    private boolean runOnce(Path chromeExecutable, String webRid) throws Exception {
        // 启动浏览器。第一次用配置的目录；如果失败（最常见的原因是配置目录
        // 被占用或损坏），就换一个全新的临时目录再试一次。
        // 这一步能救回很多「浏览器起不来」的情况，用户不用自己去清目录。
        CdpConnection connection;
        Path profileDir = resolveProfileDirectory();
        try {
            connection = launchAndAttach(chromeExecutable, profileDir);
        } catch (IOException firstFailure) {
            Path fallbackDir = Path.of(System.getProperty("java.io.tmpdir"),
                    "douyin-danmaku-chrome-" + System.nanoTime());
            com.douyindanmaku.core.DanmakuLog.error(
                    "用配置目录启动浏览器失败，改用临时目录重试一次：" + profileDir, firstFailure);
            listener.onState("原来的浏览器配置目录用不了，换一个临时目录重试…");
            connection = launchAndAttach(chromeExecutable, fallbackDir);
        }

        // 连接准备好之后再对外发布，避免网络回调拿到半成品
        cdp = connection;
        danmakuSocketId = null;
        socketUrls.clear();
        startDecoderThread();

        String pageUrl = "https://live.douyin.com/" + webRid;
        listener.onState("正在打开直播间页面…");
        connection.navigate(pageUrl);

        // ---- 等弹幕通道出现 ----
        long deadline = System.currentTimeMillis() + PAGE_SETTLE_MILLIS;
        while (running.get() && System.currentTimeMillis() < deadline) {
            if (danmakuSocketId != null) {
                break;
            }
            if (!connection.isBrowserUsable()) {
                throw new IOException("浏览器不可用了（" + connection.explainExit() + "）");
            }
            Thread.sleep(250L);
        }

        if (danmakuSocketId == null) {
            listener.onState("页面打开了，但没找到弹幕通道。"
                    + "可能原因：直播间号不对、主播没开播、或者网络访问抖音有问题。");
            return false;
        }

        listener.onState("已连接直播间 " + webRid + "，弹幕接收中");

        // ---- 保活 + 健康检查 ----
        // 这里是「全屏玩 Minecraft 也能一直收弹幕」的关键：
        // 每 20 秒确认一次页面还活着、并把它钉在活跃状态。
        long lastKeepAlive = System.currentTimeMillis();
        long lastHealthLog = lastKeepAlive;
        // 每次连接都从零开始计数，免得把上一次的数字混进来
        framesReceived.set(0);
        framesDecoded.set(0);
        eventsDispatched.set(0);
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            Thread.sleep(1000L);

            if (!connection.isBrowserUsable()) {
                throw new IOException("浏览器不可用了（" + connection.explainExit() + "）");
            }

            long now = System.currentTimeMillis();
            logHealthIfDue(lastHealthLog, now);
            if (now - lastHealthLog >= HEALTH_LOG_INTERVAL_MILLIS) {
                lastHealthLog = now;
            }
            if (now - lastKeepAlive >= KEEP_ALIVE_INTERVAL_MILLIS) {
                lastKeepAlive = now;
                connection.keepPageActive();
                // 页面被丢弃 / 被冻结到不响应时，就干脆重连一次
                if (!connection.isPageAlive()) {                    throw new IOException("直播间页面已失去响应（可能被浏览器的省电机制冻结了）");
                }
            }

            // 页面自己刷新过，弹幕通道会重新建立；这里同步一下状态
            if (danmakuSocketId == null) {
                listener.onState("弹幕通道断开了，等待页面重新建立连接…");
                long reattachDeadline = System.currentTimeMillis() + 20_000L;
                while (running.get() && danmakuSocketId == null
                        && System.currentTimeMillis() < reattachDeadline) {
                    Thread.sleep(500L);
                }
                if (danmakuSocketId == null) {
                    throw new IOException("弹幕通道断开后没能重新建立");
                }
                listener.onState("弹幕通道已恢复，继续接收弹幕");
            }
        }
        return true;
    }

    /**
     * 帧计数器，用来在日志里回答「到底收到了几帧」。
     *
     * <p>为什么需要：排查「弹幕不显示」时，最大的困难是分不清
     * 「没收到数据」和「收到了但解不出来」——以前这两种情况在日志里
     * 长得一模一样（都是什么都没有）。有了计数就能一眼区分。
     */
    private final java.util.concurrent.atomic.AtomicLong framesReceived =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong framesDecoded =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong eventsDispatched =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong decodeFailures =
            new java.util.concurrent.atomic.AtomicLong();

    /** 健康检查日志的间隔：够频繁能看出问题，又不至于刷屏。 */
    private static final long HEALTH_LOG_INTERVAL_MILLIS = 60_000L;

    private void logHealthIfDue(long lastHealthLog, long now) {
        if (now - lastHealthLog < HEALTH_LOG_INTERVAL_MILLIS) {
            return;
        }
        Thread decoder = decoderThread;
        ArrayBlockingQueue<byte[]> queue = frameQueue;
        DanmakuLog.info("运行状态：收到帧 " + framesReceived.get()
                + "，已解码 " + framesDecoded.get()
                + "，派发事件 " + eventsDispatched.get()
                + "，待解码队列 " + (queue == null ? "无" : queue.size())
                + "，解码线程 " + (decoder != null && decoder.isAlive() ? "存活" : "已停止"));
        if (framesReceived.get() == 0) {
            DanmakuLog.info("注意：一帧都没收到。弹幕通道已建立但浏览器没推数据，"
                    + "通常是页面被冻结了，或者直播间已经下播。");
        } else if (eventsDispatched.get() == 0) {
            DanmakuLog.info("注意：收到了帧但没派发出任何事件。"
                    + "如果这个状态持续，请把日志发给开发者。");
        }
    }

    /**
     * 只关掉 Chrome 和调试连接，<b>不</b>改 {@code running} 标志。
     *
     * <p>重连循环要用它：每一轮结束都要清干净，但功能本身还要继续。
     * 区分「收尾这一轮」和「彻底停止」很重要——之前把两者混在一起，
     * 结果一断线就永久停掉了。
     */
    private void closeChrome() {
        CdpConnection connection = cdp;
        cdp = null;
        if (connection != null) {
            connection.close();
        }
        Thread decoder = decoderThread;
        decoderThread = null;
        if (decoder != null) {
            decoder.interrupt();
        }
        frameQueue = null;
        socketUrls.clear();
        danmakuSocketId = null;
    }

    /**
     * 启动浏览器并连上它的调试接口，顺便把防节流和网络监听都打开。
     *
     * <p>顺序很重要：{@code Network.enable} 必须在导航之前调用，
     * 因为浏览器不会补发已经发生过的网络事件，迟了就拿不到弹幕通道的地址。
     */
    private CdpConnection launchAndAttach(Path chromeExecutable, Path profileDir) throws IOException {
        Files.createDirectories(profileDir);

        listener.onState("正在启动浏览器…");
        CdpConnection connection = new CdpConnection(profileDir);
        connection.setEventListener(this::onCdpEvent);
        connection.launchChrome(chromeExecutable, config.chromeWindowSize, config.chromeOffscreen);

        String targetId = connection.openBlankPage();
        connection.attachToPage(targetId);
        connection.enableNetwork();
        connection.prepareAntiThrottle();
        return connection;
    }

    /**
     * 决定这次用哪个浏览器配置目录。
     *
     * <h2>为什么默认固定一个目录</h2>
     * 配置目录里存着 <b>登录状态</b>。而抖音的弹幕服务器
     * <b>只向已登录的连接推送礼物消息</b>——游客能收到弹幕、进房、点赞，
     * 但收不到礼物。所以目录必须固定下来，用户登录一次之后才能一直生效。
     *
     * <p>（早期版本为了躲开浏览器配置目录的 lockfile 冲突改成「每次换新目录」，
     * 那会把登录状态一起丢掉，导致礼物永远收不到。现在改回固定目录。）
     *
     * <p>lockfile 冲突靠 {@link CdpConnection} 里的清理 + 「调试通道可达性」
     * 判断来处理，不需要靠换目录绕开。
     */
    private Path resolveProfileDirectory() {
        if (config.chromeProfileDir != null && !config.chromeProfileDir.isBlank()) {
            Path configured = Path.of(config.chromeProfileDir);
            return configured.isAbsolute() || baseDirectory == null
                    ? configured
                    : baseDirectory.resolve(configured);
        }

        Path base = baseDirectory != null
                ? baseDirectory
                : Path.of(System.getProperty("java.io.tmpdir"));
        return base.resolve("douyin-danmaku-browser");
    }

    /**
     * 打开一个可见的浏览器窗口让用户登录抖音。
     *
     * <p>为什么要这样：gift（礼物）消息只有登录态才推。
     * 用户登录一次之后，登录凭据存在配置目录里，后续连接就都能收到礼物了。
     *
     * <p>这个方法会阻塞到浏览器被关闭为止（在调用方的线程上跑）。
     *
     * @param chromeExecutable 浏览器路径
     */
    public void openLoginWindow(Path chromeExecutable) throws IOException {
        // 先把正在跑的连接停掉，否则会和登录窗口抢同一个配置目录
        stop();

        Path profileDir = resolveProfileDirectory();
        Files.createDirectories(profileDir);

        listener.onState("正在打开浏览器窗口，请在窗口里登录抖音…");
        listener.onState("登录完成后关掉那个窗口，然后重新 /dy connect 即可");

        CdpConnection connection = new CdpConnection(profileDir);
        try {
            // 故意用可见窗口（offscreen=false）——用户得能看见才能登录
            connection.launchChrome(chromeExecutable, config.chromeWindowSize, false);

            // 顺序不能反：先建标签页，再把调试接口连到那个标签页上，
            // 最后才能导航。少了中间这步 pageSocket 是 null。
            String targetId = connection.openBlankPage();
            connection.attachToPage(targetId);
            connection.navigateLoginPage();

            listener.onState("浏览器已打开。登录好之后关掉窗口，再执行 /dy connect");

            // 阻塞等用户关掉浏览器
            while (connection.isAlive()) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            connection.close();
            listener.onState("登录窗口已关闭。请执行 /dy connect 开始接收弹幕（含礼物）");
        }
    }

    // ==================================================================
    //  CDP 事件
    // ==================================================================

    private void onCdpEvent(String method, JsonObject params) {
        try {
            switch (method) {
                case "Network.webSocketCreated" -> {
                    String requestId = readString(params, "requestId");
                    String url = readString(params, "url");
                    if (requestId != null && url != null) {
                        socketUrls.put(requestId, url);
                        if (isDanmakuSocket(url) && danmakuSocketId == null) {
                            danmakuSocketId = requestId;
                            DanmakuLog.info("找到弹幕通道：" + shorten(url));
                            listener.onState("已找到弹幕通道，开始接收弹幕");
                        }
                    }
                }
                case "Network.webSocketFrameReceived" -> {
                    String requestId = readString(params, "requestId");
                    // 只处理弹幕通道的帧。别的 WebSocket（比如心跳上报）一律忽略。
                    if (requestId == null || !requestId.equals(danmakuSocketId)) {
                        return;
                    }
                    byte[] frame = decodeFramePayload(params);
                    if (frame == null || frame.length == 0) {
                        return;
                    }
                    framesReceived.incrementAndGet();
                    // 不在网络回调里做解码（gzip + protobuf 很吃 CPU），丢给解码线程
                    ArrayBlockingQueue<byte[]> queue = frameQueue;
                    if (queue != null && !queue.offer(frame)) {
                        // 队列满了就丢最旧的，保证新弹幕能进来
                        queue.poll();
                        queue.offer(frame);
                    }
                }
                case "Network.webSocketClosed" -> {
                    String requestId = readString(params, "requestId");
                    if (requestId != null && requestId.equals(danmakuSocketId)) {
                        danmakuSocketId = null;
                        listener.onState("弹幕通道断开了，页面可能会自动重连…");
                    }
                }
                default -> {
                    // 其它事件不关心
                }
            }
        } catch (RuntimeException ignored) {
            // 事件处理失败绝不能往外抛（会关掉 CDP 连接）
        }
    }

    /**
     * 从帧事件里取出二进制数据。
     *
     * <p>Chrome 对二进制帧给的是 <b>Base64 编码的字符串</b>；
     * 文本帧（opcode 为 1）才是原文。抖音用的是二进制帧。
     */
    private static byte[] decodeFramePayload(JsonObject params) {
        JsonElement responseElement = params.get("response");
        if (responseElement == null || !responseElement.isJsonObject()) {
            return null;
        }
        JsonObject response = responseElement.getAsJsonObject();

        JsonElement dataElement = response.get("payloadData");
        if (dataElement == null || dataElement.isJsonNull()) {
            return null;
        }
        String payload = dataElement.getAsString();
        if (payload.isEmpty()) {
            return null;
        }

        // opcode 1 = 文本帧，直接按 UTF-8 取字节；其余（2 = 二进制）是 Base64
        JsonElement opcodeElement = response.get("opcode");
        boolean isTextFrame = opcodeElement != null && !opcodeElement.isJsonNull()
                && opcodeElement.getAsInt() == 1;

        if (isTextFrame) {
            return payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        try {
            return Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
    }

    private static boolean isDanmakuSocket(String url) {
        return url.contains(MARKER_HOST) && url.contains(MARKER_PATH);
    }

    // ==================================================================
    //  解码线程
    // ==================================================================

    private void startDecoderThread() {
        frameQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        ArrayBlockingQueue<byte[]> queue = frameQueue;

        decoderThread = new Thread(() -> {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    byte[] frame = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (frame == null) {
                        continue;
                    }
                    DouyinProtocol.DecodedEvents decoded =
                            DouyinProtocol.decodeFrameToEvents(frame, config);
                    framesDecoded.incrementAndGet();
                    for (DanmakuEvent event : decoded.events()) {
                        eventsDispatched.incrementAndGet();
                        listener.onEvent(event);
                    }
                    // 统计类消息（在线人数 / 累计获赞）不是「事件」而是房间状态，
                    // 单独回调出去——累计获赞的权威数字来自这里，不是自己累加的。
                    if (decoded.roomStats() != null && !decoded.roomStats().isEmpty()) {
                        listener.onRoomStats(decoded.roomStats());
                    }
                    // 没见过的消息类型记一次，方便发现抖音上了新东西
                    for (String unhandled : decoded.unhandledMethods()) {
                        logUnknownMethod(unhandled);
                    }
                    // 旁观模式下不需要回 ack 和发心跳——浏览器自己会做那些事，
                    // 我们只是「偷看」它收到的内容。
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException broken) {
                    // 单帧解析失败不影响后续，但<b>绝对不能静默</b>。
                    //
                    // 这里原来是个空 catch，结果「弹幕一条都不显示」这种问题
                    // 在日志里完全看不到线索，只能靠猜。现在前几次失败会记下来。
                    if (decodeFailures.incrementAndGet() <= 5) {
                        DanmakuLog.error("解码一帧时出错（第 " + decodeFailures.get()
                                + " 次，之后不再重复记录）", broken);
                    }
                } catch (Error fatal) {
                    // StackOverflowError / OutOfMemoryError 这类不属于 RuntimeException，
                    // 不接住的话线程会直接死掉，表现就是「突然再也不出弹幕了」。
                    DanmakuLog.error("解码线程遇到严重错误，线程即将退出", fatal);
                    return;
                }
            }
        }, "douyin-danmaku-decode");
        decoderThread.setDaemon(true);
        decoderThread.start();
    }

    private final java.util.Set<String> loggedMethods = ConcurrentHashMap.newKeySet();

    private void logUnknownMethod(String method) {
        if (loggedMethods.size() < 200 && loggedMethods.add(method)) {
            DanmakuLog.debug("收到未处理的消息类型：" + method);
        }
    }

    // ==================================================================
    //  小工具
    // ==================================================================

    private static String readString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static String shorten(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query) + "?…";
    }
}
