package com.zhouzihang.devflow.review;

/**
 * Subset of the issue found by the reviewer.
 *
 * <p>结构化 findings 是"可展示 + 可断言"的关键：如果让 LLM 输出自由文本，
 * 前端没法渲染成列表，测试也没法断言；所以强制 JSON 结构并用 Jackson 解析，
 * 解析失败时降级为 PARTIAL 而不是抛 500。</p>
 *
 * @param file       相对路径（来自 diff 的 +++ b/...）
 * @param line       新文件中的行号（来自 hunk header 推算）
 * @param severity   HIGH / MEDIUM / LOW
 * @param message    问题描述
 * @param suggestion 修改建议
 */
public record Finding(String file, int line, String severity, String message, String suggestion) {

    /** 归一化严重级别（未知值按 LOW 处理，保证统计口径稳定）。 */
    public static String normalizeSeverity(String raw) {
        if (raw == null) {
            return "LOW";
        }
        String s = raw.trim().toUpperCase();
        return switch (s) {
            case "HIGH", "CRITICAL", "BLOCKER" -> "HIGH";
            case "MEDIUM", "MAJOR", "WARNING" -> "MEDIUM";
            default -> "LOW";
        };
    }
}
