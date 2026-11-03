package com.zhouzihang.exam.web;

import com.zhouzihang.exam.infra.AppCacheService;
import com.zhouzihang.exam.infra.IdempotencyGuard;
import com.zhouzihang.exam.proctor.ProctorRiskEngine;
import com.zhouzihang.exam.security.RequireRole;
import com.zhouzihang.exam.web.dto.StatsDtos;
import com.zhouzihang.exam.ws.ProctorWebSocketHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 可观测性端点：缓存命中率、锁后端、风险引擎状态。
 *
 * <p>面试讲解点：面试/上线最容易被问“你怎么证明缓存有用、Redis 挂了怎么办”。
 * 这个端点把运行态一次性暴露：Caffeine 命中率、当前幂等锁是 Redisson 还是本地降级、
 * 风险引擎活跃会话数、WebSocket 在线连接数。演示时可直接看到“Redis 断开后 backend=local”。</p>
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final AppCacheService cacheService;
    private final IdempotencyGuard idempotencyGuard;
    private final ProctorRiskEngine riskEngine;
    private final ProctorWebSocketHandler webSocketHandler;

    public StatsController(AppCacheService cacheService, IdempotencyGuard idempotencyGuard,
                           ProctorRiskEngine riskEngine, ProctorWebSocketHandler webSocketHandler) {
        this.cacheService = cacheService;
        this.idempotencyGuard = idempotencyGuard;
        this.riskEngine = riskEngine;
        this.webSocketHandler = webSocketHandler;
    }

    @GetMapping("/cache")
    @RequireRole({"ADMIN", "TEACHER"})
    public StatsDtos.CacheStatsResponse cache() {
        return new StatsDtos.CacheStatsResponse(
                cacheService.stats(),
                idempotencyGuard.backend(),
                "rule-based:sliding-window+weighted-score+dedup+consecutive-L3",
                riskEngine.activeSessions(),
                webSocketHandler.connectionCount());
    }
}
