package com.zhouzihang.devflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 相关配置（app.llm.*）。
 *
 * <p>面试讲解点：这里是自研 LLM 端口的"配置面"，等价于 Spring AI 的
 * {@code spring.ai.openai.*} / {@code spring.ai.anthropic.*} 自动配置属性；
 * 区别是 Spring AI 由 starter 自动装配 ChatModel，而我们把配置集中在一个
 * 不可变语义的属性类里，由 {@code ModelRouter} 决定"用哪个实现"，这样
 * 离线环境（无 spring-ai jar）也能保持同样的可插拔语义。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.llm")
public class LlmProperties {

    /** mock | openai | anthropic —— 指向"首选" provider，其余作为降级链候选。 */
    private String provider = "mock";

    /** 首选模型名；为空时用 provider 自己的默认模型。 */
    private String defaultModel = "gpt-4o-mini";

    /** 单次对话超时（秒）；流式对话由 SSE 超时兜底。 */
    private int timeoutSeconds = 60;

    /** 失败重试次数（不含首次）。 */
    private int maxRetries = 2;

    /** 退避基数（毫秒），第 n 次重试等待 backoff * 2^(n-1)。 */
    private long retryBackoffMs = 200;

    /** 单次审查允许的最大 diff 字符数，超出截断，防止 token 爆炸。 */
    private int maxDiffChars = 12000;

    /** 单次审查最多分几段调用（成本/时延上限）。 */
    private int maxReviewSegments = 3;

    /** 流式输出每个 token 分片之间的间隔（毫秒），仅 Mock 使用，便于肉眼观察 SSE。 */
    private long mockStreamDelayMs = 12;

    private OpenAi openai = new OpenAi();
    private Anthropic anthropic = new Anthropic();

    /** 单价表：key = 模型名，用于成本估算（不做真实计费）。 */
    private Map<String, Price> pricing = new LinkedHashMap<>();

    @Data
    public static class OpenAi {
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
        private String model = "gpt-4o-mini";
        /** 兼容层：是否走 OpenAI 协议（true 时任何兼容网关都能接入）。 */
        private boolean enabled = true;
    }

    @Data
    public static class Anthropic {
        private String baseUrl = "https://api.anthropic.com/v1";
        private String apiKey = "";
        private String model = "claude-3-5-sonnet-20241022";
        private String version = "2023-06-01";
        private boolean enabled = true;
    }

    @Data
    public static class Price {
        /** 每 1K prompt token 的美元单价。 */
        private BigDecimal inputPer1k = BigDecimal.ZERO;
        /** 每 1K completion token 的美元单价。 */
        private BigDecimal outputPer1k = BigDecimal.ZERO;
    }
}
