package com.zhouzihang.exam.web.dto;

import com.zhouzihang.exam.infra.AppCacheService;

import java.util.List;

/** 运维/演示用统计 DTO。 */
public final class StatsDtos {

    private StatsDtos() {
    }

    public record CacheStatsResponse(List<AppCacheService.CacheStatView> caches,
                                     String lockBackend,
                                     String riskEngine,
                                     int proctorActiveSessions,
                                     int wsConnections) {
    }
}
