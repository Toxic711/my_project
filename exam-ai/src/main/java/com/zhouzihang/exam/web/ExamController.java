package com.zhouzihang.exam.web;

import com.zhouzihang.exam.security.AuthPrincipal;
import com.zhouzihang.exam.security.CurrentUser;
import com.zhouzihang.exam.security.RequireRole;
import com.zhouzihang.exam.service.ExamService;
import com.zhouzihang.exam.web.dto.ExamDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/** 考试主流程接口：列表/详情/创建（教师）/开始/提交/结果。 */
@RestController
@RequestMapping("/api/exams")
public class ExamController {

    private final ExamService examService;

    public ExamController(ExamService examService) {
        this.examService = examService;
    }

    @GetMapping
    public List<ExamDtos.ExamView> list() {
        return examService.listExams(CurrentUser.require());
    }

    @GetMapping("/{id}")
    public ExamDtos.ExamDetailResponse detail(@PathVariable long id) {
        return examService.getExamDetail(id, CurrentUser.require());
    }

    @PostMapping
    @RequireRole({"TEACHER", "ADMIN"})
    public ExamDtos.ExamView create(@Valid @RequestBody ExamDtos.CreateExamRequest request) {
        return examService.createExam(request, CurrentUser.require());
    }

    @PostMapping("/{id}/start")
    @RequireRole({"STUDENT", "ADMIN"})
    public ExamDtos.StartExamResponse start(@PathVariable long id) {
        return examService.startExam(id, CurrentUser.require());
    }

    @PostMapping("/records/{recordId}/submit")
    @RequireRole({"STUDENT", "ADMIN"})
    public ExamDtos.SubmitResponse submit(@PathVariable long recordId,
                                          @Valid @RequestBody ExamDtos.SubmitRequest request) {
        return examService.submit(recordId, request, CurrentUser.require());
    }

    @GetMapping("/records/{recordId}/result")
    public ExamDtos.ResultResponse result(@PathVariable long recordId) {
        AuthPrincipal principal = CurrentUser.require();
        return examService.result(recordId, principal);
    }
}
