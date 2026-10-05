package com.douyindanmaku.fabric;

import com.douyindanmaku.core.DanmakuLog;
import com.douyindanmaku.core.DanmakuSession;
import com.douyindanmaku.core.config.ConfigStore;
import com.douyindanmaku.core.config.DanmakuConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fabric 客户端入口。
 *
 * <h2>这个类负责什么</h2>
 * <ul>
 *   <li>读配置、建 {@link DanmakuSession}</li>
 *   <li>注册 {@code /dy} 系列命令</li>
 *   <li>检测「进入世界」这个时机，触发自动连接</li>
 * </ul>
 * 真正的抖音协议、过滤、渲染逻辑都在 {@code common} 里，两个加载器共用。
 *
 * <h2>为什么用「tick 里检测玩家」而不是「直接启动时连接」</h2>
 * 模组初始化时游戏可能还停在标题界面，此时没有玩家对象，
 * 发消息会失败、也拿不到游戏目录相关的运行时环境。
 * 所以等到玩家真的进世界了再连——这也是玩家直觉上期望的时机。
 */
public final class DouyinDanmakuFabric implements ClientModInitializer {

    public static final String MOD_ID = "douyindanmaku";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static DouyinDanmakuFabric instance;

    private ConfigStore configStore;
    private DanmakuConfig config;
    private DanmakuSession session;

    /** 防止 autoConnect 在一次游戏里重复触发。 */
    private final AtomicBoolean autoConnectDone = new AtomicBoolean(false);

    /** 记录上一 tick 玩家是否已经进世界，用来检测「刚进世界」这个瞬间。 */
    private boolean wasInWorld = false;

    @Override
    public void onInitializeClient() {
        instance = this;

        // 先开日志文件，这样后面所有的初始化信息都能被记下来
        com.douyindanmaku.core.LogFile.ensure(ChatRenderer.logFile());

        // 把核心代码的日志接到模组自己的日志器上
        DanmakuLog.setSink(message -> LOGGER.info(message));

        configStore = ChatRenderer.createConfigStore();
        config = configStore.load();

        session = new DanmakuSession(
                config,
                ChatRenderer.gameDirectory(),
                ChatRenderer.messageSink(() -> config.ensureChat, () -> config.keepHistory),
                ignored -> {
                    // 状态提示已经通过 messageSink 显示过了，这里不用重复
                });

        registerCommands();
        registerTickHandler();

        LOGGER.info("[抖音弹幕] Fabric 版已加载，配置文件：{}", configStore.file());
    }

    /** 供命令处理器访问。 */
    static DouyinDanmakuFabric get() {
        return instance;
    }

    DanmakuSession session() {
        return session;
    }

    DanmakuConfig config() {
        return config;
    }

    void saveConfig() {
        configStore.save(config);
    }

    /** 配置文件路径（{@code /dy status} 里显示给用户看）。 */
    String configStorePath() {
        return configStore.file().toString();
    }

    /** 日志文件路径（排查问题时要看的就是它）。 */
    String logFilePath() {
        return ChatRenderer.logFile().toString();
    }

    // ==================================================================
    //  命令
    // ==================================================================

    /**
     * 注册 {@code /dy} 系列命令。
     *
     * <pre>
     *   /dy connect &lt;直播间号或分享链接&gt;
     *   /dy disconnect
     *   /dy status
     *   /dy reload
     * </pre>
     */
    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("dy")
                        .then(ClientCommandManager.literal("connect")
                                .then(ClientCommandManager.argument("room",
                                                com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                        .executes(context -> DouyinCommandHandler.connect(
                                                com.mojang.brigadier.arguments.StringArgumentType
                                                        .getString(context, "room")))))
                        .then(ClientCommandManager.literal("disconnect")
                                .executes(context -> DouyinCommandHandler.disconnect()))
                        .then(ClientCommandManager.literal("status")
                                .executes(context -> DouyinCommandHandler.status()))
                        .then(ClientCommandManager.literal("reload")
                                .executes(context -> DouyinCommandHandler.reload()))
                        .then(ClientCommandManager.literal("login")
                                .executes(context -> DouyinCommandHandler.login()))
                        .then(ClientCommandManager.literal("stats")
                                .executes(context -> DouyinCommandHandler.stats()))
                        .then(ClientCommandManager.literal("toggle")
                                .then(ClientCommandManager.argument("type",
                                                com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(context -> DouyinCommandHandler.toggle(
                                                com.mojang.brigadier.arguments.StringArgumentType
                                                        .getString(context, "type")))))
                        .executes(context -> DouyinCommandHandler.help())));
    }

    /**
     * 从磁盘重读配置。
     *
     * <p>就地覆盖字段而不是换对象——{@link DanmakuSession} 和各个数据源
     * 持有的都是同一个引用，换对象它们就看不见新配置了。
     */
    void reloadConfig() {
        DanmakuConfig reloaded = configStore.load();
        DanmakuConfig target = this.config;

        target.autoConnect = reloaded.autoConnect;
        target.room = reloaded.room;
        target.source = reloaded.source;
        target.chromePath = reloaded.chromePath;
        target.chromeProfileDir = reloaded.chromeProfileDir;
        target.chromeOffscreen = reloaded.chromeOffscreen;
        target.chromeWindowSize = reloaded.chromeWindowSize;
        target.signScriptPath = reloaded.signScriptPath;
        target.nodePath = reloaded.nodePath;
        target.userLevelOverride = reloaded.userLevelOverride;
        target.userLevelIconOverride = reloaded.userLevelIconOverride;
        target.fanClubLevelOverride = reloaded.fanClubLevelOverride;
        target.fansClubOverride = reloaded.fansClubOverride;
        target.nickNameOverride = reloaded.nickNameOverride;
        target.chatContentOverride = reloaded.chatContentOverride;
        target.ensureChat = reloaded.ensureChat;
        target.keepHistory = reloaded.keepHistory;
        target.showUserLevel = reloaded.showUserLevel;
        target.showFanClub = reloaded.showFanClub;
        target.chatFormat = reloaded.chatFormat;
        target.systemFormat = reloaded.systemFormat;
        target.showGift = reloaded.showGift;
        target.showMember = reloaded.showMember;
        target.showLike = reloaded.showLike;
        target.giftFormat = reloaded.giftFormat;
        target.likeFormat = reloaded.likeFormat;
        target.memberFormat = reloaded.memberFormat;
        target.filterMode = reloaded.filterMode;
        target.filterKeywords = new java.util.ArrayList<>(reloaded.filterKeywords);
        target.blockedUsers = new java.util.ArrayList<>(reloaded.blockedUsers);
        target.maxDanmakuPerSecond = reloaded.maxDanmakuPerSecond;
        target.stripEmoji = reloaded.stripEmoji;
    }

    // ==================================================================
    //  进入世界后自动连接
    // ==================================================================

    private void registerTickHandler() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> onClientTick(client));
    }

    private void onClientTick(Minecraft client) {
        boolean inWorld = client.player != null && client.level != null;

        // 刚进入世界的那一 tick
        if (inWorld && !wasInWorld) {
            if (config.autoConnect && autoConnectDone.compareAndSet(false, true)) {
                DouyinCommandHandler.autoConnect();
            }
        }

        // 退出世界时断开连接，免得在标题界面还在收弹幕
        if (!inWorld && wasInWorld) {
            if (session.isConnected()) {
                session.disconnect();
                DouyinCommandHandler.notify("已退出世界，弹幕连接已断开");
            }
            autoConnectDone.set(false);
        }

        wasInWorld = inWorld;
    }
}
