package com.zhouzihang.devflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GitHub Webhook 配置（app.github.*）。
 *
 * <p>面试讲解点：webhook secret 必须是"配置项而不是硬编码"，生产上由密钥管理系统注入；
 * 这里给它默认值只是为了 demo 能零配置跑通，README 中明确标注这是演示用弱密钥。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.github")
public class GitHubProperties {

    /** HMAC-SHA256 共享密钥，用于校验 X-Hub-Signature-256。 */
    private String webhookSecret = "devflow-webhook-secret";

    /** 只处理这些 PR 动作，其余动作视为"无需审查"。 */
    private String[] reviewActions = new String[]{"opened", "synchronize", "reopened", "ready_for_review"};

    /** webhook 原始 body 上限（字节），超限直接 413，防止签名校验前吃满内存。 */
    private int maxPayloadBytes = 1024 * 1024;
}
