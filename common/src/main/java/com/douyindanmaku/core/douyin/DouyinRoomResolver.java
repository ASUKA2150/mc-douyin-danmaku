package com.douyindanmaku.core.douyin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把「用户填的直播间号」变成「能连弹幕服务器的真实房间 ID」。
 *
 * <h2>为什么需要这一步</h2>
 * 抖音的直播间有两个 ID：
 * <ul>
 *   <li><b>web_rid</b>（短号）——用户在网页上看到的、分享链接里的那个，例如 {@code 123456789}</li>
 *   <li><b>room_id</b>（长号）——19 位数字，例如 {@code 7669017417082850102}，
 *       这是「这一场直播」的编号，<b>每次开播都会变</b>，弹幕服务器只认它</li>
 * </ul>
 * 所以必须先拿短号去换长号。
 *
 * <h2>怎么换</h2>
 * <ol>
 *   <li>GET 直播间页面，从响应头里拿 {@code ttwid} 这个 cookie（匿名就能拿）</li>
 *   <li>带着这个 cookie 调 {@code webcast/room/web/enter} 接口，返回的 JSON 里有 {@code id_str}，
 *       那就是 room_id</li>
 * </ol>
 *
 * <h2>两个坑（都踩过）</h2>
 * <ul>
 *   <li><b>cookie 必须完整回传</b>。只带一个手工拼的 {@code ttwid} 会得到
 *       HTTP 200 + 空响应体——不报错，但什么都拿不到。所以这里用了
 *       {@link CookieManager} 让 JDK 自己管理 cookie。</li>
 *   <li>直播间页面 HTML 里也有一个 {@code roomId}，但那是前端渲染的占位符，
 *       不能用。必须走接口拿 {@code id_str}。</li>
 * </ul>
 */
public final class DouyinRoomResolver {

    /** 模拟一个常见的 Chrome，不带 UA 容易被风控。 */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /** 从分享文本里抠出直播间短号。抖音分享出来一般长这样： https://v.douyin.com/xxxxx/ */
    private static final Pattern LIVE_URL_DIGITS = Pattern.compile("live\\.douyin\\.com/(\\d+)");

    /** 纯数字的短号。 */
    private static final Pattern PURE_DIGITS = Pattern.compile("^\\d+$");

    /** 从接口返回的 JSON 里抠 room_id（19 位长号）。 */
    private static final Pattern ROOM_ID_IN_JSON = Pattern.compile("\"id_str\"\\s*:\\s*\"(\\d{15,25})\"");

    /** 从直播间页面 HTML 里抠 room_id（接口失败时的兜底）。 */
    private static final Pattern ROOM_ID_IN_HTML = Pattern.compile("roomId\\\\?\"\\s*:\\s*\\\\?\"(\\d{15,25})");

    /** 解析结果。 */
    public record ResolvedRoom(String roomId, String webRid, String ttwid, String title) {
    }

    private DouyinRoomResolver() {
    }

    /**
     * 从用户输入里提取直播间短号。
     *
     * <p>支持三种写法：
     * <ul>
     *   <li>{@code 123456789} —— 纯数字</li>
     *   <li>{@code https://live.douyin.com/123456789} —— 完整链接</li>
     *   <li>一整段分享文案（里面含上面两种之一）</li>
     * </ul>
     *
     * @return 短号；识别不出来返回 {@code null}
     */
    public static String extractWebRid(String rawInput) {
        if (rawInput == null) {
            return null;
        }
        String trimmed = rawInput.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (PURE_DIGITS.matcher(trimmed).matches()) {
            return trimmed;
        }
        Matcher matcher = LIVE_URL_DIGITS.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 短号 -> 真实 room_id。
     *
     * @param webRid 直播间短号
     * @throws IOException 网络失败，或者抖音改接口了（响应里找不到 room_id）
     */
    public static ResolvedRoom resolve(String webRid) throws IOException, InterruptedException {
        // 让 JDK 自己管 cookie：先访问页面拿到 ttwid 和它附带的一串 cookie，
        // 再调接口时就会自动带上。
        CookieManager cookieManager = new CookieManager();
        HttpClient httpClient = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        String livePageUrl = "https://live.douyin.com/" + webRid;

        // 第 1 步：访问直播间页面，让 cookie 落到 cookie jar 里，
        //         顺便从 HTML 里捞一个兜底用的 room_id。
        String pageHtml;
        try {
            HttpResponse<String> pageResponse = httpClient.send(
                    HttpRequest.newBuilder(URI.create(livePageUrl))
                            .header("User-Agent", USER_AGENT)
                            .header("Referer", "https://live.douyin.com/")
                            .header("Accept-Language", "zh-CN,zh;q=0.9")
                            .timeout(Duration.ofSeconds(15))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            pageHtml = pageResponse.body() == null ? "" : pageResponse.body();
        } catch (IOException networkFailed) {
            throw new IOException("访问抖音直播间页面失败，请检查网络：" + networkFailed.getMessage(), networkFailed);
        }

        String ttwid = readTtwid(cookieManager);

        // 第 2 步：调 enter 接口拿权威的 room_id。
        String enterUrl = "https://live.douyin.com/webcast/room/web/enter/"
                + "?aid=6383&app_name=douyin_web&live_id=1&device_platform=web&language=zh-CN"
                + "&cookie_enabled=true&screen_width=1920&screen_height=1080"
                + "&browser_language=zh-CN&browser_platform=Win32&browser_name=Chrome"
                + "&browser_version=126.0.0.0"
                + "&web_rid=" + webRid
                + "&enter_from=web_live&is_need_double_stream=false";

        String enterBody;
        try {
            HttpResponse<String> enterResponse = httpClient.send(
                    HttpRequest.newBuilder(URI.create(enterUrl))
                            .header("User-Agent", USER_AGENT)
                            .header("Referer", livePageUrl)
                            .header("Accept-Language", "zh-CN,zh;q=0.9")
                            .timeout(Duration.ofSeconds(15))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            enterBody = enterResponse.body() == null ? "" : enterResponse.body();
        } catch (IOException networkFailed) {
            throw new IOException("调用抖音房间信息接口失败：" + networkFailed.getMessage(), networkFailed);
        }

        String roomId = null;
        String title = null;

        if (!enterBody.isBlank()) {
            roomId = matchFirst(ROOM_ID_IN_JSON, enterBody);
            title = readRoomTitle(enterBody);
        }

        // 兜底：接口没给（有时会返回 200 + 空 body），就从页面 HTML 里找。
        if (roomId == null) {
            roomId = matchFirst(ROOM_ID_IN_HTML, pageHtml);
        }

        if (roomId == null) {
            throw new IOException("没能从抖音拿到房间号。可能原因：直播间号填错了、"
                    + "这个直播间不存在、或者抖音改了接口（请把这一条反馈给模组作者）");
        }

        return new ResolvedRoom(roomId, webRid, ttwid, title);
    }

    /** 从 cookie jar 里读 ttwid。 */
    private static String readTtwid(CookieManager cookieManager) {
        for (java.net.HttpCookie cookie : cookieManager.getCookieStore().getCookies()) {
            if ("ttwid".equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /** 从接口返回的 JSON 里读直播间标题（读不到不影响功能）。 */
    private static String readRoomTitle(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonElement data = root.get("data");
            if (data == null || !data.isJsonObject()) {
                return null;
            }
            JsonElement rooms = data.getAsJsonObject().get("data");
            if (rooms == null || !rooms.isJsonArray() || rooms.getAsJsonArray().isEmpty()) {
                return null;
            }
            JsonElement title = rooms.getAsJsonArray().get(0).getAsJsonObject().get("title");
            return title == null || title.isJsonNull() ? null : title.getAsString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String matchFirst(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}
