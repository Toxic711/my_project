package com.zhouzihang.exam.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 题目（第 3 张表，题目即试卷内容的载体）。
 *
 * <p>面试讲解点：exam_id 允许为 NULL —— NULL 表示“题库公共题”，
 * 非 NULL 表示“已经挂到某张试卷”。用可空外键替代 exam_question 中间表，
 * 保持 10 张表口径；代价是同一题不能复用于多张试卷，需要复用时再加中间表。</p>
 */
@Data
public class Question {
    private Long id;
    /** NULL = 题库公共题 */
    private Long examId;
    /** SINGLE / MULTIPLE / JUDGE */
    private String questionType;
    private String stem;
    private String knowledgePoint;
    /** EASY / MEDIUM / HARD */
    private String difficulty;
    private Integer score;
    /** 正确答案，例如 "A"、"AC"、"T"/"F"；多选按字符排序后比较。 */
    private String correctOption;
    private String analysis;
    /** BANK（题库）/ LLM（模型生成） */
    private String source;
    private LocalDateTime createdAt;
}
