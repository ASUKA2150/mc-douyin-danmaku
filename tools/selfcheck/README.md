# 开发者自检工具

这个目录里的程序**不属于模组本体**，只是给改代码的人用的一个快速回归测试。

## 它解决什么问题

抖音弹幕是 protobuf 编码的，字段号写错了**不会报错**——只会静默地读出空值，
表现为「弹幕能收到但昵称是空的」这类很难查的问题。

`DecodeCheck` 用**人工合成的一帧**（结构和抖音真实帧一致）跑一遍完整的解码链路：

```
PushFrame(f2=log_id, f8=gzip) → Response(f1=消息, f5=ext, f9=need_ack)
  → Message(f1="WebcastChatMessage", f2=载荷)
    → ChatMessage(f2=User, f3=正文)
      → User(f3=昵称, f23=等级, f24=粉丝团)
```

覆盖的检查点：

- 三层嵌套 + gzip 解压 + 多字段类型（varint / 长度前缀）能否正确解出
- 昵称、正文、抖音等级、粉丝团等级与名称
- `need_ack` / `log_id` / `internal_ext`（不回 ack 会被服务端断开）
- ack 帧与心跳帧的**编码**是否正确（包括抖音真实心跳 `3a026862` 的结构）
- 字段号覆盖机制是否生效（抖音改字段号时的逃生口）
- 畸形/截断数据不会抛异常
- 渲染与过滤：颜色代码注入防护、换行注入防护、去重、黑白名单、限流

## 怎么跑

需要 **JDK 21**。在仓库根目录执行：

```bash
# Linux / macOS / Git Bash
bash tools/selfcheck/run.sh

# Windows PowerShell
pwsh -File tools/selfcheck/run.ps1
```

全部通过会输出 `结果：39 通过，0 失败` 并以退出码 0 结束。

## 为什么不用 JUnit

为了**不引入任何测试依赖**。这个自检只需要 JDK 和一个 `javac`，
不需要跑 Gradle、不需要下载 Minecraft、几秒钟就能出结果——
改完协议相关代码立刻验证，比等一次完整构建快得多。

## `src/com/google/gson/` 是什么

是 **Gson 的桩件**（stub），不是真实现。

核心代码里有两个文件用了 Gson（配置读写、外部 JSON 解析），
而这部分不参与本自检。为了让编译器能通过，这里放几个签名一致的空壳，
把 Gson 从依赖里摘掉——这样自检就能完全脱离 Minecraft 和 Gradle 单独运行。

正式构建用的是 Minecraft 自带的真 Gson，和这些桩件无关。
