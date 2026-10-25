# 抖音弹幕 for Minecraft

把抖音直播间的弹幕搬进 Minecraft 聊天栏。开播时不用另开窗口盯着，游戏里直接看。

---

## 下载

去 [Releases](https://github.com/ASUKA2150/mc-douyin-danmaku/releases)，按你的版本挑一个：

| Minecraft | 加载器 | 文件 |
|---|---|---|
| 1.20.1 | Fabric | `douyin-danmaku-fabric-1.20.1-1.0.0.jar` |
| 1.20.1 | Forge | `douyin-danmaku-forge-1.20.1-1.0.0.jar` |
| 1.21.1 | Fabric | `douyin-danmaku-fabric-1.21.1-1.0.0.jar` |
| 1.21.1 | Forge | `douyin-danmaku-forge-1.21.1-1.0.0.jar` |
| 1.21.1 | NeoForge | `douyin-danmaku-neoforge-1.21.1-1.0.0.jar` |
| 1.21.11 | Fabric | `douyin-danmaku-fabric-1.21.11-1.0.0.jar` |

---

## 安装

1. 把 jar 丢进 `mods`
2. 电脑上装 **Chrome**（或 Edge），模组要用它打开直播间页面
3. 进游戏，输入 `/dy connect 你的直播间号`

直播间号就是 `live.douyin.com/` 后面那串数字，链接直接贴进去也行。

---

## 命令

| 命令 | 作用 |
|---|---|
| `/dy connect <直播间号或链接>` | 连接 |
| `/dy disconnect` | 断开 |
| `/dy login` | 登录抖音（**想收礼物才需要**） |
| `/dy status` | 看当前状态 |
| `/dy reload` | 重读配置 |
| `/dy toggle <类型>` | 开关某类消息 |
| `/dy` | 帮助 |

`/dy toggle` 的类型：`chat` / `gift` / `member` / `follow` / `share` / `like`。

---

## 收不到礼物？

**抖音只向已登录的连接推礼物消息。** 游客能收到弹幕、进房、点赞，但礼物一条都没有。

执行一次 `/dy login`，在弹出的窗口里登录抖音，**然后关掉那个窗口**，再 `/dy connect`。

登录状态存在本地浏览器配置目录里，**一次就够，不用重复**。以「观众」身份旁观，不会用你的账号发任何东西。

---

## 配置

`config/douyindanmaku.json`，改完 `/dy reload`。

常用几项：

```json
{
  "showGift": true,
  "showMember": false,
  "showLike": false,
  "showFollow": true,
  "showShare": true,
  "alertOnDisconnect": true
}
```

- `showMember`（进房）、`showLike`（点赞）默认关，人多会刷屏
- `alertOnDisconnect` 关掉之后，断连就只记日志，不在聊天栏显示红字

消息模板（`chatFormat`、`giftFormat` 等）都能改，用 `&` 加字母表示颜色，比如 `&b` 青色、`&6` 金色。改坏了删掉配置文件会重新生成默认的。

---

## 已知限制

- **客户端模组**，单人游戏或自己开的局域网世界里用
- 抖音的弹幕通道里**没有「累计获赞」**，面板上的获赞数这里读不到
- 抖音偶尔会改协议，收不到就开个 issue，附上 `config/douyindanmaku.log`

---

## 致谢

协议实现参考了 [BakaDanmaku](https://github.com/TartaricAcid/BakaDanmaku)（B 站弹幕）和 [DanmuFree](https://github.com/SoraYjy/DanmuFree)（抖音弹幕抓取）。

---

## 许可证

MIT
