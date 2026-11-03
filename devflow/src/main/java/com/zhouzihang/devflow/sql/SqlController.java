package com.zhouzihang.devflow.sql;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 自然语言 SQL API。
 *
 * <pre>
 * POST /api/sql/generate     {question, provider?} → 生成并校验（违规 422 + 原因）
 * POST /api/sql/execute      {sql}                 → 再校验 + EXPLAIN + 只读执行
 * POST /api/sql/guard-check  {sql}                 → 只校验不执行（200，返回 allowed/reason）
 * GET  /api/sql/guard-info                         → 护栏配置与 schema 摘要
 * </pre>
 *
 * <p>面试讲解点：{@code guard-check} 与 {@code generate} 的差异是刻意的——
 * 前者返回 200 + 判定结果，供前端做"实时安全检查"（只要结果不要异常）；
 * 后者在违规时抛 422，因为它属于"生成失败"，调用方必须处理。
 * 同一套护栏、两种错误呈现方式，是 API 设计里的常见取舍。</p>
 */
@RestController
@RequestMapping("/api/sql")
public class SqlController {

    private final SqlService sqlService;

    public SqlController(SqlService sqlService) {
        this.sqlService = sqlService;
    }

    @PostMapping("/generate")
    public SqlService.GenerateResult generate(@Valid @RequestBody GenerateRequest req) {
        return sqlService.generate(req.question(), req.provider());
    }

    @PostMapping("/execute")
    public SqlService.ExecuteResult execute(@Valid @RequestBody ExecuteRequest req) {
        return sqlService.execute(req.sql());
    }

    @PostMapping("/guard-check")
    public SqlService.GuardCheck check(@Valid @RequestBody ExecuteRequest req) {
        return sqlService.check(req.sql());
    }

    @GetMapping("/guard-info")
    public Map<String, Object> guardInfo() {
        return sqlService.guardInfo();
    }

    /** 生成请求：provider 可选，用于演示"强制指定 provider"。 */
    public record GenerateRequest(@NotBlank String question, String provider) {
    }

    /** SQL 请求。 */
    public record ExecuteRequest(@NotBlank String sql) {
    }
}
