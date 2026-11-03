package com.zhouzihang.devflow.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 全局装配：属性类 + 线程池 + Clock。
 *
 * <p>面试讲解点：LLM 调用与 SSE 推流必须跑在"有界线程池"上，而不是默认的
 * {@code ForkJoinPool.commonPool()} 或 Spring MVC 的容器线程。原因：
 * 容器线程被一个 60s 的 LLM 调用占住 = Tomcat 线程池被耗尽 = 全站 504。
 * 这里用两个独立池把"慢 IO"与"请求线程"隔离，并给出明确的拒绝策略
 * （CallerRuns 在这里会退化成同步阻塞，所以用 AbortPolicy + 上层降级）。</p>
 */
@Configuration
@EnableConfigurationProperties({
        LlmProperties.class,
        SqlGuardProperties.class,
        GitHubProperties.class,
        ChatProperties.class,
        SeedProperties.class
})
public class AppConfig {

    /** 承载 LLM 同步调用（含超时包装）。 */
    @Bean(name = "llmTaskExecutor")
    public ThreadPoolTaskExecutor llmTaskExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(16);
        exec.setQueueCapacity(64);
        exec.setKeepAliveSeconds(60);
        exec.setThreadNamePrefix("llm-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(5);
        exec.initialize();
        return exec;
    }

    /** 承载 SSE 流式推送；与 LLM 池隔离，避免"推流慢"拖累"生成快"。 */
    @Bean(name = "sseTaskExecutor")
    public ThreadPoolTaskExecutor sseTaskExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(32);
        exec.setQueueCapacity(128);
        exec.setKeepAliveSeconds(60);
        exec.setThreadNamePrefix("sse-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        exec.setWaitForTasksToCompleteOnShutdown(false);
        exec.initialize();
        return exec;
    }

    /** 注入 Clock 便于用量统计/成本核算在测试里使用固定时间。 */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * SSE 心跳调度器。
     *
     * <p>独立成独立调度器而不是共用 {@code sseTaskExecutor}：心跳必须是"准时的小任务"，
     * 不能排在慢的生成任务后面；否则一次长生成期间心跳全被阻塞，连接仍会被网关掐断。</p>
     */
    @Bean(name = "heartbeatScheduler", destroyMethod = "shutdown")
    public java.util.concurrent.ScheduledExecutorService heartbeatScheduler() {
        return java.util.concurrent.Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }
}
