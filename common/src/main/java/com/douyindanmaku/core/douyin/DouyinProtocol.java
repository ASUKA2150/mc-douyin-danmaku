package com.douyindanmaku.core.douyin;

import com.douyindanmaku.core.config.DanmakuConfig;
import com.douyindanmaku.core.model.DanmakuEvent;
import com.douyindanmaku.core.model.DanmakuMessage;
import com.douyindanmaku.core.model.RoomStats;
import com.douyindanmaku.core.model.StatsProbe;
import com.douyindanmaku.core.proto.ProtobufReader;
import com.douyindanmaku.core.text.NumberText;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * 抖音弹幕协议的编解码。
 *
 * <h2>协议分层（这是理解本文件的关键）</h2>
 * <pre>
 *   WebSocket 二进制帧
 *     └─ PushFrame            （最外层信封）
 *          f2 = log_id        （回 ack 时要原样带回）
 *          f8 = payload       ← 这块是 gzip 压缩的！
 *               └─ 解压后 = Response
 *                    f1 = messages[]   （一帧可以装很多条消息）
 *                    f5 = internal_ext （回 ack 时要原样带回）
 *                    f9 = need_ack     （true 就必须回 ack，否则服务端会断）
 *                         └─ Message
 *                              f1 = method  （字符串，决定 payload 是什么类型）
 *                              f2 = payload
 *                                   └─ method = "WebcastChatMessage" 时是 ChatMessage
 *                                        f2 = user    （用户信息）
 *                                        f3 = content （弹幕正文）
 *                                             └─ User
 *                                                  f3  = nick_name  （昵称）
 *                                                  f23 = pay_grade  （抖音等级）
 *                                                  f24 = fans_club  （粉丝团）
 * </pre>
 *
 * <h2>字段号是可以覆盖的</h2>
 * 抖音偶尔会调整字段号。调整后不会报错，只是某些字段读出来是空的——
 * 这类问题最难排查。所以所有关键字段号都留了配置项（{@code *Override}），
 * 出问题时改配置文件即可，不用重新编译模组。
 */
public final class DouyinProtocol {

    // ------------------------------------------------------------------
    //  字段号常量
    //  标注「默认」的都可以被配置文件里的 Override 覆盖。
    // ------------------------------------------------------------------

    /** PushFrame：log_id，回 ack 时必须原样带回。 */
    public static final int PUSH_FRAME_LOG_ID = 2;
    /** PushFrame：payload（gzip 压缩的 Response 字节）。 */
    public static final int PUSH_FRAME_PAYLOAD = 8;
    /** PushFrame：payload_type，回 ack / 心跳时用。 */
    public static final int PUSH_FRAME_PAYLOAD_TYPE = 7;

    /** Response：messages，重复字段，一帧可含多条。 */
    public static final int RESPONSE_MESSAGES = 1;
    /** Response：internal_ext，回 ack 时必须原样带回。 */
    public static final int RESPONSE_INTERNAL_EXT = 5;
    /** Response：need_ack，为 true 时必须回 ack。 */
    public static final int RESPONSE_NEED_ACK = 9;
    /** Response：heartbeat_duration，服务端建议的心跳间隔（毫秒）。 */
    public static final int RESPONSE_HEARTBEAT_DURATION = 8;

    /** Message：method，消息类型名字符串。 */
    public static final int MESSAGE_METHOD = 1;
    /** Message：payload，消息体。 */
    public static final int MESSAGE_PAYLOAD = 2;

    /** ChatMessage：user。 */
    public static final int CHAT_USER = 2;
    /** ChatMessage：content，弹幕正文（默认字段号）。 */
    public static final int CHAT_CONTENT = 3;

    /** User：nick_name，昵称（默认字段号）。 */
    public static final int USER_NICK_NAME = 3;
    /** User：level。注意实测这个字段恒为 0，不能用来当等级。 */
    public static final int USER_LEVEL = 6;
    /** User：pay_grade，抖音消费等级（等级默认字段号）。 */
    public static final int USER_PAY_GRADE = 23;
    /** User：fans_club，粉丝团。 */
    public static final int USER_FANS_CLUB = 24;

    /** PayGrade：level，等级数字。 */
    public static final int PAY_GRADE_LEVEL = 6;
    /** PayGrade：new_im_icon_with_level，等级图标（等级数字有时只能从这里抠）。 */
    public static final int PAY_GRADE_ICON = 19;

    /** FansClub：data，粉丝团数据。 */
    public static final int FANS_CLUB_DATA = 1;
    /** FansClubData：club_name。 */
    public static final int FANS_CLUB_NAME = 1;
    /** FansClubData：level，粉丝团等级（默认字段号）。 */
    public static final int FANS_CLUB_LEVEL = 2;

    /** Image：url_list，图片地址列表，取第一个。 */
    public static final int IMAGE_URL_LIST = 1;

    /** 弹幕消息的 method 值。 */
    public static final String METHOD_CHAT = "WebcastChatMessage";

    /** 礼物消息的 method 值。 */
    public static final String METHOD_GIFT = "WebcastGiftMessage";

    /** 点赞消息的 method 值。 */
    public static final String METHOD_LIKE = "WebcastLikeMessage";

    /** 进入直播间消息的 method 值。 */
    public static final String METHOD_MEMBER = "WebcastMemberMessage";

    /** 直播间统计消息的 method 值（在线人数、累计观看、累计获赞）。 */
    public static final String METHOD_ROOM_USER_SEQ = "WebcastRoomUserSeqMessage";

    /** 直播间统计消息的另一个 method 值（部分房间推这个）。 */
    public static final String METHOD_ROOM_STATS = "WebcastRoomStatsMessage";

    // ------------------------------------------------------------------
    //  另一种统计消息（RoomStatsMessage）的字段号
    //  和 RoomUserSeqMessage 是不一样的两条消息，字段号也不同。
    // ------------------------------------------------------------------

    /** RoomStatsMessage：display_short，简短显示文本。 */
    public static final int ROOM_STATS_DISPLAY_SHORT = 2;
    /** RoomStatsMessage：display_middle，中等长度显示文本。 */
    public static final int ROOM_STATS_DISPLAY_MIDDLE = 3;
    /** RoomStatsMessage：display_long，完整显示文本。 */
    public static final int ROOM_STATS_DISPLAY_LONG = 4;
    /** RoomStatsMessage：display_value，在线人数（数值）。 */
    public static final int ROOM_STATS_DISPLAY_VALUE = 5;

    // ------------------------------------------------------------------
    //  礼物消息（GiftMessage）的字段号
    //  注意礼物消息里「用户」在 f7，和弹幕消息的 f2 不一样，很容易搞错。
    // ------------------------------------------------------------------

    /** GiftMessage：user（注意是 7，不是 2）。 */
    public static final int GIFT_USER = 7;
    /** GiftMessage：repeat_count，这次送了几个。 */
    public static final int GIFT_REPEAT_COUNT = 5;
    /** GiftMessage：combo_count，连击数。 */
    public static final int GIFT_COMBO_COUNT = 6;
    /** GiftMessage：repeat_end，连击是否结束。 */
    public static final int GIFT_REPEAT_END = 9;
    /** GiftMessage：gift，礼物详情子消息。 */
    public static final int GIFT_DETAIL = 15;

    /** GiftStruct：name，礼物名（注意是 16，不是 1）。 */
    public static final int GIFT_STRUCT_NAME = 16;
    /** GiftStruct：diamond_count，单价（抖币）。 */
    public static final int GIFT_STRUCT_DIAMOND = 12;
    /** GiftStruct：describe，礼物描述（礼物名的兜底）。 */
    public static final int GIFT_STRUCT_DESCRIBE = 2;

    // ------------------------------------------------------------------
    //  点赞 / 进房消息的字段号
    // ------------------------------------------------------------------

    /** LikeMessage：count，本次点赞增量（<b>不是总数</b>）。 */
    public static final int LIKE_COUNT = 2;
    /** LikeMessage：total，累计总数。抖音实测不推，留作兜底。 */
    public static final int LIKE_TOTAL = 3;
    /** LikeMessage：user。 */
    public static final int LIKE_USER = 5;

    /** MemberMessage：user。 */
    public static final int MEMBER_USER = 2;
    /** MemberMessage：member_count，当前直播间人数。 */
    public static final int MEMBER_COUNT = 3;

    // ------------------------------------------------------------------
    //  直播间统计消息（RoomUserSeqMessage）的字段号
    //
    //  这是「累计获赞」的来源——点赞增量消息只给「刚才点了几下」，
    //  想要面板上那个真实数字必须从这里取。
    //
    //  【以下字段号是实测抓出来的，不是照社区资料猜的】
    //  实测样本（一个真实直播间）：
    //    f3=1941  f7=41796  f8="4万+"  f9="1941"  f10="1941"  f11="4.2万"
    //  对照抖音面板可以确认：
    //    f3  = 当前在线人数
    //    f7  = 累计观看（数值）
    //    f8  = 累计观看（服务端格式化文本，如「4万+」）
    //    f9  = 累计获赞（数值）
    //    f11 = 累计获赞（服务端格式化文本，如「4.2万」）← 显示优先用它
    //
    //  注意：早期版本按社区资料读了 f13/f14，那两个字段实际不存在，
    //  导致获赞永远是 0、只能退回自己累加（表现为「从开启才开始计数」）。
    // ------------------------------------------------------------------

    /** RoomUserSeqMessage：total，当前在线人数。 */
    public static final int ROOM_SEQ_TOTAL = 3;
    /** RoomUserSeqMessage：累计观看人数（数值）。 */
    public static final int ROOM_SEQ_TOTAL_USER = 7;
    /** RoomUserSeqMessage：累计观看（服务端格式化文本，如「4万+」）。 */
    public static final int ROOM_SEQ_TOTAL_USER_STR = 8;
    /** RoomUserSeqMessage：在线人数的文本形式。 */
    public static final int ROOM_SEQ_TOTAL_STR = 9;

    /**
     * RoomUserSeqMessage 里那个<b>含义不确定</b>的累计量。
     *
     * <p>实测样本 {@code f7/f8/f11 = 63133 / "6万+" / "6.3万"}，
     * 而当时直播间面板上的获赞是 4 万——对不上，所以它<b>不是获赞</b>。
     * 抖音没给标签。这里只把它当作「一个累计量」保留，不声称它是什么。
     *
     * <p>曾经把它当成累计获赞显示，结果是稳定错误的数字（面板 4 万、
     * 模组显示 6.3 万）。所以现在只在 {@code %room.watched%} 里保留原文，
     * 默认模板不用它。
     */
    public static final int ROOM_SEQ_WATCHED_STR = 11;

    /**
     * 从等级图标 URL 里抠等级数字。
     *
     * <p>为什么需要这个：抖音的 {@code PayGrade.level} 经常是 0，
     * 真正的等级只体现在图标文件名里（形如 {@code new_user_grade_level_v1_12.png}）。
     */
    private static final Pattern GRADE_IN_ICON = Pattern.compile("level_v1_(\\d+)");

    private DouyinProtocol() {
    }

    // ==================================================================
    //  解码方向：网络字节 -> 我们自己的对象
    // ==================================================================

    /**
     * 一个 WebSocket 帧解出来的结果。
     *
     * @param danmakuList 这一帧里的所有弹幕
     * @param needAck     是否需要回 ack
     * @param logId       回 ack 时要带的 log_id
     * @param internalExt 回 ack 时要带的 internal_ext
     * @param methodNames 这一帧出现过的所有 method 名字（排查协议变动时非常有用）
     */
    public record DecodedFrame(
            List<DanmakuMessage> danmakuList,
            boolean needAck,
            long logId,
            String internalExt,
            List<String> methodNames
    ) {
    }

    /**
     * 解一个 WebSocket 二进制帧。
     *
     * <p>整个过程不允许抛异常：网络数据不可信，任何一步失败都只是「这一帧没解析出东西」，
     * 不应该把连接搞断。返回的 {@code methodNames} 会把见到的消息类型都列出来，
     * 排查问题时看一眼日志就知道抖音是不是换了协议。
     *
     * @param frame     WebSocket 收到的二进制数据
     * @param config    配置（用于读取字段号覆盖）
     * @param logMethod 可选的日志回调，用来记录没见过的消息类型
     */
    public static DecodedFrame decodeFrame(byte[] frame, DanmakuConfig config, java.util.function.Consumer<String> logMethod) {
        List<DanmakuMessage> danmakuList = new ArrayList<>();
        List<String> methodNames = new ArrayList<>();

        ProtobufReader.FieldSet pushFrame = ProtobufReader.parse(frame);
        long logId = pushFrame.getLong(PUSH_FRAME_LOG_ID, 0L);
        byte[] payload = pushFrame.getBytes(PUSH_FRAME_PAYLOAD, null);
        if (payload == null || payload.length == 0) {
            // 心跳 / ack 之类的空帧，没有内容可解
            return new DecodedFrame(danmakuList, false, logId, "", methodNames);
        }

        byte[] responseBytes = gunzip(payload);
        if (responseBytes == null) {
            return new DecodedFrame(danmakuList, false, logId, "", methodNames);
        }

        ProtobufReader.FieldSet response = ProtobufReader.parse(responseBytes);
        boolean needAck = response.getLong(RESPONSE_NEED_ACK, 0L) != 0L;
        String internalExt = response.getString(RESPONSE_INTERNAL_EXT, "");

        for (ProtobufReader.FieldSet message : response.getMessages(RESPONSE_MESSAGES)) {
            String method = message.getString(MESSAGE_METHOD, "");
            if (method.isEmpty()) {
                continue;
            }
            methodNames.add(method);

            if (METHOD_CHAT.equals(method)) {
                byte[] body = message.getMessageBytes(MESSAGE_PAYLOAD);
                if (body == null) {
                    continue;
                }
                DanmakuMessage danmaku = decodeChatMessage(body, config);
                if (danmaku != null) {
                    danmakuList.add(danmaku);
                }
            } else if (logMethod != null) {
                logMethod.accept(method);
            }
        }

        return new DecodedFrame(danmakuList, needAck, logId, internalExt, methodNames);
    }

    // ==================================================================
    //  完整事件解码（弹幕 + 礼物 + 点赞 + 进房）
    // ==================================================================

    /**
     * 一个帧解出来的完整结果。
     *
     * @param events      这一帧里的所有事件（已经按类型解好）
     * @param roomStats   这一帧里的直播间统计（在线人数、累计获赞等）；没有则是 {@code null}
     * @param needAck     是否需要回 ack
     * @param logId       回 ack 时要带的 log_id
     * @param internalExt 回 ack 时要带的 internal_ext
     * @param methodNames 这一帧出现过的所有 method 名字
     * @param unhandledMethods 出现了但我们没处理的 method 名字
     */
    public record DecodedEvents(
            List<DanmakuEvent> events,
            RoomStats roomStats,
            boolean needAck,
            long logId,
            String internalExt,
            List<String> methodNames,
            List<String> unhandledMethods
    ) {
    }

    /**
     * 解一个帧，把弹幕 / 礼物 / 点赞 / 进房都解出来。
     *
     * <p>整个过程不允许抛异常：网络数据不可信，任何一步失败都只是
     * 「这一帧没解析出东西」，不应该把连接搞断。
     *
     * <p>{@code unhandledMethods} 会列出现在出现、但我们没处理的类型。
     * 排查「礼物没显示」这类问题时，先看这个列表——
     * 如果 {@code WebcastGiftMessage} 在里面，说明是解码字段号的问题；
     * 如果压根不在，说明这一帧就没有礼物消息。
     */
    public static DecodedEvents decodeFrameToEvents(byte[] frame, DanmakuConfig config) {
        List<DanmakuEvent> events = new ArrayList<>();
        List<String> methodNames = new ArrayList<>();
        List<String> unhandled = new ArrayList<>();
        RoomStats roomStats = null;

        ProtobufReader.FieldSet pushFrame = ProtobufReader.parse(frame);
        long logId = pushFrame.getLong(PUSH_FRAME_LOG_ID, 0L);
        byte[] payload = pushFrame.getBytes(PUSH_FRAME_PAYLOAD, null);
        if (payload == null || payload.length == 0) {
            return new DecodedEvents(events, null, false, logId, "", methodNames, unhandled);
        }

        byte[] responseBytes = gunzip(payload);
        if (responseBytes == null) {
            return new DecodedEvents(events, null, false, logId, "", methodNames, unhandled);
        }

        ProtobufReader.FieldSet response = ProtobufReader.parse(responseBytes);
        boolean needAck = response.getLong(RESPONSE_NEED_ACK, 0L) != 0L;
        String internalExt = response.getString(RESPONSE_INTERNAL_EXT, "");

        for (ProtobufReader.FieldSet message : response.getMessages(RESPONSE_MESSAGES)) {
            String method = message.getString(MESSAGE_METHOD, "");
            if (method.isEmpty()) {
                continue;
            }
            methodNames.add(method);

            byte[] body = message.getMessageBytes(MESSAGE_PAYLOAD);
            if (body == null) {
                continue;
            }

            // 统计类消息单独处理：它不是「事件」，而是房间状态，
            // 用来刷新在线人数和累计获赞。
            if (METHOD_ROOM_USER_SEQ.equals(method)) {
                roomStats = (roomStats == null ? RoomStats.empty() : roomStats)
                        .merge(decodeRoomUserSeq(body));
                continue;
            }
            if (METHOD_ROOM_STATS.equals(method)) {
                roomStats = (roomStats == null ? RoomStats.empty() : roomStats)
                        .merge(decodeRoomStats(body));
                continue;
            }

            DanmakuEvent event = switch (method) {
                case METHOD_CHAT -> decodeChatEvent(body, config);
                case METHOD_GIFT -> decodeGiftEvent(body, config);
                case METHOD_LIKE -> decodeLikeEvent(body, config);
                case METHOD_MEMBER -> decodeMemberEvent(body, config);
                default -> null;
            };

            if (event != null) {
                events.add(event);
            } else if (METHOD_CHAT.equals(method)) {
                // 聊天消息解不出来——这是「弹幕不显示」最常见的原因，
                // 而且以前是静默失败的，排查时完全看不到线索。
                logChatDecodeFailure(body, config);
            } else if (!isKnownMethod(method)) {
                // 没见过的类型记下来，方便发现抖音上了新消息
                unhandled.add(method);
            }
        }

        return new DecodedEvents(events, roomStats, needAck, logId, internalExt, methodNames, unhandled);
    }

    /** 是不是我们已经支持的 method（含统计类）。 */
    public static boolean isKnownMethod(String method) {
        return METHOD_CHAT.equals(method) || METHOD_GIFT.equals(method)
                || METHOD_LIKE.equals(method) || METHOD_MEMBER.equals(method)
                || METHOD_ROOM_USER_SEQ.equals(method) || METHOD_ROOM_STATS.equals(method);
    }

    /**
     * 聊天消息解不出来时记一份诊断（只记一次）。
     *
     * <p>为什么要专门记：聊天弹幕是核心功能，它解不出来时用户看到的是
     * 「什么都不显示」，而代码里原来是<b>静默返回 null</b> 的——
     * 日志里一条线索都没有，只能靠猜。
     *
     * <p>这里把两个最可能的原因分别点出来：
     * <ul>
     *   <li>正文字段读出来是空的 → 抖音改了正文字段号</li>
     *   <li>找不到 User 子消息 → 抖音改了 user 字段号</li>
     * </ul>
     */
    private static final java.util.concurrent.atomic.AtomicBoolean chatFailureLogged =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static void logChatDecodeFailure(byte[] body, DanmakuConfig config) {
        if (!chatFailureLogged.compareAndSet(false, true)) {
            return;
        }
        ProtobufReader.FieldSet chat = ProtobufReader.parse(body);
        int contentField = override(config.chatContentOverride, CHAT_CONTENT);
        String content = chat.getString(contentField, "");
        ProtobufReader.FieldSet user = chat.getMessage(CHAT_USER);

        String reason;
        if (content.isBlank()) {
            reason = "正文字段（f" + contentField + "）读出来是空的";
        } else if (user == null) {
            reason = "找到了正文，但找不到 User 子消息（f" + CHAT_USER + "）";
        } else {
            reason = "未知原因";
        }

        logStatsFields("ChatMessage(解码失败)", chat);
        com.douyindanmaku.core.DanmakuLog.info(
                "聊天弹幕解码失败：" + reason
                        + "。这通常意味着抖音调整了字段号，"
                        + "可用配置文件里的 chatContentOverride / nickNameOverride 覆盖。"
                        + "（此提示只出现一次）");
    }

    /**
     * 解「直播间序号统计」消息——<b>累计获赞的来源</b>。
     *
     * <p>字段号已用真实样本实测确认，见类顶部的注释。
     */
    private static RoomStats decodeRoomUserSeq(byte[] body) {
        ProtobufReader.FieldSet stats = ProtobufReader.parse(body);

        long online = stats.getLong(ROOM_SEQ_TOTAL, 0L);
        if (online <= 0) {
            online = NumberText.parse(stats.getString(ROOM_SEQ_TOTAL_STR, ""));
        }

        // 保留抖音给的原文，不要自己换算一遍——服务端的格式化可能和我们的
        // 精度不一样，显示出来会对不上。
        String watched = readStatText(stats, ROOM_SEQ_WATCHED_STR, ROOM_SEQ_TOTAL_USER);

        List<String> dumped = logStatsFields("RoomUserSeq", stats);
        StatsProbe.record("RoomUserSeq", dumped);
        return new RoomStats(online, watched);
    }

    /**
     * 解另一种统计消息（{@code RoomStatsMessage}）。
     *
     * <p><b>这条消息里没有累计获赞</b>——只有在线人数。
     *
     * <p>曾经从这里也读获赞（按候选字段号猜 f9/f10），结果实测发现
     * {@code f9} 是在线人数：两种消息交替到达时，获赞数会在
     * 「5.6万」和「1998」之间反复横跳，日志里看得清清楚楚。
     * 现在这里一律不提供获赞，让 {@code RoomUserSeqMessage} 独占这个字段。
     */
    private static RoomStats decodeRoomStats(byte[] body) {
        ProtobufReader.FieldSet stats = ProtobufReader.parse(body);

        long online = stats.getLong(ROOM_STATS_DISPLAY_VALUE, 0L);
        if (online <= 0) {
            online = NumberText.parse(stats.getString(ROOM_STATS_DISPLAY_SHORT, ""));
        }

        List<String> dumped = logStatsFields("RoomStats", stats);
        StatsProbe.record("RoomStats", dumped);
        // 这条消息只有在线人数
        return new RoomStats(online, null);
    }

    /**
     * 读一个「可能是数字、也可能是格式化文本」的统计值。
     *
     * <p>返回的是<b>文本</b>而不是数字，因为显示时直接用它更准——
     * 抖音给的「4.2万」比自己换算出来的「4.2万」更可信
     * （我们的精度只有一位小数，服务端的可能不一样）。
     *
     * <p>取值优先用<b>文本字段</b>：文本字段的语义没有歧义，
     * 而数值字段有时和别的指标撞号。
     *
     * @param textField    优先读的文本字段号；传 0 表示没有
     * @param numberFields 候选的数值字段号（按顺序试）
     * @return 可以显示的文本；都取不到返回 {@code null}
     */
    private static String readStatText(ProtobufReader.FieldSet stats, int textField, int... numberFields) {
        if (textField > 0) {
            String text = stats.getString(textField, null);
            if (text != null && !text.isBlank()) {
                return text.trim();
            }
        }
        for (int field : numberFields) {
            // 先当数值读
            long number = stats.getLong(field, 0L);
            if (number > 0) {
                return NumberText.format(number);
            }
            // 再当文本读（有些版本同一字段给的是字符串）
            String parsed = stats.getString(field, null);
            if (parsed != null && !parsed.isBlank()) {
                long value = NumberText.parse(parsed);
                if (value > 0) {
                    return parsed.trim();
                }
            }
        }
        return null;
    }

    /**
     * 把统计消息里所有字段记进日志（每种消息只记一次），并返回给观测器。
     *
     * <p>为什么需要：抖音的统计字段号调整过好几次，社区资料互相矛盾。
     * 万一获赞取不到，这份字段清单就能直接看出真正的字段号在哪，
     * 不用让用户去抓包。
     */
    private static final java.util.Set<String> loggedStatsKinds =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 把统计消息里所有字段记进日志和观测器（每种消息的日志只记一次）。
     *
     * <p><b>输出格式对观测器很重要</b>：
     * <ul>
     *   <li>{@code f7#56315} —— 既能当数字读、又能当文本读的字段。
     *       观测器靠这个跟踪数值变化，判断哪个字段「一直在涨」。</li>
     *   <li>{@code f8="5万+"} —— 只能当文本读的字段。</li>
     *   <li>{@code f2=(子消息)} —— 解不出内容的嵌套结构。</li>
     * </ul>
     *
     * <p>为什么要区分「能当数字读」：protobuf 不记录字段类型，
     * 而 varint 和「短字符串」的字节序列经常无法区分。所以同一个字段
     * 我们两种都试一遍，把能读出来的都给出去，让判断留给观测器。
     */
    private static List<String> logStatsFields(String kind, ProtobufReader.FieldSet stats) {
        List<String> dumped = new ArrayList<>(24);
        for (int field = 1; field <= 40; field++) {
            if (!stats.has(field)) {
                continue;
            }
            long number = stats.getLong(field, Long.MIN_VALUE);
            String text = stats.getString(field, null);

            if (number != Long.MIN_VALUE) {
                // 数值可读。如果文本形式也不为空且和数字不一样，两个都报
                dumped.add("f" + field + "#" + number);
                if (text != null && !text.isBlank() && !text.equals(String.valueOf(number))) {
                    dumped.add("f" + field + "=\"" + sanitizeForLog(text) + "\"");
                }
            } else if (text != null) {
                // 只能当文本读。但如果它本身长得像数字，也报一份数值形式，
                // 否则观测器没法跟踪它的变化。
                String trimmed = text.trim();
                if (isNumericText(trimmed)) {
                    dumped.add("f" + field + "#" + trimmed);
                } else {
                    dumped.add("f" + field + "=\"" + sanitizeForLog(trimmed) + "\"");
                }
            } else {
                dumped.add("f" + field + "=(子消息)");
            }
        }

        if (loggedStatsKinds.add(kind)) {
            // 用 join 而不是直接拼，避免字段值里的换行把日志切成好几行
            // （实测踩过：f1 里带换行，字段清单被截断成两行读不出来）
            com.douyindanmaku.core.DanmakuLog.info(
                    "统计消息字段清单（" + kind + "）：" + String.join(" ", dumped));
        }
        return dumped;
    }

    /** 文本是不是纯数字（可能带千分位）。 */
    private static boolean isNumericText(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (!Character.isDigit(character) && character != ',') {
                return false;
            }
        }
        return true;
    }

    /**
     * 把一段文本处理成「能安全放进单行日志」的样子。
     *
     * <p>为什么要这么做：字段里可能直接是<b>另一个 protobuf 子消息的原始字节</b>
     * （我们把它当字符串读了），里面会含换行、控制字符、无效 UTF-8。
     * 原样写进日志会把一行切成好几行、还会出现替换字符，
     * 结果就是最关键的字段清单反而读不出来——实测踩过这个坑。
     */
    private static String sanitizeForLog(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(text.length(), 40));
        int limit = Math.min(text.length(), 40);
        for (int index = 0; index < limit; index++) {
            char character = text.charAt(index);
            if (character == '\n') {
                out.append("\\n");
            } else if (character == '\r') {
                out.append("\\r");
            } else if (character == '\t') {
                out.append("\\t");
            } else if (character < 0x20 || character == 0xFFFD) {
                // 控制字符和「无法解码」的替换字符都用一个点代替
                out.append('.');
            } else {
                out.append(character);
            }
        }
        if (text.length() > limit) {
            out.append("…");
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    //  各种事件的具体解码
    // ------------------------------------------------------------------

    /** 解一条聊天弹幕。 */
    private static DanmakuEvent decodeChatEvent(byte[] body, DanmakuConfig config) {
        DanmakuMessage danmaku = decodeChatMessage(body, config);
        return danmaku == null ? null : DanmakuEvent.chat(danmaku);
    }

    /**
     * 解一条礼物消息。
     *
     * <p>字段号踩坑提醒：礼物消息里「用户」在 <b>f7</b>，礼物名在
     * {@code gift(f15)} 下面的 <b>f16</b>——这两个都和弹幕消息不一样，
     * 对着弹幕的字段号写会解出空值（而且不报错）。
     */
    private static DanmakuEvent decodeGiftEvent(byte[] body, DanmakuConfig config) {
        ProtobufReader.FieldSet giftMessage = ProtobufReader.parse(body);

        ProtobufReader.FieldSet user = giftMessage.getMessage(GIFT_USER);
        UserInfo userInfo = readUser(user, config);

        ProtobufReader.FieldSet detail = giftMessage.getMessage(GIFT_DETAIL);
        String giftName = "";
        int diamondCount = 0;
        if (detail != null) {
            giftName = detail.getString(GIFT_STRUCT_NAME, "");
            if (giftName.isBlank()) {
                // 有些礼物没有 name，退回用描述
                giftName = detail.getString(GIFT_STRUCT_DESCRIBE, "");
            }
            diamondCount = detail.getInt(GIFT_STRUCT_DIAMOND, 0);
        }

        int repeatCount = giftMessage.getInt(GIFT_REPEAT_COUNT, 1);
        int comboCount = giftMessage.getInt(GIFT_COMBO_COUNT, 1);
        boolean repeatEnd = giftMessage.getLong(GIFT_REPEAT_END, 0L) != 0L;

        // 礼物名和数量都没有，说明这不是一条有效礼物消息（可能是统计帧）
        if (giftName.isBlank() && repeatCount <= 0) {
            return null;
        }

        return new DanmakuEvent(DanmakuEvent.Kind.GIFT, userInfo.nickname(),
                userInfo.userLevel(), userInfo.hasUserLevel(),
                userInfo.fanClubName(), userInfo.fanClubLevel(),
                "", new DanmakuEvent.Gift(giftName, repeatCount, comboCount, repeatEnd, diamondCount),
                null, null, System.currentTimeMillis());
    }

    /**
     * 解一条点赞消息。
     *
     * <p><b>注意</b>：抖音推的是增量。f2 是「这次点了几下」，
     * f3 名义上是累计总数，但实测服务端不填。这里两个都读，
     * 优先用增量，让上层自己去累加。
     */
    private static DanmakuEvent decodeLikeEvent(byte[] body, DanmakuConfig config) {
        ProtobufReader.FieldSet likeMessage = ProtobufReader.parse(body);

        long increment = likeMessage.getLong(LIKE_COUNT, 0L);
        if (increment <= 0) {
            // 增量没有时退回总数（虽然实测通常也是 0）
            increment = likeMessage.getLong(LIKE_TOTAL, 0L);
        }
        if (increment <= 0) {
            // 有些点赞帧只是统计信息，没有实际增量，丢掉
            return null;
        }

        UserInfo userInfo = readUser(likeMessage.getMessage(LIKE_USER), config);
        return new DanmakuEvent(DanmakuEvent.Kind.LIKE, userInfo.nickname(),
                userInfo.userLevel(), userInfo.hasUserLevel(),
                userInfo.fanClubName(), userInfo.fanClubLevel(),
                "", null, new DanmakuEvent.Like(increment), null, System.currentTimeMillis());
    }

    /** 解一条进房消息。 */
    private static DanmakuEvent decodeMemberEvent(byte[] body, DanmakuConfig config) {
        ProtobufReader.FieldSet memberMessage = ProtobufReader.parse(body);

        UserInfo userInfo = readUser(memberMessage.getMessage(MEMBER_USER), config);
        if (userInfo.nickname().isEmpty()) {
            // 没昵称的进房消息没有显示价值
            return null;
        }

        long memberCount = memberMessage.getLong(MEMBER_COUNT, 0L);
        return new DanmakuEvent(DanmakuEvent.Kind.MEMBER, userInfo.nickname(),
                userInfo.userLevel(), userInfo.hasUserLevel(),
                userInfo.fanClubName(), userInfo.fanClubLevel(),
                "", null, null, new DanmakuEvent.Member(memberCount), System.currentTimeMillis());
    }

    // ------------------------------------------------------------------
    //  用户信息的公共部分
    // ------------------------------------------------------------------

    /** 从 User 子消息里读出来的东西。 */
    private record UserInfo(String nickname, int userLevel, boolean hasUserLevel,
                            String fanClubName, int fanClubLevel) {
    }

    /**
     * 读 User 子消息里的昵称、消费等级、粉丝团。
     *
     * <p>弹幕、礼物、点赞、进房四种消息里的 User 结构是一样的，
     * 所以抽出来共用——以后抖音改字段号也只需要改这一处。
     */
    private static UserInfo readUser(ProtobufReader.FieldSet user, DanmakuConfig config) {
        if (user == null) {
            return new UserInfo("", 0, false, null, 0);
        }

        int nickField = override(config.nickNameOverride, USER_NICK_NAME);
        String nickname = user.getString(nickField, "");

        // ---- 消费等级 ----
        int userLevel = 0;
        boolean hasUserLevel = false;
        int payGradeField = override(config.userLevelOverride, USER_PAY_GRADE);
        ProtobufReader.FieldSet payGrade = user.getMessage(payGradeField);
        if (payGrade != null) {
            userLevel = payGrade.getInt(PAY_GRADE_LEVEL, 0);
            if (userLevel <= 0) {
                userLevel = readLevelFromIcon(payGrade, config.userLevelIconOverride);
            }
            hasUserLevel = userLevel > 0;
        }

        // ---- 粉丝团 ----
        String fanClubName = null;
        int fanClubLevel = 0;
        int fansClubField = override(config.fansClubOverride, USER_FANS_CLUB);
        ProtobufReader.FieldSet fansClub = user.getMessage(fansClubField);
        if (fansClub != null) {
            ProtobufReader.FieldSet data = fansClub.getMessage(FANS_CLUB_DATA);
            if (data != null) {
                fanClubName = data.getString(FANS_CLUB_NAME, null);
                fanClubLevel = data.getInt(override(config.fanClubLevelOverride, FANS_CLUB_LEVEL), 0);
            }
        }

        return new UserInfo(nickname, userLevel, hasUserLevel, fanClubName, fanClubLevel);
    }

    /**
     * 解一条具体的弹幕（ChatMessage）。解不出有效内容时返回 {@code null}。
     *
     * <p>用户信息（昵称/等级/粉丝团）走公共的 {@link #readUser}，
     * 和礼物、点赞、进房共用同一套字段号逻辑——以后抖音改字段号只改一处。
     */
    private static DanmakuMessage decodeChatMessage(byte[] body, DanmakuConfig config) {
        ProtobufReader.FieldSet chat = ProtobufReader.parse(body);

        int contentField = override(config.chatContentOverride, CHAT_CONTENT);
        String content = chat.getString(contentField, "");
        if (content.isBlank()) {
            return null;
        }

        ProtobufReader.FieldSet user = chat.getMessage(CHAT_USER);
        if (user == null) {
            return null;
        }

        UserInfo info = readUser(user, config);
        return new DanmakuMessage(info.nickname(), content, info.userLevel(), info.hasUserLevel(),
                info.fanClubName(), info.fanClubLevel(), System.currentTimeMillis());
    }

    /**
     * 从等级图标的 URL 里抠出等级数字。
     *
     * <p>图标 URL 形如 {@code .../new_user_grade_level_v1_12.png}，末尾的 12 就是等级。
     * 结构埋得比较深，所以直接在子树里搜「图片地址列表」这个特征字段。
     */
    private static int readLevelFromIcon(ProtobufReader.FieldSet payGrade, int iconFieldOverride) {
        int iconField = override(iconFieldOverride, PAY_GRADE_ICON);
        ProtobufReader.FieldSet icon = payGrade.getMessage(iconField);
        String url = icon == null ? null : icon.findDeepString(IMAGE_URL_LIST);
        if (url == null) {
            // 兜底：直接在整棵子树里找任何形如 level_v1_N 的字符串
            url = payGrade.findDeepString(IMAGE_URL_LIST);
        }
        if (url == null) {
            return 0;
        }
        Matcher matcher = GRADE_IN_ICON.matcher(url);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static int override(int configured, int defaultValue) {
        // 小于 0 表示「没配置，用默认值」。0 是合法的字段号，所以不能用 0 当哨兵值。
        return configured >= 0 ? configured : defaultValue;
    }

    /** gzip 解压。失败返回 {@code null}（不是所有帧都压缩过）。 */
    public static byte[] gunzip(byte[] compressed) {
        // 有些帧其实没压缩，这时直接返回原数据
        if (compressed.length < 2 || (compressed[0] != (byte) 0x1F) || (compressed[1] != (byte) 0x8B)) {
            return compressed;
        }
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, compressed.length * 3));
            gzip.transferTo(out);
            return out.toByteArray();
        } catch (IOException failed) {
            return null;
        }
    }

    /** gzip 压缩。心跳要发一个「压缩后的空内容」。 */
    public static byte[] gzip(byte[] raw) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(32, raw.length / 2));
            try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(out)) {
                gzip.write(raw);
            }
            return out.toByteArray();
        } catch (IOException impossible) {
            // 内存写入不会抛 IO 异常
            throw new IllegalStateException("gzip 压缩失败", impossible);
        }
    }

    // ==================================================================
    //  编码方向：我们要发给服务端的东西
    // ==================================================================

    /**
     * 构造 ack 帧。
     *
     * <p><b>不回 ack 会怎样</b>：服务端不会推进推送游标，表现为「每帧重复推同样的内容」，
     * 时间长了会直接断开。所以这个必须回。
     *
     * @param logId       收到的 PushFrame 的 log_id，原样带回
     * @param internalExt 收到的 Response 的 internal_ext，原样带回（不要压缩）
     */
    public static byte[] buildAck(long logId, String internalExt) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);

        // f2 = log_id (varint)
        ProtobufReader.writeVarintHeader(out, PUSH_FRAME_LOG_ID);
        ProtobufReader.writeVarint(out, logId);

        // f7 = "ack" (string)
        byte[] typeBytes = "ack".getBytes(StandardCharsets.UTF_8);
        ProtobufReader.writeLengthDelimitedHeader(out, PUSH_FRAME_PAYLOAD_TYPE, typeBytes.length);
        out.writeBytes(typeBytes);

        // f8 = internal_ext 的原始字节（注意：不压缩）
        byte[] extBytes = (internalExt == null ? "" : internalExt).getBytes(StandardCharsets.UTF_8);
        ProtobufReader.writeLengthDelimitedHeader(out, PUSH_FRAME_PAYLOAD, extBytes.length);
        out.writeBytes(extBytes);

        return out.toByteArray();
    }

    /**
     * 构造心跳帧。
     *
     * <p>心跳间隔太长会被服务端判定为掉线，太短是浪费。
     * 经验值是 5~10 秒，我们用 10 秒。
     */
    public static byte[] buildHeartbeat() {
        // f8 = gzip(空字节)
        byte[] payload = gzip(new byte[0]);
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 8);
        ProtobufReader.writeLengthDelimitedHeader(out, PUSH_FRAME_PAYLOAD, payload.length);
        out.writeBytes(payload);
        return out.toByteArray();
    }
}
