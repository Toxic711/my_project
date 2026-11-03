package com.zhouzihang.devflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SSE 对话配置（app.chat.*）。
 *
 * <p>面试讲解点：SSE 的三件套参数——总超时、心跳间隔、单次生成 token 上限——
 * 决定了"一个卡住的 LLM 请求会不会把容器线程池拖死"。生产上总超时应略小于
 * 网关的 idle timeout，心跳必须小于网关/负载均衡的空闲断开阈值（常见 60s，这里 15s）。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.chat")
public class ChatProperties {

    /** SseEmitter 总超时（毫秒）。 */
    private long sseTimeoutMs = 120_000L;

    /** 心跳注释帧间隔（毫秒），用于穿过 Nginx/网关的空闲连接回收。 */
    private long heartbeatMs = 15_000L;

    /** 单次生成最大 completion token（估算/token 计费口径）。 */
    private int maxTokens = 800;

    /** 采样温度。 */
    private double temperature = 0.7;

    /** 上下文中最多保留的历史消息条数（多轮对话用）。 */
    private int historyLimit = 10;
}
