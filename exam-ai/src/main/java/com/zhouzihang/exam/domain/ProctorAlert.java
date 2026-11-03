package com.zhouzihang.exam.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 防作弊告警（第 8 张表）。
 *
 * <p>面试讲解点：告警和事件分表。事件是“高频原始数据”（每秒级），
 * 告警是“低频业务信号”（一个去抖窗口最多一条）。
 * 分表后告警表很小，教师端查询/WebSocket 推送都只碰告警表，
 * 不会被每秒几十条事件拖慢。</p>
 */
@Data
public class ProctorAlert {
    private Long id;
    private Long examRecordId;
    /** L1 / L2 / L3 */
    private String alertLevel;
    private Integer riskScore;
    private String reason;
    private Long evidenceFileId;
    private LocalDateTime createdAt;
}
