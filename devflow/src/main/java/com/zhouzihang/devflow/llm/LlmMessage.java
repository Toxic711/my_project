package com.zhouzihang.devflow.llm;

/**
 * 一条对话消息。
 *
 * <p>对应 Spring AI 的 {@code org.springframework.ai.chat.messages.Message}
 * （SystemMessage / UserMessage / AssistantMessage 的父类型）。</p>
 *
 * @param role    system | user | assistant
 * @param content 文本内容（本实现只支持纯文本，多模态属于扩展点）
 */
public record LlmMessage(String role, String content) {

    public static LlmMessage system(String content) {
        return new LlmMessage("system", content);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage("assistant", content);
    }
}
