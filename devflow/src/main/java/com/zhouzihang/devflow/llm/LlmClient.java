package com.zhouzihang.devflow.llm;

/**
 * LLM 统一抽象（端口）。
 *
 * <p>面试讲解点：这就是自研的 "Spring AI ChatClient 等价物"。
 * Spring AI 里调用链是 {@code ChatClient.prompt(template).user(vars).call()/stream()}，
 * 底层落到某个 {@code ChatModel}；本项目把"模型实现"收敛成这个接口：</p>
 *
 * <pre>
 *   Spring AI                    →  本项目
 *   ------------------------------------------------
 *   ChatClient (门面)            →  ModelRouter + 各业务 Service
 *   ChatModel / StreamingChatModel → LlmClient（本接口）
 *   PromptTemplate + 变量渲染     →  PromptTemplateEntity + PromptRenderer
 *   Advisor（切面：日志/计费/检索） →  TokenAccounting + ModelRouter 重试装饰
 *   FunctionCallback（工具调用）    →  未实现（见 docs/DESIGN.md 已知限制）
 * </pre>
 *
 * <p>为什么自研而不是直接用 Spring AI：离线环境没有 {@code spring-ai-*} 的 jar，
 * 而且面试中能讲清"抽象边界在哪、降级怎么做"比调用一个 starter 更有价值。</p>
 */
public interface LlmClient {

    /** provider 名称，必须与配置里的 {@code app.llm.provider} 对应（mock/openai/anthropic）。 */
    String providerName();

    /** 该实现默认使用的模型名。 */
    String model();

    /**
     * 配置是否完备（真实 provider 需要 api-key）。
     * 返回 false 时 ModelRouter 会跳过它，直接走降级链。
     */
    boolean available();

    /** 同步对话。实现必须自行处理 HTTP 超时，并把异常包装成 IllegalStateException/LlmException 语义。 */
    LlmResponse chat(LlmRequest request);

    /** 流式对话。实现按 token 回调 listener，最后一个回调必须是 onComplete 或 onError。 */
    void stream(LlmRequest request, LlmStreamListener listener);
}
