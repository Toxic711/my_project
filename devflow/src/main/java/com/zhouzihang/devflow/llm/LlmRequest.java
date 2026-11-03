package com.zhouzihang.devflow.llm;

import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 调用请求（provider 无关的中间表示）。
 *
 * <p>面试讲解点：这是"端口"的入参模型。为什么不直接把 HTTP 请求体传下去？
 * 因为一旦业务代码耦合了某个厂商的 JSON 结构，换 provider 就要改业务；
 * 这里用 {@code feature} 标记业务场景（chat / sql-generate / code-review），
 * 既用于用量与成本归集，也用于 Mock 客户端的确定性输出选择。</p>
 */
@Getter
@Builder
public class LlmRequest {

    /** 请求唯一 ID，贯穿日志、用量表、SSE 帧，便于端到端排查。 */
    private final String requestId;

    /** 业务场景：chat | sql-generate | code-review。用于成本归集与 Mock 分支。 */
    private final String feature;

    /** 指定模型；为空则用 provider 默认模型。 */
    private final String model;

    /** 系统提示词（可选），等价于 Spring AI 的 SystemMessage。 */
    private final String systemPrompt;

    /** 对话消息（至少一条 user）。 */
    @Builder.Default
    private final List<LlmMessage> messages = new ArrayList<>();

    /** 期望最大 completion token。 */
    @Builder.Default
    private final int maxTokens = 800;

    /** 采样温度。 */
    @Builder.Default
    private final double temperature = 0.7;

    /** 透传元数据（超时、mock 提示、链路信息），不参与 prompt 渲染。 */
    @Builder.Default
    private final Map<String, Object> metadata = new LinkedHashMap<>();

    /** 拼成最终消息列表（system 优先）。 */
    public List<LlmMessage> allMessages() {
        List<LlmMessage> all = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            all.add(LlmMessage.system(systemPrompt));
        }
        all.addAll(messages);
        return all;
    }

    /** 取最后一条 user 消息文本，便于 Mock/日志使用。 */
    public String lastUserContent() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                return messages.get(i).content();
            }
        }
        return "";
    }
}
