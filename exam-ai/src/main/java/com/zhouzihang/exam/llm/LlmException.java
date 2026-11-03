package com.zhouzihang.exam.llm;

/** 大模型调用失败（网络/鉴权/输出不可解析）。由 Router 捕获并降级。 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
