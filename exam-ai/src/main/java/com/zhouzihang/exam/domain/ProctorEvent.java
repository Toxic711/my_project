package com.zhouzihang.exam.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 防作弊原始事件（第 7 张表）。
 *
 * <p>面试讲解点：原始事件必须落库（而不是只算风险分）。
 * 原因：① 事后申诉要能回放“第几分钟发生了什么”；② 风险模型调参需要离线样本；
 * ③ 面试/审计场景要求证据链可追溯。event_ts 存客户端毫秒时间戳，
 * created_at 存服务端接收时间，二者差值可发现“客户端改时间戳”的攻击。</p>
 */
@Data
public class ProctorEvent {
    private Long id;
    private Long examRecordId;
    private String sessionId;
    /** 1 检测到人脸，0 未检测到 */
    private Integer faceDetected;
    private Integer faceCount;
    private Double yaw;
    private Double pitch;
    private Double roll;
    /** 1 视线离开屏幕 */
    private Integer gazeOffScreen;
    /** 眼睛纵横比，越小越可能闭眼/疲劳 */
    private Double ear;
    private Integer riskScore;
    private String riskLevel;
    private Long eventTs;
    private Long evidenceFileId;
    private LocalDateTime createdAt;
}
