package com.douyindanmaku.core;

import com.douyindanmaku.core.chrome.ChromeDanmakuSource;
import com.douyindanmaku.core.config.DanmakuConfig;
import com.douyindanmaku.core.douyin.DouyinDanmakuClient;
import com.douyindanmaku.core.model.DanmakuEvent;
import com.douyindanmaku.core.model.DanmakuMessage;
import com.douyindanmaku.core.source.TcpDanmakuSource;
import com.douyindanmaku.core.text.DanmakuFilter;
import com.douyindanmaku.core.text.DanmakuFormatter;
import com.douyindanmaku.core.text.LikeCounter;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * 弹幕会话：把「数据源」和「显示」串起来的那根线。
 *
 * <pre>
 *   数据源（抖音直连 / 本机端口）
 *        │  弹幕
 *        ▼
 *   去重 + 过滤 + 限流（DanmakuFilter）
 *        │
 *        ▼
 *   渲染成文本（DanmakuFormatter）
 *        │
 *        ▼
 *   丢给聊天栏（messageSink，由各加载器实现）
 * </pre>
 *
 * <p>两个加载器（Fabric / NeoForge）都用这个类，它们只需要提供
 * 「怎么把一行文本塞进聊天栏」和「怎么把配置存到磁盘」。
 * 这样抖音协议、过滤规则、渲染逻辑就只有一份代码。
 *
 * <p><b>线程说明</b>：{@code start} / {@code stop} / {@code connect} 由
 * Minecraft 主线程调用，而消息回调来自网络线程。
 * {@link DanmakuFilter} 内部加了同步，配置则通过「读的时候取快照」来保证一致。
 */
public final class DanmakuSession {

    /** 弹幕渲染好之后往哪送。由加载器实现，必须在 Minecraft 主线程调用。 */
    @FunctionalInterface
    public interface MessageSink {
        /**
         * @param text 已经带好颜色代码的文本
         * @param kind 消息类型。加载器可以用它决定「要不要记进聊天记录」——
         *             聊天弹幕值得翻历史，礼物/进房/点赞刷过去就算了
         */
        void accept(String text, DanmakuEvent.Kind kind);
    }

    private final Consumer<String> statusSink;
    private final MessageSink messageSink;

    /** 配置。改配置时直接改这个对象的字段，读的时候用 {@link #snapshot()}。 */
    private final DanmakuConfig config;

    /** 相对路径（签名脚本等）按它解析，一般是游戏目录。 */
    private final Path baseDirectory;

    private final DanmakuFilter filter = new DanmakuFilter();

    /**
     * 点赞累计器。
     *
     * <p>抖音只推增量不推总数，所以「本场累计赞」是这里自己加出来的。
     */
    private final LikeCounter likeCounter = new LikeCounter();

    private final Object lifecycleLock = new Object();

    /** 点赞汇总的定时检查线程。 */
    private final java.util.concurrent.atomic.AtomicBoolean likeTickerRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile Thread likeTicker;

    private DouyinDanmakuClient directClient;
    private TcpDanmakuSource tcpSource;
    private ChromeDanmakuSource chromeSource;

    /** Chrome 旁观模式的最近一条状态描述（用于 /dy status）。 */
    private volatile String chromeStatus = "未连接";

    /**
     * 直播间统计（在线人数、累计获赞）。
     *
     * <p>累计获赞用这里的值而不是自己累加的——抖音给的是权威数字，
     * 自己累加只能算「本次连接期间收到的增量」，和面板上的差得很远。
     */
    private volatile com.douyindanmaku.core.model.RoomStats roomStats =
            com.douyindanmaku.core.model.RoomStats.empty();

    /**
     * @param config        配置
     * @param baseDirectory 相对路径的基准目录（游戏目录）
     * @param messageSink   弹幕往哪送
     * @param statusSink    状态提示往哪送（会再套一层 systemFormat 模板）
     */
    public DanmakuSession(DanmakuConfig config,
                          Path baseDirectory,
                          MessageSink messageSink,
                          Consumer<String> statusSink) {
        this.config = config;
        this.baseDirectory = baseDirectory;
        this.messageSink = messageSink;
        this.statusSink = statusSink;
    }

    /** 配置对象本身（想改配置就改它的字段）。 */
    public DanmakuConfig config() {
        return config;
    }

    /** 取一份配置快照，供网络线程使用。 */
    public DanmakuConfig snapshot() {
        return config.copy();
    }

    /** 当前是否已连接。 */
    public boolean isConnected() {
        synchronized (lifecycleLock) {
            if (tcpSource != null && tcpSource.isRunning()) {
                return true;
            }
            if (chromeSource != null && chromeSource.isRunning()) {
                return true;
            }
            return directClient != null && directClient.state() == DouyinDanmakuClient.State.CONNECTED;
        }
    }

    /** 当前状态的一句话描述，给 {@code /dy status} 用。 */
    public String describeState() {
        synchronized (lifecycleLock) {
            if (tcpSource != null && tcpSource.isRunning()) {
                return "正在监听本机端口 " + TcpDanmakuSource.PORT + "（等待外部程序转发）";
            }
            if (chromeSource != null && chromeSource.isRunning()) {
                return chromeStatus + "（Chrome 旁观模式）";
            }
            if (directClient == null) {
                return "未连接";
            }
            DouyinDanmakuClient.State state = directClient.state();
            return switch (state) {
                case IDLE -> "未连接";
                case CONNECTING -> "正在连接…";
                case CONNECTED -> "已连接直播间 " + config.room;
                case RECONNECTING -> "连接已断开，正在自动重连…";
                case FAILED -> "连接失败，请检查直播间号或配置";
            };
        }
    }

    // ==================================================================
    //  连接管理
    // ==================================================================

    /**
     * 连接指定直播间。
     *
     * @param roomInput 直播间号或分享链接
     */
    public void connect(String roomInput) {
        // 先停掉旧的，避免同时连两个
        stopInternal(false);

        DanmakuConfig snapshot = snapshot();
        snapshot.room = roomInput;

        switch (snapshot.source) {
            case TCP -> startTcpSource(snapshot);
            case CHROME -> startChromeSource(roomInput, snapshot);
            case DIRECT -> startDirectClient(roomInput, snapshot);
        }
    }

    /** 用当前配置里的直播间连接。 */
    public void connectConfigured() {
        String room = config.room;
        if (room == null || room.isBlank()) {
            status("还没有设置直播间号，先用 /dy connect <直播间号> 连一个");
            return;
        }
        connect(room);
    }

    /** 断开连接。 */
    public void disconnect() {
        stopInternal(true);
    }

    /**
     * 打开浏览器窗口让用户登录抖音。
     *
     * <p>为什么要登录：抖音的弹幕服务器<b>只向已登录的连接推送礼物消息</b>。
     * 游客能收到弹幕、进房、点赞，但收不到礼物。
     * 登录一次之后凭据存在浏览器配置目录里，后续连接就都能收到礼物了。
     *
     * <p>这个方法在后台线程跑，会阻塞到用户关掉浏览器窗口为止。
     */
    public void login() {
        stopInternal(false);

        DanmakuConfig snapshot = snapshot();
        java.nio.file.Path browser = com.douyindanmaku.core.chrome.ChromeFinder
                .find(snapshot.chromePath, baseDirectory);
        if (browser == null) {
            status("找不到浏览器。请安装 Chrome 或 Edge，"
                    + "或在配置文件里把 chromePath 填成浏览器的完整路径。");
            return;
        }

        status("正在打开浏览器窗口，请在里面登录抖音…");

        Thread worker = new Thread(() -> {
            try {
                ChromeDanmakuSource source = new ChromeDanmakuSource(snapshot,
                        new ChromeDanmakuSource.Listener() {
                            @Override
                            public void onState(String detail) {
                                // 登录流程的阶段提示直接转给聊天栏
                                status(detail);
                            }

                            @Override
                            public void onEvent(DanmakuEvent event) {
                                // 登录窗口不接收弹幕
                            }

                            @Override
                            public void onRoomStats(com.douyindanmaku.core.model.RoomStats stats) {
                                // 同上
                            }
                        }, baseDirectory);
                source.openLoginWindow(browser);
            } catch (Exception failed) {
                DanmakuLog.error("打开登录窗口失败", failed);
                status("打开登录窗口失败：" + failed.getMessage());
            }
        }, "douyin-danmaku-login");
        worker.setDaemon(true);
        worker.start();
    }

    private void stopInternal(boolean announce) {
        DouyinDanmakuClient previousDirect;
        TcpDanmakuSource previousTcp;
        ChromeDanmakuSource previousChrome;
        synchronized (lifecycleLock) {
            previousDirect = directClient;
            previousTcp = tcpSource;
            previousChrome = chromeSource;
            directClient = null;
            tcpSource = null;
            chromeSource = null;
        }

        if (previousDirect != null) {
            previousDirect.disconnect();
        }
        if (previousTcp != null) {
            previousTcp.stop();
        }
        if (previousChrome != null) {
            previousChrome.stop();
        }

        chromeStatus = "未连接";
        filter.reset();
        // 点赞累计也要清掉：换了直播间，或者重连过，
        // 之前那个「本场累计」就作废了（断开期间的赞我们收不到）。
        likeCounter.reset();
        // 统计同理——换了直播间，上个房间的获赞数没有意义
        roomStats = com.douyindanmaku.core.model.RoomStats.empty();
        com.douyindanmaku.core.model.StatsProbe.reset();
        stopLikeTicker();

        if (announce && (previousDirect != null || previousTcp != null || previousChrome != null)) {
            status("已断开连接");
        }
    }

    /**
     * 启动 Chrome 旁观模式。
     *
     * <p>这是默认也是最省事的模式：模组自己开一个 Chrome 去访问直播间，
     * 我们在旁边偷听它收到的弹幕。用户不需要准备 Node.js 或签名脚本。
     */
    private void startChromeSource(String roomInput, DanmakuConfig snapshot) {
        filter.reset();
        chromeStatus = "正在启动 Chrome…";

        ChromeDanmakuSource source = new ChromeDanmakuSource(snapshot,
                new ChromeDanmakuSource.Listener() {
                    @Override
                    public void onState(String detail) {
                        chromeStatus = detail;
                        // 关键节点才提示用户，避免启动过程刷屏
                        if (detail.contains("已连接") || detail.contains("找不到")
                                || detail.contains("失败") || detail.contains("没找到")
                                || detail.contains("断开了") || detail.contains("退出了")) {
                            status(detail);
                        } else {
                            DanmakuLog.info(detail);
                        }
                    }

                    @Override
                    public void onEvent(DanmakuEvent event) {
                        handleEvent(event);
                    }

                    @Override
                    public void onRoomStats(com.douyindanmaku.core.model.RoomStats stats) {
                        long previousOnline = roomStats.online();
                        String previousWatched = roomStats.watched();
                        roomStats = roomStats.merge(stats);

                        // 第一次收到统计、或者数字变了，各记一条日志。
                        // 这是排查「数字不对」类问题的第一手信息。
                        if (previousOnline == 0 && roomStats.online() > 0) {
                            DanmakuLog.info("收到直播间统计：在线 " + roomStats.online()
                                    + "，累计量 " + roomStats.watched());
                        } else if (roomStats.online() != previousOnline) {
                            DanmakuLog.info("在线人数更新为 " + roomStats.online());
                        }
                        // 那个累计量单独记：它的含义不确定，但变化本身有参考价值
                        String currentWatched = roomStats.watched();
                        if (currentWatched != null && !currentWatched.equals(previousWatched)) {
                            DanmakuLog.info("直播间累计量更新为 " + currentWatched
                                    + "（抖音没给标签，实测和面板获赞对不上，仅作参考）");
                        }
                    }
                }, baseDirectory);

        synchronized (lifecycleLock) {
            chromeSource = source;
        }
        source.connect(roomInput);
        // 点赞汇总靠「停下来」触发，而停下来之后就没有事件了，
        // 所以需要一个自己的小定时器把它兜出来。
        startLikeTicker();
    }

    private void startDirectClient(String roomInput, DanmakuConfig snapshot) {
        filter.reset();

        DouyinDanmakuClient client = new DouyinDanmakuClient(snapshot, new DouyinDanmakuClient.Listener() {
            @Override
            public void onStateChanged(DouyinDanmakuClient.State state, String detail) {
                // 只在状态真的变了的时候提示，避免重连时刷屏
                if (state == DouyinDanmakuClient.State.CONNECTED
                        || state == DouyinDanmakuClient.State.FAILED) {
                    status(detail);
                } else {
                    DanmakuLog.debug(detail);
                }
            }

            @Override
            public void onDanmaku(DanmakuMessage danmaku) {
                handleIncoming(danmaku);
            }
        }, baseDirectory);

        synchronized (lifecycleLock) {
            directClient = client;
        }
        client.connect(roomInput);
    }

    private void startTcpSource(DanmakuConfig snapshot) {
        filter.reset();

        TcpDanmakuSource source = new TcpDanmakuSource(new TcpDanmakuSource.Listener() {
            @Override
            public void onState(String detail) {
                status(detail);
            }

            @Override
            public void onDanmaku(DanmakuMessage danmaku) {
                handleIncoming(danmaku);
            }
        });

        synchronized (lifecycleLock) {
            tcpSource = source;
        }
        source.start(snapshot);
    }

    // ==================================================================
    //  消息处理
    // ==================================================================

    /**
     * 收到一个事件：过滤 -> 渲染 -> 送聊天栏。
     *
     * <p>这个方法可能在网络线程上被调用。
     */
    private void handleEvent(DanmakuEvent event) {
        // 取快照，保证整条消息用同一份配置渲染
        DanmakuConfig snapshot = snapshot();

        // 点赞只记账，不立刻显示。显示时机由 LikeCounter 决定：
        // 按人攒一波、停下来 5 秒后报一次，同一个人 30 秒内最多一次。
        if (event.kind() == DanmakuEvent.Kind.LIKE) {
            likeCounter.add(event.nickname(),
                    event.like() == null ? 0L : event.like().increment());
            drainLikeReports(snapshot);
            return;
        }

        if (!filter.shouldShow(event, snapshot)) {
            return;
        }

        renderAndSend(event, snapshot);
    }

    /**
     * 把「现在该播报的」点赞汇总发出去。
     *
     * <p>为什么不在这里直接显示原始点赞事件：点赞是超高频事件，
     * 有人能一口气点几百下。攒成一条既看得到，又不会把聊天栏刷满。
     *
     * <p>顺带说明这个数字的来历：<b>累计值是本模组自己加出来的</b>，
     * 不是抖音给的（平台不推累计总数）。断线重连后会重置。
     * 换句话说它只会比真实值小，不会大。
     */
    private void drainLikeReports(DanmakuConfig snapshot) {
        for (com.douyindanmaku.core.text.LikeCounter.Report report : likeCounter.drainReports()) {
            boolean anonymous = report.nickname() == null || report.nickname().isBlank();

            // 匿名点赞（拿不到是谁点的）默认不播报，见 likeAnonymousFormat 的说明。
            String template = anonymous ? snapshot.likeAnonymousFormat : snapshot.likeFormat;
            if (template == null || template.isBlank()) {
                continue;
            }

            DanmakuEvent summary = new DanmakuEvent(DanmakuEvent.Kind.LIKE,
                    report.nickname(), 0, false, null, 0, "",
                    null,
                    // 三个数各就各位：
                    //   increment = 这一波点了几个
                    //   total     = 本场自己累加的量（%like.session%）
                    //   online / watched = 直播间真实数据（可靠）
                    new DanmakuEvent.Like(report.amount(), likeCounter.total(),
                            roomStats.online(), roomStats.watched()),
                    null, System.currentTimeMillis());
            renderAndSend(summary, snapshot);
        }
    }

    /**
     * 当前直播间统计（在线人数、累计获赞）。
     *
     * <p>累计获赞是抖音给的权威数字，不是自己累加的。
     */
    public com.douyindanmaku.core.model.RoomStats stats() {
        return roomStats;
    }

    /**
     * 本场自己累加出来的点赞量。
     *
     * <p>这个数<b>只是「本次连接期间收到的量」</b>——断线重连会归零，
     * 断开期间的赞收不到。它只适合做「刚才这波点了多少」的即时反馈。
     *
     * <p>「抖音的累计获赞」这个数<b>拿不到</b>：弹幕通道里没有它，
     * 详见 {@code DanmakuConfig.likeFormat} 的说明。
     */
    public long likeSessionTotal() {
        return likeCounter.total();
    }

    /**
     * 输出统计字段观测报告（{@code /dy stats} 用）。
     *
     * <p>什么时候用它：想确认抖音到底推了哪些数据、在线人数对不对，
     * 或者怀疑模组读错了字段。
     */
    public void reportStats() {
        for (String line : com.douyindanmaku.core.model.StatsProbe.report()) {
            status(line);
        }
        status("&7当前在线人数：&f" + com.douyindanmaku.core.text.NumberText.format(roomStats.online())
                + "&7　｜　本场模组收到的赞：&f"
                + com.douyindanmaku.core.text.NumberText.format(likeSessionTotal()));
        status("&7（注：抖音的弹幕通道里没有「累计获赞」数据，所以本模组不显示它）");
    }

    /**
     * 启动后台计时器，定期检查有没有该播报的点赞汇总。
     *
     * <p>为什么需要它：点赞汇总的触发条件是「<b>停下来</b>」，
     * 但停下来之后就再也没有新事件了——只靠事件驱动的话，
     * 最后那一波永远播报不出来。所以需要一个自己的小定时器。
     */
    private void startLikeTicker() {
        stopLikeTicker();
        likeTickerRunning.set(true);
        Thread ticker = new Thread(() -> {
            while (likeTickerRunning.get()) {
                try {
                    // 500 毫秒足够精确（播报窗口是 5 秒），也不会浪费 CPU
                    Thread.sleep(500L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    drainLikeReports(snapshot());
                } catch (RuntimeException failed) {
                    DanmakuLog.error("点赞汇总播报失败", failed);
                }
            }
        }, "douyin-danmaku-like-ticker");
        ticker.setDaemon(true);
        likeTicker = ticker;
        ticker.start();
    }

    /** 停掉点赞计时器。 */
    private void stopLikeTicker() {
        likeTickerRunning.set(false);
        Thread ticker = likeTicker;
        likeTicker = null;
        if (ticker != null) {
            ticker.interrupt();
        }
    }

    /** 渲染一个事件并送到聊天栏。 */
    private void renderAndSend(DanmakuEvent event, DanmakuConfig snapshot) {
        String rendered = DanmakuFormatter.formatEvent(event, snapshot);
        if (rendered == null || rendered.isBlank()) {
            // 用户把这个类型关掉了，或者模板渲染成了空
            return;
        }
        try {
            messageSink.accept(rendered, event.kind());
        } catch (RuntimeException failed) {
            DanmakuLog.error("显示消息失败", failed);
        }
    }

    /** 兼容入口：直接把一条弹幕当成聊天事件处理。 */
    private void handleIncoming(DanmakuMessage danmaku) {
        handleEvent(DanmakuEvent.chat(danmaku));
    }

    /** 往聊天栏送一条模组自己的提示。 */
    public void status(String message) {
        String rendered = DanmakuFormatter.formatSystem(message, snapshot());
        try {
            messageSink.accept(rendered, DanmakuEvent.Kind.CHAT);
        } catch (RuntimeException failed) {
            DanmakuLog.error("显示提示失败", failed);
        }
        DanmakuLog.info(message);
        try {
            statusSink.accept(message);
        } catch (RuntimeException ignored) {
            // 状态回调是可选的
        }
    }
}
