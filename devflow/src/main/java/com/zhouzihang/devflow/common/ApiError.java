package com.zhouzihang.devflow.common;

/**
 * 统一错误响应体。所有 4xx/5xx 都返回这个结构，前端只需一套错误处理。
 *
 * @param code    机器可读错误码（见 {@link ErrorCodes}）
 * @param message 人类可读原因
 * @param path    请求路径，便于对照日志
 * @param traceId 服务端日志追踪 ID（模拟；生产接 Micrometer Tracing）
 */
public record ApiError(String code, String message, String path, String traceId) {
}
