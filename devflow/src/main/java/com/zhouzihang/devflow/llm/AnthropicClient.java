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
 * Anthropic Messages API 客户端。
 *
 * <p>与 OpenAI 协议的三处关键差异（面试常问"多厂商抽象到底哪里不一样"）：</p>
 * <ol>
 *   <li>system 提示词<b>不是</b> messages 里的一条，而是顶层 {@code system} 字段；</li>
 *   <li>鉴权用 {@code x-api-key} + {@code anthropic-version}，不是 Bearer；</li>
 *   <li>流式事件的 JSON 结构不同：{@code content_block_delta.delta.text}，且用
 *       {@code event:} 命名事件（OpenAI 只有 {@code data:}）。</li>
 * </ol>
 * 这些差异正是"统一抽象"存在的理由：把它们吸收在实现里，上层业务只看到 token 流。
 */
@Component
@Order(20)
public class AnthropicClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicClient.class);

    private final LlmProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public AnthropicClient(LlmProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(3, props.getTimeoutSeconds() / 4)))
                .build();
    }

    @Override
    public String providerName() {
        return "anthropic";
    }

    @Override
    public String model() {
        return props.getAnthropic().getModel();
    }

    @Override
    public boolean available() {
        LlmProperties.Anthropic cfg = props.getAnthropic();
        return cfg.isEnabled() && cfg.getApiKey() != null && !cfg.getApiKey().isBlank();
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        long start = System.currentTimeMillis();
        HttpRequest req = newRequest(buildBody(request, false));
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("anthropic http " + resp.statusCode() + ": " + snippet(resp.body()));
            }
            JsonNode root = mapper.readTree(resp.body());
            StringBuilder text = new StringBuilder();
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    text.append(block.path("text").asText(""));
                }
            }
            int pt = root.path("usage").path("input_tokens").asInt(estimPrompt(request));
            int ct = root.path("usage").path("output_tokens").asInt(TokenEstimator.estimate(text.toString()));
            return LlmResponse.builder()
                    .requestId(request.getRequestId())
                    .provider(providerName())
                    .model(root.path("model").asText(model()))
                    .content(text.toString())
                    .promptTokens(pt)
                    .completionTokens(ct)
                    .totalTokens(pt + ct)
                    .finishReason(root.path("stop_reason").asText("stop"))
                    .latencyMs(System.currentTimeMillis() - start)
                    .build();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("anthropic 调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void stream(LlmRequest request, LlmStreamListener listener) {
        long start = System.currentTimeMillis();
        listener.onStart(request.getRequestId(), providerName(), model());
        HttpRequest req = newRequest(buildBody(request, true));
        StringBuilder acc = new StringBuilder();
        try {
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                String err = snippet(new String(resp.body().readAllBytes(), StandardCharsets.UTF_8));
                listener.onError(new IllegalStateException("anthropic stream http " + resp.statusCode() + ": " + err));
                return;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue; // event: 行由 data: 内容判定，跳过
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) {
                        continue;
                    }
                    JsonNode node = mapper.readTree(data);
                    String type = node.path("type").asText("");
                    if ("content_block_delta".equals(type)) {
                        String delta = node.path("delta").path("text").asText("");
                        if (!delta.isEmpty()) {
                            acc.append(delta);
                            listener.onToken(delta);
                        }
                    } else if ("message_stop".equals(type)) {
                        break;
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
            listener.onError(new IllegalStateException("anthropic 流式失败: " + e.getMessage(), e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.onError(new IllegalStateException("anthropic 流式被中断: " + e.getMessage(), e));
        }
    }

    private ObjectNode buildBody(LlmRequest request, boolean stream) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.getModel() == null ? model() : request.getModel());
        body.put("max_tokens", request.getMaxTokens());
        body.put("temperature", request.getTemperature());
        body.put("stream", stream);
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            body.put("system", request.getSystemPrompt());
        }
        ArrayNode msgs = body.putArray("messages");
        for (LlmMessage m : request.getMessages()) {
            // Anthropic 的 messages 只接受 user/assistant
            if ("system".equals(m.role())) {
                continue;
            }
            ObjectNode n = msgs.addObject();
            n.put("role", m.role());
            n.put("content", m.content());
        }
        return body;
    }

    private HttpRequest newRequest(ObjectNode body) {
        String url = trimSlash(props.getAnthropic().getBaseUrl()) + "/messages";
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("x-api-key", props.getAnthropic().getApiKey())
                .header("anthropic-version", props.getAnthropic().getVersion())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
    }

    private int estimPrompt(LlmRequest request) {
        StringBuilder sb = new StringBuilder();
        if (request.getSystemPrompt() != null) {
            sb.append(request.getSystemPrompt());
        }
        for (LlmMessage m : request.getMessages()) {
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
