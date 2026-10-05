package com.douyindanmaku.core.douyin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 算抖音 WebSocket 连接需要的 {@code signature} 参数。
 *
 * <h2>为什么必须借外部脚本</h2>
 * 抖音要求连接 URL 上带一个 {@code signature}（内部叫 X-Bogus）。
 * 它的算法藏在抖音自己的混淆 JavaScript（webmssdk）里：那段代码有
 * 三万多层递归反调试，纯 Java 的脚本引擎跑不动（会爆栈）。
 * 所以社区通行的做法是——<b>用 Node.js 跑抖音自己的那段 JS</b>，
 * 让抖音自己去算自己的签名。好处是抖音哪天换了算法，
 * 只要换掉那个 js 文件就行，不用改一行 Java。
 *
 * <h2>为什么模组里不打包那个 js</h2>
 * 那段 JS 的版权属于字节跳动，随模组分发不合适（本项目要开源）。
 * 所以这里只调用用户自己准备的脚本，路径可配。
 *
 * <h2>算法分两步</h2>
 * <ol>
 *   <li>把连接 URL 里 13 个固定参数按固定顺序拼成 {@code k=v,k=v,...}，算 MD5。
 *       这一步是纯 Java，本类负责。</li>
 *   <li>把 MD5 值交给外部脚本，脚本调用 webmssdk 返回 X-Bogus。
 *       这一步需要 Node.js。</li>
 * </ol>
 */
public final class DouyinSigner {

    /**
     * 参与签名的 13 个参数，<b>顺序不能改</b>。
     *
     * <p>这个顺序是算法的一部分：顺序变了 MD5 就变了，签名就对不上。
     */
    private static final String[] SIGNED_PARAMS = {
            "live_id",
            "aid",
            "version_code",
            "webcast_sdk_version",
            "room_id",
            "sub_room_id",
            "sub_channel_id",
            "did_rule",
            "user_unique_id",
            "device_platform",
            "device_type",
            "ac",
            "identity",
    };

    /** 调用脚本的超时时间。jsdom 首次冷启动比较慢，给宽一点。 */
    private static final Duration SCRIPT_TIMEOUT = Duration.ofSeconds(60);

    private DouyinSigner() {
    }

    /**
     * 从连接 URL 的查询串里算出 X-MS-STUB（MD5 小写十六进制）。
     *
     * <p>注意：输入必须是<b>不含 signature</b> 的 URL，
     * 否则算出来的东西和抖音对不上。
     *
     * @param query 连接 URL 里 {@code ?} 后面那一串（可以带不带 {@code ?} 都行）
     */
    public static String computeStub(String query) {
        java.util.Map<String, String> params = parseQuery(query);

        StringBuilder joined = new StringBuilder(256);
        for (int index = 0; index < SIGNED_PARAMS.length; index++) {
            if (index > 0) {
                joined.append(',');
            }
            // 取不到的参数要留空值，不能整个跳过——位置是算法的一部分
            joined.append(SIGNED_PARAMS[index]).append('=').append(params.getOrDefault(SIGNED_PARAMS[index], ""));
        }

        return md5Hex(joined.toString());
    }

    /**
     * 调外部脚本算签名。
     *
     * @param nodePath        node 可执行文件路径；留空表示用 PATH 里的 {@code node}
     * @param scriptPath      脚本路径
     * @param xMsStub         {@link #computeStub(String)} 的结果
     * @param workingDir      脚本的工作目录（相对路径按它解析），可为 {@code null}
     * @return X-Bogus 字符串
     * @throws IOException 脚本不存在、node 没装、执行超时、或脚本没输出
     */
    public static String requestSignature(String nodePath,
                                          Path scriptPath,
                                          String xMsStub,
                                          Path workingDir) throws IOException {
        if (!Files.isRegularFile(scriptPath)) {
            throw new IOException("找不到签名脚本：" + scriptPath
                    + "。请先按 README 的「准备签名脚本」一节准备，"
                    + "或在配置文件里把 signScriptPath 改到正确位置。");
        }

        String node = (nodePath == null || nodePath.isBlank()) ? "node" : nodePath;

        ProcessBuilder processBuilder = new ProcessBuilder(node, scriptPath.toString(), xMsStub);
        processBuilder.redirectErrorStream(true);
        if (workingDir != null && Files.isDirectory(workingDir)) {
            processBuilder.directory(workingDir.toFile());
        }

        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException notFound) {
            throw new IOException("启动 Node.js 失败（" + node + "）。"
                    + "请确认已安装 Node.js，或在配置文件里把 nodePath 填成 node.exe 的完整路径。", notFound);
        }

        String output;
        try {
            // 必须先在超时内读完输出再 waitFor，否则输出量大时进程会阻塞在写管道上
            byte[] raw = process.getInputStream().readAllBytes();
            output = new String(raw, StandardCharsets.UTF_8).trim();
            if (!process.waitFor(SCRIPT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("签名脚本执行超时（" + SCRIPT_TIMEOUT.toSeconds() + " 秒）。"
                        + "首次运行通常会慢一些，如果一直超时请检查 Node.js 是否正常。");
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("签名脚本被中断", interrupted);
        }

        if (process.exitValue() != 0) {
            throw new IOException("签名脚本执行失败（退出码 " + process.exitValue() + "）：" + output);
        }
        if (output.isEmpty()) {
            throw new IOException("签名脚本没有输出任何内容，请检查脚本是否正确。");
        }

        // 脚本有可能把日志一起打到标准输出，取最后一行非空内容当签名
        String[] lines = output.split("\\R");
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = lines[index].trim();
            if (!line.isEmpty()) {
                return line;
            }
        }
        throw new IOException("签名脚本的输出里没有有效内容。");
    }

    // ------------------------------------------------------------------
    //  下面是纯计算部分，不依赖外部环境，便于单独测试
    // ------------------------------------------------------------------

    /** 把 {@code a=1&b=2} 形式解析成 map（会对值做 URL 解码）。 */
    static java.util.Map<String, String> parseQuery(String query) {
        java.util.Map<String, String> result = new java.util.HashMap<>();
        if (query == null || query.isEmpty()) {
            return result;
        }
        int start = query.startsWith("?") ? 1 : 0;
        String body = query.substring(start);

        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            if (equals < 0) {
                // 没有等号的参数（如 support_wrds_1）值算空串，不影响签名
                result.putIfAbsent(urlDecode(pair), "");
            } else {
                String key = urlDecode(pair.substring(0, equals));
                String value = urlDecode(pair.substring(equals + 1));
                result.putIfAbsent(key, value);
            }
        }
        return result;
    }

    /** URL 解码。解不出来就原样返回，不抛异常。 */
    private static String urlDecode(String text) {
        try {
            return java.net.URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return text;
        }
    }

    /** 算 MD5 并转成小写十六进制。 */
    public static String md5Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hashed = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException impossible) {
            // 所有 JVM 都必须实现 MD5
            throw new IllegalStateException("当前 JVM 不支持 MD5", impossible);
        }
    }
}
