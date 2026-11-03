package com.zhouzihang.exam.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 截图存证（第 9 张表）。
 *
 * <p>面试讲解点：数据库只存“相对路径 + 摘要 + 大小”，不存 base64。
 * 原因：① 行膨胀会让告警查询变慢；② base64 膨胀 33%，且无法被浏览器缓存/流式读取；
 * ③ 落盘后可以用对象存储（OSS/S3）替换本地目录，表结构不用动。
 * 对外只暴露受控 URL（/api/evidence/{id}），绝不回传服务器磁盘路径。</p>
 */
@Data
public class EvidenceFile {
    private Long id;
    private Long examRecordId;
    /** 相对根目录的路径，例如 20261103/12_ab12.png */
    private String relPath;
    private String sha256;
    private Long sizeBytes;
    private String mimeType;
    private LocalDateTime createdAt;
}
