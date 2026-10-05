package com.douyindanmaku.core.chrome;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 复现「浏览器意外退出了」（不属于模组本体）。
 *
 * <h2>为什么需要它</h2>
 * 单独启动浏览器是好的，但模组里会报「浏览器意外退出了」。
 * 说明问题不在启动参数，而在<b>启动之后的某一步</b>。
 * 这个程序逐步走一遍模组的调用序列，每一步都检查浏览器是否还活着，
 * 这样就能定位到底哪一步把它搞死了。
 *
 * <h2>用法</h2>
 * <pre>
 *   java ... ModLaunchProbe                        # 自动找浏览器
 *   java ... ModLaunchProbe &lt;浏览器exe&gt;
 *   java ... ModLaunchProbe &lt;浏览器exe&gt; &lt;直播间号&gt;
 * </pre>
 *
 * <p>它不会去抖音，只打开 {@code about:blank}，所以跑得很快（几秒）——
 * 目的是把「浏览器被杀」和「抖音页面问题」分开。
 */
public final class ModLaunchProbe {

    public static void main(String[] args) throws Exception {
        Path browser;
        if (args.length > 0 && !args[0].isBlank()) {
            browser = Path.of(args[0]);
        } else {
            browser = ChromeFinder.find("", null);
            if (browser == null) {
                System.out.println("找不到浏览器。");
                System.exit(1);
            }
        }
        String room = args.length > 1 && !args[1].isBlank() ? args[1] : null;

        Path profile = Path.of(System.getProperty("java.io.tmpdir"), "dy-mod-probe-" + System.nanoTime());
        Files.createDirectories(profile);

        System.out.println("浏览器   : " + browser);
        System.out.println("配置目录 : " + profile);
        System.out.println();

        CdpConnection cdp = new CdpConnection(profile);
        int step = 0;
        try {
            // ---- 完全照抄模组 ChromeDanmakuSource.runOnce 的顺序 ----
            step++;
            System.out.println("[步骤 " + step + "] launchChrome(有窗口, 挪到屏幕外)");
            cdp.launchChrome(browser, "1280,800", true);
            check(cdp, step);

            step++;
            System.out.println("[步骤 " + step + "] openBlankPage()");
            String targetId = cdp.openBlankPage();
            System.out.println("      targetId = " + targetId);
            check(cdp, step);

            step++;
            System.out.println("[步骤 " + step + "] attachToPage()");
            cdp.attachToPage(targetId);
            System.out.println("      " + cdp.debugDescribeSockets());
            check(cdp, step);

            step++;
            System.out.println("[步骤 " + step + "] setEventListener()");
            cdp.setEventListener((method, params) -> {
                if (method.startsWith("Network.webSocket")) {
                    System.out.println("      >> 事件: " + method);
                }
            });
            System.out.println("      " + cdp.debugDescribeSockets());
            check(cdp, step);

            step++;
            System.out.println("[步骤 " + step + "] enableNetwork()");
            cdp.enableNetwork();
            check(cdp, step);

            step++;
            System.out.println("[步骤 " + step + "] prepareAntiThrottle()  <-- 模组新增的，重点怀疑对象");
            cdp.prepareAntiThrottle();
            check(cdp, step);

            step++;
            System.out.println("[步骤 " + step + "] navigate(about:blank 或直播间)");
            cdp.navigate(room == null ? "about:blank" : "https://live.douyin.com/" + room);
            check(cdp, step);

            step++;
            System.out.println("[步骤 " + step + "] 等 12 秒（模拟模组等弹幕通道），期间每秒检查存活");
            for (int second = 1; second <= 12; second++) {
                Thread.sleep(1000);
                boolean alive = cdp.isAlive();
                System.out.println("      " + second + "s  浏览器存活=" + alive
                        + (alive ? "" : "   <-- 就是这一步死的！"));
                if (!alive) {
                    System.out.println();
                    System.out.println("*** 浏览器在第 " + step + " 步死掉了 ***");
                    System.out.println("      " + cdp.explainExit());
                    System.exit(1);
                }
            }

            step++;
            System.out.println("[步骤 " + step + "] keepPageActive()");
            cdp.keepPageActive();
            check(cdp, step);

            step++;
            System.out.println("[步骤 " + step + "] isPageAlive() = " + cdp.isPageAlive());
            check(cdp, step);

            System.out.println();
            System.out.println("*** 全部 " + step + " 步都通过了 —— 浏览器一直活着 ***");
            System.out.println("    说明 mod 的这段逻辑本身没问题，");
            System.out.println("    问题出在 Minecraft / NeoForge 的运行环境上（需要看日志文件）。");
        } finally {
            cdp.close();
            System.out.println();
            System.out.println("已关闭浏览器。");
        }
    }

    private static void check(CdpConnection cdp, int step) {
        boolean alive = cdp.isAlive();
        System.out.println("      浏览器存活=" + alive + (alive ? "" : "   <-- 这一步之后它就死了！"));
        if (!alive) {
            System.out.println();
            System.out.println("*** 浏览器在第 " + step + " 步之后死掉了 ***");
            System.out.println("      " + cdp.explainExit());
            System.exit(1);
        }
    }
}
