package com.zhouzihang.exam.web.dto;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

/** LLM 出题与用量统计 DTO。 */
public final class LlmDtos {

    private LlmDtos() {
    }

    public record GenerateQuestionsRequest(
            @NotBlank(message = "题型不能为空") String type,
            @NotBlank(message = "知识点不能为空") String knowledgePoint,
            @Min(value = 1, message = "至少生成 1 题") @Max(value = 10, message = "单次最多生成 10 题")
            Integer count,
            String difficulty,
            Long examId,
            String extraInstruction) {
    }

    public record GeneratedQuestionView(long id, String type, String stem, Map<String, String> options,
                                        String correctOption, String analysis, String difficulty,
                                        String knowledgePoint, int score) {
    }

    public record UsageView(int promptTokens, int completionTokens, int totalTokens, long latencyMs) {
    }

    public record GenerateQuestionsResponse(long taskId, String provider, String model, boolean fallback,
                                            UsageView usage, List<GeneratedQuestionView> questions) {
    }

    public record ProviderUsage(String provider, long taskCount, long totalTokens, long fallbackCount) {
    }

    public record LlmUsageResponse(long taskCount, long successCount, long fallbackCount,
                                   long promptTokens, long completionTokens, long totalTokens,
                                   double avgLatencyMs, List<ProviderUsage> byProvider) {
    }
}
