package com.zhouzihang.exam.ws;

import com.zhouzihang.exam.common.ApiException;
import com.zhouzihang.exam.security.AuthPrincipal;
import com.zhouzihang.exam.security.JwtService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * 面试讲解点：WebSocket 握手鉴权。
 *
 * <p>浏览器的 WebSocket API 不能自定义请求头，所以 token 只能通过 URL query 传
 * （?token=xxx）。URL 容易进访问日志，因此：① 生产必须走 wss；② token TTL 要短；
 * ③ 更安全的方案是先用一次性 ticket（HTTP 换取短期连接票据）再握手，
 * 本 demo 为简化用 token 直传，已在 DESIGN 已知限制中标注。</p>
 *
 * <p>只有 TEACHER/ADMIN 能订阅考场告警；学生连接会被 403 拒绝，
 * 避免考生自己监听“我什么时候被老师盯上了”。</p>
 */
@Slf4j
@Component
public class ProctorHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtService jwtService;

    public ProctorHandshakeInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Map<String, String> query = UriComponentsBuilder.fromUri(request.getURI())
                .build().getQueryParams().toSingleValueMap();
        String token = query.get("token");
        if (token == null || token.isBlank()) {
            log.warn("WebSocket 握手被拒绝：缺少 token，remote={}", request.getRemoteAddress());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        try {
            AuthPrincipal principal = jwtService.parse(token);
            if (!principal.isTeacher() && !principal.isAdmin()) {
                log.warn("WebSocket 握手被拒绝：角色 {} 无订阅权限", principal.role());
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
            attributes.put("username", principal.username());
            attributes.put("role", principal.role());
            attributes.put("userId", principal.userId());

            String filter = query.get("examRecordId");
            if (filter != null && !filter.isBlank()) {
                try {
                    attributes.put("examRecordId", Long.parseLong(filter));
                } catch (NumberFormatException e) {
                    response.setStatusCode(HttpStatus.BAD_REQUEST);
                    return false;
                }
            }
            return true;
        } catch (ApiException e) {
            // token 无效/过期：拒绝握手（客户端会收到握手失败）
            response.setStatusCode(e.getStatus());
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需额外处理
    }
}
