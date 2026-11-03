package com.zhouzihang.exam.llm;

/** 出题请求（与具体模型解耦）。 */
public record LlmRequest(String type, String knowledgePoint, int count, String difficulty,
                         String extraInstruction) {
}
