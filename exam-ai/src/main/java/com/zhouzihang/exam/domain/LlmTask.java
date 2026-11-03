package com.zhouzihang.exam.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * LLM 出题任务（第 10 张表）。
 *
 * <p>面试讲解点：LLM 调用必须有任务表，否则无法回答三个面试必问：
 * ① 花了多少钱（token 统计）；② 为什么这次慢（latency）；③ 生成内容能否追溯
 * （request_digest + 题目 source=LLM）。status 区分 SUCCESS / FALLBACK（降级 mock）
 * / FAILED，降级率是 LLM 工程化最重要的可观测指标之一。</p>
 */
@Data
public class LlmTask {
    private Long id;
    /** mock / openai-compatible */
    private String provider;
    private String model;
    private String questionType;
    private String knowledgePoint;
    private Integer questionCount;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Long latencyMs;
    /** SUCCESS / FALLBACK / FAILED */
    private String status;
    /** 对 prompt 取 SHA-256，便于去重/审计，不用把完整 prompt 落库。 */
    private String requestDigest;
    private Long createdBy;
    private LocalDateTime createdAt;
}
