package com.zhouzihang.exam.security;

import com.zhouzihang.exam.common.ApiException;
import com.zhouzihang.exam.config.AppProperties;
import com.zhouzihang.exam.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * JWT 签发与校验（jjwt 0.12.5）。
 *
 * <p>面试讲解点 1：HS256 对称签名适合单体/内部服务；微服务间建议 RS256，
 * 公钥分发后各服务可离线验签，密钥轮换也更平滑。</p>
 *
 * <p>面试讲解点 2：token 里只放 uid/role/realName 这类非敏感且低变更的数据，
 * 不放权限明细（token 会膨胀且吊销困难）。角色变更场景依靠短 TTL 或黑名单。</p>
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final int expireMinutes;

    public JwtService(AppProperties properties) {
        byte[] secretBytes = properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret 至少需要 32 字节（HS256 要求）");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.expireMinutes = properties.getJwt().getExpireMinutes();
    }

    public String issue(User user) {
        Instant now = Instant.now();
        Instant expireAt = now.plus(expireMinutes, ChronoUnit.MINUTES);
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("uid", user.getId())
                .claim("role", user.getRole())
                .claim("rn", user.getRealName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expireAt))
                .signWith(key)
                .compact();
    }

    /**
     * 解析并验签。失败统一抛 401，避免把“过期/伪造/格式错”的差异暴露给攻击者。
     */
    public AuthPrincipal parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Object uid = claims.get("uid");
            if (uid == null) {
                throw ApiException.unauthorized("token 缺少 uid");
            }
            return new AuthPrincipal(
                    ((Number) uid).longValue(),
                    claims.getSubject(),
                    claims.get("rn", String.class),
                    claims.get("role", String.class));
        } catch (ExpiredJwtException e) {
            throw ApiException.unauthorized("登录已过期，请重新登录");
        } catch (JwtException | IllegalArgumentException e) {
            throw ApiException.unauthorized("token 无效");
        }
    }

    public long expiresInSeconds() {
        return expireMinutes * 60L;
    }
}
