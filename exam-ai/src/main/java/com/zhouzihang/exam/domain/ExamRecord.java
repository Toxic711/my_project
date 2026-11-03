package com.zhouzihang.exam.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 考试记录 / 一次作答（第 5 张表）。
 *
 * <p>面试讲解点：唯一键 (exam_id, student_id) 是“开始考试幂等”的数据库兜底。
 * 并发双击“开始考试”时，两个请求同时 select 不到记录 → 都 insert，
 * 唯一键会让后到者抛 DuplicateKeyException，服务端捕获后回查已有记录返回，
 * 用户看到的是同一条记录，不会出现两份卷子。</p>
 */
@Data
public class ExamRecord {
    private Long id;
    private Long examId;
    private Long studentId;
    /** IN_PROGRESS / SUBMITTED */
    private String status;
    private Integer score;
    private LocalDateTime startedAt;
    private LocalDateTime deadlineAt;
    private LocalDateTime submittedAt;
    /** 客户端幂等键（可选），用于排查重复提交来源。 */
    private String submitRequestId;
    private LocalDateTime lastEventAt;
}
