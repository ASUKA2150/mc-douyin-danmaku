package com.douyindanmaku.fabric;

import com.douyindanmaku.core.DanmakuSession;
import com.douyindanmaku.core.config.DanmakuConfig;
import com.douyindanmaku.core.config.ConfigStore;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/**
 * 把渲染好的弹幕文本送进 Minecraft 聊天栏。
 *
 * <h2>两种送法</h2>
 * <ul>
 *   <li>{@code displayClientMessage(text, false)} —— 走原版「系统消息」通道。
 *       会显示在聊天栏底部，也会顶掉最旧的一条，并且记录进聊天记录。
 *       生存模式下的 {@code /say} 之类用的就是这个通道，所以模组发的提示
 *       和弹幕看起来跟原版消息一样自然。这是默认方式。</li>
 *   <li>{@code gui.getChat().addMessage(text)} —— 只写进聊天记录，不占用
 *       聊天栏的显示区域。直播时不想让弹幕糊住半个屏幕、但希望回头能翻看，
 *       就用这个（配置：{@code ensureChat = false}）。</li>
 * </ul>
 *
 * <h2>线程要求</h2>
 * WebSocket 回调在网络线程上，而 Minecraft 的聊天栏只能在主线程碰。
 * 所以这里的每个方法开头都会检查线程，不在主线程就转交过去执行。
 * <b>这一步不能省</b>，否则会随机崩溃（而且是那种很难复现的崩溃）。
 */
final class ChatRenderer {

    private ChatRenderer() {
    }

    /**
     * 送一条弹幕/提示到聊天栏。
     *
     * @param text        已经带好 {@code §} 颜色代码的文本
     * @param keepHistory 是否写进聊天记录；{@code false} 表示这条只是「一闪而过」
     * @param ensureChat  是否要占聊天栏的显示位置。
     *                    {@code false} 时只进聊天记录（见 {@link DouyinDanmakuFabric#config} 里的同名字段）
     */
    static void send(String text, boolean keepHistory, boolean ensureChat) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        if (!client.isSameThread()) {
            client.execute(() -> send(text, keepHistory, ensureChat));
            return;
        }
        if (client.player == null) {
            // 还在标题界面，等进世界之后再显示
            return;
        }

        Component component = Component.literal(text);
        if (ensureChat) {
            client.player.displayClientMessage(component, false);
        } else if (keepHistory) {
            client.gui.getChat().addMessage(component);
        }
    }

    /** 取游戏目录（模组配置、相对路径都按它解析）。 */
    static Path gameDirectory() {
        return FabricLoader.getInstance().getGameDir();
    }

    /** 取配置目录。 */
    static Path configDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }

    /** 给 {@link DanmakuSession} 用的适配器。 */
    static DanmakuSession.MessageSink messageSink(java.util.function.BooleanSupplier ensureChatFlag,
                                                  java.util.function.BooleanSupplier keepHistoryFlag) {
        return (text, kind) -> send(text,
                shouldKeepHistory(kind, keepHistoryFlag.getAsBoolean()),
                ensureChatFlag.getAsBoolean());
    }

    /**
     * 这条消息要不要记进聊天记录。
     *
     * <p>聊天弹幕值得回头翻（有人说了什么好玩的），但礼物、进房、点赞
     * 属于「当时看到就行」的信息——全都塞进记录会把真正想找的弹幕淹掉。
     */
    private static boolean shouldKeepHistory(com.douyindanmaku.core.model.DanmakuEvent.Kind kind,
                                             boolean keepHistoryEnabled) {
        return keepHistoryEnabled
                && kind == com.douyindanmaku.core.model.DanmakuEvent.Kind.CHAT;
    }

    /** 从配置里读数据源，读不出来就用默认值。 */
    static DanmakuConfig.Source parseSource(String raw) {
        if (raw == null) {
            return DanmakuConfig.Source.DIRECT;
        }
        try {
            return DanmakuConfig.Source.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return DanmakuConfig.Source.DIRECT;
        }
    }

    /** 保证配置目录存在。 */
    static ConfigStore createConfigStore() {
        return new ConfigStore(configDirectory().resolve("douyindanmaku.json"));
    }

    /**
     * 日志文件位置。
     *
     * <p>单独放一个文件而不是只写进游戏日志，是因为游戏日志又长又吵，
     * 用户遇到「连不上」的时候很难在里面找到关键信息。
     */
    static java.nio.file.Path logFile() {
        return configDirectory().resolve("douyindanmaku.log");
    }
}
