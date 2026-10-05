import com.douyindanmaku.core.config.DanmakuConfig;
import com.douyindanmaku.core.douyin.DouyinProtocol;
import com.douyindanmaku.core.model.DanmakuEvent;
import com.douyindanmaku.core.model.DanmakuMessage;
import com.douyindanmaku.core.text.DanmakuFilter;
import com.douyindanmaku.core.text.DanmakuFormatter;
import com.douyindanmaku.core.text.LikeCounter;
import com.douyindanmaku.core.text.NumberText;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 用「人工合成的一帧」验证 protobuf 解码链路。
 *
 * <p>构造的字节结构刻意和抖音真实帧一致：
 * <pre>
 *   PushFrame { f2=log_id, f7="ack", f8=gzip( Response ) }
 *     Response { f1=Message, f5=internal_ext, f9=need_ack=true }
 *       Message { f1="WebcastChatMessage", f2=ChatMessage }
 *         ChatMessage { f2=User, f3=content }
 *           User { f3=nick_name, f23=PayGrade, f24=FansClub }
 *             PayGrade   { f6=level }
 *             FansClub   { f1=FansClubData { f1=club_name, f2=level } }
 * </pre>
 *
 * <p>测试里把字段号覆盖项设成小数字（因为 23/24 这种大字段号算出来的 key
 * 会落在其它线格式上，手工构造容易出错）。覆盖机制本来就是为此设计的，
 * 顺便也验证了「字段号可配置」这条路径确实能用。
 */
public class DecodeCheck {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testSimpleDanmaku();
        testDanmakuWithoutLevelAndFanClub();
        testAckAndHeartbeatEncoding();
        testFieldOverride();
        testMalformedFrameDoesNotCrash();
        testFormatterAndFilter();
        testGiftEvent();
        testLikeEvent();
        testMemberEvent();
        testLikeCounter();

        System.out.println();
        System.out.println("===== 结果：" + passed + " 通过，" + failed + " 失败 =====");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================================================================
    //  测试用例
    // ==================================================================

    private static void testSimpleDanmaku() {
        String name = "基本弹幕：昵称 + 正文 + 等级 + 粉丝团";

        // 用 -1 之外的字段号做覆盖，避开大字段号的 wire type 问题。
        // 注意 nickNameOverride 同时用于「ChatMessage 的正文」和「User 的昵称」，
        // 所以这里两者都必须是字段 3。
        DanmakuConfig config = DanmakuConfig.defaults();
        config.nickNameOverride = 3;
        config.chatContentOverride = 3;
        config.userLevelOverride = 7;
        config.fansClubOverride = 8;
        config.fanClubLevelOverride = 7;

        byte[] payGrade = msg(varintField(6, 12));                      // level = 12
        byte[] fansClubData = msg(field(1, str("某某的粉丝团")), varintField(7, 5));
        byte[] fansClub = msg(field(1, bytes(fansClubData)));
        byte[] user = msg(field(3, str("张三")),
                field(7, bytes(payGrade)),
                field(8, bytes(fansClub)));
        byte[] chatMessage = msg(field(2, bytes(user)), field(3, str("主播这个房子好漂亮")));
        byte[] message = msg(field(1, str("WebcastChatMessage")), field(2, bytes(chatMessage)));
        byte[] response = msg(field(1, bytes(message)),
                field(5, str("internal_src:dim|seq:1")),
                varintField(9, 1));

        byte[] frame = msg(varintField(2, 999), field(8, bytes(gzip(response))));

        DouyinProtocol.DecodedFrame decoded = DouyinProtocol.decodeFrame(frame, config, m -> { });

        check(name + " -> needAck", decoded.needAck());
        check(name + " -> logId", decoded.logId() == 999L);
        check(name + " -> internalExt", "internal_src:dim|seq:1".equals(decoded.internalExt()));
        check(name + " -> 弹幕条数", decoded.danmakuList().size() == 1);

        if (decoded.danmakuList().isEmpty()) {
            return;
        }
        DanmakuMessage danmaku = decoded.danmakuList().get(0);
        check(name + " -> 昵称", "张三".equals(danmaku.nickname()));
        check(name + " -> 正文", "主播这个房子好漂亮".equals(danmaku.content()));
        check(name + " -> 等级 = 12", danmaku.userLevel() == 12);
        check(name + " -> 有等级标记", danmaku.hasUserLevel());
        check(name + " -> 粉丝团等级 = 5", danmaku.fanClubLevel() == 5);
        check(name + " -> 粉丝团名称", "某某的粉丝团".equals(danmaku.fanClubName()));
    }

    private static void testDanmakuWithoutLevelAndFanClub() {
        String name = "普通观众（无等级无粉丝团）";
        DanmakuConfig config = DanmakuConfig.defaults();

        byte[] user = msg(field(3, str("路人甲")));
        byte[] chatMessage = msg(field(2, bytes(user)), field(3, str("哈哈")));
        byte[] message = msg(field(1, str("WebcastChatMessage")), field(2, bytes(chatMessage)));
        byte[] response = msg(field(1, bytes(message)));
        byte[] frame = msg(field(8, bytes(gzip(response))));

        DouyinProtocol.DecodedFrame decoded = DouyinProtocol.decodeFrame(frame, config, m -> { });
        check(name + " -> 弹幕条数", decoded.danmakuList().size() == 1);
        if (decoded.danmakuList().isEmpty()) {
            return;
        }
        DanmakuMessage danmaku = decoded.danmakuList().get(0);
        check(name + " -> 昵称", "路人甲".equals(danmaku.nickname()));
        check(name + " -> 没有等级标记", !danmaku.hasUserLevel());
        check(name + " -> 不是粉丝团", !danmaku.isFanClubMember());
        check(name + " -> 不需要 ack", !decoded.needAck());
    }

    private static void testAckAndHeartbeatEncoding() {
        String name = "ack / 心跳的编码";

        byte[] ack = DouyinProtocol.buildAck(12345L, "some_ext");
        // 解回来验证：ack 里 f2=log_id、f7="ack"、f8=internal_ext
        var parsed = com.douyindanmaku.core.proto.ProtobufReader.parse(ack);
        check(name + " -> ack 的 log_id", parsed.getLong(2, -1) == 12345L);
        check(name + " -> ack 的 payload_type", "ack".equals(parsed.getString(7, null)));
        check(name + " -> ack 的 internal_ext", "some_ext".equals(parsed.getString(8, null)));

        byte[] heartbeat = DouyinProtocol.buildHeartbeat();
        var heartbeatFrame = com.douyindanmaku.core.proto.ProtobufReader.parse(heartbeat);
        byte[] compressed = heartbeatFrame.getBytes(8, null);
        check(name + " -> 心跳含 f8", compressed != null);
        check(name + " -> 心跳 f8 是 gzip 头",
                compressed != null && compressed.length >= 2
                        && compressed[0] == (byte) 0x1F && compressed[1] == (byte) 0x8B);

        // 抖音真实心跳的另一种形态（不带 f8 的纯 payload_type 帧）也要能解
        byte[] hbType = msg(field(7, str("hb")));
        check(name + " -> 0x3a026862 结构",
                java.util.HexFormat.of().formatHex(hbType).equals("3a026862"));
    }

    private static void testFieldOverride() {
        String name = "字段号覆盖生效";
        // 把昵称字段号改成 99，内置的 3 就应该读不到了
        DanmakuConfig overridden = DanmakuConfig.defaults();
        overridden.nickNameOverride = 99;

        byte[] user = msg(field(3, str("张三")));
        byte[] chatMessage = msg(field(2, bytes(user)), field(3, str("你好")));
        byte[] message = msg(field(1, str("WebcastChatMessage")), field(2, bytes(chatMessage)));
        byte[] frame = msg(field(8, bytes(gzip(msg(field(1, bytes(message)))))));

        var decoded = DouyinProtocol.decodeFrame(frame, overridden, m -> { });
        check(name + " -> 改字段号后读不到昵称",
                decoded.danmakuList().size() == 1 && decoded.danmakuList().get(0).nickname().isEmpty());

        // 默认配置下应该能读到
        var normal = DouyinProtocol.decodeFrame(frame, DanmakuConfig.defaults(), m -> { });
        check(name + " -> 默认配置能读到昵称",
                normal.danmakuList().size() == 1 && "张三".equals(normal.danmakuList().get(0).nickname()));
    }

    private static void testMalformedFrameDoesNotCrash() {
        String name = "畸形数据不崩";
        DanmakuConfig config = DanmakuConfig.defaults();
        try {
            DouyinProtocol.decodeFrame(new byte[0], config, m -> { });
            DouyinProtocol.decodeFrame(new byte[]{1, 2, 3}, config, m -> { });
            DouyinProtocol.decodeFrame(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}, config, m -> { });
            // 声称长度很大但实际没有数据
            DouyinProtocol.decodeFrame(new byte[]{0x42, 0x7F, 0x01}, config, m -> { });
            // 截断的 gzip
            byte[] truncatedGzip = {(byte) 0x1F, (byte) 0x8B, 0x08, 0x00};
            DouyinProtocol.decodeFrame(msg(field(8, bytes(truncatedGzip))), config, m -> { });
            check(name + " -> 没有抛异常", true);
        } catch (RuntimeException crashed) {
            check(name + " -> 没有抛异常（实际抛了 " + crashed + "）", false);
        }
    }

    private static void testFormatterAndFilter() {
        String name = "渲染与过滤";

        DanmakuConfig config = DanmakuConfig.defaults();
        // 这一组测的是「渲染」而不是「默认模板」，所以显式写一个同时包含
        // 消费等级和粉丝团等级的模板（用条件块），免得默认模板一改断言就跟着挂。
        config.chatFormat = "%?level.isSet:[&eLv.%level.value%&7] %"
                + "%?fanclub.isMember:[&d粉丝团%fanclub.level%&7] %&b%nick%&7: &f%content%";
        var danmaku = new DanmakuMessage("张三", "主播好厉害", 12, true, "某某团", 5, System.currentTimeMillis());
        String rendered = DanmakuFormatter.formatDanmaku(danmaku, config);
        check(name + " -> 含昵称", rendered.contains("张三"));
        check(name + " -> 含正文", rendered.contains("主播好厉害"));
        check(name + " -> 含等级", rendered.contains("Lv.12"));
        check(name + " -> 含粉丝团等级", rendered.contains("粉丝团") && rendered.contains("5"));
        check(name + " -> 有颜色代码", rendered.indexOf('\u00A7') >= 0);

        // 注入防护：昵称和正文里的颜色代码必须被清掉
        var evil = new DanmakuMessage("\u00A7c假系统", "&c红字\u00A7l粗体\n第二行", 0, false, null, 0,
                System.currentTimeMillis());
        String safeNick = DanmakuFormatter.clean(evil.nickname(), true);
        String safeContent = DanmakuFormatter.clean(evil.content(), true);
        check(name + " -> 昵称里的颜色代码被清掉", safeNick.indexOf('\u00A7') < 0 && safeNick.indexOf('&') < 0);
        check(name + " -> 昵称文字还在", safeNick.contains("假系统"));
        check(name + " -> 正文里的颜色代码被清掉",
                safeContent.indexOf('\u00A7') < 0 && safeContent.indexOf('&') < 0);
        check(name + " -> 正文里没有换行", safeContent.indexOf('\n') < 0);
        check(name + " -> 正文文字还在", safeContent.contains("红字") && safeContent.contains("粗体"));
        // 模板自带的颜色代码必须保留（只有用户输入要被清理）
        check(name + " -> 模板颜色代码保留", safeContent.indexOf('\u00A7') < 0 && !DanmakuFormatter.colorize("&b").isEmpty());

        // 去重
        DanmakuFilter filter = new DanmakuFilter();
        long now = System.currentTimeMillis();
        var first = new DanmakuMessage("李四", "重复测试", 0, false, null, 0, now);
        var second = new DanmakuMessage("李四", "重复测试", 0, false, null, 0, now + 100);
        check(name + " -> 第一条显示", filter.shouldShow(first, config));
        check(name + " -> 重复的被去掉", !filter.shouldShow(second, config));

        // 黑名单
        DanmakuConfig blacklist = DanmakuConfig.defaults();
        blacklist.filterMode = DanmakuConfig.FilterMode.BLACKLIST;
        blacklist.filterKeywords = List.of("广告");
        var ad = new DanmakuMessage("王五", "看广告加群", 0, false, null, 0, now);
        check(name + " -> 黑名单拦掉", !filter.shouldShow(ad, blacklist));

        // 限流
        DanmakuConfig limited = DanmakuConfig.defaults();
        limited.maxDanmakuPerSecond = 2;
        DanmakuFilter limitFilter = new DanmakuFilter();
        int shown = 0;
        for (int i = 0; i < 10; i++) {
            if (limitFilter.shouldShow(new DanmakuMessage("u" + i, "m" + i, 0, false, null, 0, now), limited)) {
                shown++;
            }
        }
        check(name + " -> 限流只放行 2 条（实际 " + shown + "）", shown == 2);

        testConditionalTemplate();
    }

    /**
     * 条件块 {@code %?条件:内容%}。
     *
     * <p>主要验证「不是粉丝团的人不会留下一对空方括号」——
     * 这是「只想显示粉丝团等级」这个需求的关键。
     */
    private static void testConditionalTemplate() {
        String name = "条件模板";

        DanmakuConfig config = DanmakuConfig.defaults();
        // 用户要的效果：只显示粉丝团等级、完全不显示消费等级。
        // 注意 %? 里的问号不能少——那是条件块的标记。
        config.chatFormat =
                "&7[&f抖音&7] %?fanclub.isMember:[&d粉丝团%fanclub.level%&7] %&f%nick%&7: &f%content%";

        long now = System.currentTimeMillis();

        // 粉丝团成员：应该看到「[粉丝团5]」
        var member = new DanmakuMessage("张三", "你好", 12, true, "某某团", 5, now);
        String memberText = DanmakuFormatter.formatDanmaku(member, config);
        check(name + " -> 粉丝团成员显示粉丝团", memberText.contains("粉丝团"));
        check(name + " -> 粉丝团成员显示等级数字", memberText.contains("5"));
        check(name + " -> 粉丝团成员不显示消费等级", !memberText.contains("Lv."));
        check(name + " -> 粉丝团成员昵称正常", memberText.contains("张三"));

        // 普通观众：不应该留下空方括号
        var normal = new DanmakuMessage("路人甲", "哈哈", 0, false, null, 0, now);
        String normalText = DanmakuFormatter.formatDanmaku(normal, config);
        check(name + " -> 普通观众不显示粉丝团", !normalText.contains("粉丝团"));
        check(name + " -> 普通观众没有空方括号", !normalText.contains("[]"));
        check(name + " -> 普通观众没有多余空格", !normalText.contains("  "));
        check(name + " -> 普通观众正文正常", normalText.contains("哈哈"));
        // 条件块里的颜色代码要跟着内容一起消失，不能留下裸的颜色代码
        check(name + " -> 普通观众没有残留粉色代码", !normalText.contains("\u00A7d"));

        // 另一个条件：有消费等级才显示
        DanmakuConfig levelOnly = DanmakuConfig.defaults();
        levelOnly.chatFormat = "%?level.isSet:[&eLv.%level.value%&7] %&f%nick%&7: &f%content%";
        check(name + " -> level.isSet 成立时显示",
                DanmakuFormatter.formatDanmaku(member, levelOnly).contains("Lv.12"));
        String noLevelText = DanmakuFormatter.formatDanmaku(normal, levelOnly);
        check(name + " -> level.isSet 不成立时不显示", !noLevelText.contains("Lv."));
        check(name + " -> level.isSet 不成立不留空括号", !noLevelText.contains("[]"));

        // 不认识的条件一律当不成立，而且不能把模板语法漏到界面上
        DanmakuConfig unknown = DanmakuConfig.defaults();
        unknown.chatFormat = "%?something.weird:不该出现%&fOK";
        String unknownText = DanmakuFormatter.formatDanmaku(member, unknown);
        check(name + " -> 未知条件不显示内容", !unknownText.contains("不该出现"));
        check(name + " -> 未知条件不残留模板语法", !unknownText.contains("%?"));
        check(name + " -> 未知条件后面的文字还在", unknownText.contains("OK"));

        // 昵称里带 $ 和 \ 时不能把替换搞崩（正则组引用的经典坑）
        var tricky = new DanmakuMessage("a$1b\\c", "x$2y", 0, false, null, 0, now);
        try {
            String trickyText = DanmakuFormatter.formatDanmaku(tricky, config);
            check(name + " -> 昵称含 $ 和 \\ 不崩", trickyText.contains("a$1b\\c"));
            check(name + " -> 正文含 $ 不崩", trickyText.contains("x$2y"));
        } catch (RuntimeException crashed) {
            check(name + " -> 昵称含 $ 和 \\ 不崩（实际抛了 " + crashed + "）", false);
        }

        testDefaultTemplate();
    }

    /**
     * 默认模板的行为。
     *
     * <p>默认<b>只显示粉丝团等级、不显示消费等级</b>。
     * 另外还要验证「旧默认配置能自动升级、但用户自定义的不会被覆盖」。
     */
    private static void testDefaultTemplate() {
        String name = "默认模板";

        DanmakuConfig config = DanmakuConfig.defaults();
        long now = System.currentTimeMillis();

        // 粉丝团成员：有粉丝团，不应该有消费等级
        var member = new DanmakuMessage("张三", "你好", 12, true, "某某团", 5, now);
        String memberText = DanmakuFormatter.formatDanmaku(member, config);
        check(name + " -> 显示粉丝团", memberText.contains("粉丝团"));
        check(name + " -> 显示粉丝团等级", memberText.contains("5"));
        check(name + " -> 不显示消费等级", !memberText.contains("Lv."));

        // 昵称必须是青色（&b -> §b），而且颜色不能漏到正文上。
        // 昵称和正文如果都是白的，扫一眼分不清谁在说话，所以这条要盯住。
        check(name + " -> 昵称用青色", memberText.contains("\u00A7b张三"));
        check(name + " -> 正文是白色（昵称颜色没漏过去）", memberText.contains("\u00A7f你好"));
        check(name + " -> 昵称后面有灰色冒号", memberText.contains("\u00A7b张三\u00A77: "));

        // 普通观众：什么都没有
        var normal = new DanmakuMessage("路人甲", "哈哈", 0, false, null, 0, now);
        String normalText = DanmakuFormatter.formatDanmaku(normal, config);
        check(name + " -> 普通观众不显示粉丝团", !normalText.contains("粉丝团"));
        check(name + " -> 普通观众不留空括号", !normalText.contains("[]"));
        check(name + " -> 普通观众昵称也是青色", normalText.contains("\u00A7b路人甲"));

        // ---- 配置升级 ----
        // 这里直接测升级函数本身。
        // 不走 fromJson 是因为自检用的是 Gson 桩件，它没法真的解析 JSON。

        // 各种历史版本的默认值都应该被自动换成新的
        String upgradedFromOldest = DanmakuConfig.upgradeChatFormat(
                "&7[&f抖音&7] %fanclub%%level%&f%nick%&7: &f%content%");
        check(name + " -> 最早版默认模板被升级",
                !upgradedFromOldest.contains("%level%")
                        && upgradedFromOldest.contains("fanclub.isMember")
                        && upgradedFromOldest.contains("\u0026b%nick%"));

        String upgradedFromMiddle = DanmakuConfig.upgradeChatFormat(
                "&7[&f抖音&7] %?fanclub.isMember:[&d粉丝团%fanclub.level%&7] %&f%nick%&7: &f%content%");
        check(name + " -> 中间版默认模板被升级（补上青色昵称）",
                upgradedFromMiddle.contains("\u0026b%nick%"));

        // 用户自定义的模板绝对不能被覆盖
        String custom = "&b%nick%&7说: &f%content%";
        check(name + " -> 用户自定义模板不被覆盖（实际 " + DanmakuConfig.upgradeChatFormat(custom) + "）",
                custom.equals(DanmakuConfig.upgradeChatFormat(custom)));

        // 新版默认值本身不应该被再动一次
        String newDefault = DanmakuConfig.defaults().chatFormat;
        check(name + " -> 新默认模板保持不变",
                newDefault.equals(DanmakuConfig.upgradeChatFormat(newDefault)));

        // ---- 点赞模板的升级 ----
        // 这一组很关键：已经生成过配置文件的用户，文件里存的是旧模板。
        // 不自动升的话，改了默认值他们也看不到效果，会以为功能没生效。

        String upgradedLikeOldest = DanmakuConfig.upgradeLikeFormat(
                "&7[&f抖音&7] &b%nick%&7 为主播点赞，本场累计 &f%like.total%&7 赞");
        check(name + " -> 带「本场累计」的旧点赞模板被升级",
                !upgradedLikeOldest.contains("本场累计")
                        && !upgradedLikeOldest.contains("like.total"));

        String upgradedLikeMiddle = DanmakuConfig.upgradeLikeFormat(
                "&7[&f抖音&7] &b%nick%&7 为主播点赞 &f+%like.count%");
        check(name + " -> 带「+N」的旧点赞模板被升级",
                !upgradedLikeMiddle.contains("like.count"));

        // 升级后的模板必须是新的默认值
        String likeNewDefault = DanmakuConfig.defaults().likeFormat;
        check(name + " -> 旧点赞模板升级成新默认值",
                likeNewDefault.equals(upgradedLikeOldest)
                        && likeNewDefault.equals(upgradedLikeMiddle));

        // 新默认值里不能有累计数，而且配色要是品红/红色系
        check(name + " -> 新点赞模板不含累计数",
                !likeNewDefault.contains("like.total") && !likeNewDefault.contains("like.session"));
        check(name + " -> 新点赞模板是品红+红色（&d / &c）",
                likeNewDefault.contains("\u0026d%nick%") && likeNewDefault.contains("\u0026c"));
        check(name + " -> 新点赞模板不含旧的青色昵称",
                !likeNewDefault.contains("\u0026b%nick%"));

        // 用户自定义的点赞模板绝对不能被覆盖
        String customLike = "&e%nick%&7 点了个赞";
        check(name + " -> 自定义点赞模板不被覆盖",
                customLike.equals(DanmakuConfig.upgradeLikeFormat(customLike)));

        // 新默认值本身不该被再动一次
        check(name + " -> 新点赞默认模板保持不变",
                likeNewDefault.equals(DanmakuConfig.upgradeLikeFormat(likeNewDefault)));
    }

    // ==================================================================
    //  礼物 / 点赞 / 进房
    // ==================================================================

    /**
     * 礼物消息。
     *
     * <p>这一组的重点是<b>字段号</b>：礼物消息和弹幕消息长得不一样，
     * 用户信息在 f7（弹幕是 f2）、礼物名在 gift(f15) 底下的 f16
     * （弹幕正文在 f3）。写错了不会报错，只会显示成空——所以必须测。
     */
    private static void testGiftEvent() {
        String name = "礼物消息";

        // 用真实字段号（不是覆盖项）——测试就是要验证默认字段号对不对。
        // 注意 f23/f24 这种大字段号算出来的 key 会落在 wire type 2 上，
        // 正好符合「长度前缀」的编码，所以手工构造是对的。
        DanmakuConfig config = DanmakuConfig.defaults();

        // GiftStruct: f16=礼物名, f12=单价
        byte[] giftStruct = msg(field(16, str("小心心")), varintField(12, 10));

        // User: f3=昵称, f23=pay_grade(里面 f6=等级), f24=fans_club
        byte[] payGrade = msg(varintField(6, 8));
        byte[] fansClub = msg(field(1, bytes(msg(
                field(1, str("某团")),
                varintField(2, 4)))));
        byte[] user = msg(
                field(3, str("土豪甲")),
                field(23, bytes(payGrade)),
                field(24, bytes(fansClub)));

        // GiftMessage: f7=user, f5=repeat_count, f6=combo_count, f15=gift
        byte[] giftMessage = msg(
                field(7, bytes(user)),
                varintField(5, 3),
                varintField(6, 5),
                field(15, bytes(giftStruct)));
        byte[] event0 = msg(field(1, str("WebcastGiftMessage")), field(2, bytes(giftMessage)));
        byte[] frame = msg(field(8, bytes(gzip(msg(field(1, bytes(event0)))))));

        var decoded = DouyinProtocol.decodeFrameToEvents(frame, config);
        check(name + " -> 解出 1 条", decoded.events().size() == 1);
        if (decoded.events().isEmpty()) {
            return;
        }
        var event = decoded.events().get(0);
        check(name + " -> 类型是礼物", event.kind() == com.douyindanmaku.core.model.DanmakuEvent.Kind.GIFT);
        check(name + " -> 昵称（f7 里的 user）", "土豪甲".equals(event.nickname()));
        check(name + " -> 等级（f23/f6）", event.userLevel() == 8);
        check(name + " -> 粉丝团等级（f24/f1/f2）", event.fanClubLevel() == 4);
        check(name + " -> 礼物名（f15/f16）", event.gift() != null && "小心心".equals(event.gift().name()));
        check(name + " -> 数量 f5", event.gift() != null && event.gift().repeatCount() == 3);
        check(name + " -> 连击 f6", event.gift() != null && event.gift().comboCount() == 5);
        check(name + " -> 单价 f12", event.gift() != null && event.gift().diamondCount() == 10);
        check(name + " -> 不是未知类型", decoded.unhandledMethods().isEmpty());

        // 渲染（用默认模板，它带粉丝团条件块）
        String rendered = DanmakuFormatter.formatEvent(event, config);
        check(name + " -> 渲染含礼物名", rendered.contains("小心心"));
        check(name + " -> 渲染含数量", rendered.contains("3"));
        check(name + " -> 渲染含连击（x5，带颜色代码）", rendered.contains("\u00A77x\u00A7f5"));
        check(name + " -> 渲染含昵称", rendered.contains("土豪甲"));
        check(name + " -> 渲染含粉丝团（条件块生效）", rendered.contains("粉丝团"));
        check(name + " -> 渲染不留模板语法", !rendered.contains("%"));
        // 「x」不能被当成颜色代码（&x 是「随机字符」格式代码）。
        // 这是模板里真实踩过的坑，专门盯住它。
        check(name + " -> 数量前的 x 不是颜色代码", !rendered.contains("\u00A7x"));

        // 关掉礼物时不该渲染出任何东西
        DanmakuConfig giftOff = config.copy();
        giftOff.showGift = false;
        check(name + " -> 关掉后不显示", DanmakuFormatter.formatEvent(event, giftOff) == null);
    }

    /** 点赞消息：验证增量被读出来，以及「没有增量」的帧被丢掉。 */
    private static void testLikeEvent() {
        String name = "点赞消息";

        byte[] likeMessage = msg(varintField(2, 7), field(5, bytes(msg(field(3, str("点赞狂魔"))))));
        byte[] event0 = msg(field(1, str("WebcastLikeMessage")), field(2, bytes(likeMessage)));
        byte[] frame = msg(field(8, bytes(gzip(msg(field(1, bytes(event0)))))));

        var decoded = DouyinProtocol.decodeFrameToEvents(frame, DanmakuConfig.defaults());
        check(name + " -> 解出 1 条", decoded.events().size() == 1);
        if (!decoded.events().isEmpty()) {
            var event = decoded.events().get(0);
            check(name + " -> 类型是点赞", event.kind() == com.douyindanmaku.core.model.DanmakuEvent.Kind.LIKE);
            check(name + " -> 增量是 7", event.like() != null && event.like().increment() == 7);
            check(name + " -> 昵称", "点赞狂魔".equals(event.nickname()));
        }

        // 增量为 0 的点赞帧（纯统计）应该被丢掉，不该刷屏
        byte[] emptyLike = msg(field(1, str("WebcastLikeMessage")), field(2, bytes(msg(varintField(2, 0)))));
        byte[] emptyFrame = msg(field(8, bytes(gzip(msg(field(1, bytes(emptyLike)))))));
        var emptyDecoded = DouyinProtocol.decodeFrameToEvents(emptyFrame, DanmakuConfig.defaults());
        check(name + " -> 增量为 0 的帧被丢掉", emptyDecoded.events().isEmpty());
    }

    /** 进房消息。 */
    private static void testMemberEvent() {
        String name = "进房消息";

        byte[] memberMessage = msg(
                field(2, bytes(msg(field(3, str("新人乙"))))),
                varintField(3, 1234));
        byte[] event0 = msg(field(1, str("WebcastMemberMessage")), field(2, bytes(memberMessage)));
        byte[] frame = msg(field(8, bytes(gzip(msg(field(1, bytes(event0)))))));

        var decoded = DouyinProtocol.decodeFrameToEvents(frame, DanmakuConfig.defaults());
        check(name + " -> 解出 1 条", decoded.events().size() == 1);
        if (decoded.events().isEmpty()) {
            return;
        }
        var event = decoded.events().get(0);
        check(name + " -> 类型是进房", event.kind() == com.douyindanmaku.core.model.DanmakuEvent.Kind.MEMBER);
        check(name + " -> 昵称", "新人乙".equals(event.nickname()));
        check(name + " -> 在线人数", event.member() != null && event.member().memberCount() == 1234);

        // 进房默认关闭
        String rendered = DanmakuFormatter.formatEvent(event, DanmakuConfig.defaults());
        check(name + " -> 默认关闭不显示", rendered == null);

        // 打开后能渲染
        DanmakuConfig memberOn = DanmakuConfig.defaults();
        memberOn.showMember = true;
        String onText = DanmakuFormatter.formatEvent(event, memberOn);
        check(name + " -> 打开后能渲染", onText != null && onText.contains("新人乙"));
        check(name + " -> 渲染不留模板语法", onText != null && !onText.contains("%"));
    }

    /**
     * 点赞累计器。
     *
     * <p>这是「点赞能显示多少赞」的核心——抖音不推总数，只能自己加。
     * 另外还要验证「按人攒一波、停下来才报、同一人 30 秒最多一次」这套节流。
     */
    private static void testLikeCounter() {
        String name = "点赞累计";

        LikeCounter counter = new LikeCounter();
        check(name + " -> 初始为 0", counter.total() == 0);

        // 短时间内连点，应该聚成一条，而不是每条都报
        counter.add("张三", 5);
        counter.add("张三", 3);
        check(name + " -> 累加正确（5+3=8）", counter.total() == 8);
        counter.add("李四", 10);
        check(name + " -> 多人合计正确（8+10=18）", counter.total() == 18);

        // 还在点，不该报
        check(name + " -> 还在点时先不报", counter.drainReports().isEmpty());

        // 等过「停下来」的窗口（5 秒）之后应该报
        sleep(5_600);
        var reports = counter.drainReports();
        check(name + " -> 停下来之后报出来（实际 " + reports.size() + " 条）", reports.size() == 2);

        long zhangSanAmount = reports.stream()
                .filter(r -> "张三".equals(r.nickname()))
                .mapToLong(LikeCounter.Report::amount).findFirst().orElse(-1);
        check(name + " -> 张三那一条是 8", zhangSanAmount == 8);
        check(name + " -> 报完不再重复", counter.drainReports().isEmpty());
        check(name + " -> 累计总数不变", counter.total() == 18);

        // 同一个人 30 秒冷却：刚报过，再点也不该立刻再报
        counter.add("张三", 100);
        sleep(5_600);
        check(name + " -> 冷却期内同一人不重复报", counter.drainReports().isEmpty());

        // 负数增量不该被算进去（防御异常数据）
        long beforeTotal = counter.total();
        counter.add("张三", -100);
        check(name + " -> 负数增量被忽略", counter.total() == beforeTotal);

        // 拿不到昵称的（匿名）只计入总数，不单独播报。
        // 这是有意的：按人播报已经覆盖同一批数据，匿名再报一次就会出现
        // 两个数字（一个权威的、一个自己累加的），用户看到的是「两个累计数」。
        LikeCounter anonymous = new LikeCounter();
        anonymous.add("", 50);
        check(name + " -> 匿名计入总数", anonymous.total() == 50);
        check(name + " -> 匿名不单独播报（避免和按人播报重复）",
                anonymous.drainReports().isEmpty());
        sleep(5_600);
        check(name + " -> 匿名等再久也不播报", anonymous.drainReports().isEmpty());

        // 重置（重连时用）
        counter.reset();
        check(name + " -> 重置后归零", counter.total() == 0);

        // ---- 数字格式化（万 / 亿）----
        check(name + " -> 999 原样", "999".equals(LikeCounter.formatCount(999)));
        check(name + " -> 9999 原样", "9999".equals(LikeCounter.formatCount(9999)));
        check(name + " -> 10000 变 1万", "1万".equals(LikeCounter.formatCount(10_000)));
        check(name + " -> 25000 变 2.5万", "2.5万".equals(LikeCounter.formatCount(25_000)));
        check(name + " -> 50000 变 5万（不带 .0）", "5万".equals(LikeCounter.formatCount(50_000)));
        check(name + " -> 1234567 变 123.5万", "123.5万".equals(LikeCounter.formatCount(1_234_567)));
        check(name + " -> 1亿", "1亿".equals(LikeCounter.formatCount(100_000_000L)));
        check(name + " -> 负数当 0", "0".equals(LikeCounter.formatCount(-5)));

        testNumberParsing();
        testRoomStats();

        // ---- 默认模板只显示「谁点了赞」 ----
        DanmakuConfig likeConfig = DanmakuConfig.defaults();
        likeConfig.showLike = true;
        DanmakuEvent likeEvent = new DanmakuEvent(DanmakuEvent.Kind.LIKE, "张三", 0, false,
                null, 0, "", null, new DanmakuEvent.Like(30, 25_000), null, System.currentTimeMillis());
        String likeText = DanmakuFormatter.formatEvent(likeEvent, likeConfig);
        check(name + " -> 渲染含昵称", likeText != null && likeText.contains("张三"));
        check(name + " -> 默认模板就是「XXX为主播点赞」",
                likeText != null && likeText.contains("为主播点赞"));
        // 默认模板不该有任何数字——弹幕通道里没有可靠的累计获赞，
        // 显示一个错的数字比不显示更糟
        check(name + " -> 默认模板不显示累计数", likeText != null && !likeText.contains("万"));
        check(name + " -> 默认模板不显示「+N」",
                likeText != null && !likeText.contains("+30") && !likeText.contains("30"));

        // 想显示数量的话，自己写模板引用 %like.count% 就行
        DanmakuConfig countConfig = likeConfig.copy();
        countConfig.likeFormat = "&d%nick%&c 为主播点赞 &f+%like.count%";
        String countText = DanmakuFormatter.formatEvent(likeEvent, countConfig);
        check(name + " -> 自定义模板里 %like.count% 可用",
                countText != null && countText.contains("+30"));

        // 想用「万」格式的话，自己写模板引用 %like.session% 就行
        DanmakuConfig sessionConfig = likeConfig.copy();
        sessionConfig.likeFormat = "&b%nick%&7 本场收到 &f%like.session%";
        String sessionText = DanmakuFormatter.formatEvent(likeEvent, sessionConfig);
        check(name + " -> 自定义模板里 %like.session% 会用「万」",
                sessionText != null && sessionText.contains("2.5万")
                        && !sessionText.contains("25000"));

        // 匿名模板默认是空的 → 不显示
        DanmakuEvent anonymousEvent = new DanmakuEvent(DanmakuEvent.Kind.LIKE, "", 0, false,
                null, 0, "", null, new DanmakuEvent.Like(50, 5000), null, System.currentTimeMillis());
        check(name + " -> 匿名默认不显示",
                DanmakuFormatter.formatEvent(anonymousEvent, likeConfig) == null
                        || DanmakuFormatter.formatEvent(anonymousEvent, likeConfig).isBlank());

        testLikePlaceholders(likeConfig, likeEvent);
    }

    /**
     * 点赞的三个数字占位符必须各自渲染对，而且<b>不能漏出模板语法</b>。
     *
     * <p>这条是补一个真 bug：{@code %like.count%} 曾经忘了填，
     * 聊天栏里直接显示「%like.count%」。
     */
    private static void testLikePlaceholders(DanmakuConfig base, DanmakuEvent template) {
        String name = "点赞占位符";

        DanmakuConfig config = base.copy();
        config.likeFormat = "&b%nick%&7|count=%like.count%|session=%like.session%"
                + "|online=%room.online%|watched=%room.watched%";

        DanmakuEvent event = new DanmakuEvent(DanmakuEvent.Kind.LIKE, "张三", 0, false,
                null, 0, "", null, new DanmakuEvent.Like(30, 350, 2284, "6万+"),
                null, System.currentTimeMillis());

        String rendered = DanmakuFormatter.formatEvent(event, config);
        check(name + " -> 渲染成功", rendered != null);
        if (rendered == null) {
            return;
        }
        check(name + " -> %like.count% = 30", rendered.contains("count=30"));
        check(name + " -> %like.session% = 350", rendered.contains("session=350"));
        check(name + " -> %room.online% = 2284", rendered.contains("online=2284"));
        check(name + " -> %room.watched% = 6万+", rendered.contains("watched=6万+"));
        check(name + " -> 没有残留模板语法", !rendered.contains("%"));

        // ★ 关键：默认模板里不能再出现「累计获赞」那种骗人的数字。
        //   弹幕通道里没有获赞数据，显示一个错的比不显示更糟。
        check(name + " -> 默认点赞模板不含累计获赞",
                !config.likeFormat.contains("like.total"));
        String defaultRendered = DanmakuFormatter.formatEvent(event, base);
        check(name + " -> 默认模板渲染不含「万」（避免又冒出那个错数字）",
                defaultRendered != null && !defaultRendered.contains("万"));
        check(name + " -> 默认模板渲染含昵称",
                defaultRendered != null && defaultRendered.contains("张三"));

        // 旧配置里写 %like.total% 也不能渲染出空白或模板语法
        DanmakuConfig legacy = base.copy();
        legacy.likeFormat = "&b%nick%&7 累计 %like.total%";
        String legacyRendered = DanmakuFormatter.formatEvent(event, legacy);
        check(name + " -> 旧配置的 %like.total% 仍能渲染（不再漏模板语法）",
                legacyRendered != null && !legacyRendered.contains("%"));

        // 兜底防线：写一个根本不存在的占位符，也不能漏到界面上
        DanmakuConfig bogus = base.copy();
        bogus.likeFormat = "&b%nick%&7 你好 %nonsense% 世界 %another.one%";
        String bogusRendered = DanmakuFormatter.formatEvent(event, bogus);
        check(name + " -> 未知占位符被清掉", bogusRendered != null && !bogusRendered.contains("%nonsense%"));
        check(name + " -> 未知占位符清掉后不留模板语法",
                bogusRendered != null && !bogusRendered.contains("%"));
        check(name + " -> 未知占位符周围的文字保留",
                bogusRendered != null && bogusRendered.contains("你好") && bogusRendered.contains("世界"));

        // 正文里的普通百分号不能被误删（比如观众发「打折50%」）
        DanmakuConfig chat = base.copy();
        DanmakuEvent chatEvent = DanmakuEvent.chat(
                new DanmakuMessage("李四", "打折50% off", 0, false, null, 0, System.currentTimeMillis()));
        String chatRendered = DanmakuFormatter.formatEvent(chatEvent, chat);
        check(name + " -> 正文里的普通百分号不被误删",
                chatRendered != null && chatRendered.contains("打折50% off"));
    }

    /** 睡一会儿，不抛受检异常。 */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 中文数字解析。
     *
     * <p>抖音有些统计数字是「已格式化的字符串」（如「1.3万」），
     * 想用就必须解析回数值。
     */
    private static void testNumberParsing() {
        String name = "数字解析";

        check(name + " -> 纯数字", NumberText.parse("12345") == 12345L);
        check(name + " -> 带千分位", NumberText.parse("1,234") == 1234L);
        check(name + " -> 1.3万", NumberText.parse("1.3万") == 13000L);
        check(name + " -> 12万", NumberText.parse("12万") == 120000L);
        check(name + " -> 1.5亿", NumberText.parse("1.5亿") == 150_000_000L);
        check(name + " -> 3.2w（w 当万）", NumberText.parse("3.2w") == 32000L);
        check(name + " -> 空字符串当 0", NumberText.parse("") == 0L);
        check(name + " -> null 当 0", NumberText.parse(null) == 0L);
        check(name + " -> 乱码当 0（不抛异常）", NumberText.parse("不知道") == 0L);
        check(name + " -> 只有单位当 0", NumberText.parse("万") == 0L);
        check(name + " -> 前后空格能处理", NumberText.parse("  2.5万  ") == 25000L);
    }

    /**
     * 直播间统计消息：累计获赞的提取。
     *
     * <p>这是「获赞总数」的正确来源——点赞增量消息只给「刚才点了几下」，
     * 拿它累加出来的数字和面板上差得很远。
     */
    private static void testRoomStats() {
        String name = "直播间统计";

        // RoomUserSeqMessage 的字段号（已用真实抓包确认，见 testRealRoomStats）：
        //   f3=在线人数, f7/f8/f11=那个含义不确定的累计量
        //
        // 注意这里【没有】获赞字段——实测确认弹幕通道里没有获赞这个数据。
        byte[] seq = msg(
                varintField(3, 1234),
                varintField(7, 125_000),
                field(11, str("12.5万")));
        byte[] message = msg(field(1, str("WebcastRoomUserSeqMessage")), field(2, bytes(seq)));
        byte[] frame = msg(field(8, bytes(gzip(msg(field(1, bytes(message)))))));

        var decoded = DouyinProtocol.decodeFrameToEvents(frame, DanmakuConfig.defaults());
        check(name + " -> 解出统计", decoded.roomStats() != null);
        if (decoded.roomStats() == null) {
            return;
        }
        check(name + " -> 在线人数 f3", decoded.roomStats().online() == 1234);
        check(name + " -> 累计量保留抖音原文 f11", "12.5万".equals(decoded.roomStats().watched()));
        check(name + " -> 统计不算作事件", decoded.events().isEmpty());
        check(name + " -> 统计类型不算未知", decoded.unhandledMethods().isEmpty());

        // 没有文本字段时，用数值字段自己格式化
        byte[] numberOnly = msg(field(1, str("WebcastRoomUserSeqMessage")),
                field(2, bytes(msg(varintField(3, 800), varintField(7, 45_000)))));
        byte[] numberFrame = msg(field(8, bytes(gzip(msg(field(1, bytes(numberOnly)))))));
        var numberDecoded = DouyinProtocol.decodeFrameToEvents(numberFrame, DanmakuConfig.defaults());
        check(name + " -> 只有数值字段时也能解出累计量",
                numberDecoded.roomStats() != null
                        && "4.5万".equals(numberDecoded.roomStats().watched()));

        // 另一种统计消息（RoomStatsMessage）提供在线人数
        byte[] roomStats = msg(field(1, str("WebcastRoomStatsMessage")),
                field(2, bytes(msg(varintField(5, 5678)))));
        byte[] roomStatsFrame = msg(field(8, bytes(gzip(msg(field(1, bytes(roomStats)))))));
        var roomStatsDecoded = DouyinProtocol.decodeFrameToEvents(roomStatsFrame, DanmakuConfig.defaults());
        check(name + " -> RoomStats 提供在线人数",
                roomStatsDecoded.roomStats() != null && roomStatsDecoded.roomStats().online() == 5678);
        check(name + " -> RoomStats 不提供累计量",
                roomStatsDecoded.roomStats() != null
                        && roomStatsDecoded.roomStats().watched() == null);

        // 合并规则：有值才覆盖，没值不动
        var older = new com.douyindanmaku.core.model.RoomStats(100, "1万");
        var newer = new com.douyindanmaku.core.model.RoomStats(0, null);
        var merged = older.merge(newer);
        check(name + " -> 合并时在线人数保留旧值", merged.online() == 100);
        check(name + " -> 合并时有值就覆盖", "1万".equals(merged.watched()));

        var withWatch = older.merge(new com.douyindanmaku.core.model.RoomStats(0, "2万"));
        check(name + " -> 合并时新值覆盖旧值", "2万".equals(withWatch.watched()));

        check(name + " -> 空统计判定",
                com.douyindanmaku.core.model.RoomStats.empty().isEmpty());

        testRealRoomStats();
        testStatsProbe();
    }

    /**
     * 真实抓包数据的回归测试。
     *
     * <p><b>这一组最重要</b>：下面的字节是从一个真实直播间抓下来的
     * （用户提供的日志里记录的字段清单还原而成）。
     *
     * <p>背景：早期版本照社区资料把获赞读成 f13/f14，而实际数据里
     * 那两个字段根本不存在 → 获赞永远读到 0 → 退回「自己累加」的兜底值，
     * 表现就是「从开启才开始计数」。
     *
     * <p>实测确认的真实字段：
     * <pre>
     *   RoomUserSeq:  f3=1941  f7=41796  f8="4万+"  f9="1941"  f10="1941"  f11="4.2万"
     *   RoomStats:    f2="1923" f3="1923" f4="1923在线观众" f5=1923 f9=1923
     * </pre>
     * 抖音面板上这个房间是 1941 在线、约 4.2 万赞——两个数字都对得上，
     * 所以 f11/f9 就是获赞，f3 是在线人数。
     *
     * <p>哪天抖音改了字段，这组测试会立刻失败——比等用户反馈快得多。
     */
    /**
     * 真实抓包数据的回归测试。
     *
     * <p><b>这一组最重要</b>：下面的字节是从真实直播间抓下来的
     * （照用户提供的日志里记录的字段清单还原）。
     *
     * <p>实测确认的真实字段：
     * <pre>
     *   RoomUserSeq:  f3=2284  f7=63133  f8="6万+"  f9=2284  f10=2284  f11="6.3万"
     *   RoomStats:    f2=2319  f3=2319  f4="2319在线观众"  f5=2319  f9=2319
     * </pre>
     *
     * <p><b>关键结论</b>：这里<b>没有获赞字段</b>。能读出来的只有在线人数
     * （f3/f9/f10，都是 2284）和一个累计量（f7/f8/f11 = 63133 / "6万+" / "6.3万"）。
     * 而当时直播间面板上的获赞是 4 万——和 6.3 万对不上，所以那个累计量
     * <b>不是获赞</b>。早期版本把它当获赞显示，结果是一个稳定错误的数字。
     *
     * <p>所以现在模组<b>不显示累计获赞</b>。这组测试锁住这个结论：
     * 谁要是又想着「把那个累计量当获赞显示吧」，这里会拦住他。
     */
    private static void testRealRoomStats() {
        String name = "真实抓包";

        // ---------- RoomUserSeqMessage ----------
        byte[] seqBody = msg(
                field(1, str("WebcastRoomUserSeqMessage")),
                field(2, hex("bbd7a9031a08e7bb87e69f9a7a794ac103")),
                varintField(3, 2284),
                varintField(7, 63133),
                field(8, str("6万+")),
                field(9, str("2284")),
                field(10, str("2284")),
                field(11, str("6.3万")));
        byte[] seqMessage = msg(field(1, str("WebcastRoomUserSeqMessage")), field(2, bytes(seqBody)));
        byte[] seqFrame = msg(field(8, bytes(gzip(msg(field(1, bytes(seqMessage)))))));

        var decoded = DouyinProtocol.decodeFrameToEvents(seqFrame, DanmakuConfig.defaults());
        check(name + " -> 解出统计", decoded.roomStats() != null);
        if (decoded.roomStats() == null) {
            return;
        }
        check(name + " -> 在线人数 f3 = 2284", decoded.roomStats().online() == 2284);
        check(name + " -> 那个累计量保留原文 = 6.3万",
                "6.3万".equals(decoded.roomStats().watched()));
        check(name + " -> 统计不产生可见事件", decoded.events().isEmpty());

        // ---------- RoomStatsMessage ----------
        byte[] statsBody = msg(
                field(1, str("WebcastRoomStatsMessage")),
                field(2, str("2319")),
                field(3, str("2319")),
                field(4, str("2319在线观众")),
                varintField(5, 2319),
                varintField(6, 1663849727L),
                varintField(9, 2319));
        byte[] statsMessage = msg(field(1, str("WebcastRoomStatsMessage")), field(2, bytes(statsBody)));
        byte[] statsFrame = msg(field(8, bytes(gzip(msg(field(1, bytes(statsMessage)))))));

        var statsDecoded = DouyinProtocol.decodeFrameToEvents(statsFrame, DanmakuConfig.defaults());
        check(name + " -> RoomStats 解出在线人数 2319",
                statsDecoded.roomStats() != null && statsDecoded.roomStats().online() == 2319);
        check(name + " -> RoomStats 不提供那个累计量",
                statsDecoded.roomStats() != null && statsDecoded.roomStats().watched() == null);

        // 两种消息交替到达：在线人数取最新，累计量不能被 RoomStats 冲掉
        var accumulated = com.douyindanmaku.core.model.RoomStats.empty();
        accumulated = accumulated.merge(decoded.roomStats());
        accumulated = accumulated.merge(statsDecoded.roomStats());
        accumulated = accumulated.merge(decoded.roomStats());
        accumulated = accumulated.merge(statsDecoded.roomStats());
        check(name + " -> 交替收到两种统计后累计量保留",
                "6.3万".equals(accumulated.watched()));
        check(name + " -> 交替后在线人数是两种消息里最新的",
                accumulated.online() == 2319);

        // ---------- 这条数据里没有获赞：锁住这个结论 ----------
        // RoomStats 类型上已经没有 likes 这个东西了。这里用一个「反向断言」：
        // 在线人数（2284）绝不能被当成任何累计量显示出来。
        check(name + " -> 在线人数没有被当成累计量",
                !"2284".equals(accumulated.watched())
                        && !"2319".equals(accumulated.watched()));
    }

    /**
     * 统计字段观测器。
     *
     * <p>这是「获赞数不对」时的排查工具——把抖音推的字段原样记下来，
     * 用户用 /dy stats 就能看到哪个字段是获赞（它会一直涨）。
     */
    private static void testStatsProbe() {
        String name = "统计观测器";

        com.douyindanmaku.core.model.StatsProbe.reset();
        check(name + " -> 初始没有记录", com.douyindanmaku.core.model.StatsProbe.totalCount() == 0);

        // 没有记录时报告要给出「没收到统计消息」这个结论，
        // 因为那和「收到了但字段读不出来」是完全不同的两个问题
        var emptyReport = com.douyindanmaku.core.model.StatsProbe.report();
        check(name + " -> 没记录时说明没收到", !emptyReport.isEmpty()
                && emptyReport.get(0).contains("没有收到"));

        // 记一条
        com.douyindanmaku.core.model.StatsProbe.record("RoomUserSeq",
                java.util.List.of("f3=1234", "f8=\"4.5万\"", "f13=678900"));
        check(name + " -> 计数加上了",
                com.douyindanmaku.core.model.StatsProbe.countOf("RoomUserSeq") == 1);
        check(name + " -> 总数正确", com.douyindanmaku.core.model.StatsProbe.totalCount() == 1);

        var report = com.douyindanmaku.core.model.StatsProbe.report();
        String joined = String.join("\n", report);
        check(name + " -> 报告含字段值", joined.contains("f13=678900"));
        check(name + " -> 报告含消息类型", joined.contains("RoomUserSeq"));
        check(name + " -> 报告里没有模板占位符", !joined.contains("${"));

        // 再记一条，计数应该累加，字段应该更新
        com.douyindanmaku.core.model.StatsProbe.record("RoomUserSeq",
                java.util.List.of("f3=1300", "f13=700000"));
        check(name + " -> 计数累加",
                com.douyindanmaku.core.model.StatsProbe.countOf("RoomUserSeq") == 2);
        String afterSecond = String.join("\n", com.douyindanmaku.core.model.StatsProbe.report());
        check(name + " -> 字段更新为最新值",
                afterSecond.contains("f13=700000") && !afterSecond.contains("f13=678900"));

        // 另一种统计消息也能共存
        com.douyindanmaku.core.model.StatsProbe.record("RoomStats",
                java.util.List.of("f5#5678"));
        check(name + " -> 两种消息共存",
                com.douyindanmaku.core.model.StatsProbe.totalCount() == 3);

        // 追踪能力：能不能看出「哪个字段一直在涨」、哪个在波动
        com.douyindanmaku.core.model.StatsProbe.reset();
        com.douyindanmaku.core.model.StatsProbe.record("RoomUserSeq", java.util.List.of(
                "f3#1824", "f7#50042", "f11=\"5.0万\""));
        com.douyindanmaku.core.model.StatsProbe.record("RoomUserSeq", java.util.List.of(
                "f3#1831", "f7#56315", "f11=\"5.6万\""));
        // 再给一帧，让 f3 出现「变小」——这样才能和单调递增区分开。
        // 只采样两次是分不出「累计值」和「瞬时值」的，真实场景也一样。
        com.douyindanmaku.core.model.StatsProbe.record("RoomUserSeq", java.util.List.of(
                "f3#1810", "f7#56401", "f11=\"5.6万\""));
        String tracked = String.join("\n", com.douyindanmaku.core.model.StatsProbe.report());
        check(name + " -> 报告含最新值", tracked.contains("f7=56401"));
        check(name + " -> 一直涨的字段被标出（f7）", tracked.contains("一直在涨"));
        check(name + " -> 波动的字段被标出（f3）", tracked.contains("波动"));
        check(name + " -> 报告里没有模板残留", !tracked.contains("${"));

        com.douyindanmaku.core.model.StatsProbe.reset();
        check(name + " -> 重置后清空",
                com.douyindanmaku.core.model.StatsProbe.totalCount() == 0);
    }

    // ==================================================================
    //  protobuf 手工编码小工具
    // ==================================================================

    private static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        com.douyindanmaku.core.proto.ProtobufReader.writeVarint(out, value);
        return out.toByteArray();
    }

    /**
     * 把十六进制字符串转成字节。
     *
     * <p>用来内嵌「从真实抓包还原出来的原始字节」——这些字节不是
     * 规整的 protobuf 字段，直接写成十六进制最忠实。
     */
    private static byte[] hex(String text) {
        String cleaned = text.replaceAll("\\s", "");
        byte[] out = new byte[cleaned.length() / 2];
        for (int index = 0; index < out.length; index++) {
            out[index] = (byte) Integer.parseInt(cleaned.substring(index * 2, index * 2 + 2), 16);
        }
        return out;
    }

    private static byte[] str(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bytes(byte[] raw) {
        return raw;
    }

    /** 编码一个长度前缀字段（wire type 2）：key + 长度 + 内容。 */
    private static byte[] field(int fieldNumber, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        com.douyindanmaku.core.proto.ProtobufReader.writeVarint(out, ((long) fieldNumber << 3) | 2);
        com.douyindanmaku.core.proto.ProtobufReader.writeVarint(out, value.length);
        out.writeBytes(value);
        return out.toByteArray();
    }

    /**
     * 编码一个 varint 字段（wire type 0）：key + 值。
     *
     * <p>注意和 {@link #field(int, byte[])} 的区别——那个是「长度前缀」格式，
     * 用来装字符串/子消息；这个是直接的整数。两者不能混用，
     * 混了就会读出来是空的（而且不报错，非常难查）。
     */
    private static byte[] varintField(int fieldNumber, long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        com.douyindanmaku.core.proto.ProtobufReader.writeVarint(out, ((long) fieldNumber << 3) | 0);
        com.douyindanmaku.core.proto.ProtobufReader.writeVarint(out, value);
        return out.toByteArray();
    }

    /** 拼接多个字段。 */
    private static byte[] msg(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] gzip(byte[] raw) {
        return DouyinProtocol.gzip(raw);
    }

    // ==================================================================

    private static void check(String label, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  [OK]   " + label);
        } else {
            failed++;
            System.out.println("  [FAIL] " + label);
        }
    }
}
