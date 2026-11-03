package com.zhouzihang.exam.web;

import com.zhouzihang.exam.security.CurrentUser;
import com.zhouzihang.exam.security.RequireRole;
import com.zhouzihang.exam.service.QuestionService;
import com.zhouzihang.exam.web.dto.LlmDtos;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/** LLM 出题接口（教师/管理员）。 */
@RestController
@RequestMapping("/api/questions")
public class QuestionController {

    private final QuestionService questionService;

    public QuestionController(QuestionService questionService) {
        this.questionService = questionService;
    }

    @PostMapping("/generate")
    @RequireRole({"TEACHER", "ADMIN"})
    public LlmDtos.GenerateQuestionsResponse generate(
            @Valid @RequestBody LlmDtos.GenerateQuestionsRequest request) {
        return questionService.generate(request, CurrentUser.require());
    }
}
