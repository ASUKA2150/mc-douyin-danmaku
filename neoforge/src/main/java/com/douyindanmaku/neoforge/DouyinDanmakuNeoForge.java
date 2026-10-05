package com.douyindanmaku.neoforge;

import com.douyindanmaku.core.DanmakuLog;
import com.douyindanmaku.core.DanmakuSession;
import com.douyindanmaku.core.config.ConfigStore;
import com.douyindanmaku.core.config.DanmakuConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NeoForge 客户端入口。
 *
 * <p>逻辑和 Fabric 版（{@code DouyinDanmakuFabric}）完全一样，
 * 区别只在「怎么注册事件、怎么注册命令」这些加载器相关的地方。
 * 抖音协议、过滤、渲染都在 {@code common} 里，两边共用一份。
 *
 * <h2>NeoForge 的事件分两条总线（这是最容易搞混的地方）</h2>
 * <ul>
 *   <li><b>模组总线</b>（构造器参数 {@code modEventBus}）——
 *       模组自己的生命周期事件，比如注册物品、注册配置。</li>
 *   <li><b>游戏总线</b>（{@code NeoForge.EVENT_BUS}）——游戏运行时的事件，
 *       比如每 tick、注册命令、玩家事件。</li>
 * </ul>
 * 我们要用的两个事件都在游戏总线上。
 */
@Mod(value = DouyinDanmakuNeoForge.MOD_ID, dist = Dist.CLIENT)
public final class DouyinDanmakuNeoForge {

    public static final String MOD_ID = "douyindanmaku";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static DouyinDanmakuNeoForge instance;

    private ConfigStore configStore;
    private DanmakuConfig config;
    private DanmakuSession session;

    /** 防止 autoConnect 在一次游戏里重复触发。 */
    private final AtomicBoolean autoConnectDone = new AtomicBoolean(false);

    /** 记录上一 tick 玩家是否已经进世界，用来检测「刚进世界」这个瞬间。 */
    private boolean wasInWorld = false;

    public DouyinDanmakuNeoForge(IEventBus modEventBus) {
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

        // 两个事件都挂在游戏总线上
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);

        LOGGER.info("[抖音弹幕] NeoForge 版已加载，配置文件：{}", configStore.file());
    }

    static DouyinDanmakuNeoForge get() {
        return instance;
    }

    DanmakuSession session() {
        return session;
    }

    DanmakuConfig config() {
        return config;
    }

    ConfigStore configStore() {
        return configStore;
    }

    void saveConfig() {
        configStore.save(config);
    }

    // ==================================================================
    //  命令
    // ==================================================================

    /**
     * 注册 {@code /dy} 系列命令。
     *
     * <p>用的是 {@link RegisterClientCommandsEvent}（客户端命令事件），
     * 而不是 {@code RegisterCommandsEvent}（服务端命令事件）。
     * 区别很重要：前者不经过服务端的权限校验，所以在**单人游戏没开作弊**时
     * 也能正常使用；后者会因为玩家权限等级为 0 而拒绝执行。
     *
     * <pre>
     *   /dy connect &lt;直播间号或分享链接&gt;
     *   /dy disconnect
     *   /dy status
     *   /dy reload
     * </pre>
     */
    private void onRegisterCommands(RegisterClientCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        var roomArgument = com.mojang.brigadier.arguments.StringArgumentType.greedyString();

        dispatcher.register(com.mojang.brigadier.builder.LiteralArgumentBuilder
                .<net.minecraft.commands.CommandSourceStack>literal("dy")
                .executes(context -> DouyinCommandHandler.help())
                .then(com.mojang.brigadier.builder.LiteralArgumentBuilder
                        .<net.minecraft.commands.CommandSourceStack>literal("connect")
                        .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                                .<net.minecraft.commands.CommandSourceStack, String>argument("room", roomArgument)
                                .executes(context -> DouyinCommandHandler.connect(
                                        com.mojang.brigadier.arguments.StringArgumentType
                                                .getString(context, "room")))))
                .then(com.mojang.brigadier.builder.LiteralArgumentBuilder
                        .<net.minecraft.commands.CommandSourceStack>literal("disconnect")
                        .executes(context -> DouyinCommandHandler.disconnect()))
                .then(com.mojang.brigadier.builder.LiteralArgumentBuilder
                        .<net.minecraft.commands.CommandSourceStack>literal("status")
                        .executes(context -> DouyinCommandHandler.status()))
                .then(com.mojang.brigadier.builder.LiteralArgumentBuilder
                        .<net.minecraft.commands.CommandSourceStack>literal("reload")
                        .executes(context -> DouyinCommandHandler.reload()))
                .then(com.mojang.brigadier.builder.LiteralArgumentBuilder
                        .<net.minecraft.commands.CommandSourceStack>literal("login")
                        .executes(context -> DouyinCommandHandler.login()))
                .then(com.mojang.brigadier.builder.LiteralArgumentBuilder
                        .<net.minecraft.commands.CommandSourceStack>literal("stats")
                        .executes(context -> DouyinCommandHandler.stats()))
                .then(com.mojang.brigadier.builder.LiteralArgumentBuilder
                        .<net.minecraft.commands.CommandSourceStack>literal("toggle")
                        .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                                .<net.minecraft.commands.CommandSourceStack, String>argument(
                                        "type", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .executes(context -> DouyinCommandHandler.toggle(
                                        com.mojang.brigadier.arguments.StringArgumentType
                                                .getString(context, "type"))))));
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

    private void onClientTick(ClientTickEvent.Post event) {
        net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
        if (client == null) {
            return;
        }

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
