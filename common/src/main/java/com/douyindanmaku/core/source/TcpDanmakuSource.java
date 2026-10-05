package com.douyindanmaku.core.source;

import com.douyindanmaku.core.config.DanmakuConfig;
import com.douyindanmaku.core.model.DanmakuMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 用本机端口接收弹幕。
 *
 * <h2>用途</h2>
 * 给「外部程序把弹幕转发过来」留一个入口。有两类场景：
 * <ul>
 *   <li>你手上已经有一个能抓抖音弹幕的软件（比如 DanmuFree），
 *       如果它有转发功能，就能直接喂给这个模组</li>
 *   <li>兼容 {@code TartaricAcid/BakaDanmaku} 的 {@code tcp} 模式——
 *       那个模组的协议是「一行一个 JSON」，本类也接受同样的格式</li>
 * </ul>
 *
 * <h2>协议</h2>
 * 模组在 {@code 127.0.0.1:8912} 上监听 TCP。任何程序连上来，
 * 按行发送以下任一格式即可：
 * <pre>
 *   {"nick":"张三","content":"你好"}
 *   {"nickname":"张三","content":"你好","level":12,"fanClubLevel":3,"fanClubName":"某某团"}
 * </pre>
 * 字段名做了兼容：{@code nick} 和 {@code nickname} 都认；
 * {@code level} 是抖音等级；粉丝团字段可选。只认 {@code content} 是必须的。
 *
 * <p>端口固定为 {@code 8912}，只监听本机回环地址，
 * 局域网里别的机器连不上（避免被别人乱发弹幕）。
 */
public final class TcpDanmakuSource {

    /** 监听端口。 */
    public static final int PORT = 8912;

    /** 事件回调。 */
    public interface Listener {
        /** 状态变化。 */
        void onState(String detail);

        /** 收到一条弹幕。 */
        void onDanmaku(DanmakuMessage danmaku);
    }

    private final Listener listener;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private Thread acceptThread;

    /** 当前连上来的客户端，断开时要一起关掉。 */
    private final List<Socket> clients = new CopyOnWriteArrayList<>();

    public TcpDanmakuSource(Listener listener) {
        this.listener = listener;
    }

    /** 是否在监听。 */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 开始监听。端口被占用会直接返回失败提示，不会抛异常。
     *
     * @return 是否成功
     */
    public boolean start(DanmakuConfig config) {
        if (!running.compareAndSet(false, true)) {
            return true;
        }

        try {
            // 只绑回环地址：外部网络连不上，安全
            serverSocket = new ServerSocket(PORT, 16, InetAddress.getLoopbackAddress());
        } catch (IOException portTaken) {
            running.set(false);
            listener.onState("无法监听端口 " + PORT + "（可能被别的程序占用了）：" + portTaken.getMessage());
            return false;
        }

        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "douyin-danmaku-tcp");
            thread.setDaemon(true);
            return thread;
        });

        acceptThread = new Thread(() -> acceptLoop(config), "douyin-danmaku-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        listener.onState("已开始监听本机端口 " + PORT + "，等待外部程序转发弹幕…");
        return true;
    }

    /** 停止监听并关掉所有连接。 */
    public void stop() {
        running.set(false);

        for (Socket client : clients) {
            closeQuietly(client);
        }
        clients.clear();

        closeQuietly(serverSocket);
        serverSocket = null;

        ExecutorService current = executor;
        executor = null;
        if (current != null) {
            current.shutdownNow();
        }

        listener.onState("已停止监听端口 " + PORT);
    }

    private void acceptLoop(DanmakuConfig config) {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                clients.add(client);
                ExecutorService current = executor;
                if (current != null) {
                    current.execute(() -> handleClient(client, config));
                }
            } catch (IOException stopped) {
                // 关闭时 accept 会抛异常，正常现象
                return;
            }
        }
    }

    /** 读一个客户端发来的行。 */
    private void handleClient(Socket client, DanmakuConfig config) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                DanmakuMessage danmaku = ExternalJson.parse(line, config);
                if (danmaku != null) {
                    listener.onDanmaku(danmaku);
                }
            }
        } catch (IOException disconnected) {
            // 客户端断开，正常现象
        } finally {
            clients.remove(client);
            closeQuietly(client);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // 关不掉就算了
        }
    }
}
