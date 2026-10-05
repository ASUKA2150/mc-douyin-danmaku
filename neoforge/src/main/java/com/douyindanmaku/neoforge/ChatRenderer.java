package com.douyindanmaku.neoforge;

import com.douyindanmaku.core.DanmakuSession;
import com.douyindanmaku.core.config.ConfigStore;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * 把渲染好的弹幕文本送进 Minecraft 聊天栏。
 *
 * <p>这个类的 Fabric 版是 {@code com.douyindanmaku.fabric.ChatRenderer}，
 * 内容几乎一样——因为两个加载器用的都是官方 Mojang 映射，
 * Minecraft 侧的 API 名字完全一致，只有「怎么拿到游戏目录」不同。
 *
 * <h2>线程要求</h2>
 * WebSocket 回调在网络线程上，而聊天栏只能在主线程碰。
 * 所以这里会检查线程，不在主线程就转交过去执行。
 * <b>这一步不能省</b>，否则会随机崩溃。
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
     *                    {@code false} 时只进聊天记录（见配置里的同名字段）
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

    /** 取游戏目录（相对路径按它解析）。 */
    static Path gameDirectory() {
        return FMLPaths.GAMEDIR.get();
    }

    /** 取配置目录。 */
    static Path configDirectory() {
        return FMLPaths.CONFIGDIR.get();
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

    /** 建配置文件读写器。 */
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
