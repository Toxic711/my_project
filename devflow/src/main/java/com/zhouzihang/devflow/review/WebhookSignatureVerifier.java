package com.zhouzihang.devflow.review;

import com.zhouzihang.devflow.config.GitHubProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * GitHub webhook 签名校验（HMAC-SHA256 + 常量时间比较）。
 *
 * <p>面试讲解点：这是"零信任"的典型场景——webhook 端点暴露在公网，
 * 任何人都能 POST 过来。所以必须：</p>
 * <ol>
 *   <li>用共享 secret 对<b>原始请求体</b>做 HMAC-SHA256（不是解析后的 JSON，
 *       JSON 序列化再哈希必然不一致——这是最常见的实现 bug）；</li>
 *   <li>用 {@link MessageDigest#isEqual} 做<b>常量时间比较</b>，防止攻击者用
 *       响应时间差做逐字节爆破签名；</li>
 *   <li>缺失/格式不对/长度不对一律拒绝（不要"跳过校验继续处理"，那是致命漏洞）。</li>
 * </ol>
 * <p>注意：HMAC 比较的是十六进制字符串的字节，长度不同时 {@code isEqual} 会快速返回 false，
 * 但长度本身不是秘密，所以不构成有效侧信道。</p>
 */
@Component
public class WebhookSignatureVerifier {

    private static final String PREFIX = "sha256=";

    private final GitHubProperties props;

    public WebhookSignatureVerifier(GitHubProperties props) {
        this.props = props;
    }

    /** 校验 GitHub 的 X-Hub-Signature-256 头。 */
    public boolean verify(String rawBody, String signatureHeader) {
        if (rawBody == null || signatureHeader == null) {
            return false;
        }
        String header = signatureHeader.trim();
        if (!header.toLowerCase(Locale.ROOT).startsWith(PREFIX)) {
            return false;
        }
        String provided = header.substring(PREFIX.length()).trim().toLowerCase(Locale.ROOT);
        if (provided.isEmpty()) {
            return false;
        }
        String expected = sign(rawBody, props.getWebhookSecret());
        // 常量时间比较
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    /** 计算签名（测试与 scripts 生成签名用，也是 verify 的唯一实现来源）。 */
    public static String sign(String rawBody, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("计算 HMAC-SHA256 失败: " + e.getMessage(), e);
        }
    }

    /** 生成可直接放进请求头的完整签名值（含 sha256= 前缀）。 */
    public static String signHeader(String rawBody, String secret) {
        return PREFIX + sign(rawBody, secret);
    }
}
