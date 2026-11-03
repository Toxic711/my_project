package com.zhouzihang.exam.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 生成的一道题（结构化中间模型）。
 *
 * <p>面试讲解点：为什么让模型直接输出结构化 JSON，而不是“先写文章再正则解析”？
 * 判分、组卷、去重都依赖字段；prompt 里显式要求 JSON + 后端做 schema 校验，
 * 可以把“模型幻觉”限制在字段级（缺字段/选项数不对直接拒绝这道题），
 * 而不是让脏数据进题库。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneratedQuestion {
    private String type;
    private String stem;
    /** A/B/C/D 或 T/F；用 LinkedHashMap 保证序列化顺序稳定。 */
    @Builder.Default
    private Map<String, String> options = new LinkedHashMap<>();
    private String correctOption;
    private String analysis;
    private String difficulty;
    private String knowledgePoint;
    private int score;
}
