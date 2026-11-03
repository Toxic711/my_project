package com.zhouzihang.exam.web.dto;

import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 防作弊事件/告警 DTO。 */
public final class ProctorDtos {

    private ProctorDtos() {
    }

    /**
     * 前端 MediaPipe 采集的一帧特征。
     * 布尔/数值字段允许缺省：缺省时服务端按“未检测到异常”处理，避免误报。
     */
    public record ProctorEventRequest(
            @NotNull(message = "examRecordId 不能为空") Long examRecordId,
            String sessionId,
            Boolean faceDetected,
            Integer faceCount,
            Double yaw,
            Double pitch,
            Double roll,
            Boolean gazeOffScreen,
            Double ear,
            Long timestamp,
            /** 可选：base64 缩略图（可带 data:image/png;base64, 前缀），落盘后只回受控 URL。 */
            String thumbnailBase64) {
    }

    public record ProctorEventResponse(long eventId, long examRecordId, int riskScore, String riskLevel,
                                       boolean alertTriggered, Long alertId, String reason, String evidenceUrl) {
    }

    public record ProctorAlertView(long id, long examRecordId, String level, int riskScore,
                                   String reason, String evidenceUrl, LocalDateTime createdAt) {
    }

    public record AlertsResponse(long examRecordId, long total, Map<String, Long> byLevel,
                                 List<ProctorAlertView> alerts) {
    }

    public record ProctorEventView(long id, long examRecordId, int riskScore, String riskLevel,
                                   boolean faceDetected, int faceCount, boolean gazeOffScreen,
                                   double yaw, double pitch, double ear, long eventTs) {
    }
}
