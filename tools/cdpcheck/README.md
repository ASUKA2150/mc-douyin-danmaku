# 浏览器旁观模式 · 对照实验

这个目录里的程序**不属于模组本体**，是开发时用来验证浏览器旁观模式的。

## 两个程序，验证两件不同的事

这两件事互相独立，必须分开验——**接线坏了和抖音那边有问题，表现都是「收不到弹幕」**。

### 1. `CdpLocalProbe` —— 验证 CDP 接线（不依赖抖音）

用完全确定的环境检查「模组能不能通过调试接口观察到页面的 WebSocket 数据」。
顺便验证浏览器自动探测、调试端口、标签页管理、命令通道、保活接口。

**它抓过真 bug：** CDP 的监听链路有好几个会静默失败的环节，
最典型的一个是——

> 事件其实已经到达了客户端，但因为连接建立时还没挂上事件监听器，
> 事件被静默丢弃。表现为「什么都收不到」，完全不报错。

这个坑真实发生过：抖音那边明明连上了弹幕通道，模组却一条弹幕都收不到。

### 2. `CdpDouyinProbe` —— 验证真实抖音直播间

先打开抖音直播首页读出一个**正在开播**的房间号（不依赖「手头刚好有个在播的房间」），
再进那个直播间，看能不能拿到弹幕通道、收到多少帧。

## 怎么跑

需要 **JDK 21** 和一个 Chrome/Edge。先构建一次模组（编译出 class 文件）：

```bash
./gradlew :fabric:compileJava
```

然后：

```bash
# Linux / macOS / Git Bash
bash tools/cdpcheck/run.sh              # 接线对照实验（约 10 秒）
bash tools/cdpcheck/run.sh douyin       # 真实抖音测试（约 60 秒）

# Windows PowerShell
pwsh -File tools/cdpcheck/run.ps1
pwsh -File tools/cdpcheck/run.ps1 -Douyin
```

不传浏览器路径时走**模组自己的探测逻辑**（`ChromeFinder`），
所以这个程序也能用来确认「自动探测在你机器上找得对不对」。
也可以直接指定浏览器：

```bash
bash tools/cdpcheck/run.sh --browser "/path/to/msedge.exe"
```

### 期望输出（接线对照实验）

```
[0] 自动探测到的浏览器: C:\Program Files\Google\Chrome\Application\chrome.exe
[1] 启动浏览器… 调试端口 = xxxxx
[4] setEventListener 之后: pageSocket=已挂上(...), browserSocket=已挂上(...)
    >> 监听器收到: Network.webSocketCreated
  *** 成功：CDP 接线正常 ***
```

看到 `Network.webSocketCreated` 就说明接线是好的。
如果这里**一个事件都没有**，问题在模组这边，不要在抖音那边找。

## 实测记录（开发时验证过的）

| 浏览器 | 接线实验 | 真实抖音直播间 |
|---|---|---|
| Chrome 154 | 通过 | 通过 —— 48 帧 / 71KB 载荷 |
| Edge（Chromium） | 通过 | 通过 —— 46 帧 / 68KB 载荷 |

两者都拿到了带页面自算 `signature` 的弹幕通道 URL，
说明**不需要自备签名脚本**这条路是通的。

