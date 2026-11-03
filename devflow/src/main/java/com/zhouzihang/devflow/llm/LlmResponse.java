package com.zhouzihang.devflow.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM 调用响应（provider 无关）。
 *
 * <p>对应 Spring AI 的 {@code ChatResponse}（含 {@code ChatResponseMetadata}
 * 的 usage）。{@code degraded=true} 表示"原本想用真实 provider，最终回落到 Mock"，
 * 这个标记会一路透传到 API 响应和前端，避免演示时把 Mock 输出误认为真实模型输出。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmResponse {

    private String requestId;
    private String provider;
    private String model;
    private String content;
    private int promptTokens;
    private int completionTokens;
    private int totalTokens;
    private String finishReason;
    private long latencyMs;
    /** 是否因降级链回落到非首选 provider。 */
    private boolean degraded;
    /** 本次调用估算成本（美元）；由 TokenAccounting 回填。 */
    private java.math.BigDecimal costUsd;
}
