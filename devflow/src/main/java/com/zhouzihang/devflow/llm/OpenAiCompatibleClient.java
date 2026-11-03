package com.zhouzihang.devflow.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhouzihang.devflow.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * OpenAI 兼容协议客户端（/chat/completions）。
 *
 * <p>面试讲解点：叫 "OpenAiCompatible" 而不是 "OpenAi"，是因为大量国产网关
 * （DeepSeek、通义、vLLM、One-API 等）都实现了同一套 HTTP 协议；只要
 * {@code app.llm.openai.base-url} 指向它们，代码零改动即可切换。
 * 这是"面向协议而不是面向厂商"的直接收益。</p>
 *
 * <p>对应 Spring AI：{@code OpenAiChatModel}（非流式 {@code call()}、
 * 流式 {@code stream()}）。这里用 JDK 自带 {@code java.net.http}，
 * 不引入 webflux / OKHttp，离线环境零额外依赖。</p>
 */
@Component
@Order(10)
public class OpenAiCompatibleClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);

    private final LlmProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public OpenAiCompatibleClient(LlmProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(3, props.getTimeoutSeconds() / 4)))
                .build();
    }

    @Override
    public String providerName() {
        return "openai";
    }

    @Override
    public String model() {
        return props.getOpenai().getModel();
    }

    @Override
    public boolean available() {
        LlmProperties.OpenAi cfg = props.getOpenai();
        return cfg.isEnabled() && cfg.getApiKey() != null && !cfg.getApiKey().isBlank();
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        long start = System.currentTimeMillis();
        ObjectNode body = buildBody(request, false);
        HttpRequest req = newRequest(body);
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("openai http " + resp.statusCode() + ": " + snippet(resp.body()));
            }
            JsonNode root = mapper.readTree(resp.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            JsonNode usage = root.path("usage");
            int pt = usage.path("prompt_tokens").asInt(estimPrompt(request));
            int ct = usage.path("completion_tokens").asInt(TokenEstimator.estimate(content));
            return LlmResponse.builder()
                    .requestId(request.getRequestId())
                    .provider(providerName())
                    .model(root.path("model").asText(model()))
                    .content(content)
                    .promptTokens(pt)
                    .completionTokens(ct)
                    .totalTokens(usage.path("total_tokens").asInt(pt + ct))
                    .finishReason(root.path("choices").path(0).path("finish_reason").asText("stop"))
                    .latencyMs(System.currentTimeMillis() - start)
                    .build();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("openai 调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void stream(LlmRequest request, LlmStreamListener listener) {
        long start = System.currentTimeMillis();
        listener.onStart(request.getRequestId(), providerName(), model());
        ObjectNode body = buildBody(request, true);
        HttpRequest req = newRequest(body);
        StringBuilder acc = new StringBuilder();
        try {
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                String err = snippet(new String(resp.body().readAllBytes(), StandardCharsets.UTF_8));
                listener.onError(new IllegalStateException("openai stream http " + resp.statusCode() + ": " + err));
                return;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith(":")) {
                        continue; // 心跳/注释行
                    }
                    if (!line.startsWith("data:")) {
                        continue; // event:/id: 等 SSE 字段，OpenAI 协议不用
                    }
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    JsonNode node = mapper.readTree(data);
                    String delta = node.path("choices").path(0).path("delta").path("content").asText("");
                    if (!delta.isEmpty()) {
                        acc.append(delta);
                        listener.onToken(delta);
                    }
                }
            }
            int pt = estimPrompt(request);
            int ct = TokenEstimator.estimate(acc.toString());
            listener.onComplete(LlmResponse.builder()
                    .requestId(request.getRequestId())
                    .provider(providerName())
                    .model(model())
                    .content(acc.toString())
                    .promptTokens(pt)
                    .completionTokens(ct)
                    .totalTokens(pt + ct)
                    .finishReason("stop")
                    .latencyMs(System.currentTimeMillis() - start)
                    .build());
        } catch (IOException e) {
            listener.onError(new IllegalStateException("openai 流式失败: " + e.getMessage(), e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.onError(new IllegalStateException("openai 流式被中断: " + e.getMessage(), e));
        }
    }

    private ObjectNode buildBody(LlmRequest request, boolean stream) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.getModel() == null ? model() : request.getModel());
        body.put("stream", stream);
        body.put("temperature", request.getTemperature());
        body.put("max_tokens", request.getMaxTokens());
        ArrayNode msgs = body.putArray("messages");
        for (LlmMessage m : request.allMessages()) {
            ObjectNode n = msgs.addObject();
            n.put("role", m.role());
            n.put("content", m.content());
        }
        return body;
    }

    private HttpRequest newRequest(ObjectNode body) {
        String url = trimSlash(props.getOpenai().getBaseUrl()) + "/chat/completions";
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.getOpenai().getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
    }

    private int estimPrompt(LlmRequest request) {
        StringBuilder sb = new StringBuilder();
        for (LlmMessage m : request.allMessages()) {
            sb.append(m.content());
        }
        return TokenEstimator.estimate(sb.toString());
    }

    private static String trimSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
