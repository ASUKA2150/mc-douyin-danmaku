package com.douyindanmaku.fabric;

import com.douyindanmaku.core.DanmakuSession;
import com.douyindanmaku.core.config.DanmakuConfig;
import com.douyindanmaku.core.douyin.DouyinRoomResolver;

/**
 * {@code /dy} 命令的实现。
 *
 * <p>命令本身只做「参数校验 + 调 {@link DanmakuSession}」，
 * 不碰任何网络细节。
 * 返回值是 Brigadier 要求的「命令结果」，1 表示成功、0 表示失败。
 */
final class DouyinCommandHandler {

    private DouyinCommandHandler() {
    }

    static int help() {
        notify("抖音弹幕模组 —— 可用命令：");
        notify("  &f/dy connect <直播间号> &7连接直播间（也支持粘贴分享链接）");
        notify("  &f/dy disconnect &7断开连接");
        notify("  &f/dy status &7查看当前状态");
        notify("  &f/dy reload &7重新读取配置文件");
        notify("  &f/dy toggle &7<chat|gift|member|like> &7开关某类消息");
        return 1;
    }

    /** {@code /dy connect <直播间>}。 */
    static int connect(String roomInput) {
        DouyinDanmakuFabric mod = DouyinDanmakuFabric.get();
        if (mod == null) {
            return 0;
        }

        String webRid = DouyinRoomResolver.extractWebRid(roomInput);
        if (webRid == null) {
            notify("&c无法识别直播间号：「" + roomInput + "」");
            notify("&7请填纯数字短号（如 &f123456789&7），或直接粘贴直播间链接。");
            return 0;
        }

        DanmakuConfig config = mod.config();
        if (config.source == DanmakuConfig.Source.TCP) {
            notify("&7当前数据源是 &fTCP&7（等待外部程序转发），不需要填直播间号。");
            notify("&7想改成连抖音，请把配置文件里的 &fsource &7改成 &fCHROME&7 后执行 &f/dy reload&7。");
        }

        config.room = webRid;
        mod.saveConfig();

        mod.session().connect(webRid);
        return 1;
    }

    /** 进世界时按配置自动连接。 */
    static int autoConnect() {
        DouyinDanmakuFabric mod = DouyinDanmakuFabric.get();
        if (mod == null) {
            return 0;
        }
        DanmakuConfig config = mod.config();

        // TCP 模式是「等别人连过来」，不需要直播间号
        if (config.source == DanmakuConfig.Source.TCP) {
            mod.session().connect("");
            return 1;
        }
        // Chrome 旁观和直连都需要直播间号
        if (config.room == null || config.room.isBlank()) {
            notify("&7配置里没有直播间号，用 &f/dy connect <直播间号> &7连一个。");
            return 0;
        }
        mod.session().connect(config.room);
        return 1;
    }

    /** {@code /dy disconnect}。 */
    static int disconnect() {
        DouyinDanmakuFabric mod = DouyinDanmakuFabric.get();
        if (mod == null) {
            return 0;
        }
        mod.session().disconnect();
        return 1;
    }

    /** {@code /dy status}。 */
    static int status() {
        DouyinDanmakuFabric mod = DouyinDanmakuFabric.get();
        if (mod == null) {
            return 0;
        }
        DanmakuConfig config = mod.config();

        notify("&7状态：&f" + mod.session().describeState());
        notify("&7数据源：&f" + config.source
                + (config.room == null || config.room.isBlank() ? "" : "&7，直播间：&f" + config.room));
        notify("&7配置文件：&f" + mod.configStorePath());
        notify("&7日志文件：&f" + mod.logFilePath());
        return 1;
    }

    /** {@code /dy reload}。 */
    static int reload() {
        DouyinDanmakuFabric mod = DouyinDanmakuFabric.get();
        if (mod == null) {
            return 0;
        }
        mod.reloadConfig();
        notify("&a配置已重新读取。如果连接参数改了，请执行 &f/dy disconnect&a 再 &f/dy connect&a。");
        return 1;
    }

    /** 往聊天栏发一条模组提示。 */
    static void notify(String message) {
        DouyinDanmakuFabric mod = DouyinDanmakuFabric.get();
        if (mod != null) {
            mod.session().status(message);
        }
    }

    /**
     * {@code /dy stats}：输出直播间统计字段的观测报告。
     *
     * <p>获赞数不对的时候用它——能直接看出抖音推了哪些字段。
     */
    static int stats() {
        DouyinDanmakuFabric mod = DouyinDanmakuFabric.get();
        if (mod == null) {
            return 0;
        }
        mod.session().reportStats();
        return 1;
    }

    /**
     * {@code /dy login}：打开浏览器窗口让用户登录抖音。
     *
     * <p>为什么要登录：礼物消息只有登录态才推。游客能收到弹幕、进房、点赞，
     * 但收不到礼物。登录一次就长期有效（凭据存在浏览器配置目录里）。
     */
    static int login() {
        DouyinDanmakuFabric mod = DouyinDanmakuFabric.get();
        if (mod == null) {
            return 0;
        }
        notify("&7即将打开一个浏览器窗口，请在里面登录抖音。");
        notify("&7登录好之后 &f关掉那个窗口&7，再执行 &f/dy connect&7 即可。");
        mod.session().login();
        return 1;
    }

    /**
     * {@code /dy toggle <类型>}：开关某一类消息。
     *
     * <p>直播中临时调整用——比如观众刷礼物刷屏了，想先关掉礼物提示
     * 专心看弹幕，不用去改配置文件重启。改完立刻存盘，下次启动还是这个设置。
     */
    static int toggle(String what) {
        DouyinDanmakuFabric mod = DouyinDanmakuFabric.get();
        if (mod == null) {
            return 0;
        }
        DanmakuConfig config = mod.config();

        String key = what == null ? "" : what.trim().toLowerCase(java.util.Locale.ROOT);
        switch (key) {
            case "gift", "礼物" -> {
                config.showGift = !config.showGift;
                notify("&7礼物消息：&f" + onOff(config.showGift));
            }
            case "member", "enter", "进房", "入场" -> {
                config.showMember = !config.showMember;
                notify("&7进房消息：&f" + onOff(config.showMember));
            }
            case "like", "点赞" -> {
                config.showLike = !config.showLike;
                notify("&7点赞播报：&f" + onOff(config.showLike)
                        + (config.showLike
                        ? "&7（按人攒一波，停下来 5 秒后报一次；同一人 30 秒最多一次）" : ""));
            }
            default -> {
                notify("&c要开关哪一种？可用：&fchat&7 / &fgift&7 / &fmember&7 / &flike");
                notify("&7例如：&f/dy toggle gift&7 切换礼物消息");
                return 0;
            }
        }

        mod.saveConfig();
        return 1;
    }

    /** 进房和点赞默认是关的，所以启动时提示一下怎么开。 */
    static void announceTogglesAtStartup(DanmakuConfig config) {
        if (!config.showMember && !config.showLike) {
            notify("&7提示：进房和点赞默认关闭，用 &f/dy toggle member&7 和 &f/dy toggle like&7 打开。");
        }
    }

    private static String onOff(boolean value) {
        return value ? "&a已开启" : "&c已关闭";
    }
}
