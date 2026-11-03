package com.zhouzihang.exam.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 一次 LLM 调用的结果（含 token 用量与降级标记）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmGeneration {
    private String provider;
    private String model;
    private String prompt;
    private String rawContent;
    private List<GeneratedQuestion> questions;
    private int promptTokens;
    private int completionTokens;
    private long latencyMs;
    /** 是否发生降级（OpenAI 调用失败后回退 mock）。 */
    private boolean fallback;
    /** SUCCESS / FALLBACK / FAILED */
    private String status;
}
