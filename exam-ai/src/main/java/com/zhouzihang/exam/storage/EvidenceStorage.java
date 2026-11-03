package com.zhouzihang.exam.storage;

import com.zhouzihang.exam.common.ApiException;
import com.zhouzihang.exam.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 截图存证落盘（本地磁盘实现）。
 *
 * <p>面试讲解点 1：为什么只存相对路径 + sha256 + size？
 * ① 数据库行不膨胀；② sha256 可去重/校验完整性（证据链）；③ 迁移到 OSS/S3 时
 * 只需替换本类实现，表结构不变（依赖倒置）。</p>
 *
 * <p>面试讲解点 2：路径安全。存储路径由服务端生成（日期目录 + recordId + UUID），
 * 绝不拼接用户输入；读取时再做一次 normalize + startsWith(root) 校验，
 * 防止 ../ 路径穿越。对外只暴露 /api/evidence/{id} 这种受控 URL。</p>
 *
 * <p>面试讲解点 3：输入校验。只接受 PNG/JPEG 魔数，拒绝任意 base64 落盘
 * （否则会变成“任意文件上传”漏洞）；限制单张 512KB，防止磁盘被打爆。</p>
 */
@Slf4j
@Component
public class EvidenceStorage {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    private final Path root;
    private final int maxBytes;
    private final boolean enabled;

    public EvidenceStorage(AppProperties properties) {
        this.root = Paths.get(properties.getStorage().getEvidenceDir()).toAbsolutePath().normalize();
        this.maxBytes = properties.getProctor().getEvidenceMaxBytes();
        this.enabled = properties.getProctor().isEvidenceEnabled();
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 保存一张 base64 缩略图。
     *
     * @return 相对路径、摘要、大小、mime
     */
    public StoredEvidence save(long examRecordId, String base64) {
        if (!enabled) {
            throw ApiException.badRequest("截图存证功能已关闭");
        }
        byte[] bytes = decode(base64);
        if (bytes.length == 0) {
            throw ApiException.badRequest("截图内容为空");
        }
        if (bytes.length > maxBytes) {
            throw ApiException.badRequest("截图过大，上限 " + (maxBytes / 1024) + "KB");
        }
        String mimeType;
        String extension;
        if (startsWith(bytes, PNG_MAGIC)) {
            mimeType = "image/png";
            extension = "png";
        } else if (startsWith(bytes, JPEG_MAGIC)) {
            mimeType = "image/jpeg";
            extension = "jpg";
        } else {
            throw ApiException.badRequest("仅支持 PNG/JPEG 截图");
        }

        String day = LocalDate.now().format(DAY);
        String fileName = examRecordId + "_" + UUID.randomUUID().toString().replace("-", "") + "." + extension;
        Path dir = root.resolve(day);
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(fileName), bytes);
        } catch (IOException e) {
            log.error("截图落盘失败 dir={}", dir, e);
            throw new IllegalStateException("截图落盘失败", e);
        }
        return new StoredEvidence(day + "/" + fileName, sha256(bytes), bytes.length, mimeType);
    }

    /** 读取受控相对路径对应的文件；路径越界直接拒绝。 */
    public byte[] read(String relPath) {
        Path target = root.resolve(relPath).normalize();
        if (!target.startsWith(root)) {
            throw ApiException.forbidden("非法的存证路径");
        }
        if (!Files.isRegularFile(target)) {
            throw ApiException.notFound("存证文件不存在");
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new IllegalStateException("读取存证失败", e);
        }
    }

    private byte[] decode(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw ApiException.badRequest("截图 base64 为空");
        }
        String payload = base64;
        int comma = payload.indexOf(',');
        if (payload.startsWith("data:") && comma > 0) {
            payload = payload.substring(comma + 1);
        }
        try {
            return Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("截图 base64 非法");
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 落盘结果（服务层据此写 evidence_file 表）。 */
    public record StoredEvidence(String relPath, String sha256, long sizeBytes, String mimeType) {
    }
}
