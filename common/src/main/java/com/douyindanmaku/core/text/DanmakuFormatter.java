package com.douyindanmaku.core.text;

import com.douyindanmaku.core.config.DanmakuConfig;
import com.douyindanmaku.core.model.DanmakuEvent;
import com.douyindanmaku.core.model.DanmakuMessage;

/**
 * 把一条弹幕渲染成要在聊天栏里显示的文本。
 *
 * <h2>模板语法</h2>
 * <pre>
 *   %nick%      发送者昵称
 *   %content%   弹幕正文
 *   %level%     抖音消费等级，形如 {@code [Lv.12] }（没等级则为空）
 *   %fanclub%   粉丝团标记，形如 {@code [粉丝团 5] }（没粉丝团则为空）
 *   %room%      直播间号（渲染提示信息时用）
 * </pre>
 *
 * <h2>条件块（用来避免空括号）</h2>
 * 模板里可以直接写「有才显示」的段落，语法是 {@code %?条件:内容%}。
 * 条件不成立时，连同段落里的颜色代码一起消失。
 *
 * <pre>
 *   %level.isSet:[&eLv.%level.value%&7] %                只有取到消费等级才显示
 *   %fanclub.isMember:[&d粉丝团%fanclub.level%&7] %      是粉丝团成员才显示
 *   %fanclub.isNew:[&d新粉&7] %                          刚加入粉丝团才显示
 * </pre>
 *
 * <p>可用的条件：
 * <table border="1">
 *   <caption>条件列表</caption>
 *   <tr><th>条件</th><th>成立的含义</th></tr>
 *   <tr><td>{@code level.isSet}</td><td>取到了抖音消费等级</td></tr>
 *   <tr><td>{@code fanclub.isMember}</td><td>是粉丝团成员</td></tr>
 *   <tr><td>{@code fanclub.isSet}</td><td>同上（别名）</td></tr>
 *   <tr><td>{@code fanclub.isNew}</td><td>粉丝团等级为 1（刚加入）</td></tr>
 * </table>
 * 内容里可以用 {@code %level.value%}、{@code %fanclub.level%}、
 * {@code %fanclub.name%} 取具体数值。
 *
 * <p>为什么需要这个：如果只写 {@code [%fanclub.level%]}，
 * 遇到不是粉丝团的观众就会在聊天栏留下一对空方括号 {@code []}，很难看。
 *
 * <h2>颜色</h2>
 * 模板里用 {@code &} 加颜色字母表示颜色，例如 {@code &b} 是青色、{@code &7} 是灰色。
 * 会被自动转成 Minecraft 内部的 {@code §} 符号——直接用 {@code §} 写也行。
 *
 * <h2>安全性（这一段很重要）</h2>
 * 弹幕正文和昵称是<b>陌生人可控的输入</b>，如果不处理会有两种问题：
 * <ul>
 *   <li><b>颜色代码注入</b>：有人在弹幕里打 {@code §c} 就能把后面的文字染色，
 *       甚至伪造出「系统提示」的样子。所以要先把用户输入里的 {@code §} 和 {@code &}
 *       全都去掉，再拼进模板。</li>
 *   <li><b>换行注入</b>：有人在弹幕里塞换行符，一行弹幕会变成好几行，
 *       刷屏效果翻倍。所以换行也要去掉。</li>
 * </ul>
 * 另外替换时用了 {@link java.util.regex.Matcher#quoteReplacement}：
 * 昵称里如果有 {@code $} 或 {@code \}，不转义的话会被当成正则替换的组引用，
 * 轻则显示错乱、重则抛异常。
 */
public final class DanmakuFormatter {

    /**
     * 条件块的起始标记：{@code %?条件:}。
     *
     * <p>只用来定位开头；结束位置由 {@link #resolveConditionals} 手工扫描，
     * 不能用正则——原因见那个方法的注释。
     */
    private static final java.util.regex.Pattern CONDITIONAL_START =
            java.util.regex.Pattern.compile("%\\?([A-Za-z][A-Za-z0-9.]*):");

    /**
     * 扫描条件块前用来顶替占位符的哨兵字符。
     *
     * <p>选一个用户不可能打出来、也不会出现在颜色代码里的字符。
     * U+0001（SOH 控制符）正合适——{@link #clean} 会把用户输入里的
     * 控制字符全删掉，所以外面来的数据不会混进这个字符。
     */
    private static final char PLACEHOLDER_SHIELD = '\u0001';

    /**
     * 模板里所有合法的占位符名字。
     *
     * <p>加新占位符时这里<b>必须</b>同步加一行，否则条件块扫描会把它
     * 当成杂散的 {@code %} 处理。这是这套设计的代价——换来的是
     * 条件块断句 100% 确定。
     */
    private static final java.util.List<String> PLACEHOLDER_NAMES = java.util.List.of(
            "nick", "content", "room", "kind",
            "level", "level.value",
            "fanclub", "fanclub.level", "fanclub.name",
            "gift.name", "gift.count", "gift.combo", "gift.diamond",
            "like.count", "like.total", "like.session",
            "room.online", "room.watched",
            "member.count");

    /** emoji 的码点范围。Minecraft 字体画不出彩色 emoji，会变成方框。 */
    private static final int[][] EMOJI_RANGES = {
            {0x1F300, 0x1FAFF},   // 各种表情、手势、食物等
            {0x1F000, 0x1F2FF},   // 麻将、扑克、信封等
            {0x2600, 0x27BF},     // 杂项符号、装饰符号
            {0x2B00, 0x2BFF},     // 箭头、几何图形
            {0xFE00, 0xFE0F},     // 变体选择符（跟在 emoji 后面）
            {0x1F1E6, 0x1F1FF},   // 区域指示符（国旗）
            {0x200D, 0x200D},     // 零宽连接符
            {0x2190, 0x21FF},     // 箭头
            {0x2700, 0x27BF},     // 装饰符号补充
            {0x1F900, 0x1F9FF},   // 补充表情
    };

    private DanmakuFormatter() {
    }

    /**
     * 格式化一个任意类型的事件（弹幕 / 礼物 / 点赞 / 进房）。
     *
     * <p>四种类型走的是<b>同一条路径</b>：选模板 → 处理条件块 → 填占位符 → 上色。
     * 差别只有「用哪个模板」和「能填哪些占位符」，
     * 所以以后加新消息类型时只要在 {@link #templateFor} 里加一行。
     *
     * <p>顺序很讲究，这里踩过坑：
     * <ol>
     *   <li><b>先清理用户输入再替换</b>——反过来的话，清理会把我们自己加进去的
     *       颜色代码一起吃掉，整行变白。</li>
     *   <li><b>先上色再处理条件块</b>——这样条件不成立时，
     *       那段里的颜色代码跟着一起消失，不会留下裸的 {@code §d}。</li>
     *   <li><b>条件块处理完才填值</b>——反过来的话，填进去的内容里可能带
     *       {@code %} 或其它字符，会把条件块的语法撑坏。</li>
     * </ol>
     *
     * @return 可以直接丢给聊天栏的文本；这个类型被用户关掉时返回 {@code null}
     */
    public static String formatEvent(DanmakuEvent event, DanmakuConfig config) {
        String template = templateFor(event, config);
        if (template == null) {
            return null;
        }

        // 第 1 步：先处理条件块，决定每一段留不留。
        // 放在最前面做，是因为这一步要扫描模板的语法结构，
        // 掺进用户输入或颜色代码都会干扰判断。
        String afterConditionals = resolveConditionals(template, event);

        // 第 2 步：清理用户输入并填进去（防颜色代码注入、换行注入）
        String filled = afterConditionals
                .replace("%nick%", clean(event.nickname(), config.stripEmoji))
                .replace("%content%", clean(event.content(), config.stripEmoji));

        // 第 3 步：填非用户来源的占位符（礼物名、等级、累计赞数等）
        String withValues = fillEventValues(filled, event);

        // 第 4 步：最后才把 & 转成 §。
        // 放最后是有意的：如果提前转，条件块扫描时看到的引号符就变了；
        // 而且转换本身可能引入 x（&x 是「随机字符」格式代码），
        // 得让占位符都填完之后再动。
        String colored = colorize(withValues);

        // 第 5 步：兜底清掉没被替换的占位符。
        //
        // 为什么需要：万一某个占位符忘了填（开发时真的踩过——
        // %like.count% 漏填，聊天栏里直接显示 "%like.count%"），
        // 用户看到的就是一行模板语法。宁可显示成空白，也不要漏出来。
        return stripLeftoverPlaceholders(colored);
    }

    /**
     * 删掉没被替换掉的 {@code %占位符%}。
     *
     * <p>只删「看起来像占位符」的（{@code %} + 字母数字点 + {@code %}），
     * 不会误伤用户正文里的普通百分号。
     */
    private static String stripLeftoverPlaceholders(String text) {
        if (text.indexOf('%') < 0) {
            return text;
        }
        return LEFTOVER_PLACEHOLDER.matcher(text).replaceAll("");
    }

    /** 匹配没被替换掉的占位符。 */
    private static final java.util.regex.Pattern LEFTOVER_PLACEHOLDER =
            java.util.regex.Pattern.compile("%[A-Za-z][A-Za-z0-9.]*%|%\\?[A-Za-z][A-Za-z0-9.]*:");

    /**
     * 这个事件该用哪个模板。
     *
     * @return 模板字符串；用户把这个类型关掉了、或者模板被清空了，都返回 {@code null}
     */
    private static String templateFor(DanmakuEvent event, DanmakuConfig config) {
        String template = switch (event.kind()) {
            case CHAT -> config.chatFormat;
            case GIFT -> config.giftFormat;
            case MEMBER -> config.memberFormat;
            case LIKE -> event.nickname() == null || event.nickname().isBlank()
                    // 拿不到昵称的点赞用另一个模板。它默认是空字符串，
                    // 也就是「不显示」——「不知道是谁」的点赞播报价值不大。
                    ? config.likeAnonymousFormat
                    : config.likeFormat;
        };

        // 开关关掉、或者模板被清空，都表示这条不该显示。
        // 注意要判 isBlank：空模板如果不拦，会渲染出一条空消息塞进聊天栏。
        if (template == null || template.isBlank()) {
            return null;
        }
        boolean enabled = switch (event.kind()) {
            case CHAT -> true;
            case GIFT -> config.showGift;
            case MEMBER -> config.showMember;
            case LIKE -> config.showLike;
        };
        return enabled ? template : null;
    }

    /**
     * 填非用户输入的占位符。
     *
     * <p>这些值来自协议字段（礼物名、数量、累计赞数）。礼物名是抖音配的、
     * 理论上安全，但仍然过一遍 {@link #clean} —— 多一道防御不亏。
     */
    private static String fillEventValues(String text, DanmakuEvent event) {
        String result = text;

        if (event.hasUserLevel()) {
            result = result.replace("%level.value%", String.valueOf(event.userLevel()));
        }
        if (event.isFanClubMember()) {
            result = result.replace("%fanclub.level%", String.valueOf(event.fanClubLevel()));
            result = result.replace("%fanclub.name%",
                    clean(event.fanClubName() == null ? "" : event.fanClubName(), false));
        }

        DanmakuEvent.Gift gift = event.gift();
        if (gift != null) {
            result = result
                    .replace("%gift.name%", clean(gift.name() == null ? "" : gift.name(), false))
                    .replace("%gift.count%", String.valueOf(Math.max(1, gift.repeatCount())))
                    // 连击数自带颜色代码。注意「x」前面必须跟颜色代码——
                    // x 本身是 Minecraft 的「随机字符」格式代码，裸写会被吃掉。
                    .replace("%gift.combo%", gift.comboCount() > 1
                            ? " &7x&f" + gift.comboCount() : "")
                    .replace("%gift.diamond%", String.valueOf(gift.diamondCount()));
        }

        DanmakuEvent.Like like = event.like();
        if (like != null) {
            // 只有「这一波点了几个」和「本场模组收到多少」。
            // 没有「抖音累计获赞」——弹幕通道里没有这个数据，
            // 详见 DanmakuConfig.likeFormat 的说明。
            result = result
                    .replace("%like.count%", LikeCounter.formatCount(like.increment()))
                    .replace("%like.session%", LikeCounter.formatCount(like.total()))
                    // 兼容旧模板：以前 %like.total% 指向一个其实是别的东西的数字，
                    // 现在退回本场累加值，免得旧配置渲染出空白。
                    .replace("%like.total%", LikeCounter.formatCount(like.total()));

            // 直播间的真实数据（这两个是可靠的）
            if (like.online() > 0) {
                result = result.replace("%room.online%", NumberText.format(like.online()));
            }
            if (like.watched() != null && !like.watched().isBlank()) {
                result = result.replace("%room.watched%", clean(like.watched(), false));
            }
        }

        DanmakuEvent.Member member = event.member();
        if (member != null && member.memberCount() > 0) {
            result = result.replace("%member.count%", String.valueOf(member.memberCount()));
        }

        return result;
    }

    /**
     * 渲染一条弹幕。
     *
     * <p>这是给「只想渲染弹幕」的调用方用的便捷入口，内部转成事件统一处理。
     *
     * @param danmaku 弹幕
     * @param config  配置（决定显示哪些前缀、用哪个模板）
     * @return 可以直接丢给聊天栏的字符串（已含 {@code §} 颜色代码）
     */
    public static String formatDanmaku(DanmakuMessage danmaku, DanmakuConfig config) {
        String rendered = formatEvent(DanmakuEvent.chat(danmaku), config);
        return rendered == null ? "" : rendered;
    }

    /**
     * 处理条件块，条件成立就留下内容、不成立就整段删掉。
     *
     * <h2>为什么不用正则，而要手工扫描</h2>
     * 条件块的结束 {@code %} 和占位符的 {@code %} 是同一个字符，
     * 靠正则没法可靠断句——实测四种写法都会在某类模板上出错：
     * <ul>
     *   <li>非贪婪 + 「后面不是 {@code %}」：会在 {@code %fanclub.level%}
     *       的中间截断，把内容切坏</li>
     *   <li>贪婪 + 「后面不是 {@code %}」：一个模板里写两个条件块时，
     *       第一个会把第二个整个吞掉</li>
     * </ul>
     *
     * <h2>怎么断句</h2>
     * 扫描之前先把所有 {@code %占位符%} 换成一个哨兵字符，
     * 于是「哪里是条件块的结束」就变成一个确定的问题了——
     * 剩下的 {@code %} 一定是条件块的边界。处理完再把哨兵换回去。
     *
     * <p>这么做还有个好处：找不到合法结束符时不会把模板切坏，
     * 原样保留（宁可显示得难看，也不要静默吃掉用户写的东西）。
     */
    private static String resolveConditionals(String text, DanmakuEvent event) {
        // 先给每个占位符分配一个唯一编号，替换成「哨兵+编号+哨兵」。
        // 用编号而不是统一的哨兵，是为了还原时能把占位符<b>原名</b>还回去——
        // 只还一个 % 的话后面的填值步骤就找不到它了。
        java.util.Map<String, String> shieldToPlaceholder = new java.util.LinkedHashMap<>();
        String shielded = shieldPlaceholders(text, shieldToPlaceholder);

        java.util.regex.Matcher matcher = CONDITIONAL_START.matcher(shielded);
        StringBuilder result = new StringBuilder(shielded.length());
        int cursor = 0;
        boolean changed = false;

        while (matcher.find(cursor)) {
            String condition = matcher.group(1);
            int bodyStart = matcher.end();

            int bodyEnd = findConditionalEnd(shielded, bodyStart);
            if (bodyEnd < 0) {
                // 找不到结束符，说明模板写坏了。原样保留，不要静默吞掉。
                break;
            }

            // 把标记之前的原文照抄过来
            result.append(shielded, cursor, matcher.start());
            // 条件成立才把内容放进去
            if (isConditionMet(condition, event)) {
                result.append(shielded, bodyStart, bodyEnd);
            }
            cursor = bodyEnd + 1;
            changed = true;
            matcher.region(cursor, shielded.length());
        }

        if (changed) {
            result.append(shielded, cursor, shielded.length());
        }
        return restorePlaceholders(changed ? result.toString() : shielded, shieldToPlaceholder);
    }

    /**
     * 把 {@code %占位符%} 换成「哨兵 + 编号 + 哨兵」，让条件块扫描时
     * 不会看到多余的 {@code %}。
     *
     * <p>按名字长度<b>倒序</b>替换，避免短名字先匹配掉长名字的一部分
     * （比如先用 {@code %like.count%} 而不是 {@code %like%}）。
     *
     * @param shieldToPlaceholder 输出参数：编号映射回原始占位符文本
     */
    private static String shieldPlaceholders(String text, java.util.Map<String, String> shieldToPlaceholder) {
        String result = text;
        java.util.List<String> names = new java.util.ArrayList<>(PLACEHOLDER_NAMES);
        names.sort((left, right) -> Integer.compare(right.length(), left.length()));

        int index = 0;
        for (String name : names) {
            String placeholder = "%" + name + "%";
            if (!result.contains(placeholder)) {
                continue;
            }
            String token = PLACEHOLDER_SHIELD + Integer.toString(index++, Character.MAX_RADIX)
                    + PLACEHOLDER_SHIELD;
            shieldToPlaceholder.put(token, placeholder);
            result = result.replace(placeholder, token);
        }
        return result;
    }

    /** 把哨兵换回占位符原文。 */
    private static String restorePlaceholders(String text, java.util.Map<String, String> shieldToPlaceholder) {
        String result = text;
        for (java.util.Map.Entry<String, String> entry : shieldToPlaceholder.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    /**
     * 从 {@code start} 开始找一个条件块的结束符。
     *
     * <p>因为调用前已经把占位符换成了哨兵，这里看到的 {@code %}
     * 一定就是条件块的边界，直接找第一个即可。
     *
     * @return 结束符的下标；找不到返回 {@code -1}
     */
    private static int findConditionalEnd(String text, int start) {
        return text.indexOf('%', start);
    }

    /**
     * 判断一个条件是否成立。不认识的条件一律当作「不成立」。
     *
     * <p>条件是模板语法的一部分，写错了不会报错、只是那段不显示，
     * 所以这里必须把「不认识」当成不成立，不能让模板原文漏到聊天栏。
     */
    private static boolean isConditionMet(String condition, DanmakuEvent event) {
        return switch (condition) {
            case "level.isSet" -> event.hasUserLevel();
            case "fanclub.isMember", "fanclub.isSet" -> event.isFanClubMember();
            case "fanclub.isNew" -> event.fanClubLevel() == 1;
            // 按消息类型判断：想「只在有人送礼物时显示某段」可以用这个
            case "kind.isChat" -> event.kind() == DanmakuEvent.Kind.CHAT;
            case "kind.isGift" -> event.kind() == DanmakuEvent.Kind.GIFT;
            case "kind.isLike" -> event.kind() == DanmakuEvent.Kind.LIKE;
            case "kind.isMember" -> event.kind() == DanmakuEvent.Kind.MEMBER;
            // 连击数大于 1 才显示（避免每个单发礼物都带个「x1」）
            case "gift.isCombo" -> event.gift() != null && event.gift().comboCount() > 1;
            // 连击结束了：抖音会为一次连击推多条消息，只有最后一条带这个标记
            case "gift.isRepeatEnd" -> event.gift() != null && event.gift().repeatEnd();
            default -> false;
        };
    }

    /**
     * 渲染模组自己的提示信息（连接成功、断开、报错等）。
     *
     * <p>这些文本不含用户输入，所以不需要做注入清理。
     *
     * @param message 正文
     * @param config  配置
     */
    public static String formatSystem(String message, DanmakuConfig config) {
        return colorize(config.systemFormat.replace("%content%", message));
    }

    /**
     * 把 {@code &} 颜色代码转成 Minecraft 的 {@code §}。
     *
     * <p>Minecraft 里 {@code §} 后面跟 {@code 0-9a-f} 是颜色、
     * {@code k-o} 是各种特效、{@code r} 是重置。
     */
    public static String colorize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder result = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '&' && index + 1 < text.length()) {
                char next = Character.toLowerCase(text.charAt(index + 1));
                if (isColorCode(next)) {
                    result.append('\u00A7').append(next);
                    index++;
                    continue;
                }
            }
            result.append(current);
        }
        return result.toString();
    }

    /** 判断是不是合法的颜色/格式代码。 */
    private static boolean isColorCode(char character) {
        return (character >= '0' && character <= '9')
                || (character >= 'a' && character <= 'f')
                || (character >= 'k' && character <= 'o')
                || character == 'r';
    }

    /**
     * 清理用户输入：去掉颜色代码、控制字符，按需去掉 emoji。
     *
     * <p>这是防注入的关键一步，见类注释里的说明。
     */
    public static String clean(String raw, boolean stripEmoji) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder(raw.length());
        int index = 0;
        while (index < raw.length()) {
            int codePoint = raw.codePointAt(index);
            int charCount = Character.charCount(codePoint);

            // 1) 去掉 Minecraft 颜色代码。
            //    § 是 U+00A7，属于 Latin-1 的编码字符，不归下面「控制字符」那条管，
            //    所以必须在这里单独处理——否则别人在弹幕里打 §c 就能给自己上色、
            //    甚至伪造出「系统提示」的样子。
            if (codePoint == '\u00A7' || codePoint == '&') {
                index += charCount;
                if (index < raw.length()) {
                    char next = Character.toLowerCase(raw.charAt(index));
                    if (isColorCode(next)) {
                        index++;   // 连后面的颜色字母一起吃掉
                    }
                }
                continue;
            }

            // 2) 去掉控制字符（换行、制表等），它们在聊天栏里会造成刷屏
            if (codePoint < 0x20 || codePoint == 0x7F) {
                index += charCount;
                continue;
            }

            // 3) 按需去掉 emoji
            if (stripEmoji && isEmoji(codePoint)) {
                index += charCount;
                continue;
            }

            result.appendCodePoint(codePoint);
            index += charCount;
        }
        return result.toString();
    }

    private static boolean isEmoji(int codePoint) {
        for (int[] range : EMOJI_RANGES) {
            if (codePoint >= range[0] && codePoint <= range[1]) {
                return true;
            }
        }
        return false;
    }
}
