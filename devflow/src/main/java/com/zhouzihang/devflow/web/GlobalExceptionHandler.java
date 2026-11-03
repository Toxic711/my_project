package com.zhouzihang.devflow.web;

import com.zhouzihang.devflow.common.ApiError;
import com.zhouzihang.devflow.common.ApiException;
import com.zhouzihang.devflow.common.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 统一异常处理。
 *
 * <p>面试讲解点：这里区分了"业务预期错误"（ApiException → 4xx，不告警）和
 * "未知错误"（500，必须告警 + 记录堆栈）。很多团队把一切异常都当 500，
 * 结果监控噪声掩盖了真正的故障；把 SQL 护栏的拒绝做成 422 是同一思路。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest req) {
        // 业务异常不打堆栈，只记一行，避免日志被用户输入错误刷爆
        log.info("业务异常 code={} path={} msg={}", ex.getCode(), req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus())
                .body(new ApiError(ex.getCode(), ex.getMessage(), req.getRequestURI(), traceId()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(new ApiError(ErrorCodes.VALIDATION_ERROR, detail, req.getRequestURI(), traceId()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception ex, HttpServletRequest req) {
        return ResponseEntity.badRequest()
                .body(new ApiError(ErrorCodes.VALIDATION_ERROR, "请求体或参数不合法: " + ex.getMessage(),
                        req.getRequestURI(), traceId()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(ErrorCodes.NOT_FOUND, "资源不存在: " + req.getRequestURI(),
                        req.getRequestURI(), traceId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnknown(Exception ex, HttpServletRequest req) {
        String traceId = traceId();
        // 未知异常必须带堆栈，否则线上无法定位
        log.error("未处理异常 traceId={} path={}", traceId, req.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(ErrorCodes.INTERNAL_ERROR, "服务内部错误，请提供 traceId 联系管理员",
                        req.getRequestURI(), traceId));
    }

    private String traceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
