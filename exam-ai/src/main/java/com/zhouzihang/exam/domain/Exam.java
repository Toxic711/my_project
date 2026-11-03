package com.zhouzihang.exam.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 考试（第 2 张表）。
 *
 * <p>面试讲解点：状态机 DRAFT -> PUBLISHED -> CLOSED。
 * 学生只能看到/开始 PUBLISHED 的考试；关闭由教师手动或定时任务触发。
 * question_count 与 total_score 是冗余快照字段：试卷题目变动后，
 * 历史考试记录仍能还原当时的题量/总分（否则统计会漂移）。</p>
 */
@Data
public class Exam {
    private Long id;
    private String title;
    private String description;
    private Integer questionCount;
    private Integer durationMinutes;
    private Integer totalScore;
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
}
