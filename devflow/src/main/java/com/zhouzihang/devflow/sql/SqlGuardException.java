package com.zhouzihang.devflow.sql;

import com.zhouzihang.devflow.common.ApiException;
import com.zhouzihang.devflow.common.ErrorCodes;
import org.springframework.http.HttpStatus;

/**
 * SQL 护栏拒绝异常。
 *
 * <p>面试讲解点：护栏拒绝必须是"可解释的拒绝"。{@link Reason} 是机器可读的分类，
 * message 是给人看的说明。前端可以把 reason 直接映射成提示文案，监控可以按
 * reason 统计"最常被拦截的违规类型"，反哺 prompt 优化。</p>
 */
public class SqlGuardException extends ApiException {

    /** 拒绝原因分类（也是测试断言的稳定依据）。 */
    public enum Reason {
        EMPTY,
        TOO_LONG,
        COMMENT_NOT_ALLOWED,
        MULTI_STATEMENT,
        NOT_SELECT,
        FORBIDDEN_KEYWORD,
        FORBIDDEN_FUNCTION,
        SCHEMA_NOT_ALLOWED,
        TABLE_NOT_ALLOWED,
        LIMIT_EXCEEDED,
        LIMIT_INVALID,
        UNPARSEABLE
    }

    private final Reason reason;

    public SqlGuardException(Reason reason, String detail) {
        super(ErrorCodes.SQL_GUARD_VIOLATION, HttpStatus.UNPROCESSABLE_ENTITY,
                "[" + reason.name() + "] " + detail);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
