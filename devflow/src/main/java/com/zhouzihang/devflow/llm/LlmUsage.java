package com.zhouzihang.devflow.llm;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * LLM 用量记录（落库表 llm_usage）。
 *
 * <p>面试讲解点：用量与计费必须<b>每次调用一行、只追加不修改</b>（append-only）。
 * 这样"某天成本突增"可以精确归因到 requestId → feature → model，
 * 也避免并发更新同一行造成写竞争。汇总查询用内存聚合（数据量小时最简单，
 * 生产可换成按小时预聚合的物化视图）。</p>
 */
@Data
@TableName("llm_usage")
public class LlmUsage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String requestId;
    private String feature;
    private String provider;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    /** 估算成本（美元），保留 6 位小数。 */
    private BigDecimal costUsd;
    private Integer latencyMs;
    private LocalDateTime createdAt;
}
