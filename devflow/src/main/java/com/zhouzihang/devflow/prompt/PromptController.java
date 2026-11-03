package com.zhouzihang.devflow.prompt;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Prompt 模板管理 API。
 *
 * <pre>
 * POST   /api/prompts                 新建（version=1）
 * PUT    /api/prompts/{id}            生成新版本（version = max+1）
 * GET    /api/prompts                 每个 code 的最新版本
 * GET    /api/prompts/{code}          最新版本详情
 * GET    /api/prompts/{code}/versions 全部版本（新→旧）
 * POST   /api/prompts/{code}/render   渲染预览（缺变量报 422）
 * </pre>
 *
 * <p>面试讲解点：为什么"改模板"用 PUT /{id} 而不是 PUT /{code}？
 * 因为版本是<b>新资源</b>，语义上更像 POST 子资源；这里保留 PUT 是为了让
 * "编辑已有模板"符合 REST 直觉（幂等性由前端传 id 保证），同时在响应里返回
 * 新版本号，调用方可立刻确认生效版本。</p>
 */
@RestController
@RequestMapping("/api/prompts")
public class PromptController {

    private final PromptService promptService;

    public PromptController(PromptService promptService) {
        this.promptService = promptService;
    }

    @PostMapping
    public ResponseEntity<PromptTemplate> create(@Valid @RequestBody CreateRequest req) {
        PromptTemplate created = promptService.create(req.code(), req.name(), req.content());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public PromptTemplate update(@PathVariable Long id, @RequestBody UpdateRequest req) {
        return promptService.createVersion(id, req.name(), req.content());
    }

    @GetMapping
    public List<PromptTemplate> list() {
        return promptService.listLatest();
    }

    @GetMapping("/{code}")
    public PromptTemplate latest(@PathVariable String code) {
        return promptService.latest(code);
    }

    @GetMapping("/{code}/versions")
    public List<PromptTemplate> versions(@PathVariable String code) {
        return promptService.versions(code);
    }

    @PostMapping("/{code}/render")
    public PromptService.RenderedPrompt render(@PathVariable String code,
                                               @RequestBody(required = false) Map<String, Object> body) {
        Map<String, String> vars = PromptRenderer.toStringMap(body == null ? Map.of() : body);
        return promptService.render(code, vars);
    }

    /** 新建请求。 */
    public record CreateRequest(@NotBlank String code, String name, @NotBlank String content) {
    }

    /** 新版本请求：两个字段都可空——为空则继承上一版。 */
    public record UpdateRequest(String name, String content) {
    }
}
