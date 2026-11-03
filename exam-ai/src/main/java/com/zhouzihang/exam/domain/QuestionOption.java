package com.zhouzihang.exam.domain;

import lombok.Data;

/**
 * 选项（第 4 张表）。
 *
 * <p>面试讲解点：选项独立成表而不是塞 JSON 字段，便于按选项统计正确率、
 * 做题目质量分析（干扰项是否有效）；唯一键 (question_id, option_key)
 * 保证一题内 A/B/C/D 不重复。</p>
 */
@Data
public class QuestionOption {
    private Long id;
    private Long questionId;
    /** A/B/C/D，判断题用 T/F */
    private String optionKey;
    private String content;
}
