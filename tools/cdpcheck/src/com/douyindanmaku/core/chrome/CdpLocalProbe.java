package com.douyindanmaku.core.chrome;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * CDP 接线对照实验（不属于模组本体）。
 *
 * <h2>它验证什么</h2>
 * 「模组能不能通过调试接口观察到页面收到的 WebSocket 数据」这件事，
 * 有若干个会<b>静默失败</b>的环节（失败时完全不报错，只是什么都收不到）。
 * 这个程序用完全确定的环境把它们一个个验证掉：
 *
 * <ol>
 *   <li>能不能找到并启动浏览器（Chrome / Edge 都行）</li>
 *   <li>能不能读到它的调试端口、访问 {@code /json/version} 和 {@code /json/list}</li>
 *   <li>能不能建标签页、连上页面级调试接口</li>
 *   <li><b>事件能不能真的送到监听器</b>——这一步历史上真的坏过：
 *       事件到了客户端，但因为连接建立时还没挂监听器而被静默丢弃</li>
 *   <li>能不能在页面里执行 JavaScript（命令/响应通道）</li>
 * </ol>
 *
 * <h2>用法</h2>
 * <pre>
 *   java ... CdpLocalProbe                       # 自动找浏览器
 *   java ... CdpLocalProbe &lt;浏览器exe路径&gt;        # 指定浏览器
 * </pre>
 * 不传参数时会用 {@link ChromeFinder} 自动探测，所以这个程序也能用来
 * 确认「模组的浏览器自动探测在你机器上找得对不对」。
 *
 * <p>不用 {@code data:} URL —— 浏览器会拦掉里面的脚本，反而引入无关变量。
 */
public final class CdpLocalProbe {

    public static void main(String[] args) throws Exception {
        int probePort = findFreePort();

        Path browser;
        if (args.length > 0 && !args[0].isBlank()) {
            browser = Path.of(args[0]);
            System.out.println("[0] 使用指定的浏览器: " + browser);
        } else {
            // 走模组自己的探测逻辑 —— 顺便验证它对不对
            browser = ChromeFinder.find("", null);
            if (browser == null) {
                System.out.println("找不到任何 Chrome / Edge。请安装其中之一，"
                        + "或把浏览器路径作为参数传进来。");
                return;
            }
            System.out.println("[0] 自动探测到的浏览器: " + browser);
        }
        if (!Files.isRegularFile(browser)) {
            System.out.println("指定的浏览器不存在: " + browser);
            return;
        }

        Path profile = Path.of(System.getProperty("java.io.tmpdir"), "dy-cdp-local-" + System.nanoTime());
        Files.createDirectories(profile);

        List<String> events = new CopyOnWriteArrayList<>();
        CdpConnection cdp = new CdpConnection(profile);
        try {
            System.out.println("[1] 启动浏览器…");
            int port = cdp.launchChrome(browser, "1024,700", true);
            System.out.println("    调试端口 = " + port);

            String targetId = cdp.openBlankPage();
            System.out.println("[2] 新建标签页 targetId = " + targetId);

            cdp.attachToPage(targetId);
            System.out.println("[3] attachToPage 之后: " + cdp.debugDescribeSockets());

            cdp.setEventListener((method, params) -> {
                events.add(method);
                System.out.println("    >> 监听器收到: " + method);
            });
            System.out.println("[4] setEventListener 之后: " + cdp.debugDescribeSockets());

            cdp.enableNetwork();
            cdp.prepareAntiThrottle();
            System.out.println("[5] Network 监听 + 防节流已就绪");

            cdp.navigate("about:blank");
            System.out.println("[6] evaluate('1+1') = " + cdp.evaluate("1+1"));

            // 在页面里主动发起一个 WebSocket 连接（连一个没人监听的端口，
            // 所以会连接失败 —— 但只要 CDP 报得出 webSocketCreated 就算成功）
            String script = "(() => { try {"
                    + "  window.__probe = new WebSocket('ws://127.0.0.1:" + probePort + "/probe');"
                    + "  window.__probe.onopen = () => window.__probe.send('hello');"
                    + "  return 'started';"
                    + "} catch (e) { return 'error: ' + e; } })()";
            System.out.println("[7] evaluate 结果 = " + cdp.evaluate(script));

            // 保活接口也顺便验证一下，避免它在真实使用时才暴露问题
            cdp.keepPageActive();
            System.out.println("[8] keepPageActive() 已调用; isPageAlive() = " + cdp.isPageAlive());

            System.out.println("[9] 等 6 秒收集事件…");
            for (int i = 0; i < 6; i++) {
                Thread.sleep(1000);
                System.out.println("    " + (i + 1) + "s  监听器被调用=" + cdp.listenerInvocations.get()
                        + "  收消息=" + cdp.messagesReceived.get()
                        + "  事件=" + cdp.eventsReceived.get()
                        + "  WebSocket事件=" + events.size());
            }

            System.out.println();
            System.out.println("  监听器收到的全部事件类型：");
            if (events.isEmpty()) {
                System.out.println("    （一个都没有）");
            } else {
                events.stream().distinct().forEach(m -> System.out.println("     - " + m));
            }

            boolean sawWebSocketCreated = events.contains("Network.webSocketCreated");
            System.out.println();
            if (sawWebSocketCreated) {
                System.out.println("  *** 成功：CDP 接线正常 ***");
                System.out.println("  看到了 Network.webSocketCreated，说明事件能正确送到监听器。");
                System.out.println("  这个浏览器（" + browser.getFileName() + "）可以正常使用。");
            } else {
                System.out.println("  *** 失败：没看到 Network.webSocketCreated ***");
                System.out.println("  说明监听链路有问题，不要在抖音那边找原因。");
                System.exit(1);
            }
        } finally {
            cdp.close();
            System.out.println("[10] 浏览器已关闭");
        }
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
