package com.zhouzihang.devflow.review;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码审查 API。
 *
 * <pre>
 * POST /api/review               {diff, repo?, prNumber?} → 结构化 findings + 统计
 * GET  /api/review/sample        内置示例 diff（前端一键体验）
 * GET  /api/review/runs/{id}     按 id 回看某次审查
 * GET  /api/review/runs?limit=   最近审查记录（简表）
 * </pre>
 */
@RestController
@RequestMapping("/api/review")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping
    public ReviewService.ReviewResult review(@Valid @RequestBody ReviewRequest req) {
        return reviewService.review(req.diff(), "manual", req.repo(), req.prNumber(), null);
    }

    @GetMapping("/sample")
    public Map<String, Object> sample() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("description", SampleDiff.DESCRIPTION);
        m.put("diff", SampleDiff.TEXT);
        return m;
    }

    @GetMapping("/runs/{id}")
    public ReviewService.ReviewResult run(@PathVariable long id) {
        return reviewService.getRun(id);
    }

    @GetMapping("/runs")
    public List<ReviewRun> runs(@RequestParam(defaultValue = "10") int limit) {
        return reviewService.recentRuns(limit);
    }

    /** 审查请求。diff 为 unified diff 文本。 */
    public record ReviewRequest(@NotBlank String diff, String repo, Integer prNumber) {
    }
}
