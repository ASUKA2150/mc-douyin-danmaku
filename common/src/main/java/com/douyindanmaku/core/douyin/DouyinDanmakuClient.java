package com.douyindanmaku.core.douyin;

import com.douyindanmaku.core.config.DanmakuConfig;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 直接连接抖音弹幕服务器。
 *
 * <h2>工作流程</h2>
 * <ol>
 *   <li>短号换长号（{@link DouyinRoomResolver}）</li>
 *   <li>拼连接 URL，算签名（{@link DouyinSigner}）</li>
 *   <li>连上去，然后：</li>
 *   <li>每 10 秒发一次心跳（不发会被服务端断开）</li>
 *   <li>收到需要确认的帧就回 ack（不回会「卡住」——一直重推同样的内容）</li>
 *   <li>解出弹幕丢给监听器</li>
 *   <li>断线自动重连（指数退避，最多退到 60 秒）</li>
 * </ol>
 *
 * <h2>为什么用 JDK 自带的 HttpClient 而不是 Netty</h2>
 * Minecraft 自己就带着 Netty。如果我们再带一份，版本冲突会让游戏起不来——
 * 这是很常见的模组崩溃原因。JDK 11 起自带的 {@code java.net.http} 有 WebSocket 客户端，
 * 零依赖、零冲突，功能上也完全够用。
 *
 * <h2>线程模型</h2>
 * 所有网络回调都在 HttpClient 自己的线程上，<b>不是</b> Minecraft 主线程。
 * 监听器的实现方负责切回主线程（见各加载器里的 {@code ChatRenderer}）。
 */
public final class DouyinDanmakuClient {

    /** 连接状态。 */
    public enum State {
        /** 没在连接。 */
        IDLE,
        /** 正在解析房间号 / 算签名 / 握手。 */
        CONNECTING,
        /** 已连上，正在收弹幕。 */
        CONNECTED,
        /** 断线了，正在等待重连。 */
        RECONNECTING,
        /** 出错了，已停止。 */
        FAILED
    }

    /** 事件回调。实现方要注意：这些方法都在网络线程上被调用。 */
    public interface Listener {
        /** 状态变化。{@code detail} 是给人看的一句话说明。 */
        void onStateChanged(State state, String detail);

        /** 收到一条弹幕。 */
        void onDanmaku(com.douyindanmaku.core.model.DanmakuMessage danmaku);
    }

    /**
     * 备选的弹幕服务器域名。
     *
     * <p>域名里必须含 {@code -ws-web-}。抖音会时不时换域名、下线旧域名，
     * 所以这里存多个，连不上就换下一个试，而不是直接失败。
     */
    private static final String[] WS_HOSTS = {
            "webcast5-ws-web-hl.douyin.com",
            "webcast3-ws-web-lf.douyin.com",
            "webcast100-ws-web-lq.douyin.com",
    };

    /** 心跳间隔。太短浪费流量，太长会被判定掉线。 */
    private static final long HEARTBEAT_SECONDS = 10L;

    /** 重连退避的最大间隔。 */
    private static final long MAX_BACKOFF_SECONDS = 60L;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private final DanmakuConfig config;
    private final Listener listener;
    /** 相对路径按它解析（一般是游戏目录）。 */
    private final Path baseDirectory;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger hostIndex = new AtomicInteger(0);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    private volatile HttpClient httpClient;
    private volatile WebSocket webSocket;
    private volatile State state = State.IDLE;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> heartbeatTask;

    /** 重连尝试次数，用来算退避时间。 */
    private int attempt;

    public DouyinDanmakuClient(DanmakuConfig config, Listener listener, Path baseDirectory) {
        this.config = config.copy();
        this.listener = listener;
        this.baseDirectory = baseDirectory;
    }

    /** 当前状态。 */
    public State state() {
        return state;
    }

    // ==================================================================
    //  对外接口
    // ==================================================================

    /**
     * 开始连接。这个方法立刻返回，真正的连接在后台线程进行。
     *
     * @param roomInput 直播间号或分享链接
     */
    public void connect(String roomInput) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        shuttingDown.set(false);
        attempt = 0;
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "douyin-danmaku");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.execute(() -> connectLoop(roomInput));
    }

    /** 断开连接并停止。 */
    public void disconnect() {
        shuttingDown.set(true);
        running.set(false);

        ScheduledFuture<?> heartbeat = heartbeatTask;
        if (heartbeat != null) {
            heartbeat.cancel(false);
            heartbeatTask = null;
        }

        WebSocket socket = webSocket;
        webSocket = null;
        if (socket != null) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (RuntimeException ignored) {
                // 已经断了，无所谓
            }
            socket.abort();
        }

        ScheduledExecutorService executor = scheduler;
        scheduler = null;
        if (executor != null) {
            executor.shutdownNow();
        }

        updateState(State.IDLE, "已断开连接");
    }

    // ==================================================================
    //  连接与重连
    // ==================================================================

    private void connectLoop(String roomInput) {
        String webRid = DouyinRoomResolver.extractWebRid(roomInput);
        if (webRid == null) {
            updateState(State.FAILED, "无法识别直播间号：「" + roomInput + "」。"
                    + "请填数字短号或直播间链接。");
            running.set(false);
            return;
        }

        while (running.get() && !shuttingDown.get()) {
            try {
                attemptOnce(webRid);
                // 正常断开会走到这里，说明服务端主动断了，等一下再重连
                attempt = 0;
                if (!running.get() || shuttingDown.get()) {
                    return;
                }
                updateState(State.RECONNECTING, "连接已断开，3 秒后重连…");
                sleep(3);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception failure) {
                if (!running.get() || shuttingDown.get()) {
                    return;
                }
                attempt++;
                long backoff = Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(attempt, 6));
                updateState(State.RECONNECTING,
                        "连接失败（" + describe(failure) + "），" + backoff + " 秒后重试…");
                try {
                    sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 连一次，阻塞到连接结束。 */
    private void attemptOnce(String webRid) throws Exception {
        // ---- 第 1 步：拿真实房间号 ----
        updateState(State.CONNECTING, "正在解析直播间 " + webRid + " …");
        DouyinRoomResolver.ResolvedRoom room = DouyinRoomResolver.resolve(webRid);

        String titleSuffix = room.title() == null || room.title().isBlank()
                ? "" : "（" + room.title() + "）";
        updateState(State.CONNECTING, "已找到直播间" + titleSuffix + "，正在计算签名…");

        // ---- 第 2 步：算签名 ----
        String userUniqueId = randomUserUniqueId();
        String query = buildQuery(room.roomId(), userUniqueId);

        String stub = DouyinSigner.computeStub(query);
        Path scriptPath = resolvePath(config.signScriptPath);
        String signature = DouyinSigner.requestSignature(config.nodePath, scriptPath, stub, baseDirectory);

        String connectUrl = "wss://" + WS_HOSTS[hostIndex.get() % WS_HOSTS.length]
                + "/webcast/im/push/v2/?" + query + "&signature=" + signature;

        // ---- 第 3 步：连上去 ----
        updateState(State.CONNECTING, "正在连接弹幕服务器…");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        httpClient = client;

        WebSocket socket = client.newWebSocketBuilder()
                .header("User-Agent", USER_AGENT)
                .header("Origin", "https://live.douyin.com")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(connectUrl), new FrameHandler(room.roomId()))
                .join();

        webSocket = socket;
        attempt = 0;
        updateState(State.CONNECTED, "已连接到直播间" + titleSuffix);

        // ---- 第 4 步：起心跳 ----
        startHeartbeat(socket);

        // ---- 第 5 步：等它断开 ----
        awaitClosure();
    }

    /** 拼连接 URL 的查询串（<b>不含</b> signature，签名要基于它来算）。 */
    private String buildQuery(String roomId, String userUniqueId) {
        // internal_ext 里带 \ 和 | 这些字符，整体要做 URL 编码
        String internalExt = "internal_src:dim|wss_push_room_id:" + roomId
                + "|wss_push_did:" + userUniqueId
                + "|first_req_ms:0|fetch_time:0|seq:1|wss_info:0-0-0-0|wrds_v:0";

        List<String> params = new ArrayList<>(24);
        params.add("app_name=douyin_web");
        params.add("version_code=180800");
        params.add("webcast_sdk_version=1.3.0");
        params.add("update_version_code=1.3.0");
        params.add("compress=gzip");
        params.add("live_id=1");
        params.add("aid=6383");
        params.add("did_rule=3");
        params.add("device_platform=web");
        params.add("identity=audience");
        params.add("room_id=" + roomId);
        params.add("user_unique_id=" + userUniqueId);
        params.add("cursor=d-1_u-1");
        params.add("host=https://live.douyin.com");
        params.add("im_path=/webcast/im/fetch/");
        params.add("need_persist_msg_count=15");
        params.add("support_wrds=1");
        params.add("internal_ext=" + urlEncode(internalExt));

        return String.join("&", params);
    }

    /** 生成一个随机的 19 位数字当「设备用户 ID」。每次连接都换新的。 */
    private static String randomUserUniqueId() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        StringBuilder builder = new StringBuilder(19);
        builder.append(random.nextInt(1, 10));       // 首位不能是 0
        for (int index = 1; index < 19; index++) {
            builder.append(random.nextInt(0, 10));
        }
        return builder.toString();
    }

    private void startHeartbeat(WebSocket socket) {
        ScheduledExecutorService executor = scheduler;
        if (executor == null) {
            return;
        }
        heartbeatTask = executor.scheduleAtFixedRate(() -> {
            if (!running.get() || socket != webSocket) {
                return;
            }
            try {
                socket.sendBinary(ByteBuffer.wrap(DouyinProtocol.buildHeartbeat()), true);
            } catch (RuntimeException sendFailed) {
                // 发送失败说明连接已断，等收尾逻辑处理
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 阻塞到 WebSocket 关闭。
     *
     * <p>{@code java.net.http.WebSocket} 没有「等待关闭」的 API，
     * 所以这里用一个自己通知自己的闩锁。
     */
    private void awaitClosure() throws InterruptedException {
        while (running.get() && !shuttingDown.get()) {
            WebSocket socket = webSocket;
            if (socket == null || socket.isOutputClosed()) {
                return;
            }
            Thread.sleep(500L);
        }
    }

    private void sleep(long seconds) throws InterruptedException {
        Thread.sleep(Duration.ofSeconds(seconds).toMillis());
    }

    private Path resolvePath(String configured) {
        Path path = Path.of(configured);
        return path.isAbsolute() || baseDirectory == null ? path : baseDirectory.resolve(path);
    }

    private void updateState(State newState, String detail) {
        state = newState;
        try {
            listener.onStateChanged(newState, detail);
        } catch (RuntimeException ignored) {
            // 监听器出问题不该影响网络线程
        }
    }

    private static String urlEncode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private static String describe(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return message.length() > 120 ? message.substring(0, 120) + "…" : message;
    }

    // ==================================================================
    //  WebSocket 回调
    // ==================================================================

    /**
     * 处理服务端推来的帧。
     *
     * <p>注意这个类是 {@code WebSocket.Listener}，回调都在网络线程上，
     * 所以不能直接操作游戏界面。
     */
    private final class FrameHandler implements WebSocket.Listener {

        private final String roomId;
        /** 收大帧时要自己拼，因为 onBinary 分片回调不保证一次给全。 */
        private ByteBuffer pending;

        FrameHandler(String roomId) {
            this.roomId = roomId;
        }

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            if (pending == null) {
                pending = ByteBuffer.allocate(Math.max(1024, data.remaining() * 2));
            }
            if (pending.remaining() < data.remaining()) {
                ByteBuffer larger = ByteBuffer.allocate(pending.position() + data.remaining());
                pending.flip();
                larger.put(pending);
                pending = larger;
            }
            pending.put(data);

            if (last) {
                ByteBuffer complete = pending;
                pending = null;
                complete.flip();
                byte[] frame = new byte[complete.remaining()];
                complete.get(frame);
                handleFrame(frame);
            }

            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            running.set(false);
            webSocket = null;
            updateState(State.RECONNECTING, "服务端关闭了连接（" + statusCode + " " + reason + "）");
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            running.set(false);
            webSocket = null;
            updateState(State.RECONNECTING, "连接出错：" + describe(
                    error instanceof Exception asException ? asException : new Exception(error)));
        }

        /** 处理一个完整的二进制帧。 */
        private void handleFrame(byte[] frame) {
            if (frame.length == 0) {
                return;
            }

            DouyinProtocol.DecodedFrame decoded;
            try {
                decoded = DouyinProtocol.decodeFrame(frame, config, method ->
                        logOnce("收到未处理的消息类型：" + method));
            } catch (RuntimeException broken) {
                // 解析失败只丢这一帧，不断连接
                return;
            }

            // 必须回 ack，否则服务端会一直重推同样的内容
            if (decoded.needAck()) {
                WebSocket socket = webSocket;
                if (socket != null) {
                    try {
                        socket.sendBinary(ByteBuffer.wrap(
                                DouyinProtocol.buildAck(decoded.logId(), decoded.internalExt())), true);
                    } catch (RuntimeException ignored) {
                        // 连接已断，交给重连逻辑
                    }
                }
            }

            for (com.douyindanmaku.core.model.DanmakuMessage danmaku : decoded.danmakuList()) {
                try {
                    listener.onDanmaku(danmaku);
                } catch (RuntimeException ignored) {
                    // 单条显示失败不影响收包
                }
            }
        }
    }

    /**
     * 同一个消息类型只记一次日志。
     *
     * <p>抖音推的消息类型很多，每次都记会把日志刷爆。
     */
    private final java.util.Set<String> loggedMethods = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void logOnce(String message) {
        if (loggedMethods.size() < 200 && loggedMethods.add(message)) {
            com.douyindanmaku.core.DanmakuLog.debug(message);
        }
    }
}
