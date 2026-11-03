package com.zhouzihang.exam.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhouzihang.exam.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容端点实现（java.net.http，无 SDK 依赖）。
 *
 * <p>面试讲解点 1：为什么要“OpenAI 兼容”而不是写死 OpenAI？
 * 国内大量模型服务（DeepSeek/通义/智谱等）都提供 /v1/chat/completions 兼容协议，
 * 配置 base-url 即可切换，无需改代码。</p>
 *
 * <p>面试讲解点 2：超时/重试策略。HttpClient 设置 connect timeout，
 * 单次请求设置总 timeout；失败不在这里重试（交给 Router 降级 Mock），
 * 避免“LLM 慢导致接口雪崩”。</p>
 *
 * <p>面试讲解点 3：绝不信任模型输出。content 可能是 ```json 包裹、可能缺字段，
 * 这里做去围栏 + 字段解析，业务层还会再做一次校验（选项数、答案合法）。</p>
 */
@Slf4j
@Component
public class OpenAiCompatibleClient implements LlmClient {

    private static final String PROVIDER = "openai-compatible";

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleClient(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** 只有显式选择 openai 且配置了 Key 才启用；否则 Router 走 Mock。 */
    public boolean isConfigured() {
        AppProperties.Llm llm = properties.getLlm();
        return "openai".equalsIgnoreCase(llm.getProvider())
                && llm.getApiKey() != null && !llm.getApiKey().isBlank();
    }

    @Override
    public LlmGeneration generate(LlmRequest request) {
        long start = System.currentTimeMillis();
        String prompt = LlmPrompts.build(request);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", properties.getLlm().getModel());
            body.put("temperature", 0.7);
            body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            body.put("response_format", Map.of("type", "json_object"));

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(properties.getLlm().getBaseUrl()) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(properties.getLlm().getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getLlm().getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new LlmException("LLM HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode choice = root.path("choices").path(0).path("message").path("content");
            if (choice.isMissingNode() || choice.asText().isBlank()) {
                throw new LlmException("LLM 响应缺少 choices[0].message.content");
            }
            List<GeneratedQuestion> questions = parseQuestions(choice.asText());
            JsonNode usage = root.path("usage");
            int promptTokens = usage.path("prompt_tokens").asInt(prompt.length() / 4);
            int completionTokens = usage.path("completion_tokens").asInt(0);
            long latency = System.currentTimeMillis() - start;

            return LlmGeneration.builder()
                    .provider(PROVIDER)
                    .model(properties.getLlm().getModel())
                    .prompt(prompt)
                    .rawContent(choice.asText())
                    .questions(questions)
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .latencyMs(latency)
                    .fallback(false)
                    .status("SUCCESS")
                    .build();
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("调用 OpenAI 兼容端点失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    private List<GeneratedQuestion> parseQuestions(String content) throws Exception {
        String json = stripCodeFence(content);
        JsonNode node = objectMapper.readTree(json);
        JsonNode array = node.has("questions") ? node.get("questions") : node;
        if (!array.isArray()) {
            throw new LlmException("LLM 输出不是题目数组");
        }
        List<GeneratedQuestion> questions = new ArrayList<>();
        for (JsonNode item : array) {
            GeneratedQuestion q = new GeneratedQuestion();
            q.setType(item.path("type").asText(null));
            q.setStem(item.path("stem").asText(null));
            q.setCorrectOption(item.path("correctOption").asText(null));
            q.setAnalysis(item.path("analysis").asText(null));
            q.setDifficulty(item.path("difficulty").asText(null));
            q.setKnowledgePoint(item.path("knowledgePoint").asText(null));
            q.setScore(item.path("score").asInt(5));
            Map<String, String> options = new LinkedHashMap<>();
            JsonNode optionsNode = item.path("options");
            if (optionsNode.isObject()) {
                optionsNode.fields().forEachRemaining(e -> options.put(e.getKey(), e.getValue().asText()));
            }
            q.setOptions(options);
            questions.add(q);
        }
        return questions;
    }

    private static String stripCodeFence(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        String body = firstLineEnd > 0 ? trimmed.substring(firstLineEnd + 1) : trimmed;
        if (body.endsWith("```")) {
            body = body.substring(0, body.length() - 3);
        }
        return body.trim();
    }

    private static String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
