package com.zhouzihang.devflow.review;

import com.zhouzihang.devflow.config.GitHubProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GitHub Webhook 签名校验测试。
 *
 * <p>面试点：webhook 是公网入口，不验签等于允许任何人伪造"PR 已合并"事件触发付费 LLM 调用。
 * 验签必须用**原始 body 字节**，并且用常量时间比较避免时序侧信道。</p>
 */
class WebhookSignatureVerifierTest {

    private static final String SECRET = "devflow-webhook-secret";
    private static final String BODY = "{\"action\":\"opened\",\"number\":42}";

    private WebhookSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        GitHubProperties props = new GitHubProperties();
        props.setWebhookSecret(SECRET);
        verifier = new WebhookSignatureVerifier(props);
    }

    @Test
    @DisplayName("正确签名通过校验")
    void validSignature() {
        String header = WebhookSignatureVerifier.signHeader(BODY, SECRET);
        assertNotNull(header);
        assertTrue(header.startsWith("sha256="));
        assertTrue(verifier.verify(BODY, header));
    }

    @Test
    @DisplayName("body 被篡改后签名失效")
    void tamperedBodyRejected() {
        String header = WebhookSignatureVerifier.signHeader(BODY, SECRET);
        assertFalse(verifier.verify(BODY + " ", header));
        assertFalse(verifier.verify("{\"action\":\"closed\"}", header));
    }

    @Test
    @DisplayName("缺失/格式错误的签名一律拒绝")
    void missingOrMalformedSignatureRejected() {
        assertFalse(verifier.verify(BODY, null));
        assertFalse(verifier.verify(BODY, ""));
        assertFalse(verifier.verify(BODY, "sha256=deadbeef"));
        assertFalse(verifier.verify(BODY, "not-a-signature"));
    }

    @Test
    @DisplayName("用错误密钥签名必须失败")
    void wrongSecretRejected() {
        String header = WebhookSignatureVerifier.signHeader(BODY, "another-secret");
        assertFalse(verifier.verify(BODY, header));
    }
}
