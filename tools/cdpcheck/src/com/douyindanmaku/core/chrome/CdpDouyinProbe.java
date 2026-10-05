package com.douyindanmaku.core.chrome;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 抖音真实房间端到端探针（不属于模组本体）。
 *
 * <h2>它验证什么</h2>
 * {@link CdpLocalProbe} 验证的是「CDP 接线通不通」；这个验证的是
 * 「<b>真实抖音直播间能不能抓到弹幕</b>」——两件事互相独立，必须分开验。
 *
 * <p>它做三件事：
 * <ol>
 *   <li>先打开抖音直播首页，从页面里读出一个<b>正在开播</b>的房间号
 *       （这样不依赖「手头刚好有个在播的房间号」）</li>
 *   <li>进那个直播间，观察有没有出现弹幕 WebSocket 通道</li>
 *   <li>如果出现了，统计收到的帧数和载荷大小</li>
 * </ol>
 *
 * <h2>用法</h2>
 * <pre>
 *   java ... CdpDouyinProbe                              # 自动找浏览器 + 自动找房间
 *   java ... CdpDouyinProbe &lt;浏览器exe&gt;                 # 指定浏览器
 *   java ... CdpDouyinProbe &lt;浏览器exe&gt; &lt;直播间号&gt;      # 指定房间，跳过自动发现
 * </pre>
 *
 * <h2>已知前提</h2>
 * 浏览器必须是<b>有窗口</b>模式。实测 Chrome 在 {@code --headless=new} 下
 * 页面根本不会建立弹幕连接（不是 CDP 观测不到，是抖音的 JS 自己不建）。
 *
 * @return 进程退出码：0 = 找到了弹幕通道，1 = 没找到
 */
public final class CdpDouyinProbe {

    public static void main(String[] args) throws Exception {
        Path browser;
        if (args.length > 0 && !args[0].isBlank()) {
            browser = Path.of(args[0]);
        } else {
            browser = ChromeFinder.find("", null);
            if (browser == null) {
                System.out.println("找不到任何 Chrome / Edge。");
                System.exit(1);
            }
        }
        String forcedRoom = args.length > 1 && !args[1].isBlank() ? args[1] : null;

        System.out.println("[0] 浏览器: " + browser);
        Path profile = Path.of(System.getProperty("java.io.tmpdir"), "dy-cdp-douyin-" + System.nanoTime());
        Files.createDirectories(profile);

        CdpConnection cdp = new CdpConnection(profile);
        String[] danmakuUrl = new String[1];
        int[] frameCount = new int[1];
        int[] payloadChars = new int[1];

        try {
            System.out.println("[1] 启动浏览器（有窗口 + 独立 profile）…");
            cdp.launchChrome(browser, "1280,800", true);
            String targetId = cdp.openBlankPage();
            cdp.attachToPage(targetId);
            cdp.setEventListener((method, params) -> {
                try {
                    if ("Network.webSocketCreated".equals(method)) {
                        String url = params.has("url") ? params.get("url").getAsString() : "";
                        if (url.contains("webcast") && url.contains("/im/push") && danmakuUrl[0] == null) {
                            danmakuUrl[0] = url;
                            System.out.println("  *** 找到弹幕通道 ***");
                            System.out.println("  " + url.substring(0, Math.min(190, url.length())));
                        }
                    } else if ("Network.webSocketFrameReceived".equals(method)) {
                        frameCount[0]++;
                        var response = params.getAsJsonObject("response");
                        if (response != null && response.has("payloadData")) {
                            payloadChars[0] += response.get("payloadData").getAsString().length();
                        }
                    }
                } catch (RuntimeException ignored) {
                    // 事件处理不能抛
                }
            });
            cdp.enableNetwork();
            cdp.prepareAntiThrottle();
            System.out.println("[2] Network 监听 + 防节流已就绪");

            String room = forcedRoom;
            if (room == null) {
                System.out.println("[3] 打开抖音直播首页，找一个正在开播的房间…");
                cdp.navigate("https://live.douyin.com/");
                Thread.sleep(9000);
                room = cdp.evaluate(
                        "(() => { const a = [...document.querySelectorAll('a[href*=\"live.douyin.com/\"]')]"
                                + ".map(x => x.href).filter(h => /live\\.douyin\\.com\\/\\d{6,}/.test(h));"
                                + " return a.length ? a[0] : ''; })()");
                System.out.println("    首页给出的房间: " + room);
            }

            String digits = room == null ? "" : room.replaceAll("\\D", "");
            if (digits.isEmpty()) {
                System.out.println("  没能拿到房间号，无法继续。");
                System.exit(1);
            }
            if (forcedRoom != null) {
                digits = forcedRoom.replaceAll("\\D", "");
            }

            System.out.println("[4] 导航到 https://live.douyin.com/" + digits);
            cdp.navigate("https://live.douyin.com/" + digits);

            System.out.println("[5] 等 35 秒收弹幕（顺便周期性保活，模拟长时间挂着）…");
            long deadline = System.currentTimeMillis() + 35_000;
            long lastKeepAlive = System.currentTimeMillis();
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(1000);
                if (System.currentTimeMillis() - lastKeepAlive > 20_000) {
                    lastKeepAlive = System.currentTimeMillis();
                    cdp.keepPageActive();
                }
            }

            System.out.println();
            System.out.println("  isPageAlive() = " + cdp.isPageAlive());
            if (danmakuUrl[0] != null) {
                System.out.println("  === 成功 ===");
                System.out.println("  含 signature 参数: " + danmakuUrl[0].contains("signature="));
                System.out.println("  收到帧数: " + frameCount[0]);
                System.out.println("  累计载荷字符数: " + payloadChars[0]);
            } else {
                System.out.println("  === 未找到弹幕通道 ===");
                System.out.println("  收到帧数: " + frameCount[0]);
                System.exit(1);
            }
        } finally {
            cdp.close();
            System.out.println("[6] 浏览器已关闭");
        }
    }
}
