package com.zhouzihang.exam.infra;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 本地热点缓存（Caffeine），带命中率统计。
 *
 * <p>面试讲解点 1：为什么是 Caffeine 而不是 Redis？
 * 考试详情/题库计数属于“读多写少、允许秒级不一致”的本地热点，
 * Caffeine 是进程内访问（纳秒级），比 Redis 网络往返快 2~3 个数量级；
 * 代价是多实例间可能短暂不一致，用 30~60s 的短 TTL 控制。</p>
 *
 * <p>面试讲解点 2：缓存必须可观测。{@code recordStats()} 暴露 hits/misses/hitRate，
 * 没有命中率数据的缓存等于没有，面试时要能说出“热点缓存命中率多少、
 * 失效策略是什么、缓存穿透怎么防”。</p>
 *
 * <p>面试讲解点 3：为什么不缓存列表？列表 key 维度多（分页/筛选），
 * 命中率低且失效复杂；这里只缓存“按 id 的详情”和“题库计数”两类稳定 key。</p>
 */
@Component
public class AppCacheService {

    public static final String EXAM_DETAIL = "examDetail";
    public static final String QUESTION_BANK = "questionBank";

    private final Map<String, Cache<String, Object>> caches = new LinkedHashMap<>();

    public AppCacheService() {
        caches.put(EXAM_DETAIL, Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(Duration.ofSeconds(60))
                .recordStats()
                .build());
        caches.put(QUESTION_BANK, Caffeine.newBuilder()
                .maximumSize(200)
                .expireAfterWrite(Duration.ofSeconds(30))
                .recordStats()
                .build());
    }

    /** 读缓存，miss 时用 loader 回源并写入。loader 抛异常时不会污染缓存。 */
    @SuppressWarnings("unchecked")
    public <T> T get(String cacheName, String key, Supplier<T> loader) {
        Cache<String, Object> cache = caches.get(cacheName);
        if (cache == null) {
            throw new IllegalArgumentException("未知缓存: " + cacheName);
        }
        return (T) cache.get(key, k -> loader.get());
    }

    public void invalidate(String cacheName, String key) {
        Cache<String, Object> cache = caches.get(cacheName);
        if (cache != null) {
            cache.invalidate(key);
        }
    }

    public void invalidateAll(String cacheName) {
        Cache<String, Object> cache = caches.get(cacheName);
        if (cache != null) {
            cache.invalidateAll();
        }
    }

    /** 供 GET /api/stats/cache 输出。 */
    public List<CacheStatView> stats() {
        List<CacheStatView> result = new ArrayList<>();
        caches.forEach((name, cache) -> {
            CacheStats s = cache.stats();
            result.add(new CacheStatView(
                    name,
                    s.hitCount(),
                    s.missCount(),
                    Math.round(s.hitRate() * 10000.0) / 10000.0,
                    s.evictionCount(),
                    cache.estimatedSize()));
        });
        return result;
    }

    /** 缓存统计快照。hitRate 保留 4 位小数，避免前端展示 0.33333333。 */
    public record CacheStatView(String name, long hits, long misses, double hitRate,
                                long evictions, long size) {
    }
}
