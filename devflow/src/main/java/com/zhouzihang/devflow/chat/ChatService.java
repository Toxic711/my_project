package com.zhouzihang.devflow.chat;

import com.zhouzihang.devflow.common.ApiException;
import com.zhouzihang.devflow.common.ErrorCodes;
import com.zhouzihang.devflow.config.ChatProperties;
import com.zhouzihang.devflow.llm.LlmMessage;
import com.zhouzihang.devflow.llm.LlmRequest;
import com.zhouzihang.devflow.prompt.PromptRenderer;
import com.zhouzihang.devflow.prompt.PromptSeeder;
import com.zhouzihang.devflow.prompt.PromptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 对话准备：渲染 Prompt 模板 → 组装 provider 无关的 {@link LlmRequest}。
 *
 * <p>面试讲解点：把"渲染 + 组装"和"推流"分开，是因为两者失败语义不同——
 * 渲染失败（模板不存在/变量缺失）应在<b>建立 SSE 连接前</b>以 4xx 返回；
 * 推流失败则以 SSE {@code error} 帧返回。混在一起就会出现"已经回了 200 却只能
 * 在流里报错"的尴尬。</p>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final PromptService promptService;
    private final ChatProperties chatProperties;

    public ChatService(PromptService promptService, ChatProperties chatProperties) {
        this.promptService = promptService;
        this.chatProperties = chatProperties;
    }

    /**
     * 渲染模板并组装请求。
     *
     * @param promptCode 模板 code，空则用内置 chat
     * @param variables  模板变量（最少包含 question）
     * @param provider   可选：强制指定 provider（用于演示降级链）
     */
    public PreparedChat prepare(String promptCode, Map<String, String> variables, String provider) {
        String code = (promptCode == null || promptCode.isBlank()) ? "chat" : promptCode;
        Map<String, String> vars = variables == null ? Map.of() : variables;
        String rendered;
        int version;
        try {
            PromptService.RenderedPrompt r = promptService.render(code, vars);
            rendered = r.text();
            version = r.version();
        } catch (ApiException e) {
            if (!ErrorCodes.NOT_FOUND.equals(e.getCode())) {
                throw e; // 变量缺失等属于用户输入错误，直接 422
            }
            log.warn("prompt[{}] 不存在，回退内置 CHAT 模板", code);
            rendered = PromptRenderer.render(PromptSeeder.CHAT_TEMPLATE, vars);
            version = 0;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mock.question", vars.getOrDefault("question", ""));
        if (provider != null && !provider.isBlank()) {
            metadata.put("provider", provider);
        }
        LlmRequest request = LlmRequest.builder()
                .requestId("chat-" + UUID.randomUUID())
                .feature("chat")
                .messages(List.of(LlmMessage.user(rendered)))
                .maxTokens(chatProperties.getMaxTokens())
                .temperature(chatProperties.getTemperature())
                .metadata(metadata)
                .build();
        return new PreparedChat(request.getRequestId(), code, version, rendered, request);
    }

    /** 准备结果。 */
    public record PreparedChat(String requestId, String promptCode, int promptVersion,
                               String renderedPrompt, LlmRequest request) {
    }
}
