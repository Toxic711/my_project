package com.zhouzihang.exam.infra;

import com.zhouzihang.exam.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;

/**
 * Redisson 客户端的“惰性 + 可降级”封装。
 *
 * <p>面试讲解点：为什么不在启动时创建 RedissonClient？
 * 因为 demo 要求“Redis 不可用也能启动”。如果 Redisson 在 @Bean 初始化阶段连不上，
 * 整个 Spring 上下文会启动失败。这里改为首次使用（提交答卷）时才尝试连接：
 * 连不上就记一条 WARN，后续走本地内存锁/缓存，业务不受阻。
 * 这是典型的“外部依赖必须可降级”的工程约束。</p>
 *
 * <p>超时参数刻意调小（connect 500ms、1 次重试），避免 Redis 宕机时请求被拖住。</p>
 */
@Slf4j
@Component
public class RedissonSupport {

    private final AppProperties properties;

    private volatile RedissonClient client;
    private volatile boolean initialized = false;
    private volatile String initError;

    public RedissonSupport(AppProperties properties) {
        this.properties = properties;
    }

    /** 返回当前是否可用；首次调用触发连接尝试（同一进程只尝试一次）。 */
    public boolean available() {
        initIfNeeded();
        return client != null;
    }

    public RedissonClient client() {
        initIfNeeded();
        return client;
    }

    public String describe() {
        if (available()) {
            return "redisson(" + properties.getRedis().getAddress() + ")";
        }
        return "local-fallback(" + (initError == null ? "not-initialized" : initError) + ")";
    }

    private void initIfNeeded() {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            initialized = true;
            AppProperties.Redis redis = properties.getRedis();
            if (!redis.isEnabled()) {
                initError = "app.redis.enabled=false";
                log.info("Redis 已禁用，使用本地内存实现");
                return;
            }
            Config config = new Config();
            config.useSingleServer()
                    .setAddress(redis.getAddress())
                    .setConnectTimeout(500)
                    .setTimeout(1200)
                    .setRetryAttempts(1)
                    .setRetryInterval(300)
                    .setConnectionMinimumIdleSize(1)
                    .setConnectionPoolSize(4);
            long start = System.currentTimeMillis();
            try {
                client = Redisson.create(config);
                log.info("Redisson 连接成功 {}，耗时 {}ms", redis.getAddress(), System.currentTimeMillis() - start);
            } catch (Exception e) {
                initError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("Redis 不可用（{}ms），自动降级为本地内存实现：{}",
                        System.currentTimeMillis() - start, initError);
                client = null;
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        if (client != null) {
            try {
                client.shutdown();
            } catch (Exception e) {
                log.warn("关闭 Redisson 失败: {}", e.getMessage());
            }
        }
    }
}
