package com.zhouzihang.devflow.llm;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用量与成本 API。
 *
 * <pre>
 * GET /api/usage/summary            总量 + 按模型 + 按业务场景
 * GET /api/usage/recent?limit=20    最近调用明细
 * </pre>
 *
 * <p>面试讲解点：这个接口存在的意义不是"给面试官看一个 JSON"，而是把
 * <b>成本可视</b>做出来——LLM 应用最常见的线上事故是"某天账单暴涨"，
 * 而罪魁往往是某个 feature 的 prompt 变长（例如把整个仓库塞进审查 prompt）。
 * 有了 byFeature 的 token 分布，才能定位到"是谁"。</p>
 */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final TokenAccounting accounting;

    public UsageController(TokenAccounting accounting) {
        this.accounting = accounting;
    }

    @GetMapping("/summary")
    public TokenAccounting.UsageSummary summary() {
        return accounting.summary();
    }

    @GetMapping("/recent")
    public List<LlmUsage> recent(@RequestParam(defaultValue = "20") int limit) {
        return accounting.recent(Math.min(200, Math.max(1, limit)));
    }
}
