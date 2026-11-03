package com.zhouzihang.devflow.llm;

/**
 * 流式回调监听器。
 *
 * <p>面试讲解点：Spring AI 的流式返回是 {@code Flux<ChatResponse>}（Reactor）；
 * 本项目 pom 里没有 webflux，也不该为了流式引入整个响应式栈。用回调
 * （listener）表达同一语义：{@code onStart → onToken* → onComplete/onError}，
 * 与 Flux 的 {@code onSubscribe → onNext* → onComplete/onError} 一一对应。
 * 上层的 SSE 控制器把回调翻译成 {@code SseEmitter} 帧即可，转换层很薄。</p>
 */
public interface LlmStreamListener {

    /** 首个 token 之前回调一次。 */
    default void onStart(String requestId, String provider, String model) {
    }

    /** 增量 token。 */
    void onToken(String delta);

    /** 正常结束，携带聚合后的完整响应（含 usage）。 */
    void onComplete(LlmResponse response);

    /** 异常结束；调用方负责发 error 帧并关闭流。 */
    void onError(Throwable error);
}
