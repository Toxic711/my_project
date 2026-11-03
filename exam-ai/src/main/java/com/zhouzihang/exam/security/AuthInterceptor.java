package com.zhouzihang.exam.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhouzihang.exam.common.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证 + RBAC 拦截器。
 *
 * <p>面试讲解点 1：认证（你是谁）和授权（你能不能做）分开：
 * 先验 token 得到 principal，再按 {@link RequireRole} 判角色。</p>
 *
 * <p>面试讲解点 2：为什么不用 Filter？HandlerInterceptor 能拿到 HandlerMethod，
 * 可以直接读方法/类上的注解，避免在 URL 上重复维护权限表。
 * 代价是它只覆盖 DispatcherServlet 内的请求，静态资源/其他 Servlet 不经过它。</p>
 *
 * <p>面试讲解点 3：拒绝时手写 JSON body 而不是 sendError，
 * 因为 sendError 会走容器错误页，前端拿到的 Content-Type 不可控。</p>
 */
@Slf4j
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public AuthInterceptor(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // CORS 预检不带 token，直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "缺少 Authorization: Bearer <token>");
            return false;
        }

        AuthPrincipal principal;
        try {
            principal = jwtService.parse(authorization.substring(BEARER_PREFIX.length()).trim());
        } catch (ApiException e) {
            writeError(response, e.getStatus(), e.getCode(), e.getMessage());
            return false;
        }
        CurrentUser.set(principal);

        if (handler instanceof HandlerMethod handlerMethod) {
            RequireRole requireRole = handlerMethod.getMethodAnnotation(RequireRole.class);
            if (requireRole == null) {
                requireRole = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
            }
            if (requireRole != null && !Arrays.asList(requireRole.value()).contains(principal.role())) {
                CurrentUser.clear();
                writeError(response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "当前角色 " + principal.role() + " 无权访问该接口");
                return false;
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 必须清理，否则线程池复用会泄漏身份
        CurrentUser.clear();
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("path", "");
        body.put("timestamp", Instant.now().toString());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
