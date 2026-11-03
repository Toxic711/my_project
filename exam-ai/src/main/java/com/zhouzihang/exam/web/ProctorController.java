package com.zhouzihang.exam.web;

import com.zhouzihang.exam.security.CurrentUser;
import com.zhouzihang.exam.security.RequireRole;
import com.zhouzihang.exam.service.ProctorService;
import com.zhouzihang.exam.web.dto.ProctorDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/** 防作弊事件接入与告警查询接口。 */
@RestController
@RequestMapping("/api/proctor")
public class ProctorController {

    private final ProctorService proctorService;

    public ProctorController(ProctorService proctorService) {
        this.proctorService = proctorService;
    }

    /** 学生端 MediaPipe 每帧/每 N 帧上报一次。 */
    @PostMapping("/events")
    @RequireRole({"STUDENT", "ADMIN"})
    public ProctorDtos.ProctorEventResponse ingest(
            @Valid @RequestBody ProctorDtos.ProctorEventRequest request) {
        return proctorService.ingest(request, CurrentUser.require());
    }

    /** 教师端告警列表（可按等级过滤），学生只能查自己的记录。 */
    @GetMapping("/alerts")
    @RequireRole({"STUDENT", "TEACHER", "ADMIN"})
    public ProctorDtos.AlertsResponse alerts(
            @RequestParam long examRecordId,
            @RequestParam(required = false) String level,
            @RequestParam(defaultValue = "50") int limit) {
        return proctorService.alerts(examRecordId, level, limit, CurrentUser.require());
    }

    /** 教师端查看原始事件（调试/申诉回放用）。 */
    @GetMapping("/events")
    @RequireRole({"TEACHER", "ADMIN"})
    public List<ProctorDtos.ProctorEventView> events(
            @RequestParam long examRecordId,
            @RequestParam(defaultValue = "50") int limit) {
        return proctorService.recentEvents(examRecordId, limit, CurrentUser.require());
    }
}
