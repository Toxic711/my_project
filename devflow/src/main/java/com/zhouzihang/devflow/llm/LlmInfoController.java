package com.zhouzihang.devflow.llm;

import com.zhouzihang.devflow.config.LlmProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 路由自省接口（演示/排障用）。
 *
 * <p>面试讲解点：降级链"能不能生效"必须可观察。这个接口直接吐出
 * 每个 provider 的 {@code available()} 结果与当前会尝试的链路，
 * 排查"为什么走了 Mock"时一眼就能看出是没配 api-key 还是被开关关了。</p>
 */
@RestController
@RequestMapping("/api/llm")
public class LlmInfoController {

    private final ModelRouter router;
    private final LlmProperties props;

    public LlmInfoController(ModelRouter router, LlmProperties props) {
        this.router = router;
        this.props = props;
    }

    @GetMapping("/info")
    public Map<String, Object> info(@RequestParam(required = false) String prefer) {
        String preferred = (prefer == null || prefer.isBlank()) ? props.getProvider() : prefer;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configuredProvider", props.getProvider());
        m.put("preferredProvider", preferred);
        m.put("resolvedChain", router.resolveChain(preferred));
        m.put("clients", router.clientInfo());
        m.put("defaultModel", props.getDefaultModel());
        m.put("timeoutSeconds", props.getTimeoutSeconds());
        m.put("maxRetries", props.getMaxRetries());
        m.put("retryBackoffMs", props.getRetryBackoffMs());
        m.put("pricing", props.getPricing());
        List<String> notes = List.of(
                "mock 恒可用：它是降级链终点，保证离线/无 key 环境可用",
                "真实 provider 需要配置 api-key，未配置时 available=false 会被自动跳过",
                "流式调用一旦吐出首个 token 就不再切换 provider，避免内容重复");
        m.put("notes", notes);
        return m;
    }
}
