package com.zhouzihang.devflow.llm;

import com.zhouzihang.devflow.common.ApiException;
import com.zhouzihang.devflow.common.ErrorCodes;
import com.zhouzihang.devflow.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型路由 + 超时 + 重试 + 降级链。
 *
 * <p>面试讲解点（核心）：这是整个平台"可用性"的兜底。三段式设计：</p>
 * <ol>
 *   <li><b>路由</b>：按 {@code prefer=provider} 选首选实现；配置里 provider 为空则用
 *       {@code app.llm.provider}。provider 不唯一，靠 {@link LlmClient#providerName()} 匹配。</li>
 *   <li><b>重试</b>：同一 provider 内指数退避重试（200ms → 400ms），只对瞬时故障有效；
 *       持续失败才降级，避免"还没试完就换人"。</li>
 *   <li><b>降级链</b>：openai → anthropic → mock。mock 永远可用，所以离线/无 key
 *       环境永远不会 500，只会返回带 {@code degraded=true} 的 Mock 输出——
 *       前端据此提示"当前为降级响应"，而不是假装成功。</li>
 * </ol>
 *
 * <p>超时实现：把阻塞的 {@code chat} 提交到有界线程池 + {@code Future.get(timeout)}。
 * 注意 JDK HttpClient 的 send 不响应中断，因此超时后任务可能仍跑到自然结束——
 * 这是已知限制（见 DESIGN.md），生产应换成可取消的异步客户端（如 WebClient）。
 * 流式调用不走超时包装（超时由 SSE 侧 {@code SseEmitter} 总超时兜底）。</p>
 */
@Service
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    /** 降级顺序（Mock 永远最后，作为可用性兜底）。 */
    private static final List<String> FALLBACK_ORDER = List.of("openai", "anthropic", "mock");

    private final Map<String, LlmClient> byProvider;
    private final LlmProperties props;
    private final ThreadPoolTaskExecutor llmTaskExecutor;
    private final TokenAccounting accounting;

    public ModelRouter(List<LlmClient> clients,
                       LlmProperties props,
                       @Qualifier("llmTaskExecutor") ThreadPoolTaskExecutor llmTaskExecutor,
                       TokenAccounting accounting) {
        this.props = props;
        this.llmTaskExecutor = llmTaskExecutor;
        this.accounting = accounting;
        Map<String, LlmClient> map = new LinkedHashMap<>();
        for (LlmClient c : clients) {
            map.put(c.providerName(), c);
        }
        this.byProvider = map;
    }

    /** 首选 provider：请求级覆盖 > 全局配置 > mock。 */
    public String preferredProvider(LlmRequest request) {
        Object override = request.getMetadata().get("provider");
        if (override != null && !String.valueOf(override).isBlank()) {
            return String.valueOf(override);
        }
        String p = props.getProvider();
        return (p == null || p.isBlank()) ? "mock" : p;
    }

    /** 当前会实际尝试的 provider 顺序（已过滤不可用实现）。 */
    public List<String> resolveChain(String preferred) {
        Set<String> chain = new LinkedHashSet<>();
        if (preferred != null && !preferred.isBlank()) {
            chain.add(preferred);
        }
        for (String p : FALLBACK_ORDER) {
            chain.add(p);
        }
        List<String> result = new ArrayList<>();
        for (String p : chain) {
            LlmClient c = byProvider.get(p);
            if (c != null && c.available()) {
                result.add(p);
            }
        }
        // 理论不可达：Mock 恒可用
        if (result.isEmpty()) {
            result.add("mock");
        }
        return result;
    }

    /**
     * 同步对话（带超时/重试/降级）。
     */
    public LlmResponse chat(LlmRequest request) {
        String preferred = preferredProvider(request);
        List<String> chain = resolveChain(preferred);
        RuntimeException lastError = null;
        for (String provider : chain) {
            LlmClient client = byProvider.get(provider);
            if (client == null) {
                continue;
            }
            int attempts = Math.max(1, props.getMaxRetries() + 1);
            for (int attempt = 1; attempt <= attempts; attempt++) {
                try {
                    LlmResponse resp = callWithTimeout(client, request);
                    resp.setDegraded(!provider.equals(preferred));
                    if (resp.getRequestId() == null) {
                        resp.setRequestId(request.getRequestId());
                    }
                    accounting.record(request.getFeature(), resp);
                    if (!provider.equals(preferred)) {
                        log.warn("LLM 降级：preferred={} actual={} requestId={}", preferred, provider, request.getRequestId());
                    }
                    return resp;
                } catch (RuntimeException e) {
                    lastError = e;
                    log.warn("LLM 调用失败 provider={} attempt={}/{} requestId={} err={}",
                            provider, attempt, attempts, request.getRequestId(), e.getMessage());
                    if (attempt < attempts) {
                        sleepBackoff(attempt);
                    }
                }
            }
        }
        throw new ApiException(ErrorCodes.LLM_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                "所有 LLM provider 均不可用（尝试链=" + chain + "）："
                        + (lastError == null ? "未知原因" : lastError.getMessage()), lastError);
    }

    /**
     * 流式对话（带降级，但已出 token 后不再切换 provider，避免内容重复/错乱）。
     */
    public void stream(LlmRequest request, LlmStreamListener listener) {
        String preferred = preferredProvider(request);
        List<String> chain = resolveChain(preferred);
        RuntimeException lastError = null;
        for (String provider : chain) {
            LlmClient client = byProvider.get(provider);
            if (client == null) {
                continue;
            }
            AtomicBoolean firstToken = new AtomicBoolean(false);
            AtomicBoolean completed = new AtomicBoolean(false);
            AtomicReference<Throwable> streamError = new AtomicReference<>();
            LlmStreamListener delegate = new LlmStreamListener() {
                @Override
                public void onStart(String requestId, String p, String model) {
                    listener.onStart(requestId, p, model);
                }

                @Override
                public void onToken(String delta) {
                    firstToken.set(true);
                    listener.onToken(delta);
                }

                @Override
                public void onComplete(LlmResponse response) {
                    completed.set(true);
                    response.setDegraded(!provider.equals(preferred));
                    if (response.getRequestId() == null) {
                        response.setRequestId(request.getRequestId());
                    }
                    accounting.record(request.getFeature(), response);
                    listener.onComplete(response);
                }

                @Override
                public void onError(Throwable error) {
                    streamError.set(error);
                }
            };
            try {
                client.stream(request, delegate);
            } catch (RuntimeException e) {
                streamError.set(e);
            }
            if (completed.get()) {
                return;
            }
            if (firstToken.get()) {
                // 已经推送过 token：不能再换 provider（会重复内容），直接把错误抛给上层
                Throwable err = streamError.get() == null
                        ? new IllegalStateException("流式输出中断") : streamError.get();
                listener.onError(err);
                return;
            }
            lastError = new RuntimeException("provider=" + provider + " 流式失败: "
                    + (streamError.get() == null ? "unknown" : streamError.get().getMessage()), streamError.get());
            log.warn("LLM 流式失败，尝试降级：provider={} requestId={} err={}",
                    provider, request.getRequestId(), lastError.getMessage());
        }
        listener.onError(lastError == null
                ? new ApiException(ErrorCodes.LLM_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "无可用 LLM provider")
                : lastError);
    }

    /** 供 /api/llm/info 演示用：各 provider 的可用性与当前路由链。 */
    public List<Map<String, Object>> clientInfo() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (LlmClient c : byProvider.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("provider", c.providerName());
            m.put("model", c.model());
            m.put("available", c.available());
            list.add(m);
        }
        return list;
    }

    public String configuredProvider() {
        return props.getProvider();
    }

    private LlmResponse callWithTimeout(LlmClient client, LlmRequest request) {
        long timeoutMs = Math.max(1, props.getTimeoutSeconds()) * 1000L;
        CompletableFuture<LlmResponse> future;
        try {
            future = CompletableFuture.supplyAsync(() -> client.chat(request), llmTaskExecutor);
        } catch (RejectedExecutionException e) {
            // 线程池满：快速失败，交给降级链，而不是让请求线程同步阻塞
            throw new IllegalStateException("LLM 线程池已满，拒绝执行", e);
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("LLM 调用超时(" + timeoutMs + "ms) provider=" + client.providerName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new IllegalStateException("LLM 调用被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException(cause.getMessage(), cause);
        }
    }

    private void sleepBackoff(int attempt) {
        long ms = Math.max(1, props.getRetryBackoffMs()) * (1L << Math.min(5, attempt - 1));
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
