# 抖音弹幕 for Minecraft

把**抖音直播间的弹幕**实时搬到 Minecraft 聊天栏里。开播的时候不用再拿手机、也不用另开个窗口盯着，游戏里直接就是弹幕姬。

支持 **Minecraft 1.20.1 和 1.21.1**，Fabric / NeoForge / Forge 三个加载器都有对应版本。

**当前是 `main` 分支**，包含全部四个版本，适合开发。只想给某一个
Minecraft 版本编译的话，用对应的精简分支（依赖更少、构建更快）：

| 分支 | 内容 | 备注 |
|---|---|---|
| `main` | 全部四个版本 | 当前分支，开发用 |
| [`1.20.x`](../../tree/1.20.x) | 1.20.1 的 Fabric + Forge | |
| [`1.21.x`](../../tree/1.21.x) | 1.21.1 的 Fabric + NeoForge | **不需要 JDK 17** |

> **只是想下载来玩？** 不用管分支，去
> [Releases](https://github.com/ASUKA2150/mc-douyin-danmaku/releases)
> 按你的 Minecraft 版本挑对应的包就行。

> 这个项目的灵感来自 [BakaDanmaku](https://github.com/TartaricAcid/BakaDanmaku)（B 站弹幕，最早把「弹幕进聊天栏」这条路走通的）和 [DanmuFree](https://github.com/SoraYjy/DanmuFree)（抖音弹幕抓取客户端，抖音协议的实现思路来自它）。详见文末「致谢与开源说明」。

---

## 目录

- [效果](#效果)
- [快速开始](#快速开始)
  - [第一步：装模组](#第一步装模组)
  - [第二步：装 Chrome 或 Edge](#第二步装-chrome-或-edge)
  - [第三步：连接直播间](#第三步连接直播间)
- [**完整使用教程：所有指令和配置项**](docs/使用教程.md)
- [配置文件详解](#配置文件详解)
- [游戏内命令](#游戏内命令)
- [常见问题](#常见问题)
- [它是怎么工作的](#它是怎么工作的)
- [自己编译](#自己编译)
- [致谢与开源说明](#致谢与开源说明)
- [许可证](#许可证)
- [参与贡献](#参与贡献)

> **想查指令怎么用、想改显示样式、想排查问题，直接看
> [完整使用教程](docs/使用教程.md)** —— 那边写得更细。
> 这个 README 主要是讲原理和怎么自己编译。

---

## 效果

游戏里大概长这样（默认只显示粉丝团等级，昵称是青色的）：

```
[抖音] [粉丝团5] 张三: 主播这个房子好漂亮
[抖音] 李四: 哈哈哈哈哈哈哈哈
[抖音] 王五: 关注了
```

颜色、前缀、显示什么内容，都可以在配置文件里改，改完 `/dy reload` 就生效。

---

## 快速开始

### 第一步：装模组

1. 先确认你的 **Minecraft 版本**和**加载器**（见下面的表格）
2. 去仓库的 **Releases** 页面，下载对应的 jar
3. 丢进 `.minecraft/mods` 文件夹

| 你的游戏 | 下载这个 | 还要装什么 |
|---|---|---|
| Minecraft **1.20.1** + Fabric | `douyin-danmaku-fabric-1.20.1-1.0.0.jar` | [Fabric API](https://modrinth.com/mod/fabric-api) |
| Minecraft **1.20.1** + Forge | `douyin-danmaku-forge-1.20.1-1.0.0.jar` | 不用 |
| Minecraft **1.21.1** + Fabric | `douyin-danmaku-fabric-1.21.1-1.0.0.jar` | [Fabric API](https://modrinth.com/mod/fabric-api) |
| Minecraft **1.21.1** + NeoForge | `douyin-danmaku-neoforge-1.21.1-1.0.0.jar` | 不用 |

**文件名里的版本号就是对应的 Minecraft 版本**，照着挑就不会错。

为什么没有别的组合（不是偷懒，是上游就没有）：

| Minecraft | Fabric | NeoForge | Forge |
|---|---|---|---|
| 1.21.1 | 有 | 有 | 无 |
| 1.20.1 | 有 | 无 | 有 |

- **1.20.1 没有 NeoForge**：NeoForge 是 Forge 分家出来的，
  第一个版本给的是 Minecraft 1.20.2。
- **1.21.1 没有 Forge**：1.20.2 之后两者分道扬镳，
  新版本主流是 NeoForge，Forge 基本停在 1.20.1。

> 想自己编译也行，见[自己编译](#自己编译)。

模组是**纯客户端**的，服务端不用装（装了也没用）。

### 第二步：装 Chrome 或 Edge

模组需要一个 **Chrome 或 Edge 浏览器**替你去访问直播间。两个都行，功能一模一样
（Edge 也是 Chromium 内核，用的是同一套调试接口，实测可用）。

这是这个模组最省事的地方。抖音的弹幕服务器要求连接时带一个签名参数，
而这个签名**必须**由抖音自己那段混淆过的 JavaScript 算出来。让真实浏览器去访问，
签名和风控就全交给浏览器了，我们只是在旁边「偷听」它收到的数据。
所以**你不需要装 Node.js，也不需要去找什么签名脚本**。

一般电脑上这俩总有一个。模组是**先找 Chrome、再找 Edge**；
想强制用 Edge 的话，把配置文件里的 `chromePath` 填成 `msedge.exe` 的完整路径就行，
比如 `C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe`。

**关于那个会自动打开的浏览器窗口**：模组会另起一个独立的浏览器
（用它自己的配置目录，不碰你平时用的那个，也不会去读你的登录状态和浏览记录），
窗口默认挪到屏幕外。它**必须有个窗口**才能工作——实测浏览器在无头模式下
压根不会建立弹幕连接，所以这个窗口省不掉。

#### 全屏玩 Minecraft 会不会影响收弹幕？

**这是最容易出问题的地方，模组专门处理过了。**

你全屏玩 MC 的时候，那个浏览器窗口会被系统判定成「被遮挡」，然后浏览器就开始省电：
后台标签页的定时器降到一分钟一次、渲染进程降级，极端情况下**直接把整个页面丢掉**
（丢掉 = 页面被销毁，连接必断）。再加上抖音自己也有「长时间无操作就暂停」的逻辑。

所以模组做了四层防护：

1. **关掉浏览器的省电行为**。启动时带上了
   `--disable-background-timer-throttling`、`--disable-backgrounding-occluded-windows`
   （这条专门针对「窗口被遮挡」）、`--disable-renderer-backgrounding`、
   `--disable-tab-discarding`，另外还合并了一个 `--disable-features`，
   把遮挡检测、内存节省器、标签页冻结这些特性一起关了。
2. **把页面钉在活跃状态**。每 20 秒调一次调试接口
   `Page.setWebLifecycleState: active`，顺便模拟一次轻微的鼠标移动，
   让抖音自己的「无操作计时器」一直归零。
3. **模拟页面有焦点**。用 `Emulation.setFocusEmulationEnabled`。
   页面一失去焦点，很多网站就会主动降低刷新率甚至暂停。
4. **自动重连**。万一还是断了（页面自己刷新了、网络抖了、主播重新开播），
   模组会指数退避自动重连（5 秒起，最多 60 秒），不用你手动 `/dy connect`。

> 顺带一提：窗口是**挪到屏幕外**，不是**最小化**，这也是故意的。
> 最小化的窗口会被系统施加更强的 CPU 节流；挪到屏幕外就还是正常状态。

如果还是遇到中途断连，欢迎来提 issue——那说明浏览器版本的行为变了。
临时应对办法是把 `chromeOffscreen` 改成 `false`，让窗口留在桌面上（别最小化）。

### 第三步：连接直播间

进游戏、进一个世界，然后：

```
/dy connect 123456789
```

`123456789` 就是你的直播间号，也就是直播间网址 `https://live.douyin.com/123456789`
最后那串数字。**直接整条分享链接粘进去也行**，模组会自己解析。

连上之后聊天栏会提示：

```
[抖音弹幕] 已连接直播间 123456789，弹幕接收中
```

第一次连接要等十几秒（Chrome 得启动、还得加载页面），之后就开始出弹幕了。

> **不想用 Chrome？** 配置里还有另外两种模式。
> `DIRECT` 是模组自己连抖音弹幕服务器（省资源，但要你自己准备签名脚本，
> 见文末附录）；`TCP` 是等外部程序把弹幕转发进来，适合你手上已经有别的
> 抓弹幕工具的情况。

想让它每次进游戏自动连，把配置文件里的 `autoConnect` 改成 `true`，再把 `room` 填好。

---

## 配置文件详解

第一次启动游戏后会自动生成：

```
.minecraft/config/douyindanmaku.json
```

改完在游戏里敲 `/dy reload` 就生效（如果改的是连接参数，得先 `/dy disconnect` 再 `/dy connect`）。

```jsonc
{
  // ============ 基本设置 ============
  "autoConnect": false,          // 进世界后是否自动连接
  "room": "",                    // 直播间号，也可以是分享链接

  // 数据来源，三选一：
  //   "CHROME" = 用浏览器旁观（默认，最省事，不需要准备任何东西）
  //              Chrome 或 Edge 都行，见下面的 chromePath
  //   "DIRECT" = 模组自己连抖音弹幕服务器（省资源，但要自备签名脚本）
  //   "TCP"    = 等外部程序把弹幕转发过来
  "source": "CHROME",

  // ============ 浏览器旁观模式设置 ============
  // chrome.exe 或 msedge.exe 的完整路径，留空 = 自动查找（先 Chrome 后 Edge）
  "chromePath": "",
  "chromeProfileDir": "",        // 浏览器的独立配置目录，留空 = 游戏目录下

  // 是否把浏览器窗口挪到屏幕外（默认开）。
  // 注意两点：① 不能改成无头模式——实测无头下不会建立弹幕连接；
  //          ② 是「挪到屏幕外」而不是「最小化」——最小化会被系统更强地节流
  "chromeOffscreen": true,
  "chromeWindowSize": "1280,800",

  // ============ 直连模式（DIRECT）需要的路径 ============
  "signScriptPath": "sign/sign_runner.js",  // 签名脚本，相对路径按游戏目录算
  "nodePath": "",                // node.exe 路径，留空表示用 PATH 里的

  // ============ 显示设置 ============
  "showUserLevel": true,         // 显示抖音等级（配合 %level.value% 使用）
  "showFanClub": true,           // 显示粉丝团（配合 %fanclub.level% 使用）

  // 弹幕是否要占聊天栏的显示位置。
  //   true  = 正常显示在聊天栏底部（会顶掉最旧的一条）
  //   false = 只写进聊天记录，不占屏幕（直播时不想被弹幕糊住半个屏幕就用这个）
  "ensureChat": true,

  // 是否把弹幕写进聊天记录（按 T 打开的那个界面能翻到）
  "keepHistory": true,

  "stripEmoji": true,            // 去掉 emoji（MC 字体画不出来，会变方框）

  // 弹幕模板。& 加字母表示颜色，&7 灰 &f 白 &b 青 &e 黄 &d 粉
  //
  // 占位符：
  //   %nick%           昵称
  //   %content%        弹幕正文
  //   %level.value%    抖音消费等级的数字
  //   %fanclub.level%  粉丝团等级数字
  //   %fanclub.name%   粉丝团名称
  //
  // 条件块 %?条件:内容%：条件成立才显示这一段，不成立时连颜色代码一起消失。
  //   可用条件：level.isSet（取到消费等级）/ fanclub.isMember（是粉丝团成员）
  //             fanclub.isSet（同上）/ fanclub.isNew（粉丝团等级为 1，即刚加入）
  //   —— 用它就不会出现「不是粉丝团的人留下一对空括号 []」这种难看的情况。
  //
  // 注意：%level.value% 和 %fanclub.level% 都要写完整，没有 %level% 这种简写。
  //
  // 默认模板：只显示粉丝团等级（不显示消费等级），昵称用青色。
  // 没进粉丝团的人前面什么都不加，也不会留下空括号。
  //
  // 昵称用青色而不是白色是有意的：昵称和正文如果都是白的，
  // 扫一眼分不清是谁在说话。
  "chatFormat": "&7[&f抖音&7] %?fanclub.isMember:[&d粉丝团%fanclub.level%&7] %&b%nick%&7: &f%content%",

  // 想连消费等级一起显示（消费等级在前、粉丝团在后）：
  // "chatFormat": "&7[&f抖音&7] %?level.isSet:[&eLv.%level.value%&7] %?fanclub.isMember:[&d粉丝团%fanclub.level%&7] %&b%nick%&7: &f%content%",
  //
  // 只显示消费等级、不显示粉丝团：
  // "chatFormat": "&7[&f抖音&7] %?level.isSet:[&eLv.%level.value%&7] %&b%nick%&7: &f%content%",
  //
  // 两个都不显示，只要昵称和正文：
  // "chatFormat": "&7[&f抖音&7] &b%nick%&7: &f%content%",
  //
  // 换昵称颜色：把上面所有模板里的 &b 换成想要的颜色即可
  //   &b 青（默认）  &e 黄  &a 绿  &9 深蓝  &6 金  &d 粉

  // ============ 各种消息类型的开关与模板 ============
  "showGift": true,              // 显示礼物
  "showMember": false,           // 显示进房（默认关，人气高的房间会刷屏）
  "showLike": false,             // 显示点赞（默认关，同一个人 30 秒最多播报一次）

  "giftFormat": "&7[&f抖音&7] %?fanclub.isMember:[&d粉丝团%fanclub.level%&7] %&b%nick%&7 送出了 &e%gift.name% &7x&f%gift.count%%gift.combo%",
  "memberFormat": "&7[&f抖音&7] %?fanclub.isMember:[&d粉丝团%fanclub.level%&7] %&b%nick%&7 进入了直播间",
  "likeFormat": "&7[&f抖音&7] &d%nick%&c 为主播点赞",

  // 礼物可用占位符：%gift.name% 礼物名 / %gift.count% 数量
  //                 %gift.combo% 连击（连击数 ≤1 时为空白）
  //                 %gift.diamond% 单价（抖币）
  //
  // 点赞可用占位符：%like.count%  这一波点了几个（默认不显示，想要可加回模板）
  //                 %like.session% 本场模组收到的赞（断线归零，只少不多）
  //                 %room.online%  直播间当前在线人数
  //
  // 注意：抖音的弹幕通道里没有「累计获赞」，所以没有对应的占位符。
  //
  // 点赞消息默认用品红 + 红色（&d 昵称 &c 动作），和聊天弹幕的青色区分开，
  // 一眼就能看出这条是点赞而不是弹幕。

  // 模组自己的提示信息（连接成功、报错等）的模板
  "systemFormat": "&8[&b抖音弹幕&8] &7%content%",

  // ============ 过滤设置 ============
  "filterMode": "DISABLED",      // DISABLED 不过滤 / BLACKLIST 黑名单 / WHITELIST 白名单
  "filterKeywords": [],          // 关键词，例如 ["广告", "加群"]
  "blockedUsers": [],            // 屏蔽这些人的弹幕（精确匹配昵称）

  // 每秒最多显示几条，0 = 不限制。
  // 热门直播间弹幕刷得快，设 10~20 能明显减少掉帧
  "maxDanmakuPerSecond": 0,

  // ============ 高级：抖音协议字段号覆盖 ============
  // 正常情况下保持 -1 不要动。
  // 如果哪天发现「等级显示不出来」或者「昵称是空的」，而弹幕本身正常，
  // 说明抖音调整了 protobuf 字段号。这时把对应项改成新的字段号即可，
  // 不用等模组更新。具体字段号可以去 DouyinLiveWebFetcher 的
  // protobuf/douyin.py 里查。
  //   -1 = 用内置默认值（0 是合法的字段号，所以不能拿 0 当「没设置」）
  "userLevelOverride": -1,       // User.pay_grade 的字段号（默认 23）
  "userLevelIconOverride": -1,   // 等级图标的字段号（默认 19，等级数字有时只能从图标 URL 抠）
  "fansClubOverride": -1,        // User.fans_club 的字段号（默认 24）
  "fanClubLevelOverride": -1,    // FansClubData.level 的字段号（默认 2）
  "nickNameOverride": -1,        // 昵称字段号（默认 3）
  "chatContentOverride": -1      // 弹幕正文字段号（默认 3）
}
```

### TCP 模式说明

`source` 设成 `"TCP"` 时，模组会在 **`127.0.0.1:8912`** 上监听
（只绑本机回环地址，局域网里别的机器连不上，安全）。

任何程序连上来，**按行**发 JSON 就能显示弹幕：

```json
{"nick":"张三","content":"你好"}
{"nickname":"张三","content":"你好","level":12,"fanClubLevel":3,"fanClubName":"某某团"}
```

字段名做了兼容：`nick` 和 `nickname` 都认；`level` 是抖音等级；
粉丝团那几个字段是可选的。只有 `content` 是必填。

---

## 游戏内命令

| 命令 | 作用 |
|---|---|
| `/dy connect <直播间号或链接>` | 连接直播间 |
| `/dy disconnect` | 断开连接 |
| `/dy login` | 打开浏览器登录抖音（**想收礼物必须做这一步**） |
| `/dy status` | 看当前状态、用的哪个数据源、配置文件在哪 |
| `/dy stats` | 输出直播间统计字段报告（怀疑数据读错时用） |
| `/dy reload` | 重新读配置文件 |
| `/dy toggle <类型>` | 快速开关某类消息 |
| `/dy` | 显示帮助 |

### 收不到礼物？先登录

**抖音的弹幕服务器只向「已登录」的连接推礼物消息。** 游客状态下弹幕、
进房、点赞都能收到，但礼物一条都不会来——这是抖音的限制，不是模组的问题。

所以想看礼物，执行一次就行：

```
/dy login
```

会弹出一个浏览器窗口，在里面登录抖音，**然后关掉那个窗口**，
再 `/dy connect`。登录凭据存在浏览器配置目录里，**登录一次长期有效**，
以后不用重复。

> 登录后是以「已登录观众」的身份旁观，不需要额外授权，
> 也不会用你的账号发任何东西。

`/dy toggle` 能用的类型：`chat` / `gift` / `member` / `like`。比如观众开始刷礼物刷屏，
你想先专心看弹幕，就敲 `/dy toggle gift`。改完立刻存盘。

> 如果 `/dy` 提示你没权限，说明你在服务器里。
> 这模组是客户端的，**只在单人游戏或者自己开的局域网世界里用**。

### 能显示哪些消息

| 类型 | 默认 | 显示效果 |
|---|---|---|
| 聊天弹幕 | 开 | `[抖音] [粉丝团5] 张三: 你好呀主播` |
| 礼物 | 开 | `[抖音] [粉丝团7] 土豪乙 送出了 小心心 x3 x5` |
| 进房 | 关 | `[抖音] 游客丁 进入了直播间` |
| 点赞 | 关 | `[抖音] 张三 为主播点赞` |

**进房和点赞默认是关的**。人气高的直播间进房会刷屏，点赞更是高频事件，
逐条显示的话，真正的弹幕就被淹了。想看就用 `/dy toggle member`
或 `/dy toggle like` 打开。

### 点赞是怎么显示的

点赞**不是逐条显示**，而是按人攒一波再播报：

- **攒 5 秒**：一个人停下来之后，把这一波合并成一条消息。
  一口气点 100 下也只会出一条。
- **每人 30 秒冷却**：同一个人 30 秒内最多被播报一次，
  所以一直点也只会偶尔冒一条。
- **拿不到昵称的点赞不单独播报**，只计入总数。
  抖音有些点赞消息是不带用户信息的，这类要是也播报一遍，就会和按人播报重复。

### 关于「累计获赞」

**抖音的弹幕通道里没有累计获赞这个数据，所以模组不显示它。**

怎么确认的：把统计消息的所有字段完整导出来比对过，能读出来的数值只有两类——
当前在线人数，以及一个累计量。而那个累计量和直播间面板上的获赞数**对不上**
（实测面板 4 万的时候它是 6.3 万），所以它不是获赞，抖音也没给它标签。

早期版本把它当成获赞显示过，结果就是一个稳定错误的数字。
与其显示一个错的，不如不显示。

模组自己倒是会把收到的点赞增量累加起来，但这个数字只是
「**本次连接期间**收到的量」——断线重连就归零，所以它只用来判断
「刚才这一波点了多少」。想显示的话，在模板里用 `%like.session%`，
详见[使用教程](docs/使用教程.md)。

**在线人数是可靠的**，用 `%room.online%` 就能显示。

---

## 常见问题

### 连不上 / 没反应

第一件事：敲 `/dy status`，它会告诉你当前状态、用的哪种数据源、配置文件在哪。

**浏览器旁观模式（默认）的排查顺序：**

1. **浏览器到底打开了没？** 模组会起一个独立的浏览器窗口
   （Chrome 或 Edge）。如果 `chromeOffscreen` 是 `true`（默认），
   它会藏在屏幕外，但你在任务管理器里应该能看到一个多出来的
   `chrome.exe` 或 `msedge.exe` 进程。
2. **找不到浏览器** → 聊天栏会提示。把配置里的 `chromePath` 填成
   `chrome.exe` 或 `msedge.exe` 的完整路径，比如
   `C:\Program Files\Google\Chrome\Application\chrome.exe` 或
   `C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe`，
   然后 `/dy reload`。
3. **第一次连接要等十几秒**——浏览器冷启动加上加载直播间页面。
   模组最多等 12 秒找弹幕通道，超时就会提示「没找到弹幕通道」。
4. **那个直播间没在开播**。没开播的房间不会有弹幕通道，
   换个正在直播的房间试试。顺便也能验证你房间号填对没有。
5. **别改成无头模式**。实测浏览器在无头模式下**根本不会建立弹幕连接**，
   所以窗口必须存在（挪到屏幕外是可以的）。
6. **中途断连**。模组会自动重连（5 秒起、最多 60 秒一次），
   聊天栏会显示重连提示。如果反复断，把 `chromeOffscreen` 改成 `false`
   让窗口留在桌面上（**不要最小化**——最小化会被系统更强地节流）。

**DIRECT 模式排查**（只有你把 `source` 改成 `DIRECT` 才用得上）：

- 先单独试试签名脚本能不能跑。在游戏目录下执行
  `node sign/sign_runner.js 0123456789abcdef0123456789abcdef`
  - 报「找不到模块 jsdom」→ 你忘了执行 `npm install jsdom`
  - 报 `xxx is not a function` → `sign.js` 暴露的函数名变了，
    把 `sign_runner.js` 里调用的那个名字改掉
  - 正常的话会输出一串签名
- 弹幕服务器域名变了：模组内置了几个备选会自动轮换，
  但要是全被下线，就得更新模组了。

### 我想只显示粉丝团等级，不显示消费等级

**这就是默认行为，什么都不用改。** 默认模板是：

```jsonc
"chatFormat": "&7[&f抖音&7] %?fanclub.isMember:[&d粉丝团%fanclub.level%&7] %&b%nick%&7: &f%content%"
```

效果（昵称是**青色**的，方便一眼定位是谁在说话）：

```
粉丝团成员： [抖音] [粉丝团5] 张三: 你好
普通观众：   [抖音] 路人甲: 哈哈
                    └青色┘
```

注意中间那个 `%?` 是**条件块**的标记，不能省。它的作用就是
「条件成立才显示这一段」——没有它的话，不是粉丝团的观众会留下一对空方括号 `[]`。

> **老配置文件会自动升级**：如果你之前生成过配置文件，模板还停在历史版本的默认值
> （同时显示消费等级的、或者昵称还是白色的），新版启动时会自动帮你换成上面这个。
> 但如果你**自己动手改过**模板，那就绝不会被覆盖——你的偏好优先。

改完敲 `/dy reload` 生效，不用重启游戏。

### 昵称颜色不好看，想换一个

改模板里昵称前面那个颜色代码就行（默认是 `&b` 青色）：

```jsonc
"chatFormat": "&7[&f抖音&7] %?fanclub.isMember:[&d粉丝团%fanclub.level%&7] %&e%nick%&7: &f%content%"
                                                                             ↑ 改成 &e 就是黄色
```

| 写法 | 颜色 | 写法 | 颜色 |
|---|---|---|---|
| `&b` | 青色（默认） | `&6` | 金色 |
| `&e` | 黄色（最醒目） | `&d` | 粉色 |
| `&a` | 绿色 | `&9` | 深蓝 |
| `&c` | 红色 | `&5` | 紫色 |

> **改的时候别把 `%nick%` 后面的 `&7` 和 `&f` 删掉。**
> `&7` 是冒号的灰色、`&f` 是正文的白色——颜色代码会一直影响到后面，
> 少了它们，正文会跟着变成昵称的颜色，整行一个色就又分不清了。

### 弹幕能收到，但等级/粉丝团不显示

多半是抖音调了 protobuf 的字段号（这类问题**不会报错**，只是字段读出来是空的）。
把配置文件里的 `userLevelOverride` / `userLevelIconOverride` /
`fansClubOverride` / `fanClubLevelOverride` 改成新的字段号，再 `/dy reload`，
不用重新编译。字段号可以去 `DouyinLiveWebFetcher` 的 `protobuf/douyin.py` 里查。

### 弹幕里的 emoji 变成方框

正常现象——Minecraft 的默认字体不含彩色 emoji。
配置里 `stripEmoji` 保持 `true` 就会直接把它们去掉，眼不见为净。

### 弹幕太卡 / 掉帧

把 `maxDanmakuPerSecond` 设成 `10` 或 `20`，超出的弹幕会被丢掉。
直播场景下丢几条不影响观看，但掉帧这事儿主播和观众都能立刻感觉到。

### 会不会封号

模组是**匿名旁观**你自己指定直播间的公开弹幕：不登录你的账号、
不发送任何内容、也不修改直播间数据，对抖音来说它就是一个普通观众在用浏览器看直播。
但抖音的接口毕竟不是公开 API，**使用风险请自行判断**。建议只连自己的直播间。

### 已知限制

**已经实测验证过的：**

- **浏览器旁观模式端到端跑通了真实直播间**，而且 **Chrome 和 Edge 都测过**：
  能从抖音直播首页找到正在开播的房间，拿到弹幕通道
  （URL 里带的是页面自己算好的 `signature`），持续收到数据帧。
  「不需要自备签名脚本」这条路是通的。
- **真实直播场景下弹幕、礼物、进房、点赞都能正常显示**，
  在开发者自己的直播间和别人的直播间都实测确认过。
- **统计消息的字段号来自真实抓包**，不是照社区资料推测的。
  测试用例里内嵌了从真实抓包还原出来的字节，字段一旦变了会立刻失败。
- **防节流措施实测有效**：`Page.setWebLifecycleState`、
  `Emulation.setFocusEmulationEnabled`、周期性保活这些调用在
  Chrome 和 Edge 上都能正常执行，页面在长时间测试后仍然活着。
- **协议解码链路有回归测试覆盖**：用人工合成的「结构等同真实帧」的数据
  跑通弹幕 / 礼物 / 点赞 / 进房四种消息的完整解包，
  含昵称、等级、粉丝团、礼物名与数量、点赞增量、在线人数，
  一共 **187 项检查全部通过**（自己跑：`bash tools/selfcheck/run.sh`）。
- **模板系统有专门测试覆盖**：条件块断句、占位符填充、
  颜色代码注入防护、未替换占位符的兜底清理。
- **配置读写容错**：配置文件损坏时回退到默认值，不会导致游戏起不来。

**还没验证、可能要靠实际使用来确认的：**

- **抖音等级和粉丝团等级的实际显示效果。** 解码逻辑是完整的，
  但抖音的 `pay_grade.level` 实测经常是 0（真实等级有时只体现在图标 URL
  的文件名里）。代码里做了「先读字段、读不到再从图标 URL 提取」的双重兜底，
  两条路径都有测试覆盖，但**没在真实弹幕上确认过最终显示效果**。
  万一等级不显示，用配置文件里的 `userLevelOverride` /
  `userLevelIconOverride` 调整就行，不用重新编译。
- **连续跑好几个小时的稳定性。** 防节流和自动重连都实现了，
  但长时间运行在开发阶段没法充分验证。真断了的话模组会自己重连，
  聊天栏会有提示。
- **抖音协议变更。** 签名算法、protobuf 字段号、服务器域名，
  这些东西抖音随时可能改。模组做了容错（字段号可以用配置覆盖、
  服务器域名有备选轮换），但没法保证永久可用。

**两种备用模式也都完整实现了**，随时能切：
`DIRECT`（自己连抖音，省资源但得自备签名脚本）、
`TCP`（外部程序转发，任何能发 JSON 的程序往 `127.0.0.1:8912` 发一行就行）。

---

## 它是怎么工作的

默认走的是 **浏览器旁观模式**（Chrome / Edge 都行）：

```
┌──────────────────────────────────────────────────────┐
│  模组启动一个独立的 Chrome，打开你的直播间页面        │
│  （有窗口模式 —— 无头模式下抖音不建立弹幕连接）        │
└───────────────────────┬──────────────────────────────┘
                        │ Chrome 自己跟抖音握手：
                        │ 签名、cookie、风控 全部由浏览器处理
                        ▼
┌──────────────────────────────────────────────────────┐
│  抖音弹幕服务器                                        │
└───────────────────────┬──────────────────────────────┘
                        │ WebSocket 二进制帧（protobuf + gzip）
                        ▼
┌──────────────────────────────────────────────────────┐
│  我们通过 Chrome 调试接口「旁观」这些帧（不注入、不改页面）│
└───────────────────────┬──────────────────────────────┘
                        │
                        ▼
┌──────────────────────────────────────────────────────┐
│  模组核心（common 模块，两个加载器共用）               │
│                                                      │
│   1. 认出弹幕通道   ChromeDanmakuSource               │
│   2. 解 protobuf    DouyinProtocol（手写解码器）       │
│   3. 去重/过滤/限流 DanmakuFilter                     │
│   4. 渲染文本       DanmakuFormatter                  │
└───────────────────────┬──────────────────────────────┘
                        │ 一行带颜色的文本
                        ▼
┌──────────────────────────────────────────────────────┐
│  Fabric 端 / NeoForge 端（只管把文本塞进聊天栏）       │
└──────────────────────────────────────────────────────┘
```

`DIRECT` 模式（备用）就是自己连抖音弹幕服务器了，得多做几件事：
短号换长号（`DouyinRoomResolver`）、算签名（`DouyinSigner` 调 Node.js）、
发心跳、回 ack（不回的话服务端会把你踢掉）。

几个值得说的设计决定：

- **数据源是可插拔的**。抖音协议变得很勤（签名算法、protobuf 字段号、
  服务器域名都会变），把传输层单独隔出来，坏了大不了只改一个地方。
- **核心代码零第三方依赖**。只用 JDK 自带的 `java.net.http`（HTTP 和
  WebSocket 都有）加 Minecraft 自带的 Gson。要是用 Netty，会和 Minecraft
  自己带的 Netty 版本打架——这是很常见的模组崩溃原因。
- **不用 Mixin**。整个模组没有一处字节码注入，兼容性好，
  也不会因为 Minecraft 版本更新就崩。
- **三个加载器共用同一份核心代码**，只有「怎么注册事件、怎么发聊天消息」
  这些跟加载器有关的部分才分开写。所以加版本、修 bug 都只改一处。
- **不用 Mixin**。这一点在多模组环境下很重要：Mixin 是往游戏类里插代码，
  同一个类被好几个模组改的时候很容易互相打架。本模组完全不碰这条路。

---

## 自己编译

需要 **JDK 21**。

> 只编译 Fabric 和 NeoForge 的话，到这一步就够了。
> 要连 Forge 1.20.1 一起编译，还需要一个 **JDK 17**，见下面「关于 JDK 17」。

```bash
git clone https://github.com/ASUKA2150/mc-douyin-danmaku.git
cd mc-douyin-danmaku
./gradlew collectJars
```

产物在 `build/libs/`，一次出四个：

```
douyin-danmaku-fabric-1.20.1-1.0.0.jar
douyin-danmaku-fabric-1.21.1-1.0.0.jar
douyin-danmaku-forge-1.20.1-1.0.0.jar
douyin-danmaku-neoforge-1.21.1-1.0.0.jar
```

只想编译其中一个（子项目名就是目录名，但 `fabric/` 对应 `:fabric-1.21.1`）：

```bash
./gradlew :fabric-1.20.1:build
./gradlew :forge-1.20.1:build
./gradlew :neoforge-1.21.1:build
```

### 关于 JDK 17

**为什么需要两个 JDK**：Fabric 和 NeoForge 的构建插件都认 JDK 21，
但 Forge 1.20.1 用的 ForgeGradle 本身跑在 Java 17 上——这是它写死的，
不是我们能选的。

**Gradle 不一定能自己找到 JDK 17。** 它只会在几个固定位置找
（当前 JVM、`JAVA_HOME`、Windows 注册表、PATH），装在别处就找不到，
报错长这样：

```
Cannot find a Java installation matching languageVersion=17
```

**解决办法**：在你的**用户级**配置文件里告诉 Gradle 路径。
这个文件不在仓库里，只对你本机生效，不会影响别人：

```
Windows      C:\Users\<你的用户名>\.gradle\gradle.properties
Linux/macOS  ~/.gradle/gradle.properties
```

里面写一行（多个 JDK 用逗号隔开，**用正斜杠**）：

```properties
org.gradle.java.installations.paths=C:/Program Files/Microsoft/jdk-17.0.13.11-hotspot
```

不想改文件也行，把 `JAVA_HOME` 指向 JDK 17 再执行构建即可。
或者干脆只编译其它三个模块，跳过 Forge。

### Windows 用户注意

Windows 上有**两个坑**，都会让上面那几条命令跑不起来：

**坑一：`./gradlew` 是 Linux 写法。** `cmd` 和 PowerShell 不认，要用 `gradlew.bat`：

```bat
gradlew.bat collectJars
```

（如果你用 **Git Bash**，那 `./gradlew` 是可以的。）

**坑二：建议设置 `JAVA_HOME`。** 光把 `java` 加进 PATH 不够，
Gradle 需要这个环境变量，否则可能报 `JAVA_HOME is not set`。

`cmd` 里临时设置（只对当前窗口有效）：

```bat
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot"
gradlew.bat collectJars
```

PowerShell 里是：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot'
.\gradlew.bat collectJars
```

**想一劳永逸**，把 `JAVA_HOME` 设成系统环境变量：Win 键搜索「环境变量」→
编辑系统环境变量 → 环境变量 → 新建，变量名 `JAVA_HOME`，
值是你的 JDK 目录（**不要带 `\bin`**）。

> 怎么找 JDK 目录？在 `cmd` 里执行 `where java`，
> 输出大概是 `C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot\bin\java.exe`，
> **去掉结尾的 `\bin\java.exe`** 就是 `JAVA_HOME` 该填的值。

**坑三：首次构建会比较慢。** 要下载 Gradle 发行包、Minecraft、
以及三个加载器的依赖，视网络情况可能要几分钟到十几分钟。
Gradle 官方分发站在国内经常连不上，
`gradle/wrapper/gradle-wrapper.properties` 里的地址已经换成腾讯云镜像了。

**坑四：编译 Forge 时可能遇到证书报错。**

```
Failed to validate certificate for host 'https://maven.minecraftforge.net/'
```

这是 ForgeGradle 启动时自己多做的一次证书校验。国内不少网络
（以及某些杀毒软件、公司代理）会做 TLS 中间人，导致它失败。
`gradle.properties` 里已经加了这个开关绕过去：

```properties
systemProp.net.minecraftforge.gradle.check.certs=false
```

它只影响这一次额外的连通性检查，不影响 Gradle 自己下载依赖时的证书校验。
如果你在墙外、或者没遇到这个问题，把那行删掉就行。

构建完会自动跑一次元数据自检（`verifyJars`），检查**四个** jar 里的
`fabric.mod.json` / `mods.toml` / `neoforge.mods.toml` / `pack.mcmeta`
对不对，包括：

- 模板占位符有没有都替换掉（漏了会让游戏加载失败）
- `license` 字段在不在（Forge 把它列为必填，缺了会**拒绝加载整个模组包**）
- 依赖的版本区间合不合法，以及**和 jar 文件名里的 MC 版本是否一致**
- `pack.mcmeta` 在不在、`pack_format` 有没有值
  （Forge / NeoForge 会把模组当资源包加载，缺了会报 ResourcePackInfo 错误）
- 图标和入口类是不是真的打进 jar 了

这类毛病在构建阶段完全看不出来，只有装进游戏才暴露，而且报错往往很绕，
所以提前拦住。这几条检查都是踩过坑之后补上的。

### 代码结构

```
mc-douyin-danmaku/
├── common/src/main/java/          ← 核心代码，两个加载器共用
│   └── com/douyindanmaku/core/
│       ├── DanmakuSession.java      把数据源和显示串起来
│       ├── config/                  配置
│       ├── douyin/                  抖音协议
│       │   ├── DouyinProtocol.java     protobuf 编解码（两种模式共用）
│       │   ├── DouyinDanmakuClient.java 直连模式的 WebSocket 连接
│       │   ├── DouyinRoomResolver.java 直播间接号解析
│       │   └── DouyinSigner.java       直连模式的签名
│       ├── chrome/                  浏览器旁观模式（Chrome / Edge 通用）
│       │   ├── ChromeDanmakuSource.java 启动浏览器 + 收弹幕 + 保活 + 重连
│       │   ├── CdpConnection.java       Chrome DevTools Protocol 客户端
│       │   └── ChromeFinder.java        自动找浏览器装在哪
│       ├── model/                   弹幕数据模型
│       ├── proto/                   手写的 protobuf 读取器
│       ├── source/                  其它数据源（TCP）
│       └── text/                    过滤与渲染
├── fabric/                        ← Fabric 1.21.1 端（约 435 行）
├── fabric-1.20.1/                 ← Fabric 1.20.1 端（和上面逐字节相同）
├── neoforge/                      ← NeoForge 1.21.1 端（约 426 行）
└── forge-1.20.1/                  ← Forge 1.20.1 端（约 458 行）
```

> 目录名和 Gradle 子项目名不完全对应（`fabric/` 对应子项目
> `:fabric-1.21.1`），映射关系写在 `settings.gradle` 里。

`common` 里的代码**不允许 import 任何 Fabric / NeoForge / Forge / Minecraft 的类**，
这样它才能被所有版本共用。这条约定靠 `build.gradle` 里的注释和代码审查保证。

那四个加载器端的代码里，真正碰 Minecraft 的只有十几个方法调用，
而且这些 API 在 1.20.1 到 1.21.4 之间**一个都没变**——所以换版本时
适配层几乎不用改，这也是多版本工程能维持下去的原因。

### 支持矩阵

Minecraft 版本和加载器的组合并不是任意的，实际情况是：

| Minecraft | Fabric | NeoForge | Forge |
|---|---|---|---|
| 1.21.1 | 有 | 有 | — |
| 1.20.1 | 有 | — | 有 |

两个「—」的原因：

- **1.20.1 没有 NeoForge。** NeoForge 是 Forge 分家出来的，第一个版本是给
  Minecraft 1.20.2 的。1.20.1 时代只有 Forge。
- **1.21.1 没有 Forge。** 1.20.2 之后 Forge 和 NeoForge 分道扬镳，
  新版本的主流是 NeoForge，Forge 基本停在 1.20.1 那条线。

### 加一个新 Minecraft 版本

因为核心代码（5500 多行，占整个项目的 80%）完全不依赖 Minecraft，
加新版本的成本很低，步骤是：

1. 复制一个现有的子项目目录，比如把 `fabric-1.20.1/` 复制成 `fabric-1.20.4/`
2. 改新目录里 `gradle.properties` 的版本号：
   `minecraft.version`、`java.version`（1.20.5 起才是 21）、以及加载器相关版本
3. 改新目录里 `pack.mcmeta` 的 `pack_format`
   （每个 MC 版本都不一样，填错不影响功能，但资源包页会提示版本不符）
4. 在 `settings.gradle` 里加一行 `include 'fabric-1.20.4'`
5. 编译，修可能的 API 差异

前四步基本是机械操作，真正的工作量在第五步。不过从实测看，
本文用到的那些 Minecraft API 从 1.20.1 到 1.21.4 都没变过，
所以第五步往往也是空的。

根 `build.gradle` 里的 `collectJars` / `verifyJars` 会**自动发现**所有子项目，
不用改。自检还会核对「jar 文件名里的 MC 版本」和「元数据里声明的版本」是否一致，
防止复制子项目时忘了改版本号。

**但要先确认加载器本身支持那个 MC 版本。** 这不是代码问题，是上游的现实：
NeoForge 没有 1.20.1，Forge 没有 1.21.1，加之前先查一下。

### 开发者自检

改完协议相关的代码，不用启动游戏也能快速验证解码链路：

```bash
bash tools/selfcheck/run.sh        # Linux / macOS / Git Bash
pwsh -File tools/selfcheck/run.ps1 # Windows PowerShell
```

它用人工合成的「结构等同真实帧」的数据跑一遍完整解码链路，几秒出结果，
不需要 Gradle，也不用下载 Minecraft。详见 `tools/selfcheck/README.md`。

---

## 附录：DIRECT 模式（自备签名脚本）

> 只有你把配置里的 `source` 改成 `"DIRECT"` 才需要看这一节。
> 默认的浏览器旁观模式什么都不用准备。

`DIRECT` 模式是模组自己连抖音弹幕服务器。好处是不用开 Chrome、省内存；
代价是**抖音要求连接时带一个 `signature` 参数**，而这个签名必须用抖音自己的
混淆 JavaScript（`webmssdk`）算出来——那段 JS 有三万多层递归反调试，
纯 Java 跑不动，只能靠 Node.js 去跑它。

**这段 JS 的版权属于字节跳动，不适合跟着模组一起分发**，所以得你自己准备：

1. **装 Node.js**：去 <https://nodejs.org> 下 LTS 版，装完敲 `node --version`
   能出版本号就行。

2. **拿到签名的 JS 文件**：社区里长期维护这份产物的是
   [`saermart/DouyinLiveWebFetcher`](https://github.com/saermart/DouyinLiveWebFetcher)
   （GitHub 打不开就用 Gitee 镜像 <https://gitee.com/iuact/DouyinLiveWebFetcher>）。
   你要的是里面的 `sign.js`。

3. **写个包装脚本**：在游戏目录下建 `sign` 文件夹，把 `sign.js` 放进去，
   再新建一个 `sign_runner.js`：

   ```javascript
   // 用法：node sign_runner.js <md5值>
   // 作用：加载抖音的 webmssdk，用传入的 md5 换出 X-Bogus 签名
   const path = require('path');
   const fs = require('fs');

   // jsdom 给 webmssdk 补一个浏览器环境——它是给浏览器写的，裸 Node 跑不起来
   const { JSDOM } = require('jsdom');
   const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', {
     url: 'https://live.douyin.com/',
     pretendToBeVisual: true,
   });
   global.window = dom.window;
   global.document = dom.window.document;
   global.navigator = dom.window.navigator;
   global.location = dom.window.location;

   const source = fs.readFileSync(path.join(__dirname, 'sign.js'), 'utf8');
   dom.window.eval(source);

   const md5 = process.argv[2];
   if (!md5) {
     console.error('缺少参数：需要传入 md5 值');
     process.exit(1);
   }

   // 抖音 webmssdk 对外的签名函数。不同版本名字可能不一样，
   // 报 xxx is not a function 时翻一下 sign.js 最后导出的名字改这里。
   const sign = dom.window.getSign ? dom.window.getSign({ 'X-MS-STUB': md5 }) : null;
   const result = sign && sign['X-Bogus'];
   if (!result) {
     console.error('没能算出签名，请检查 sign.js 版本');
     process.exit(1);
   }
   process.stdout.write(result);
   ```

4. **装 jsdom**：在 `sign` 文件夹里执行 `npm install jsdom`。

最终目录结构：

```
.minecraft/
├── mods/
│   └── douyin-danmaku-fabric-1.21.1-1.0.0.jar
└── sign/
    ├── sign.js            ← 从 DouyinLiveWebFetcher 拿的
    ├── sign_runner.js     ← 上面那段
    ├── package.json
    └── node_modules/      ← npm install jsdom 生成的
```

然后把配置里的 `source` 改成 `"DIRECT"`，`/dy reload`，再 `/dy connect`。

---

## 致谢与开源说明

这个项目的实现参考了下面这些开源项目，在此致谢：

| 项目 | 作者 | 许可 | 参考了什么 |
|---|---|---|---|
| [BakaDanmaku](https://github.com/TartaricAcid/BakaDanmaku) | TartaricAcid | 未声明 | **架构思路**：把「直播间→聊天栏」做成可插拔的站点抽象、配置目录与热重载的组织方式 |
| [DanmuFree](https://github.com/SoraYjy/DanmuFree) | SoraYjy | MIT | **抖音协议实现思路**：短号换长号的接口调用方式、签名分两步（MD5 + 外部 JS）、protobuf 三层结构与 ack/心跳的必要性 |
| [DouyinLiveWebFetcher](https://github.com/saermart/DouyinLiveWebFetcher) | saermart | AGPL-3.0 | 签名脚本来源、protobuf 字段号参考 |
| [DyDanmaku](https://github.com/tiangalon/DyDanmaku) | tiangalon | All-Rights-Reserved | 只作为「同类需求已有实现」的调研参考，**没复制任何代码** |
| [Chrome DevTools Protocol](https://chromedevtools.github.io/devtools-protocol/) | Google | — | 默认数据源用的调试接口（公开规范，只读，不注入） |

**几点说明：**

- 本项目的 Java 代码是**独立写的**，没有复制上述任何项目的源码。
  其中「抖音协议怎么用」这类**事实性信息**（接口地址、字段号、
  编解码结构）属于协议知识，参考自 `DouyinLiveWebFetcher` 的
  protobuf 定义和 `DanmuFree` 的协议文档。
  **协议知识本身不受版权保护**，所以本项目可以自由选择许可证。
- **本仓库不包含**抖音的 `webmssdk` / `sign.js`（版权属于字节跳动）。
  只有 `DIRECT` 模式会用到它，而且需要用户自己准备，模组只是调用。
  默认的浏览器旁观模式完全用不到它。

**免责声明**：本项目仅供学习和个人直播自用。请勿用于大规模抓取，
或任何违反抖音用户协议和相关法律法规的用途。
建议只连接自己的直播间。使用风险自负。

---

## 许可证

本项目以 [MIT](LICENSE) 许可证开源，版权归 ASUKA2150 所有。

简单说就是：**随便用**。你可以自由使用、修改、分发、甚至拿去商用或闭源，
唯一的条件是保留版权声明（也就是别把 `LICENSE` 文件删掉）。

> 做这个模组的初衷就是让 MC 小主播能方便地看弹幕。
> 觉得有用的话直接拿走用就行，搬运到别的地方也完全没问题。

## 参与贡献

欢迎提 Issue 和 Pull Request。反馈问题时，**附上 `config/douyindanmaku.log`
会非常有帮助**——它记录了浏览器启动过程、收到的消息类型、以及统计字段清单，
大部分问题看这个文件就能定位。

提交代码前请先跑一遍自检，确认没有破坏现有功能：

```bash
bash tools/selfcheck/run.sh
```
