package com.zhouzihang.exam.service;

import com.zhouzihang.exam.common.ApiException;
import com.zhouzihang.exam.domain.EvidenceFile;
import com.zhouzihang.exam.domain.ExamRecord;
import com.zhouzihang.exam.domain.ProctorAlert;
import com.zhouzihang.exam.domain.ProctorEvent;
import com.zhouzihang.exam.mapper.EvidenceFileMapper;
import com.zhouzihang.exam.mapper.ExamRecordMapper;
import com.zhouzihang.exam.mapper.ProctorAlertMapper;
import com.zhouzihang.exam.mapper.ProctorEventMapper;
import com.zhouzihang.exam.proctor.ProctorRiskEngine;
import com.zhouzihang.exam.proctor.ProctorSignal;
import com.zhouzihang.exam.proctor.RiskAssessment;
import com.zhouzihang.exam.security.AuthPrincipal;
import com.zhouzihang.exam.storage.EvidenceStorage;
import com.zhouzihang.exam.web.dto.ProctorDtos;
import com.zhouzihang.exam.ws.ProctorWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 面试讲解点（防作弊服务编排）：事件接入 -> 风险评估 -> 存证 -> 落库 -> 实时推送。
 *
 * <p>处理顺序（很关键）：先评估再落库。原始事件表只存“已算出风险分”的事件，
 * 这样即使风险引擎升级权重，历史数据也保留了当时的判定结果（可回溯、可解释）。</p>
 *
 * <p>为什么事件要校验归属？POST /api/proctor/events 是学生端调用的，
 * 如果不校验 examRecordId 归属，任何学生都能给别的考生“刷”高危事件（恶意诬告）。
 * 这里对 STUDENT 强校验 record.studentId == 当前用户；教师/管理员可代发（调试）。</p>
 */
@Slf4j
@Service
public class ProctorService {

    private static final Set<String> ALERT_LEVELS = Set.of("L1", "L2", "L3");
    private static final int MAX_ALERT_REASON = 250;

    private final ProctorRiskEngine riskEngine;
    private final ProctorEventMapper eventMapper;
    private final ProctorAlertMapper alertMapper;
    private final EvidenceFileMapper evidenceFileMapper;
    private final ExamRecordMapper recordMapper;
    private final EvidenceStorage evidenceStorage;
    private final ProctorWebSocketHandler webSocketHandler;

    public ProctorService(ProctorRiskEngine riskEngine, ProctorEventMapper eventMapper,
                          ProctorAlertMapper alertMapper, EvidenceFileMapper evidenceFileMapper,
                          ExamRecordMapper recordMapper, EvidenceStorage evidenceStorage,
                          ProctorWebSocketHandler webSocketHandler) {
        this.riskEngine = riskEngine;
        this.eventMapper = eventMapper;
        this.alertMapper = alertMapper;
        this.evidenceFileMapper = evidenceFileMapper;
        this.recordMapper = recordMapper;
        this.evidenceStorage = evidenceStorage;
        this.webSocketHandler = webSocketHandler;
    }

    public ProctorDtos.ProctorEventResponse ingest(ProctorDtos.ProctorEventRequest request,
                                                   AuthPrincipal principal) {
        ExamRecord record = recordMapper.findById(request.examRecordId());
        if (record == null) {
            throw ApiException.notFound("考试记录不存在");
        }
        assertOwner(record, principal);
        if ("SUBMITTED".equals(record.getStatus())) {
            throw ApiException.conflict("考试已提交，不再接收防作弊事件");
        }

        long timestamp = request.timestamp() == null || request.timestamp() <= 0
                ? System.currentTimeMillis() : request.timestamp();
        ProctorSignal signal = new ProctorSignal(
                record.getId(),
                request.sessionId(),
                request.faceDetected() == null || request.faceDetected(),
                request.faceCount() == null ? 1 : request.faceCount(),
                request.yaw() == null ? 0D : request.yaw(),
                request.pitch() == null ? 0D : request.pitch(),
                request.roll() == null ? 0D : request.roll(),
                request.gazeOffScreen() != null && request.gazeOffScreen(),
                request.ear() == null ? 0D : request.ear(),
                timestamp);

        RiskAssessment assessment = riskEngine.evaluate(signal);

        Long evidenceFileId = null;
        String evidenceUrl = null;
        if (request.thumbnailBase64() != null && !request.thumbnailBase64().isBlank()) {
            EvidenceStorage.StoredEvidence stored = evidenceStorage.save(record.getId(), request.thumbnailBase64());
            EvidenceFile file = new EvidenceFile();
            file.setExamRecordId(record.getId());
            file.setRelPath(stored.relPath());
            file.setSha256(stored.sha256());
            file.setSizeBytes(stored.sizeBytes());
            file.setMimeType(stored.mimeType());
            evidenceFileMapper.insert(file);
            evidenceFileId = file.getId();
            evidenceUrl = "/api/evidence/" + file.getId();
        }

        ProctorEvent event = new ProctorEvent();
        event.setExamRecordId(record.getId());
        event.setSessionId(request.sessionId());
        event.setFaceDetected(signal.faceDetected() ? 1 : 0);
        event.setFaceCount(signal.faceCount());
        event.setYaw(signal.yaw());
        event.setPitch(signal.pitch());
        event.setRoll(signal.roll());
        event.setGazeOffScreen(signal.gazeOffScreen() ? 1 : 0);
        event.setEar(signal.ear());
        event.setRiskScore(assessment.score());
        event.setRiskLevel(assessment.level());
        event.setEventTs(signal.timestamp());
        event.setEvidenceFileId(evidenceFileId);
        eventMapper.insert(event);

        Long alertId = null;
        if (assessment.isAlert()) {
            String reason = truncate(assessment.reason(), MAX_ALERT_REASON);
            ProctorAlert alert = new ProctorAlert();
            alert.setExamRecordId(record.getId());
            alert.setAlertLevel(assessment.level());
            alert.setRiskScore(assessment.score());
            alert.setReason(reason);
            alert.setEvidenceFileId(evidenceFileId);
            alertMapper.insert(alert);
            alertId = alert.getId();

            ProctorDtos.ProctorAlertView view = new ProctorDtos.ProctorAlertView(
                    alertId, record.getId(), assessment.level(), assessment.score(), reason,
                    evidenceUrl, LocalDateTime.now());
            // 事务边界之外：先落库再推送，保证“能查到的一定推送过”的最终一致
            webSocketHandler.broadcast(view);
            log.info("防作弊告警 level={} recordId={} score={} reason={}",
                    assessment.level(), record.getId(), assessment.score(), reason);
        }

        recordMapper.touchLastEvent(record.getId(), LocalDateTime.now());
        return new ProctorDtos.ProctorEventResponse(event.getId(), record.getId(), assessment.score(),
                assessment.level(), assessment.isAlert(), alertId, assessment.reason(), evidenceUrl);
    }

    public ProctorDtos.AlertsResponse alerts(long examRecordId, String level, int limit,
                                             AuthPrincipal principal) {
        ExamRecord record = recordMapper.findById(examRecordId);
        if (record == null) {
            throw ApiException.notFound("考试记录不存在");
        }
        assertOwner(record, principal);
        String normalizedLevel = null;
        if (level != null && !level.isBlank()) {
            normalizedLevel = level.trim().toUpperCase(Locale.ROOT);
            if (!ALERT_LEVELS.contains(normalizedLevel)) {
                throw ApiException.badRequest("level 只能是 L1/L2/L3");
            }
        }
        int size = Math.min(Math.max(limit, 1), 200);
        List<ProctorAlert> alerts = alertMapper.findByRecord(examRecordId, normalizedLevel, size);
        Map<String, Long> byLevel = new LinkedHashMap<>();
        long total = 0;
        for (ProctorAlertMapper.AlertLevelCount row : alertMapper.countByLevel(examRecordId)) {
            byLevel.put(row.getLevel(), row.getCount());
            total += row.getCount() == null ? 0 : row.getCount();
        }
        List<ProctorDtos.ProctorAlertView> views = new ArrayList<>();
        for (ProctorAlert alert : alerts) {
            views.add(toView(alert));
        }
        return new ProctorDtos.AlertsResponse(examRecordId, total, byLevel, views);
    }

    public List<ProctorDtos.ProctorEventView> recentEvents(long examRecordId, int limit,
                                                           AuthPrincipal principal) {
        ExamRecord record = recordMapper.findById(examRecordId);
        if (record == null) {
            throw ApiException.notFound("考试记录不存在");
        }
        assertOwner(record, principal);
        int size = Math.min(Math.max(limit, 1), 200);
        return eventMapper.findByRecordId(examRecordId, size).stream()
                .map(e -> new ProctorDtos.ProctorEventView(e.getId(), e.getExamRecordId(),
                        e.getRiskScore(), e.getRiskLevel(),
                        e.getFaceDetected() != null && e.getFaceDetected() == 1,
                        e.getFaceCount() == null ? 0 : e.getFaceCount(),
                        e.getGazeOffScreen() != null && e.getGazeOffScreen() == 1,
                        e.getYaw() == null ? 0 : e.getYaw(),
                        e.getPitch() == null ? 0 : e.getPitch(),
                        e.getEar() == null ? 0 : e.getEar(),
                        e.getEventTs() == null ? 0 : e.getEventTs()))
                .toList();
    }

    private ProctorDtos.ProctorAlertView toView(ProctorAlert alert) {
        String evidenceUrl = alert.getEvidenceFileId() == null ? null : "/api/evidence/" + alert.getEvidenceFileId();
        return new ProctorDtos.ProctorAlertView(alert.getId(), alert.getExamRecordId(), alert.getAlertLevel(),
                alert.getRiskScore() == null ? 0 : alert.getRiskScore(), alert.getReason(), evidenceUrl,
                alert.getCreatedAt());
    }

    private void assertOwner(ExamRecord record, AuthPrincipal principal) {
        if (principal.isStudent() && !record.getStudentId().equals(principal.userId())) {
            throw ApiException.forbidden("不能操作其他考生的考试记录");
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
