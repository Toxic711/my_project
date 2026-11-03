package com.zhouzihang.exam.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 作答明细（第 6 张表）。
 *
 * <p>面试讲解点：唯一键 (exam_record_id, question_id) 是交卷幂等的最后一道防线。
 * 即使 Redisson 锁因 Redis 抖动/网络分区失效，重复写入也会被数据库拒绝，
 * 保证“一份卷子对一道题只有一条作答”。</p>
 */
@Data
public class Answer {
    private Long id;
    private Long examRecordId;
    private Long questionId;
    /** 学生作答，例如 "A"、"AC"、"T"；未作答为 NULL。 */
    private String answerValue;
    /** 1 正确，0 错误（冗余字段，便于统计，避免每次 join 题目表）。 */
    private Integer correctFlag;
    private Integer score;
    private LocalDateTime answeredAt;
}
