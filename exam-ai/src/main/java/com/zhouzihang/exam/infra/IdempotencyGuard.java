package com.zhouzihang.exam.infra;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 分布式锁门面：优先 Redisson，Redis 不可用时降级为 JVM 内锁。
 *
 * <p>面试讲解点 1：为什么交卷需要分布式锁？
 * “重复提交”有两个层次：① 用户手抖/前端重试的串行重复；② 多实例/多线程的并发重复。
 * 前者用“状态判断”就能挡，后者必须靠锁或唯一键。本项目双保险：
 * Redisson 锁把同一份卷子的提交串行化，answer 唯一键做数据库层兜底。</p>
 *
 * <p>面试讲解点 2：锁的 leaseTime 一定要显式给。
 * 不传 leaseTime 时 Redisson 用 watchdog（默认 30s 续期），进程崩溃后要等 30s 才释放；
 * 交卷是短任务，这里给 10s 租约，即使线程被杀也能自动释放。</p>
 *
 * <p>面试讲解点 3：锁 key 必须带业务维度（submit:record:{id}），
 * 不能只用全局锁，否则所有考生交卷互相排队。</p>
 */
@Slf4j
@Component
public class IdempotencyGuard {

    private static final String KEY_PREFIX = "exam:lock:";

    private final RedissonSupport redissonSupport;
    private final Map<String, ReentrantLock> localLocks = new ConcurrentHashMap<>();

    public IdempotencyGuard(RedissonSupport redissonSupport) {
        this.redissonSupport = redissonSupport;
    }

    /**
     * 尝试加锁。返回的 {@link LockHandle} 必须 try-with-resources 或 finally close。
     *
     * @param businessKey 业务唯一键，例如 "submit:record:12"
     * @param waitMs      最多等待多久
     * @param leaseMs     持有租约（Redisson 自动释放兜底），本地锁忽略该参数
     */
    public LockHandle tryLock(String businessKey, long waitMs, long leaseMs) {
        String redisKey = KEY_PREFIX + businessKey;
        if (redissonSupport.available()) {
            RLock lock = redissonSupport.client().getLock(redisKey);
            try {
                boolean acquired = lock.tryLock(waitMs, leaseMs, TimeUnit.MILLISECONDS);
                return acquired ? LockHandle.redis(lock) : LockHandle.notAcquired();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return LockHandle.notAcquired();
            } catch (RuntimeException e) {
                // Redis 中途挂掉：记录后降级本地锁，保证交卷主流程可用
                log.warn("Redisson 加锁异常，降级本地锁: {}", e.toString());
            }
        }
        ReentrantLock local = localLocks.computeIfAbsent(businessKey, k -> new ReentrantLock());
        try {
            boolean acquired = local.tryLock(waitMs, TimeUnit.MILLISECONDS);
            return acquired ? LockHandle.local(businessKey, local, localLocks) : LockHandle.notAcquired();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return LockHandle.notAcquired();
        }
    }

    /** 当前生效的锁后端，供 /api/stats/cache 展示。 */
    public String backend() {
        return redissonSupport.available() ? "redisson" : "local";
    }

    /** 锁句柄：统一 Redisson / 本地锁的释放语义。 */
    public static final class LockHandle implements AutoCloseable {

        private final RLock redisLock;
        private final ReentrantLock localLock;
        private final String localKey;
        private final Map<String, ReentrantLock> localRegistry;
        private final boolean acquired;

        private LockHandle(RLock redisLock, ReentrantLock localLock, String localKey,
                           Map<String, ReentrantLock> localRegistry, boolean acquired) {
            this.redisLock = redisLock;
            this.localLock = localLock;
            this.localKey = localKey;
            this.localRegistry = localRegistry;
            this.acquired = acquired;
        }

        static LockHandle redis(RLock lock) {
            return new LockHandle(lock, null, null, null, true);
        }

        static LockHandle local(String key, ReentrantLock lock, Map<String, ReentrantLock> registry) {
            return new LockHandle(null, lock, key, registry, true);
        }

        static LockHandle notAcquired() {
            return new LockHandle(null, null, null, null, false);
        }

        public boolean acquired() {
            return acquired;
        }

        @Override
        public void close() {
            if (!acquired) {
                return;
            }
            if (redisLock != null) {
                try {
                    if (redisLock.isHeldByCurrentThread()) {
                        redisLock.unlock();
                    }
                } catch (Exception e) {
                    log.warn("释放 Redisson 锁失败: {}", e.getMessage());
                }
            }
            if (localLock != null) {
                try {
                    localLock.unlock();
                } catch (IllegalMonitorStateException ignored) {
                    // 理论不可达：只释放持锁线程
                }
                if (!localLock.hasQueuedThreads()) {
                    localRegistry.remove(localKey, localLock);
                }
            }
        }
    }
}
