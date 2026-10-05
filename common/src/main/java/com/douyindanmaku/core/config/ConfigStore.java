package com.douyindanmaku.core.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 把配置读写到磁盘。
 *
 * <p>两个加载器的配置文件位置不同（Fabric 是 {@code config/douyindanmaku.json}，
 * NeoForge 是 {@code config/douyindanmaku.json}，其实一样），
 * 但读写逻辑一样，所以放在这里。
 *
 * <p>设计原则：<b>配置出问题绝不能影响游戏启动</b>。
 * 所以读失败就用默认值，写失败只记日志、不抛异常。
 */
public final class ConfigStore {

    private final Path configFile;

    public ConfigStore(Path configFile) {
        this.configFile = configFile;
    }

    /** 配置文件路径。 */
    public Path file() {
        return configFile;
    }

    /**
     * 读取配置。文件不存在就创建一份默认的。
     */
    public DanmakuConfig load() {
        try {
            if (!Files.isRegularFile(configFile)) {
                DanmakuConfig defaults = DanmakuConfig.defaults();
                save(defaults);
                return defaults;
            }
            String json = Files.readString(configFile, StandardCharsets.UTF_8);
            DanmakuConfig loaded = DanmakuConfig.fromJson(json);
            // 用户手改坏了字段时，把规范化的结果写回去，顺便让他看到正确格式
            return loaded.normalized();
        } catch (IOException failed) {
            com.douyindanmaku.core.DanmakuLog.error("读取配置失败，改用默认配置", failed);
            return DanmakuConfig.defaults();
        }
    }

    /**
     * 保存配置。
     *
     * <p>先写临时文件再原子替换，这样即使写一半断电也不会把原配置文件弄坏。
     */
    public void save(DanmakuConfig config) {
        try {
            Path parent = configFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = configFile.resolveSibling(configFile.getFileName() + ".tmp");
            Files.writeString(temporary, config.toJson(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, configFile,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                // 某些文件系统不支持原子替换，退回普通移动
                Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failed) {
            com.douyindanmaku.core.DanmakuLog.error("保存配置失败：" + configFile, failed);
        }
    }
}
