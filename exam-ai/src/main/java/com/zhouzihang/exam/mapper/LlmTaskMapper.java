package com.zhouzihang.exam.mapper;

import com.zhouzihang.exam.domain.LlmTask;
import lombok.Data;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** LLM 任务与 token 统计表访问。 */
@Mapper
public interface LlmTaskMapper {

    @Insert("insert into llm_task(provider, model, question_type, knowledge_point, question_count, prompt_tokens, "
            + "completion_tokens, total_tokens, latency_ms, status, request_digest, created_by) "
            + "values(#{provider}, #{model}, #{questionType}, #{knowledgePoint}, #{questionCount}, #{promptTokens}, "
            + "#{completionTokens}, #{totalTokens}, #{latencyMs}, #{status}, #{requestDigest}, #{createdBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(LlmTask task);

    @Select("select count(*) as task_count, "
            + "coalesce(sum(prompt_tokens), 0) as prompt_tokens, "
            + "coalesce(sum(completion_tokens), 0) as completion_tokens, "
            + "coalesce(sum(total_tokens), 0) as total_tokens, "
            + "coalesce(avg(latency_ms), 0) as avg_latency_ms, "
            + "coalesce(sum(case when status = 'FALLBACK' then 1 else 0 end), 0) as fallback_count "
            + "from llm_task")
    LlmUsageTotals usageTotals();

    @Select("select provider, count(*) as task_count, coalesce(sum(total_tokens), 0) as total_tokens, "
            + "coalesce(sum(case when status = 'FALLBACK' then 1 else 0 end), 0) as fallback_count "
            + "from llm_task group by provider order by provider")
    List<LlmUsageByProvider> usageByProvider();

    /** token 汇总（单行）。 */
    @Data
    class LlmUsageTotals {
        private Long taskCount;
        private Long promptTokens;
        private Long completionTokens;
        private Long totalTokens;
        private Double avgLatencyMs;
        private Long fallbackCount;
    }

    /** 按 provider 聚合。 */
    @Data
    class LlmUsageByProvider {
        private String provider;
        private Long taskCount;
        private Long totalTokens;
        private Long fallbackCount;
    }
}
