package com.douyindanmaku.forge;

import com.douyindanmaku.core.DanmakuLog;
import com.douyindanmaku.core.DanmakuSession;
import com.douyindanmaku.core.config.ConfigStore;
import com.douyindanmaku.core.config.DanmakuConfig;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Forge 1.21.1 客户端入口。
 *
 * <p>逻辑和 Fabric / NeoForge 版完全一样，
 * 区别只在「怎么注册事件、怎么注册命令」这些加载器相关的地方。
 * 抖音协议、过滤、渲染都在 {@code common} 里，所有加载器共用一份。
 *
 * <h2>和 Forge 1.20.1 版的差异：只有一处</h2>
 * <p>两个版本的 Forge API 几乎一样（模组总线、游戏总线、命令事件
 * 的写法全都相同），唯一不同的是<b>每 tick 事件</b>：
 *
 * <table border="1">
 *   <tr><th></th><th>Forge 1.20.1</th><th>Forge 1.21.1</th></tr>
 *   <tr>
 *     <td>事件类</td>
 *     <td>{@code TickEvent.ClientTickEvent}<br>一个 tick 触发<b>两次</b></td>
 *     <td>{@code TickEvent.ClientTickEvent.Post}<br>只触发一次</td>
 *   </tr>
 *   <tr>
 *     <td>要不要判 phase</td>
 *     <td>要，用 {@code event.phase} 区分 START / END</td>
 *     <td>不用</td>
 *   </tr>
 * </table>
 *
 * <p>原因：Forge 在 1.21.1 上跟进了 NeoForge 的新事件模型，
 * 给 {@code ClientTickEvent} 加了 {@code Pre} / {@code Post} 两个子类
 * （实测确认，两个子类都有公开无参构造器）。
 * 老的 {@code ClientTickEvent} 仍然可用，但一个 tick 触发两次。
 *
 * <p>所以这个文件比 forge-1.20.1 那份<b>更简单</b>——
 * 少了一次 phase 判断，也就少了一类会写错的地方。
 *
 * <h2>和 NeoForge 1.21.1 版的差异</h2>
 * <table border="1">
 *   <tr><th></th><th>Forge 1.21.1</th><th>NeoForge 1.21.1</th></tr>
 *   <tr>
 *     <td>拿模组总线</td>
 *     <td>{@code FMLJavaModLoadingContext.get().getModEventBus()}</td>
 *     <td>构造器参数直接注入 {@code IEventBus}</td>
 *   </tr>
 *   <tr>
 *     <td>游戏总线</td>
 *     <td>{@code net.minecraftforge.common.MinecraftForge.EVENT_BUS}</td>
 *     <td>{@code net.neoforged.neoforge.common.NeoForge.EVENT_BUS}</td>
 *   </tr>
 *   <tr>
 *     <td>客户端命令</td>
 *     <td>{@code net.minecraftforge.client.event.RegisterClientCommandsEvent}</td>
 *     <td>{@code net.neoforged.neoforge.client.event.RegisterClientCommandsEvent}</td>
 *   </tr>
 * </table>
 */
@Mod(DouyinDanmakuForge.MOD_ID)
public final class DouyinDanmakuForge {

    public static final String MOD_ID = "douyindanmaku";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static DouyinDanmakuForge instance;

    private ConfigStore configStore;
    private DanmakuConfig config;
    private DanmakuSession session;

    /** 防止 autoConnect 在一次游戏里重复触发。 */
    private final AtomicBoolean autoConnectDone = new AtomicBoolean(false);

    /** 记录上一 tick 玩家是否已经进世界，用来检测「刚进世界」这个瞬间。 */
    private boolean wasInWorld = false;

    public DouyinDanmakuForge() {
        instance = this;

        // Forge 拿模组总线的方式：
        // 走 FMLJavaModLoadingContext 的单例，而不是往构造器里注入参数
        // （构造器注入是 NeoForge 1.20.5 之后才有的写法，Forge 一直没有）。
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

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
        // （客户端命令和 tick 都是「游戏运行时」的事件，不是模组生命周期事件）
        MinecraftForge.EVENT_BUS.addListener(this::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);

        // modEventBus 目前用不到，但保留引用方便以后加配置界面之类的功能
        if (modEventBus == null) {
            LOGGER.warn("拿不到模组事件总线，这不影响弹幕功能");
        }

        DouyinCommandHandler.announceTogglesAtStartup(config);

        LOGGER.info("[抖音弹幕] Forge 版已加载，配置文件：{}", configStore.file());
    }

    static DouyinDanmakuForge get() {
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
     * <p>用的是 {@code RegisterClientCommandsEvent}（客户端命令事件），
     * 而不是 {@code RegisterCommandsEvent}（服务端命令事件）。
     * 区别很重要：前者不经过服务端的权限校验，所以在<b>单人游戏没开作弊</b>时
     * 也能正常使用；后者会因为玩家权限等级为 0 而拒绝执行。
     *
     * <pre>
     *   /dy connect &lt;直播间号或分享链接&gt;
     *   /dy disconnect
     *   /dy status
     *   /dy reload
     *   /dy toggle &lt;类型&gt;
     * </pre>
     */
    private void onRegisterCommands(net.minecraftforge.client.event.RegisterClientCommandsEvent event) {
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
        target.likeAnonymousFormat = reloaded.likeAnonymousFormat;
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

    /**
     * 每 tick 调一次，用来检测「刚进世界」和「刚退出世界」。
     *
     * <p>参数类型是 {@code ClientTickEvent.Post}，不是
     * {@code ClientTickEvent}——这是 Forge 1.21.1 和 1.20.1 唯一的不同。
     *
     * <p>用 {@code Post} 的好处是它一个 tick 只触发一次，
     * 不需要像 1.20.1 那样判断 {@code event.phase}。
     * 老的 {@code ClientTickEvent} 仍然可以注册，但会触发两次，
     * 那样下面的进/退世界检测就会各跑两遍。
     */
    private void onClientTick(TickEvent.ClientTickEvent.Post event) {
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
