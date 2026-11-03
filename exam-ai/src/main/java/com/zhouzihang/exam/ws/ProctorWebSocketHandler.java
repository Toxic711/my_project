package com.zhouzihang.exam.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhouzihang.exam.web.dto.ProctorDtos;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 面试讲解点（防作弊实时链路）：教师端告警推送 WebSocket Handler。
 *
 * <p>为什么用 WebSocket 而不是轮询？防作弊告警的价值在“实时”——
 * 轮询（哪怕 3s 一次）在海量考生场景下 QPS 不可接受，且延迟不可控。
 * 这里用 Spring 原生 {@link TextWebSocketHandler}（离线仓库只有 spring-websocket，
 * 不走 starter/SockJS，避免额外依赖），握手阶段由 {@link ProctorHandshakeInterceptor}
 * 校验 JWT 与角色。</p>
 *
 * <p>面试讲解点 2：并发发送安全。WebSocketSession 不是线程安全的，
 * 多个考生同时告警时直接 sendMessage 会抛 "TEXT_PARTIAL_WRITING"。
 * 这里用 {@link ConcurrentWebSocketSessionDecorator} 包装：内部加锁 + 发送超时 + 缓冲上限，
 * 慢客户端不会拖垮推送线程。</p>
 *
 * <p>面试讲解点 3：按考试记录过滤。教师可订阅全考场（不带 examRecordId），
 * 也可只订阅某个考生，前端 URL 传 ?examRecordId=12 即可，服务端只推该记录。</p>
 */
@Slf4j
@Component
public class ProctorWebSocketHandler extends TextWebSocketHandler {

    private static final int SEND_TIME_LIMIT_MS = 5000;
    private static final int BUFFER_SIZE_LIMIT = 64 * 1024;

    private final ObjectMapper objectMapper;
    /** sessionId -> 并发安全装饰后的 session。 */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public ProctorWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        WebSocketSession decorated = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
        sessions.put(session.getId(), decorated);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "CONNECTED");
        payload.put("role", session.getAttributes().get("role"));
        payload.put("username", session.getAttributes().get("username"));
        payload.put("examRecordIdFilter", session.getAttributes().get("examRecordId"));
        decorated.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        log.info("WebSocket 已连接 id={} user={} role={} filter={}", session.getId(),
                session.getAttributes().get("username"),
                session.getAttributes().get("role"),
                session.getAttributes().get("examRecordId"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("WebSocket 已断开 id={} status={} 当前连接数={}", session.getId(), status, sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessions.remove(session.getId());
        log.warn("WebSocket 传输异常 id={}: {}", session.getId(), exception.getMessage());
    }

    /** 广播一条告警；已按考试记录过滤、按会话装饰器串行化发送。 */
    public void broadcast(ProctorDtos.ProctorAlertView alert) {
        if (sessions.isEmpty()) {
            return;
        }
        String json;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "PROCTOR_ALERT");
            payload.put("alert", alert);
            payload.put("serverTs", System.currentTimeMillis());
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("告警序列化失败", e);
            return;
        }
        sessions.forEach((id, session) -> {
            Object filter = session.getAttributes().get("examRecordId");
            if (filter instanceof Long filterId && filterId != alert.examRecordId()) {
                return;
            }
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            } catch (IOException | IllegalStateException e) {
                log.warn("推送告警失败，移除会话 id={}: {}", id, e.getMessage());
                sessions.remove(id);
            }
        });
        log.info("已推送 {} 级告警 recordId={} score={} 在线会话={}",
                alert.level(), alert.examRecordId(), alert.riskScore(), sessions.size());
    }

    public int connectionCount() {
        return sessions.size();
    }
}
