package com.zhouzihang.exam.config;

import com.zhouzihang.exam.ws.ProctorHandshakeInterceptor;
import com.zhouzihang.exam.ws.ProctorWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 装配（原生 spring-websocket，不走 starter/SockJS）。
 *
 * <p>面试讲解点：{@code setAllowedOrigins("*")} 仅用于 demo；生产必须收敛到前端域名，
 * 否则任意站点都能用教师 token 建立连接（CSWSH 风险）。</p>
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ProctorWebSocketHandler handler;
    private final ProctorHandshakeInterceptor handshakeInterceptor;

    public WebSocketConfig(ProctorWebSocketHandler handler,
                           ProctorHandshakeInterceptor handshakeInterceptor) {
        this.handler = handler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/proctor")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
