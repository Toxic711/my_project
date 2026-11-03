package com.zhouzihang.devflow.common;

/**
 * 全局错误码。统一错误码是"可观测性"的起点：
 * 客户端能按 code 分支处理，日志/指标能按 code 聚合，面试时也能讲清
 * "哪些错误是用户输入问题（4xx），哪些是上游依赖问题（5xx）"。
 */
public final class ErrorCodes {

    private ErrorCodes() {
    }

    /** 参数校验失败。 */
    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    /** 资源不存在。 */
    public static final String NOT_FOUND = "NOT_FOUND";
    /** 资源冲突（如 prompt code 已存在）。 */
    public static final String CONFLICT = "CONFLICT";
    /** 模板变量缺失。 */
    public static final String PROMPT_MISSING_VARIABLE = "PROMPT_MISSING_VARIABLE";
    /** SQL 护栏拒绝执行（最高优先级错误码之一）。 */
    public static final String SQL_GUARD_VIOLATION = "SQL_GUARD_VIOLATION";
    /** SQL 语法/执行失败。 */
    public static final String SQL_EXECUTION_ERROR = "SQL_EXECUTION_ERROR";
    /** 上游 LLM 不可用且降级链耗尽。 */
    public static final String LLM_UNAVAILABLE = "LLM_UNAVAILABLE";
    /** LLM 输出无法按约定结构解析。 */
    public static final String LLM_BAD_OUTPUT = "LLM_BAD_OUTPUT";
    /** webhook 签名缺失或不匹配。 */
    public static final String WEBHOOK_SIGNATURE_INVALID = "WEBHOOK_SIGNATURE_INVALID";
    /** 内部错误。 */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
}
