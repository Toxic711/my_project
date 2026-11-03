package com.zhouzihang.exam.web;

import com.zhouzihang.exam.security.RequireRole;
import com.zhouzihang.exam.service.QuestionService;
import com.zhouzihang.exam.web.dto.LlmDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** LLM 用量统计（token 成本可观测）。 */
@RestController
@RequestMapping("/api/llm")
public class LlmController {

    private final QuestionService questionService;

    public LlmController(QuestionService questionService) {
        this.questionService = questionService;
    }

    @GetMapping("/usage")
    @RequireRole({"TEACHER", "ADMIN"})
    public LlmDtos.LlmUsageResponse usage() {
        return questionService.usage();
    }
}
