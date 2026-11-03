package com.zhouzihang.devflow.prompt;

import com.zhouzihang.devflow.common.ApiException;
import com.zhouzihang.devflow.common.ErrorCodes;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ${var} 占位符渲染 + 渲染后校验。
 *
 * <p>面试讲解点：模板渲染不是"字符串 replace 就完事"，真正的坑有三个：</p>
 * <ol>
 *   <li><b>缺变量必须报错</b>，不能静默留空——否则 LLM 收到 "请审查这段 diff：" 后面空无一物，
 *       它会开始编内容（幻觉），比直接 4xx 危险得多。所以这里缺变量直接抛
 *       {@code PROMPT_MISSING_VARIABLE} 422。</li>
 *   <li><b>渲染后要二次校验</b>：变量值本身可能包含 {@code ${...}}（如用户输入），
 *       渲染后若仍有残留占位符说明注入污染，也要报错。</li>
 *   <li><b>不做 SQL/HTML 转义</b>，但要在文档里写清楚"变量值直接进 prompt"，
 *       因为 LLM 侧的注入防护属于提示词工程范畴（见 DESIGN.md 安全一节）。</li>
 * </ol>
 *
 * <p>对应 Spring AI 的 {@code PromptTemplate}（同样用 {@code ${}} 或 {@code {}} 占位），
 * 但 Spring AI 的渲染校验较弱，这里补了"缺变量→失败"的强约束。</p>
 */
public final class PromptRenderer {

    /** 变量名：字母数字下划线，支持点号分层（如 ${pr.title}）。 */
    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.]*)}");

    private PromptRenderer() {
    }

    /** 提取模板中出现的变量名（保持出现顺序、去重）。 */
    public static Set<String> extractVariables(String content) {
        Set<String> vars = new LinkedHashSet<>();
        if (content == null) {
            return vars;
        }
        Matcher m = VAR.matcher(content);
        while (m.find()) {
            vars.add(m.group(1));
        }
        return vars;
    }

    /** 渲染并校验。缺变量 → 422，残留占位符 → 422。 */
    public static String render(String content, Map<String, String> variables) {
        if (content == null) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "模板内容为空");
        }
        Map<String, String> vars = variables == null ? Map.of() : variables;
        Set<String> required = extractVariables(content);
        List<String> missing = required.stream()
                .filter(v -> {
                    String val = vars.get(v);
                    return val == null || val.isBlank();
                })
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new ApiException(ErrorCodes.PROMPT_MISSING_VARIABLE, HttpStatus.UNPROCESSABLE_ENTITY,
                    "模板变量缺失: " + missing + "（需要变量: " + required + "）");
        }
        Matcher m = VAR.matcher(content);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(content, last, m.start());
            // Matcher.appendReplacement 会把 $ 当分组引用，手工拼接更安全
            out.append(vars.get(m.group(1)));
            last = m.end();
        }
        out.append(content.substring(last));
        String rendered = out.toString();
        // 渲染后校验：变量值里若塞了 ${...}，说明存在模板注入，必须拒绝
        Set<String> residual = extractVariables(rendered);
        if (!residual.isEmpty()) {
            throw new ApiException(ErrorCodes.VALIDATION_ERROR, HttpStatus.UNPROCESSABLE_ENTITY,
                    "渲染后仍存在未解析占位符: " + residual + "（变量值中不允许再包含 ${} 语法）");
        }
        return rendered;
    }

    /** 变量名列表 → 逗号分隔字符串（用于持久化冗余字段）。 */
    public static String joinVariables(String content) {
        return String.join(",", extractVariables(content));
    }

    /** 判断模板是否包含某变量（测试/演示友好）。 */
    public static boolean hasVariable(String content, String name) {
        return extractVariables(content).contains(name);
    }

    /** 把 Map<String,?> 宽松转成 Map<String,String>（HTTP 入参常用）。 */
    public static Map<String, String> toStringMap(Map<String, ?> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        raw.forEach((k, v) -> out.put(k, v == null ? null : String.valueOf(v)));
        return out;
    }
}
