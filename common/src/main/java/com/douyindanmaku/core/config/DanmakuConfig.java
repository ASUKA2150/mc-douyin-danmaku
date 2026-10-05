package com.douyindanmaku.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 模组配置。
 *
 * <p>这是一个普通的可变对象（不是 record），因为它有 20 多个字段，
 * 而「命令里临时改一个开关」是很常见的操作——如果用不可变对象，
 * 每次都要把 20 多个字段重新抄一遍，很容易漏参数。
 * 这里用 {@link #copy()} 来解决「不想改到原对象」的场景。
 *
 * <p>线程安全：WebSocket 线程读配置、Minecraft 主线程改配置。
 * 直接共享一个可变对象会有可见性问题，所以约定：
 * <b>跨线程只传 {@link #copy()} 出来的快照</b>。
 */
public final class DanmakuConfig {

    /** 弹幕数据来源。 */
    public enum Source {
        /**
         * 让 Chrome 去访问直播间，我们在旁边偷听它收到的弹幕（推荐）。
         *
         * <p>这是最省事的方案，也是唯一<b>不需要你准备任何东西</b>的方案：
         * 模组会自己启动一个 Chrome 打开你的直播间页面，
         * 然后通过 Chrome 的调试接口「旁观」页面自己收到的弹幕数据。
         *
         * <p>为什么这样做：
         * <ul>
         *   <li>抖音要求连接弹幕服务器时带一个签名参数，而那个签名必须由
         *       抖音自己的混淆 JS 算出来。让真实浏览器去访问，
         *       签名和风控就都由浏览器自己处理了，我们完全不用管。</li>
         *   <li>只旁观、不注入、不修改页面，也不需要登录账号。</li>
         * </ul>
         *
         * <p>注意：Chrome 必须是<b>有窗口</b>模式运行。实测抖音在无头模式下
         * 不会建立弹幕连接，所以启动时会看到一个小窗口，属正常现象。
         */
        CHROME,

        /**
         * 直接连接抖音弹幕服务器。
         *
         * <p>这个方案最省资源（不用开 Chrome），但需要你自己准备一段签名脚本
         * （因为那段 JS 的版权属于字节跳动，不适合随模组一起分发）。
         * 准备方法见 README「准备签名脚本」一节。
         */
        DIRECT,

        /**
         * 由外部程序把弹幕转发过来（兼容 TartaricAcid/BakaDanmaku 的 {@code tcp} 模式）。
         *
         * <p>模组在本机开一个监听端口，任何程序连上来按行发 JSON 即可显示。
         */
        TCP
    }

    /** 弹幕过滤模式。 */
    public enum FilterMode {
        /** 不过滤。 */
        DISABLED,
        /** 黑名单：命中任一关键词就丢弃。 */
        BLACKLIST,
        /** 白名单：只有命中关键词才显示。 */
        WHITELIST
    }

    // ------------------------------------------------------------------
    //  基本设置
    // ------------------------------------------------------------------

    /** 游戏启动后是否自动连接。 */
    public boolean autoConnect = false;

    /**
     * 直播间标识。可以是纯数字短号（如 {@code 123456789}），
     * 也可以是完整分享链接（如 {@code https://live.douyin.com/123456789}），
     * 连接时自动解析。
     */
    public String room = "";

    /** 数据来源。 */
    public Source source = Source.CHROME;

    // ------------------------------------------------------------------
    //  Chrome 模式（CHROME）需要的设置
    // ------------------------------------------------------------------

    /**
     * Chrome 可执行文件路径。留空表示自动探测常见安装位置
     * （Program Files、Program Files (x86)、用户目录下的 Local AppData）。
     */
    public String chromePath = "";

    /**
     * Chrome 的独立配置目录。留空表示用系统临时目录下的一个固定目录。
     *
     * <p>故意用一个独立目录而不是你平时用的 Chrome 配置：
     * <ul>
     *   <li>Chrome 同一个配置目录不允许被两个进程同时使用，
     *       复用会导致新启动的那个直接退出</li>
     *   <li>独立目录意味着不读取你的登录状态和浏览记录，更干净</li>
     * </ul>
     * 抖音弹幕是匿名可看的，不需要登录。
     */
    public String chromeProfileDir = "";

    /**
     * 是否把 Chrome 窗口挪到屏幕外。
     *
     * <p>实测抖音在无头模式下不会建立弹幕连接，所以 Chrome 必须是有窗口的。
     * 开启此项会把窗口移到可视区域之外，眼不见为净。
     */
    public boolean chromeOffscreen = true;

    /**
     * Chrome 窗口尺寸。窗口太小可能影响页面加载，一般不用改。
     */
    public String chromeWindowSize = "1280,800";

    // ------------------------------------------------------------------
    //  直连模式（DIRECT）需要的路径
    // ------------------------------------------------------------------

    /**
     * 签名脚本路径。相对路径按「游戏目录」解析。
     *
     * <p>模组会执行 {@code node <脚本> <md5>}，脚本把算好的签名打到标准输出。
     */
    public String signScriptPath = "sign/sign_runner.js";

    /** Node.js 可执行文件路径。留空表示用 PATH 里的 {@code node}。 */
    public String nodePath = "";

    // ------------------------------------------------------------------
    //  抖音协议字段号覆盖
    //  抖音偶尔会调整 protobuf 字段号，导致某些字段读不出来（而且不会报错，
    //  只是显示为空）。留出这几个开关，出问题时不用重新编译模组。
    //  -1 表示「用内置默认值」，0 是合法的字段号所以不能当哨兵。
    // ------------------------------------------------------------------

    /** 用户（抖音）等级字段号覆盖。-1 = 用默认值。 */
    public int userLevelOverride = -1;

    /** 用户等级图标 URL 字段号覆盖（等级数字有时只能从图标 URL 里抠出来）。-1 = 用默认值。 */
    public int userLevelIconOverride = -1;

    /** 粉丝团等级字段号覆盖。-1 = 用默认值。 */
    public int fanClubLevelOverride = -1;

    /** 粉丝团（User 里的那个子消息）字段号覆盖。-1 = 用默认值。 */
    public int fansClubOverride = -1;

    /** 昵称字段号覆盖。-1 = 用默认值。 */
    public int nickNameOverride = -1;

    /** 弹幕正文字段号覆盖。-1 = 用默认值。 */
    public int chatContentOverride = -1;

    // ------------------------------------------------------------------
    //  显示设置
    // ------------------------------------------------------------------

    /**
     * 是否让弹幕必定出现在聊天栏。
     *
     * <p>Minecraft 原版会把「聊天栏里已经有 100 条消息时」的旧消息挤掉；
     * 更重要的是某些界面下聊天栏不显示。开启此项后弹幕除了正常显示，
     * 还会被强制保留在可见区域。关掉它更接近原版行为。
     */
    public boolean ensureChat = true;

    /** 是否把弹幕写进聊天记录（按 T 打开的那个界面）。 */
    public boolean keepHistory = true;

    /** 是否在弹幕前显示抖音等级。 */
    public boolean showUserLevel = true;

    /** 是否在弹幕前显示粉丝团信息。 */
    public boolean showFanClub = true;

    /**
     * 弹幕输出模板。
     *
     * <p>设计考虑：
     * <ul>
     *   <li>默认<b>只显示粉丝团等级，不显示消费等级</b>；没进粉丝团的人
     *       前面什么都不加（靠条件块 {@code %?...:...%} 实现，不会留下空括号）。</li>
     *   <li>昵称用青色（{@code &b}）而不是白色：昵称和正文如果都是白的，
     *       扫一眼分不清谁在说话。青色和「[抖音弹幕]」提示同色，一眼能定位。</li>
     *   <li>昵称后面紧跟着 {@code &7}（灰色冒号）再 {@code &f}（白色正文）——
     *       这两个不能省，否则昵称的颜色会漏到正文上，整行一个颜色。</li>
     * </ul>
     *
     * <p>想看消费等级就把 {@code %?level.isSet:...%} 那段加回去，
     * 各种写法见 README。
     */
    public String chatFormat =
            "&7[&f抖音&7] %?fanclub.isMember:[&d粉丝团%fanclub.level%&7] %&b%nick%&7: &f%content%";

    /** 模组自己的提示信息模板（连接成功、断开连接等）。 */
    public String systemFormat = "&8[&b抖音弹幕&8] &7%content%";

    // ------------------------------------------------------------------
    //  各种消息类型的开关与模板
    //  弹幕之外的三种消息（礼物 / 进房 / 点赞）都单独给模板，
    //  因为它们的字段和弹幕差很多，共用一套模板会很难写。
    // ------------------------------------------------------------------

    /**
     * 是否显示礼物消息。
     *
     * <p>礼物是主播最有用的信息之一（能立刻看到谁在支持你），所以默认开。
     */
    public boolean showGift = true;

    /**
     * 是否显示进房消息。
     *
     * <p>默认<b>关</b>：人气高的直播间进房会刷屏，把弹幕淹掉。
     * 想开就改成 true。
     */
    public boolean showMember = false;

    /**
     * 是否显示点赞消息。
     *
     * <p>默认<b>关</b>：点赞是高频事件，逐条显示会刷屏。
     * 打开后也不是每条都显示，而是<b>按人攒一波、停顿后报一次累计</b>，
     * 同一个人 30 秒内最多播报一次。
     */
    public boolean showLike = false;

    /**
     * 礼物消息模板。
     *
     * <p><b>两个容易踩的坑</b>（都写在这里免得以后又踩）：
     * <ol>
     *   <li>数量前面的那个「x」必须写成 {@code x&f}，不能写成 {@code &7x&f}——
     *       因为 {@code x} 是 Minecraft 的合法格式代码（{@code &x} = 随机字符），
     *       写成 {@code &7x} 会被当成颜色代码吃掉，数量前面的 x 就没了。</li>
     *   <li>{@code %gift.combo%} 后面要跟 {@code &r} 复位，
     *       否则后面的内容会继承它的颜色。</li>
     * </ol>
     */
    public String giftFormat =
            "&7[&f抖音&7] %?fanclub.isMember:[&d粉丝团%fanclub.level%&7] %&b%nick%"
                    + "&7 送出了 &e%gift.name% &7x&f%gift.count%%gift.combo%";

    /**
     * 点赞消息模板。
     *
     * <h2>为什么没有「累计获赞」占位符</h2>
     * 抖音的弹幕通道里<b>没有累计获赞这个数据</b>。实测把所有统计字段 dump
     * 出来对过：只有「当前在线人数」和一个累计量（{@code f7/f8/f11}，
     * 63133 / "6万+" / "6.3万"），而那个累计量和直播间面板上的获赞数
     * <b>对不上</b>（实测面板 4 万，它是 6.3 万）。所以它不是获赞。
     *
     * <p>曾经把它当成获赞显示，结果就是一个稳定错误的数字。
     * 与其显示一个错的，不如不显示——所以这个模板里没有累计获赞。
     *
     * <h2>配色</h2>
     * 点赞用<b>红色到品红</b>这一段（{@code &d} 品红昵称 + {@code &c} 红色动作），
     * 和抖音里点赞是红心的观感一致，也和聊天弹幕的青色区分开，
     * 一眼就能看出「这条是点赞，不是弹幕」。
     *
     * <h2>可用占位符</h2>
     * <ul>
     *   <li>{@code %nick%} —— 是谁点的</li>
     *   <li>{@code %like.count%} —— 这一波点了几个（默认不显示，想要可以加回模板）</li>
     *   <li>{@code %like.session%} —— 本场模组收到的总量（不可靠，断线会归零）</li>
     *   <li>{@code %room.online%} —— 直播间当前在线人数（可靠）</li>
     * </ul>
     *
     * <p>播报时机：按人攒一波，停下来 5 秒后报一次；
     * 同一个人 30 秒内最多播报一次（有人一直点也不会刷屏）。
     */
    public String likeFormat = "&7[&f抖音&7] &d%nick%&c 为主播点赞";

    /**
     * 拿不到昵称时用的点赞模板。
     *
     * <p><b>现在基本用不上了</b>：拿不到昵称的点赞只计入总数、不单独播报
     * （否则会和按人播报重复，冒出两个互相矛盾的数字）。
     * 保留这个配置项只是为了兼容旧配置文件，填了也不会生效。
     */
    public String likeAnonymousFormat = "";

    /** 进房消息模板。 */
    public String memberFormat =
            "&7[&f抖音&7] %?fanclub.isMember:[&d粉丝团%fanclub.level%&7] %&b%nick%&7 进入了直播间";

    // ------------------------------------------------------------------
    //  过滤设置
    // ------------------------------------------------------------------

    /** 过滤模式。 */
    public FilterMode filterMode = FilterMode.DISABLED;

    /** 过滤关键词，匹配时忽略大小写。 */
    public List<String> filterKeywords = new ArrayList<>();

    /** 屏蔽的用户昵称，匹配时忽略大小写、精确匹配。 */
    public List<String> blockedUsers = new ArrayList<>();

    /**
     * 每秒最多显示多少条。
     *
     * <p>弹幕洪水（比如抽奖、热门直播间）时，逐条渲染会明显掉帧。
     * 设为 0 表示不限制。建议热门直播间设 10~20。
     */
    public int maxDanmakuPerSecond = 0;

    /**
     * 是否去掉昵称和正文里的 emoji。
     *
     * <p>Minecraft 的字体不含彩色 emoji，遇到会显示成方框，
     * 所以默认去掉。想看原样就关掉。
     */
    public boolean stripEmoji = true;

    // ------------------------------------------------------------------
    //  构造与工具方法
    // ------------------------------------------------------------------

    /** 默认配置。 */
    public static DanmakuConfig defaults() {
        return new DanmakuConfig();
    }

    /**
     * 旧版本的默认模板，用来判断「用户是不是从没改过模板」。
     *
     * <p>这个值是 1.0.0 早期版本的默认值——那时还同时显示消费等级，
     * 而且昵称是白色。两种旧默认值在这里都列出来，
     * 只要配置里的模板命中其中之一，就说明用户没自定义过，可以放心升级。
     */
    private static final String[] OLD_DEFAULT_CHAT_FORMATS = {
            // 最早：粉丝团 + 消费等级都显示，昵称白色
            "&7[&f抖音&7] %fanclub%%level%&f%nick%&7: &f%content%",
            // 中间版本：只显示粉丝团，但昵称还是白色
            "&7[&f抖音&7] %?fanclub.isMember:[&d粉丝团%fanclub.level%&7] %&f%nick%&7: &f%content%",
    };

    /**
     * 旧版本的默认点赞模板。
     *
     * <p>列出来的原因和聊天模板一样：已经生成过配置文件的用户，
     * 文件里存的是旧默认值，不删配置就永远看不到新效果。
     *
     * <p>这里列出的两个版本都带有「本场累计」——那个数字来自
     * {@code %like.total%}，而它显示的其实不是获赞（详见 likeFormat 的说明），
     * 所以整个去掉了。
     */
    private static final String[] OLD_DEFAULT_LIKE_FORMATS = {
            // 最早：显示「本场累计」，颜色是青色
            "&7[&f抖音&7] &b%nick%&7 为主播点赞，本场累计 &f%like.total%&7 赞",
            // 中间版本：去掉了累计，但还有个「+N」
            "&7[&f抖音&7] &b%nick%&7 为主播点赞 &f+%like.count%",
    };

    /**
     * 把「还是旧默认模板」的配置自动升到新默认模板。
     *
     * <p>为什么要这么做：新版本改了默认模板，但已经生成过配置文件的用户，
     * 文件里存的是旧默认值，他们不删配置就永远看不到新效果，
     * 而且会以为功能没生效。
     *
     * <p>安全性：只在字段<b>完全等于</b>某个旧默认值时才替换。
     * 用户只要动过一个字符，就说明他有自己的偏好，此时绝不覆盖。
     */
    private void upgradeOldDefaultTemplate() {
        chatFormat = upgradeChatFormat(chatFormat);
        likeFormat = upgradeLikeFormat(likeFormat);
    }

    /**
     * 升级逻辑的纯函数版本（不依赖任何外部状态，方便单独测试）。
     *
     * @param configured 配置文件里读到的模板
     * @return 该用的模板
     */
    public static String upgradeChatFormat(String configured) {
        for (String oldDefault : OLD_DEFAULT_CHAT_FORMATS) {
            if (oldDefault.equals(configured)) {
                return defaults().chatFormat;
            }
        }
        return configured;
    }

    /**
     * 点赞模板的自动升级。
     *
     * <p>和 {@link #upgradeChatFormat} 同理：只在完全命中旧默认值时才替换。
     *
     * @param configured 配置文件里读到的模板
     * @return 该用的模板
     */
    public static String upgradeLikeFormat(String configured) {
        for (String oldDefault : OLD_DEFAULT_LIKE_FORMATS) {
            if (oldDefault.equals(configured)) {
                return defaults().likeFormat;
            }
        }
        return configured;
    }

    /** 深拷贝。跨线程传递、或改配置前想留个底都用它。 */
    public DanmakuConfig copy() {
        DanmakuConfig copy = new DanmakuConfig();
        copy.autoConnect = autoConnect;
        copy.room = room;
        copy.source = source;
        copy.chromePath = chromePath;
        copy.chromeProfileDir = chromeProfileDir;
        copy.chromeOffscreen = chromeOffscreen;
        copy.chromeWindowSize = chromeWindowSize;
        copy.signScriptPath = signScriptPath;
        copy.nodePath = nodePath;
        copy.userLevelOverride = userLevelOverride;
        copy.userLevelIconOverride = userLevelIconOverride;
        copy.fanClubLevelOverride = fanClubLevelOverride;
        copy.fansClubOverride = fansClubOverride;
        copy.nickNameOverride = nickNameOverride;
        copy.chatContentOverride = chatContentOverride;
        copy.ensureChat = ensureChat;
        copy.keepHistory = keepHistory;
        copy.showUserLevel = showUserLevel;
        copy.showFanClub = showFanClub;
        copy.chatFormat = chatFormat;
        copy.systemFormat = systemFormat;
        copy.showGift = showGift;
        copy.showMember = showMember;
        copy.showLike = showLike;
        copy.giftFormat = giftFormat;
        copy.likeFormat = likeFormat;
        copy.likeAnonymousFormat = likeAnonymousFormat;
        copy.memberFormat = memberFormat;
        copy.filterMode = filterMode;
        copy.filterKeywords = new ArrayList<>(filterKeywords);
        copy.blockedUsers = new ArrayList<>(blockedUsers);
        copy.maxDanmakuPerSecond = maxDanmakuPerSecond;
        copy.stripEmoji = stripEmoji;
        return copy;
    }

    /**
     * 把 {@code null} 和明显非法的值修好。
     *
     * <p>配置文件是给用户手改的，缺项、写成 null、模板留空都很常见，
     * 这里统一兜住，避免启动就崩。
     */
    public DanmakuConfig normalized() {
        DanmakuConfig fallback = defaults();
        if (room == null) {
            room = "";
        }
        if (source == null) {
            source = fallback.source;
        }
        if (signScriptPath == null || signScriptPath.isBlank()) {
            signScriptPath = fallback.signScriptPath;
        }
        if (nodePath == null) {
            nodePath = "";
        }
        if (chromePath == null) {
            chromePath = "";
        }
        if (chromeProfileDir == null) {
            chromeProfileDir = "";
        }
        if (chromeWindowSize == null || !chromeWindowSize.contains(",")) {
            chromeWindowSize = fallback.chromeWindowSize;
        }
        if (chatFormat == null || chatFormat.isBlank()) {
            chatFormat = fallback.chatFormat;
        } else {
            upgradeOldDefaultTemplate();
        }
        if (systemFormat == null || systemFormat.isBlank()) {
            systemFormat = fallback.systemFormat;
        }
        if (giftFormat == null || giftFormat.isBlank()) {
            giftFormat = fallback.giftFormat;
        }
        if (likeFormat == null || likeFormat.isBlank()) {
            likeFormat = fallback.likeFormat;
        }
        if (memberFormat == null || memberFormat.isBlank()) {
            memberFormat = fallback.memberFormat;
        }
        // likeAnonymousFormat 允许是空字符串（表示不显示匿名点赞），所以这里不补默认值
        if (likeAnonymousFormat == null) {
            likeAnonymousFormat = "";
        }
        if (filterMode == null) {
            filterMode = fallback.filterMode;
        }
        if (filterKeywords == null) {
            filterKeywords = new ArrayList<>();
        }
        if (blockedUsers == null) {
            blockedUsers = new ArrayList<>();
        }
        if (maxDanmakuPerSecond < 0) {
            maxDanmakuPerSecond = 0;
        }
        // 字段号覆盖：写成正数之外的任何值都当成「没配置」
        if (userLevelOverride < 0) {
            userLevelOverride = -1;
        }
        if (userLevelIconOverride < 0) {
            userLevelIconOverride = -1;
        }
        if (fanClubLevelOverride < 0) {
            fanClubLevelOverride = -1;
        }
        if (fansClubOverride < 0) {
            fansClubOverride = -1;
        }
        if (nickNameOverride < 0) {
            nickNameOverride = -1;
        }
        if (chatContentOverride < 0) {
            chatContentOverride = -1;
        }
        return this;
    }

    // ------------------------------------------------------------------
    //  JSON 读写
    // ------------------------------------------------------------------

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** 解析 JSON。任何异常都回退到默认配置——配置文件写坏了不该让游戏起不来。 */
    public static DanmakuConfig fromJson(String json) {
        if (json == null || json.isBlank()) {
            return defaults();
        }
        try {
            DanmakuConfig parsed = GSON.fromJson(json, DanmakuConfig.class);
            return parsed == null ? defaults() : parsed.normalized();
        } catch (RuntimeException malformed) {
            return defaults();
        }
    }

    /** 序列化成带缩进的 JSON，方便用户手改。 */
    public String toJson() {
        return GSON.toJson(normalized());
    }
}
