package com.zhouzihang.exam.llm;

/**
 * LLM 出题端口（六边形架构的 port）。
 *
 * <p>面试讲解点：业务层只依赖这个接口，不依赖 OpenAI SDK / Spring AI。
 * 好处：① 离线环境可以用 Mock 跑通全流程与测试；② 换模型（GPT/Claude/通义）
 * 只加一个实现；③ 单测不需要网络 mock 框架。CONVENTIONS 明确禁止 spring-ai-*，
 * 这个自研端口就是替代方案。</p>
 */
public interface LlmClient {

    LlmGeneration generate(LlmRequest request);

    /** 当前实现标识，用于任务表/统计。 */
    String providerName();
}
