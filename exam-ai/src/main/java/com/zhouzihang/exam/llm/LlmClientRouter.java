package com.zhouzihang.exam.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 面试讲解点：LLM 客户端路由 + 自动降级。
 *
 * <p>规则：只有 provider=openai 且配置了 api-key 才走真实模型；
 * 否则直接 Mock。真实调用抛异常（网络/鉴权/输出不可解析）时，
 * <b>捕获异常并回退 Mock</b>，把 status 标成 FALLBACK 落库。
 * 这样“模型挂了”不会导致出题接口 500，而是降级返回可用题目，
 * 同时 /api/llm/usage 能看到降级率 —— 这是 LLM 功能可用性的关键设计。</p>
 *
 * <p>面试讲解点 2：降级不能“静默”。必须记录 provider=model/fallback 标记，
 * 让调用方和监控知道这次结果是模型生成的还是模板兜底的。</p>
 */
@Slf4j
@Service
public class LlmClientRouter {

    private final MockLlmClient mockLlmClient;
    private final OpenAiCompatibleClient openAiCompatibleClient;

    public LlmClientRouter(MockLlmClient mockLlmClient, OpenAiCompatibleClient openAiCompatibleClient) {
        this.mockLlmClient = mockLlmClient;
        this.openAiCompatibleClient = openAiCompatibleClient;
    }

    public LlmGeneration generate(LlmRequest request) {
        if (openAiCompatibleClient.isConfigured()) {
            try {
                return openAiCompatibleClient.generate(request);
            } catch (RuntimeException e) {
                log.warn("LLM 调用失败，降级 Mock: {}", e.getMessage());
                LlmGeneration fallback = mockLlmClient.generate(request);
                fallback.setProvider("mock(fallback)");
                fallback.setFallback(true);
                fallback.setStatus("FALLBACK");
                return fallback;
            }
        }
        LlmGeneration generation = mockLlmClient.generate(request);
        generation.setStatus("SUCCESS");
        return generation;
    }

    /** 当前“主选”provider 描述，供 usage 接口/README 说明。 */
    public String activeProvider() {
        return openAiCompatibleClient.isConfigured()
                ? openAiCompatibleClient.providerName() + ":" + "configured"
                : "mock";
    }
}
