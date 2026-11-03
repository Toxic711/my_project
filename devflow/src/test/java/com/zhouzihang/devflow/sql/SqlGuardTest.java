package com.zhouzihang.devflow.sql;

import com.zhouzihang.devflow.config.SqlGuardProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL 护栏测试 —— 本项目安全性的核心。
 *
 * <p>面试点：LLM 生成的 SQL 一律"不可信"，必须过静态校验：
 * 只允许单条 SELECT、表白名单、强制 LIMIT、禁多语句、禁注释绕过。
 * 这些用例就是"如果模型被提示词注入，能不能兜住"的答案。</p>
 */
class SqlGuardTest {

    private SqlGuard guard;

    @BeforeEach
    void setUp() {
        SqlGuardProperties props = new SqlGuardProperties();
        props.setAllowedTables(java.util.Arrays.asList("users", "orders", "products"));
        props.setMaxRows(200);
        props.setAutoAppendLimit(true);
        props.setMaxSqlLength(8000);
        guard = new SqlGuard(props);
    }

    @Test
    @DisplayName("合法 SELECT 通过，并自动补 LIMIT")
    void allowedSelectGetsLimit() {
        SqlGuard.GuardResult result = guard.validate("select id, name from users");
        assertNotNull(result);
        assertEquals(200, result.appliedLimit());
        assertTrue(result.limitAppended(), "未写 LIMIT 时应自动补上");
        assertTrue(result.sql().toLowerCase().contains("limit"));
    }

    @Test
    @DisplayName("写操作必须拒绝（NOT_SELECT）")
    void writeStatementsRejected() {
        assertThrows(SqlGuardException.class, () -> guard.validate("update users set name = 'x'"));
        assertThrows(SqlGuardException.class, () -> guard.validate("delete from orders"));
        assertThrows(SqlGuardException.class, () -> guard.validate("insert into users(id) values(1)"));
        assertThrows(SqlGuardException.class, () -> guard.validate("drop table users"));
    }

    @Test
    @DisplayName("多语句必须拒绝（防 ; DROP TABLE 注入）")
    void multiStatementRejected() {
        assertThrows(SqlGuardException.class,
                () -> guard.validate("select * from users; drop table users"));
    }

    @Test
    @DisplayName("非白名单表必须拒绝")
    void tableNotAllowedRejected() {
        assertThrows(SqlGuardException.class, () -> guard.validate("select * from secret_table"));
        assertThrows(SqlGuardException.class, () -> guard.validate("select * from mysql.user"));
    }

    @Test
    @DisplayName("注释绕过被清理后仍需通过校验")
    void commentBypassRejected() {
        // 用注释藏起真实意图：先清理注释，UPDATE 仍会被识别为非法
        assertThrows(SqlGuardException.class,
                () -> guard.validate("select 1 /* x */ ; update users set name='x'"));
    }

    @Test
    @DisplayName("超长 SQL 直接拒绝（防把超大 prompt 塞进解析器）")
    void tooLongRejected() {
        StringBuilder sb = new StringBuilder("select * from users where id in (");
        for (int i = 0; i < 3000; i++) {
            sb.append(i).append(",");
        }
        sb.append("1)");
        assertThrows(SqlGuardException.class, () -> guard.validate(sb.toString()));
    }

    @Test
    @DisplayName("已带 LIMIT 时不重复追加，且不超过上限")
    void existingLimitRespected() {
        SqlGuard.GuardResult result = guard.validate("select * from users limit 10");
        assertTrue(result.sql().toLowerCase().contains("limit 10"));
        assertEquals(10, result.appliedLimit());
    }
}
