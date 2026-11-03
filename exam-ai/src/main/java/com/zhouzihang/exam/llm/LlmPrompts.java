package com.zhouzihang.exam.llm;

/**
 * 出题 prompt 的单一来源，Mock 与 OpenAI 实现共用，避免两份 prompt 漂移。
 *
 * <p>面试讲解点：prompt 也是“生产代码”，应该版本化、可回归。
 * 这里把“输出 JSON schema”写进 prompt，并要求模型只输出 JSON，
 * 后端再做强校验，这是 LLM 工程化的基本盘。</p>
 */
public final class LlmPrompts {

    private LlmPrompts() {
    }

    public static String build(LlmRequest request) {
        String difficulty = request.difficulty() == null || request.difficulty().isBlank()
                ? "MEDIUM" : request.difficulty();
        StringBuilder sb = new StringBuilder();
        sb.append("你是一名资深命题老师。请围绕知识点「").append(request.knowledgePoint())
                .append("」生成 ").append(request.count()).append(" 道 ").append(request.type())
                .append(" 题，难度 ").append(difficulty).append("。\n")
                .append("要求：\n")
                .append("1) 每题给出题干 stem、选项 options（A/B/C/D；判断题用 T/F 两个选项）、")
                .append("正确答案 correctOption、解析 analysis、难度 difficulty、知识点 knowledgePoint、分值 score；\n")
                .append("2) 单选题 correctOption 为一个字母，多选题为两个字母（如 \"AC\"），判断题为 T 或 F；\n")
                .append("3) 只输出一个 JSON 对象，格式 {\"questions\":[{\"type\":\"SINGLE\",...}]}，不要输出多余文字。\n");
        if (request.extraInstruction() != null && !request.extraInstruction().isBlank()) {
            sb.append("补充要求：").append(request.extraInstruction()).append('\n');
        }
        return sb.toString();
    }
}
