package com.zhouzihang.devflow.chat;

import com.zhouzihang.devflow.common.ErrorCodes;
import com.zhouzihang.devflow.config.ChatProperties;
import com.zhouzihang.devflow.llm.LlmResponse;
import com.zhouzihang.devflow.llm.LlmStreamListener;
import com.zhouzihang.devflow.llm.ModelRouter;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE 流式对话。
 *
 * <p>面试讲解点（SSE 的工程细节，逐条都是踩坑点）：</p>
 * <ol>
 *   <li><b>必须关掉中间层缓冲</b>：{@code Cache-Control: no-cache} +
 *       {@code X-Accel-Buffering: no}，否则 Nginx 会攒够 4KB 才转发，
 *       表现为"流式变成了等一整块"。</li>
 *   <li><b>心跳</b>：每 {@code app.chat.heartbeat-ms} 发一个 SSE 注释帧 {@code : ping}，
 *       注释帧不会被客户端 {@code onmessage} 触发，但能维持 TCP/网关空闲检测。</li>
 *   <li><b>总超时</b>：{@code SseEmitter(timeoutMs)} 必须设，否则慢连接会长期占用线程。</li>
 *   <li><b>客户端断开要停上游</b>：注册 {@code onCompletion/onTimeout/onError}，
 *       置位取消标记 + 中断工作线程，让上游 LLM 循环立即退出
 *       ——不处理的话会白烧 token（成本问题）并占住线程（容量问题）。</li>
 *   <li><b>错误域分离</b>：参数/模板错误在建立连接前返回 4xx JSON；
 *       已经 200 之后的失败只能发 {@code event: error} 再正常关闭。</li>
 * </ol>
 *
 * <p>为什么用 {@code SseEmitter} 而不是 WebFlux 的 {@code Flux<ServerSentEvent>}：
 * 引入 webflux 会改变整个 Web 栈（Servlet→Reactive），而本项目已经依赖 MVC + MyBatis；
 * SseEmitter 用回调表达同一个"服务器推送"语义，代价小得多。</p>
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final ModelRouter router;
    private final ChatProperties chatProperties;
    private final ThreadPoolTaskExecutor sseTaskExecutor;
    private final ScheduledExecutorService heartbeatScheduler;

    public ChatController(ChatService chatService, ModelRouter router, ChatProperties chatProperties,
                          @Qualifier("sseTaskExecutor") ThreadPoolTaskExecutor sseTaskExecutor,
                          @Qualifier("heartbeatScheduler") ScheduledExecutorService heartbeatScheduler) {
        this.chatService = chatService;
        this.router = router;
        this.chatProperties = chatProperties;
        this.sseTaskExecutor = sseTaskExecutor;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    /**
     * POST 版本（推荐）：请求体可携带任意变量与 provider 覆盖。
     * 浏览器 {@code EventSource} 不支持 POST，前端用 fetch + ReadableStream 读取。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamPost(@RequestBody ChatRequest request, HttpServletResponse response) {
        return start(request.promptCode(), request.variables(), request.provider(), response);
    }

    /**
     * GET 版本：专为浏览器 {@code EventSource}（只支持 GET）准备。
     * 演示页会同时展示 fetch 流式与 EventSource 两种消费方式。
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamGet(@RequestParam(defaultValue = "chat") String promptCode,
                                @RequestParam(required = false) String question,
                                @RequestParam(required = false) String provider,
                                HttpServletResponse response) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (question != null && !question.isBlank()) {
            vars.put("question", question);
        }
        return start(promptCode, vars, provider, response);
    }

    private SseEmitter start(String promptCode, Map<String, String> variables, String provider,
                             HttpServletResponse response) {
        // 渲染与参数校验在建立连接前完成：失败走统一 4xx，而不是在流里报错
        ChatService.PreparedChat prepared = chatService.prepare(promptCode, variables, provider);
        // 关键响应头：禁止缓存 + 禁止反向代理缓冲，否则流式失效
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Request-Id", prepared.requestId());
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8");

        SseEmitter emitter = new SseEmitter(chatProperties.getSseTimeoutMs());
        try {
            sseTaskExecutor.execute(() -> run(emitter, prepared));
        } catch (RejectedExecutionException e) {
            log.warn("SSE 线程池已满，拒绝请求 requestId={}", prepared.requestId());
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("code", ErrorCodes.INTERNAL_ERROR, "message", "服务繁忙，请稍后重试")));
            } catch (IOException ignore) {
                // 忽略：连接可能已断开
            }
            emitter.complete();
        }
        return emitter;
    }

    private void run(SseEmitter emitter, ChatService.PreparedChat prepared) {
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicInteger index = new AtomicInteger();
        Thread worker = Thread.currentThread();
        Runnable cancel = () -> {
            if (closed.compareAndSet(false, true)) {
                // 中断工作线程 → 上游 LLM 循环 sleep/读取时立刻退出，停止烧 token
                worker.interrupt();
            }
        };
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancel);
        emitter.onError(t -> cancel.run());

        ScheduledFuture<?> heartbeat = null;
        try {
            send(emitter, "start", payload(
                    "requestId", prepared.requestId(),
                    "promptCode", prepared.promptCode(),
                    "promptVersion", prepared.promptVersion(),
                    "provider", router.preferredProvider(prepared.request()),
                    "chain", router.resolveChain(router.preferredProvider(prepared.request())),
                    "heartbeatMs", chatProperties.getHeartbeatMs()));
            long hb = Math.max(1000L, chatProperties.getHeartbeatMs());
            heartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    cancel.run();
                }
            }, hb, hb, TimeUnit.MILLISECONDS);

            router.stream(prepared.request(), new LlmStreamListener() {
                @Override
                public void onStart(String requestId, String provider, String model) {
                    send(emitter, "meta", payload("provider", provider, "model", model));
                }

                @Override
                public void onToken(String delta) {
                    if (closed.get()) {
                        throw new IllegalStateException("客户端已断开，停止上游生成");
                    }
                    send(emitter, "delta", payload("i", index.incrementAndGet(), "text", delta));
                }

                @Override
                public void onComplete(LlmResponse response) {
                    send(emitter, "done", payload(
                            "requestId", response.getRequestId(),
                            "provider", response.getProvider(),
                            "model", response.getModel(),
                            "degraded", response.isDegraded(),
                            "chars", response.getContent() == null ? 0 : response.getContent().length(),
                            "frames", index.get(),
                            "promptTokens", response.getPromptTokens(),
                            "completionTokens", response.getCompletionTokens(),
                            "totalTokens", response.getTotalTokens(),
                            "costUsd", response.getCostUsd(),
                            "latencyMs", response.getLatencyMs()));
                }

                @Override
                public void onError(Throwable error) {
                    if (closed.get()) {
                        return; // 客户端已断开，无需再发
                    }
                    send(emitter, "error", payload("code", ErrorCodes.LLM_UNAVAILABLE,
                            "message", error == null ? "未知错误" : String.valueOf(error.getMessage())));
                }
            });
        } catch (RuntimeException e) {
            log.warn("SSE 推流异常 requestId={} err={}", prepared.requestId(), e.getMessage());
            if (!closed.get()) {
                send(emitter, "error", payload("code", ErrorCodes.INTERNAL_ERROR,
                        "message", String.valueOf(e.getMessage())));
            }
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            try {
                emitter.complete();
            } catch (RuntimeException ignore) {
                // 连接已关闭时 complete 可能抛异常，忽略
            }
        }
    }

    /** 统一发送：客户端断开（IOException）时抛出，交由上层取消上游。 */
    private void send(SseEmitter emitter, String event, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            throw new IllegalStateException("SSE 发送失败: " + e.getMessage(), e);
        }
    }

    /** Map.of 最多 10 对且不允许 null，这里用可变 Map 支持任意键值（含 null）。 */
    private Map<String, Object> payload(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    /** 对话请求：promptCode 可空（默认 chat），variables 至少包含 question。 */
    public record ChatRequest(String promptCode, Map<String, String> variables, String provider) {
    }
}
