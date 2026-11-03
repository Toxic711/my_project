package com.zhouzihang.exam.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import javax.servlet.http.HttpServletRequest;
import java.time.Instant;

/**
 * 全局异常处理：所有错误统一为 {@code {code, message, path, timestamp}}。
 *
 * <p>面试讲解点：唯一键冲突(DuplicateKeyException)要转 409 而不是 500。
 * 本项目的交卷幂等既依赖 Redisson 锁，也依赖 answer 表的唯一键兜底；
 * 当唯一键真的冲突时，说明并发已经撞上，返回可识别的 409 让客户端重试/查询结果。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException e, HttpServletRequest request) {
        return ResponseEntity.status(e.getStatus())
                .body(new ApiError(e.getCode(), e.getMessage(), request.getRequestURI(), Instant.now().toString()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        FieldError first = e.getBindingResult().getFieldError();
        String message = first == null ? "参数校验失败" : first.getField() + " " + first.getDefaultMessage();
        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_FAILED", message, request.getRequestURI(), Instant.now().toString()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(new ApiError("BAD_JSON", "请求体不是合法 JSON", request.getRequestURI(), Instant.now().toString()));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiError> handleDuplicate(DuplicateKeyException e, HttpServletRequest request) {
        log.warn("唯一键冲突: {} {}", request.getRequestURI(), e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("DUPLICATE_KEY", "重复请求/数据已存在", request.getRequestURI(), Instant.now().toString()));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoHandlerFoundException e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND", "接口不存在", request.getRequestURI(), Instant.now().toString()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleAny(Exception e, HttpServletRequest request) {
        log.error("未处理异常 {} {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", "服务器内部错误", request.getRequestURI(), Instant.now().toString()));
    }

    /** 统一错误体。timestamp 用 ISO 字符串，避免不同客户端对时间戳格式理解不一致。 */
    public record ApiError(String code, String message, String path, String timestamp) {
    }
}
