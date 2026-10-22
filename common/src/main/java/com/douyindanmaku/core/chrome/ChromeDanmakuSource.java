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

    /**
     * 弹幕通道断了之后，先干等多久看页面能不能自己接回来。
     *
     * <p>原来的值是 20 秒，太短了：网络抖动、抖音那边切服务器都可能超过它，
     * 一超时就白重建一次浏览器。实测日志里几次断连都是卡在这个 20 秒上。
     *
     * <p>45 秒是权衡：足够度过常见的抖动，又不至于让用户等太久看不到弹幕
     * （真等不到我们会去刷新页面，见 {@link #PAGE_RELOAD_WAIT_MILLIS}）。
     */
    private static final long CHANNEL_RECOVER_WAIT_MILLIS = 45_000L;

    /**
     * 刷新页面之后，再等多久弹幕通道出现。
     *
     * <p>比上面那个长一些：刷新要重新下载页面、执行 JS，本身就要几秒。
     */
    private static final long PAGE_RELOAD_WAIT_MILLIS = 60_000L;

    /**
     * 健康检查连续失败几次才判定「浏览器真的没了」。
     *
     * <p>检查本身是一次跨进程的同步调用（{@code Browser.getVersion}），
     * 浏览器在忙着 GC、或者系统整体卡顿时，单次超时是正常的。
     *
     * <p>取 3：每轮间隔 1 秒，等于给浏览器 3 秒的喘息时间。
     * 真的死了的话 3 秒后照样会被发现，不会漏判太久。
     */
    private static final int HEALTH_FAILURE_TOLERANCE = 3;

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
     * <p>这个循环会一直转到用户主动断开、或者连续失败太多次为止。
     *
     * <h2>为什么要给重试设上限</h2>
     * 一开始这里是无条件无限重试的，理由是「直播中途断连很常见」。
     * 但实测发现有个很烦的情况：<b>主播没开播时，它会一直转下去</b>——
     * 每 5~60 秒开一次浏览器去看一眼，转一整晚，日志里堆满重复的失败。
     *
     * <p>所以现在把「该不该继续」交给 {@link ReconnectPolicy} 判断：
     * 它按失败原因分两档上限，到顶就停下来并告诉用户怎么办。
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

            ReconnectPolicy policy = new ReconnectPolicy();

            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    boolean ok = runOnce(chromeExecutable, webRid);
                    if (!running.get()) {
                        return;
                    }
                    if (ok) {
                        // 成功连上过一次就清零——直播是个长期过程，
                        // 中途断几次很正常，不该累积到上限。
                        policy.recordSuccess();
                    } else {
                        // runOnce 返回 false 只有一种情况：页面开了但没找到弹幕通道
                        policy.recordChannelNotFound();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception failed) {
                    policy.recordOtherFailure();
                    DanmakuLog.error("Chrome 旁观失败（第 " + policy.consecutiveFailures() + " 次）", failed);
                    listener.onState("连接失败：" + failed.getMessage());
                } finally {
                    closeChrome();
                }

                if (!running.get()) {
                    return;
                }

                // ---- 到上限就停下来，并给出能照做的提示 ----
                if (!policy.shouldRetry()) {
                    if (policy.stoppedBecauseChannelNotFound()) {
                        listener.onState("连续 " + policy.consecutiveFailures()
                                + " 次都没找到直播间画面，先停止重试。最常见的原因是主播没开播。");
                        listener.onState("等主播开播后，用 /dy connect " + webRid + " 再连一次即可。");
                    } else {
                        listener.onState("连续 " + policy.consecutiveFailures()
                                + " 次连接失败，先停止重试。"
                                + "可以看看日志文件里的报错，修好后用 /dy connect " + webRid + " 重连。");
                    }
                    return;
                }

                long backoffSeconds = policy.backoffSeconds();
                listener.onState("弹幕连接已断开，" + backoffSeconds + " 秒后自动重连（第 "
                        + (policy.consecutiveFailures() + 1) + " 次）…");
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
        //
        // 判定「浏览器死了」用 isBrowserUsable()：它的定义是
        // 「调试通道能应答 或 进程还活着」。所以这里返回 false 意味着
        // 进程真的没了、通道也不通——那确实没救，早点报错别干等。
        //
        // 注意：浏览器刚启动的那一两秒里，CDP 通道还没握上手是正常的，
        // 但那种情况进程是活的，isBrowserUsable() 会返回 true，
        // 循环会继续等，不会误判。
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
        // 浏览器连续几次健康检查没通过。累积到阈值才判定「真的没了」。
        //
        // 为什么要容忍几次：CDP 是一次跨进程的同步调用，在浏览器忙着
        // 做 GC、或者系统整体卡顿的时候，单次调用超时是正常现象。
        // 早期版本一次不通就抛异常，等于把好好的连接推倒重来——
        // 用户看到的就是「突然断联」。
        int consecutiveHealthFailures = 0;
        // 每次连接都从零开始计数，免得把上一次的数字混进来
        framesReceived.set(0);
        framesDecoded.set(0);
        eventsDispatched.set(0);
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            Thread.sleep(1000L);

            if (connection.isBrowserUsable()) {
                consecutiveHealthFailures = 0;
            } else {
                consecutiveHealthFailures++;
                if (consecutiveHealthFailures >= HEALTH_FAILURE_TOLERANCE) {
                    throw new IOException("浏览器不可用了（" + connection.explainExit() + "）");
                }
                // 还没到阈值，这一轮先跳过，给浏览器一次喘息的机会
                continue;
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
                if (!connection.isPageAlive()) {
                    throw new IOException("直播间页面已失去响应"
                            + "（可能被浏览器的省电机制冻结了）");
                }
            }

            // 页面自己刷新过，弹幕通道会重新建立；这里同步一下状态。
            //
            // 这段是「玩着玩着弹幕就没了」的修复点，改动理由见
            // recoverChannel() 的注释。
            if (danmakuSocketId == null) {
                if (!recoverChannel(connection, webRid)) {
                    throw new IOException("弹幕通道断开后，等待和刷新页面都没能恢复");
                }
            }
        }
        return true;
    }

    /**
     * 弹幕通道断了之后尝试恢复。<b>不重建浏览器。</b>
     *
     * <h2>为什么要有这个方法（修的是什么）</h2>
     * 以前的逻辑是：通道一断 → 等 20 秒 → 还不行就抛异常 →
     * 外层把浏览器整个关掉重建。实测这个做法有两个问题：
     *
     * <ol>
     *   <li><b>弹幕会长时间消失。</b>重建浏览器要重新启动 Chrome、
     *       重新打开直播间页面、等页面加载完（十几秒起步），
     *       而这段时间里用户看到的是一片安静。</li>
     *   <li><b>多数时候根本不需要重建。</b>抖音的网页在 WebSocket
     *       断掉之后，自己往往不会重连（我们的实测日志里
     *       「弹幕通道断开了，页面可能会自动重连…」后面从来没跟过
     *       「弹幕通道已恢复」）。但<b>页面本身是好的</b>——
     *       刷新一下就能重新建立通道，比重启浏览器快得多，
     *       也不会多弹一个窗口出来。</li>
     * </ol>
     *
     * <p>所以现在分三步，一步比一步重：
     * <ol>
     *   <li>先等 {@link #CHANNEL_RECOVER_WAIT_MILLIS}：可能是网络抖一下，
     *       或者抖音服务器那边短暂切换，页面自己就接回来了。</li>
     *   <li>还不行就<b>刷新页面</b>（{@code Page.reload}），
     *       再等一次。复用同一个浏览器进程，登录状态、窗口位置都不变。</li>
     *   <li>刷新也救不回来（比如页面已经彻底卡死、或者浏览器进程没了），
     *       才返回 {@code false} 让外层走重建流程。</li>
     * </ol>
     *
     * @return 通道恢复了返回 {@code true}；救不回来返回 {@code false}
     */
    private boolean recoverChannel(CdpConnection connection, String webRid) throws InterruptedException {
        listener.onState("弹幕通道断开了，等待页面自动恢复…");

        // 第一步：干等，看页面自己能不能接回来
        if (waitForChannel(CHANNEL_RECOVER_WAIT_MILLIS)) {
            listener.onState("弹幕通道已恢复，继续接收弹幕");
            return true;
        }

        // 等不到就先确认浏览器还在。真的没了就别刷了，直接重建。
        //
        // 这里只要求「进程还在」，不要求 CDP 通道此刻就通——
        // 因为紧接着要做的事正是刷新页面来重建通道。
        if (!running.get() || !connection.isAlive()) {
            return false;
        }

        // 第二步：刷新页面
        listener.onState("页面没能自己接回来，刷新一下…");
        try {
            danmakuSocketId = null;
            socketUrls.clear();
            connection.reloadPage(false);
        } catch (Exception reloadFailed) {
            DanmakuLog.error("刷新直播间页面失败", reloadFailed);
            return false;
        }

        if (waitForChannel(PAGE_RELOAD_WAIT_MILLIS)) {
            listener.onState("刷新后弹幕通道已恢复，继续接收弹幕");
            return true;
        }

        // 第三步：刷新也没用。可能是直播间真的关了，交给外层处理。
        DanmakuLog.info("刷新页面后仍未找到弹幕通道（直播间 " + webRid + "）");
        return false;
    }

    /**
     * 等弹幕通道重新出现。
     *
     * @return 在超时前出现了返回 {@code true}
     */
    private boolean waitForChannel(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (running.get() && danmakuSocketId == null
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(250L);
        }
        return danmakuSocketId != null;
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
    /**
     * 启动浏览器并连上它的调试接口，顺便把防节流和网络监听都打开。
     *
     * <p>顺序很重要：{@code Network.enable} 必须在导航之前调用，
     * 因为浏览器不会补发已经发生过的网络事件，迟了就拿不到弹幕通道的地址。
     *
     * <h2>为什么失败时要自己收拾干净</h2>
     * 这一步里任何一个环节失败，{@code connection} 都还是局部变量，
     * 调用方拿不到它。而 {@code launchChrome} 一旦成功，<b>Chrome 进程已经
     * 起来了、并且占着配置目录</b>。不在这里关掉的话：
     *
     * <ol>
     *   <li>浏览器进程泄漏，用户会看到一堆关不掉的窗口</li>
     *   <li>更要命的是那个进程会占着 <b>lockfile</b>，导致调用方
     *       「换个目录重试」时新进程把启动<b>转交给它然后自己退出</b>，
     *       表现为 {@code 退出码 0}——实测日志里正是这么连环失败的。</li>
     * </ol>
     *
     * <p>所以这里用 try/catch 兜住，失败就把半成品关掉再往外抛。
     */
    private CdpConnection launchAndAttach(Path chromeExecutable, Path profileDir) throws IOException {
        Files.createDirectories(profileDir);

        listener.onState("正在启动浏览器…");
        CdpConnection connection = new CdpConnection(profileDir);
        connection.setEventListener(this::onCdpEvent);
        try {
            connection.launchChrome(chromeExecutable, config.chromeWindowSize, config.chromeOffscreen);
            String targetId = connection.openBlankPage();
            connection.attachToPage(targetId);
            connection.enableNetwork();
            connection.prepareAntiThrottle();
            return connection;
        } catch (IOException | RuntimeException failed) {
            // 半成品必须关掉：它可能已经启动了一个占着配置目录的浏览器进程。
            // 关它本身也可能抛异常，那种情况忽略——不能让它盖掉真正的原因。
            try {
                connection.close();
            } catch (RuntimeException ignored) {
                // 已经不可达了，没什么可做的
            }
            throw failed;
        }
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

            // 阻塞等用户关掉浏览器。
            //
            // 这里必须用 isOurProcessRunning() 而不是 isAlive()：
            // 后者在「启动被转交给已有实例」时会恒为 true，
            // 一旦发生转交这个循环就永远退不出来（窗口关了也不提示）。
            while (connection.isOurProcessRunning()) {
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
