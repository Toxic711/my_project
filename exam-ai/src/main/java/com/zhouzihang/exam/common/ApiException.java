package com.zhouzihang.exam.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码 + 稳定错误码，由 GlobalExceptionHandler 统一转 JSON。
 *
 * <p>面试讲解点：不要用 RuntimeException 满世界 throw 再靠 500 兜底。
 * 401/403/404/409 的语义要明确，前端和监控才能区分“没登录”和“业务冲突”。</p>
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }
}
