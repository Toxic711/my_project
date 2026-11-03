package com.zhouzihang.devflow.prompt;

import com.zhouzihang.devflow.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt 模板渲染测试。
 *
 * <p>面试点：缺变量必须报错而不是静默留空——否则模型会对着空上下文"编内容"（幻觉），
 * 比直接返回 422 危险得多。</p>
 */
class PromptRendererTest {

    @Test
    @DisplayName("提取变量：支持点号分层，按出现顺序去重")
    void extractVariables() {
        Set<String> vars = PromptRenderer.extractVariables(
                "请审查仓库 ${pr.repo} 的 PR #${pr.number}，重点关注 ${focus}，再次强调 ${focus}。");
        assertEquals(3, vars.size());
        assertTrue(vars.contains("pr.repo"));
        assertTrue(vars.contains("pr.number"));
        assertTrue(vars.contains("focus"));
    }

    @Test
    @DisplayName("渲染：变量注入后不残留占位符")
    void renderReplacesAll() {
        String out = PromptRenderer.render("你好 ${name}，今天是 ${day}。",
                Map.of("name", "子航", "day", "周一"));
        assertEquals("你好 子航，今天是 周一。", out);
        assertFalse(out.contains("${"));
    }

    @Test
    @DisplayName("缺变量：抛 422（ApiException），不能静默留空")
    void missingVariableFails() {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("name", "子航");
        ApiException ex = assertThrows(ApiException.class,
                () -> PromptRenderer.render("${name} / ${missingVar}", vars));
        assertTrue(("" + ex.getMessage()).contains("missingVar"));
    }

    @Test
    @DisplayName("注入污染：变量值里带 ${...} 视为非法（渲染后残留检测）")
    void injectionPollutionRejected() {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("topic", "${secret}");
        assertThrows(ApiException.class,
                () -> PromptRenderer.render("请解释 ${topic}", vars));
    }

    @Test
    @DisplayName("无变量模板直接返回原文")
    void noVariables() {
        assertEquals("纯文本模板", PromptRenderer.render("纯文本模板", Map.of()));
        assertTrue(PromptRenderer.extractVariables(null).isEmpty());
    }
}
