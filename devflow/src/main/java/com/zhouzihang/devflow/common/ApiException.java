package com.zhouzihang.devflow.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常。携带 HTTP 状态 + 机器可读 code + 人类可读原因。
 *
 * <p>面试讲解点：为什么不直接抛 {@code RuntimeException} 让框架兜底成 500？
 * 因为"SQL 被护栏拒绝"是<b>预期的安全行为</b>，不是系统故障：它必须是 422 +
 * 明确 reason（用户要能看懂"你写的 UPDATE 被拒绝"），否则监控会把它当成
 * 服务错误、告警噪音，用户也拿不到可操作的反馈。</p>
 */
public class ApiException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public ApiException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public ApiException(String code, HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(code, HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(ErrorCodes.NOT_FOUND, HttpStatus.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(ErrorCodes.CONFLICT, HttpStatus.CONFLICT, message);
    }

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(code, HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
