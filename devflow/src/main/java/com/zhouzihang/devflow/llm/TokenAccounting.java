package com.zhouzihang.devflow.llm;

import com.zhouzihang.devflow.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Token 与成本核算。
 *
 * <p>面试讲解点：这是 Spring AI 里 "Advisor / ObservationHandler" 的等价物——
 * 一个横切关注点（计量），但不能写成 AOP 拦截某个方法，因为流式调用的
 * 用量是"结束时才知道"的，必须由 {@link ModelRouter} 在 onComplete 时显式上报。
 * 所以这里做成显式服务而不是切面，理由写在 DESIGN.md 的成本核算一节。</p>
 *
 * <p>成本口径：{@code cost = promptTokens/1000 * inputPrice + completionTokens/1000 * outputPrice}，
 * 单价来自 {@code app.llm.pricing.<model>}，未知模型记 0 成本并在汇总里保留 token 数，
 * 避免"算不出成本就丢数据"。</p>
 */
@Service
public class TokenAccounting {

    private static final Logger log = LoggerFactory.getLogger(TokenAccounting.class);

    private final LlmUsageMapper mapper;
    private final LlmProperties props;
    private final Clock clock;

    public TokenAccounting(LlmUsageMapper mapper, LlmProperties props, Clock clock) {
        this.mapper = mapper;
        this.props = props;
        this.clock = clock;
    }

    /**
     * 记录一次调用（append-only）。返回带 costUsd 回填的响应，便于 API 直接返回。
     */
    @Transactional
    public LlmResponse record(String feature, LlmResponse response) {
        BigDecimal cost = estimateCost(response.getModel(), response.getPromptTokens(), response.getCompletionTokens());
        response.setCostUsd(cost);
        LlmUsage usage = new LlmUsage();
        usage.setRequestId(response.getRequestId());
        usage.setFeature(feature == null ? "unknown" : feature);
        usage.setProvider(response.getProvider());
        usage.setModel(response.getModel());
        usage.setPromptTokens(response.getPromptTokens());
        usage.setCompletionTokens(response.getCompletionTokens());
        usage.setTotalTokens(response.getTotalTokens());
        usage.setCostUsd(cost);
        usage.setLatencyMs((int) Math.min(Integer.MAX_VALUE, response.getLatencyMs()));
        usage.setCreatedAt(LocalDateTime.now(clock));
        try {
            mapper.insert(usage);
        } catch (RuntimeException e) {
            // 计量失败绝不影响主流程（这是"可观测性"与"业务可用性"的边界判断）
            log.warn("写入 llm_usage 失败 requestId={} err={}", response.getRequestId(), e.getMessage());
        }
        return response;
    }

    /** 按模型单价估算成本（美元，6 位小数）。 */
    public BigDecimal estimateCost(String model, int promptTokens, int completionTokens) {
        LlmProperties.Price price = props.getPricing().get(model);
        if (price == null) {
            price = props.getPricing().get(props.getDefaultModel());
        }
        if (price == null) {
            return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
        }
        BigDecimal input = BigDecimal.valueOf(promptTokens)
                .divide(BigDecimal.valueOf(1000), 10, RoundingMode.HALF_UP)
                .multiply(price.getInputPer1k());
        BigDecimal output = BigDecimal.valueOf(completionTokens)
                .divide(BigDecimal.valueOf(1000), 10, RoundingMode.HALF_UP)
                .multiply(price.getOutputPer1k());
        return input.add(output).setScale(6, RoundingMode.HALF_UP);
    }

    /** 全量汇总（内存聚合，理由见 LlmUsageMapper 注释）。 */
    public UsageSummary summary() {
        List<LlmUsage> all = mapper.selectList(null);
        long promptTokens = 0;
        long completionTokens = 0;
        long totalTokens = 0;
        BigDecimal cost = BigDecimal.ZERO;
        Map<String, long[]> byModelTokens = new LinkedHashMap<>();
        Map<String, BigDecimal> byModelCost = new LinkedHashMap<>();
        Map<String, long[]> byFeatureTokens = new LinkedHashMap<>();
        Map<String, BigDecimal> byFeatureCost = new LinkedHashMap<>();
        for (LlmUsage u : all) {
            int p = u.getPromptTokens() == null ? 0 : u.getPromptTokens();
            int c = u.getCompletionTokens() == null ? 0 : u.getCompletionTokens();
            int t = u.getTotalTokens() == null ? p + c : u.getTotalTokens();
            BigDecimal uc = u.getCostUsd() == null ? BigDecimal.ZERO : u.getCostUsd();
            promptTokens += p;
            completionTokens += c;
            totalTokens += t;
            cost = cost.add(uc);
            byModelTokens.computeIfAbsent(nz(u.getModel()), k -> new long[3]);
            long[] mt = byModelTokens.get(nz(u.getModel()));
            mt[0]++;
            mt[1] += p + c;
            byModelCost.merge(nz(u.getModel()), uc, BigDecimal::add);
            byFeatureTokens.computeIfAbsent(nz(u.getFeature()), k -> new long[3]);
            long[] ft = byFeatureTokens.get(nz(u.getFeature()));
            ft[0]++;
            ft[1] += p + c;
            byFeatureCost.merge(nz(u.getFeature()), uc, BigDecimal::add);
        }
        List<ModelUsage> models = new ArrayList<>();
        byModelTokens.forEach((model, v) -> models.add(new ModelUsage(
                model, v[0], v[1], byModelCost.getOrDefault(model, BigDecimal.ZERO).setScale(6, RoundingMode.HALF_UP))));
        models.sort(Comparator.comparingLong(ModelUsage::requests).reversed());
        List<FeatureUsage> features = new ArrayList<>();
        byFeatureTokens.forEach((f, v) -> features.add(new FeatureUsage(
                f, v[0], v[1], byFeatureCost.getOrDefault(f, BigDecimal.ZERO).setScale(6, RoundingMode.HALF_UP))));
        features.sort(Comparator.comparingLong(FeatureUsage::requests).reversed());
        return new UsageSummary((long) all.size(), promptTokens, completionTokens, totalTokens,
                cost.setScale(6, RoundingMode.HALF_UP), models, features);
    }

    public List<LlmUsage> recent(int limit) {
        return mapper.recent(limit);
    }

    private String nz(String s) {
        return s == null ? "unknown" : s;
    }

    /** 单模型聚合行。 */
    public record ModelUsage(String model, long requests, long totalTokens, BigDecimal costUsd) {
    }

    /** 单业务场景聚合行。 */
    public record FeatureUsage(String feature, long requests, long totalTokens, BigDecimal costUsd) {
    }

    /** 汇总响应体。 */
    public record UsageSummary(long requests, long promptTokens, long completionTokens, long totalTokens,
                               BigDecimal costUsd, List<ModelUsage> byModel, List<FeatureUsage> byFeature) {
    }
}
