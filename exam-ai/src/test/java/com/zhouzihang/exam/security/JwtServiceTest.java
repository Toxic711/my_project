package com.zhouzihang.exam.security;

import com.zhouzihang.exam.config.AppProperties;
import com.zhouzihang.exam.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWT 签发/校验测试。
 *
 * <p>覆盖：正常往返、篡改签名必须失败、过期时间元信息。
 * 面试点：JWT 的 payload 是 base64 明文（不是加密），所以不能放敏感信息；
 * 这里只放 userId、username、role。</p>
 */
class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getJwt().setSecret("unit-test-secret-key-0123456789-0123456789");
        props.getJwt().setExpireMinutes(60);
        jwtService = new JwtService(props);
    }

    private User user() {
        User u = new User();
        u.setId(7L);
        u.setUsername("student1");
        u.setRealName("张同学");
        u.setRole("STUDENT");
        return u;
    }

    @Test
    @DisplayName("签发的 token 能被解析回同样的身份信息")
    void issueAndParse() {
        String token = jwtService.issue(user());
        assertNotNull(token);
        AuthPrincipal principal = jwtService.parse(token);
        assertEquals(7L, principal.userId());
        assertEquals("student1", principal.username());
        assertEquals("STUDENT", principal.role());
        assertTrue(principal.isStudent());
    }

    @Test
    @DisplayName("篡改 token 必须解析失败（不能接受伪造身份）")
    void tamperedTokenRejected() {
        String token = jwtService.issue(user());
        String tampered = token.substring(0, token.length() - 3) + "abc";
        assertThrows(RuntimeException.class, () -> jwtService.parse(tampered));
    }

    @Test
    @DisplayName("过期时间暴露给前端（秒），用于自动续期")
    void expiresInSeconds() {
        assertEquals(3600, jwtService.expiresInSeconds());
    }
}
