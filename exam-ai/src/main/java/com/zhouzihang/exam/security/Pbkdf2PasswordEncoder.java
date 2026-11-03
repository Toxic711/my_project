package com.zhouzihang.exam.security;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PBKDF2 密码哈希（PBKDF2WithHmacSHA256）。
 *
 * <p>面试讲解点 1：为什么不能明文/单次 MD5？
 * 单次哈希可被 GPU 秒级碰撞，必须加盐 + 迭代。PBKDF2 是 JDK 内置算法，
 * 离线环境无需引入 spring-security-crypto，实现成本最低。</p>
 *
 * <p>面试讲解点 2：迭代次数是安全/性能的权衡。demo 取 60000 次（单次约 30~60ms），
 * 生产建议 210000 次（OWASP 2023 建议值），并用独立 salt 让彩虹表失效。</p>
 *
 * <p>存储格式：{@code pbkdf2$60000$<saltBase64>$<hashBase64>}。
 * 带算法前缀和参数，未来升级迭代次数时可以做“验证通过后静默升级”。</p>
 */
@Component
public class Pbkdf2PasswordEncoder {

    private static final String PREFIX = "pbkdf2";
    private static final int ITERATIONS = 60_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    public String encode(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(rawPassword.toCharArray(), salt, ITERATIONS);
        return PREFIX + "$" + ITERATIONS + "$" + b64(salt) + "$" + b64(hash);
    }

    /** 校验密码；任何格式异常都返回 false，不向调用方泄漏“是格式问题还是密码错误”。 */
    public boolean matches(String rawPassword, String stored) {
        if (rawPassword == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(rawPassword.toCharArray(), salt, iterations);
            // 恒定时间比较，避免时序侧信道
            return MessageDigest.isEqual(actual, expected);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 计算失败", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }

    /** demo 种子数据生成用：打印密码哈希，便于维护 data-h2.sql。 */
    public static void main(String[] args) {
        Pbkdf2PasswordEncoder encoder = new Pbkdf2PasswordEncoder();
        String[] plain = {"admin123", "teacher123", "student123"};
        for (String p : plain) {
            String hash = encoder.encode(p);
            // 自校验，避免生成脏数据
            if (!encoder.matches(p, hash)) {
                throw new IllegalStateException("哈希自校验失败: " + p);
            }
            System.out.println(p + " => " + hash);
        }
        // 兜底：确认 UTF-8 无 BOM 的脚本场景下不依赖外部 locale
        System.out.println("charset=" + StandardCharsets.UTF_8.name());
    }
}
