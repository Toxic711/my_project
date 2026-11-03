package com.zhouzihang.devflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SQL 护栏配置（app.sql-guard.*）。
 *
 * <p>面试讲解点：护栏的"可配置化"本身就是安全设计——白名单表、最大行数、
 * 是否强制 EXPLAIN 都不写死在代码里，方便按环境收紧（demo 宽松、生产严格），
 * 也让"面试官问：如果线上表变了怎么办"有答案：改配置 + 重启，不动代码。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.sql-guard")
public class SqlGuardProperties {

    /** 允许查询的表名白名单（小写比较）。 */
    private List<String> allowedTables = new ArrayList<>(Arrays.asList("users", "orders", "products"));

    /** 单次查询允许返回/扫描的最大行数，用作强制 LIMIT 上限。 */
    private int maxRows = 200;

    /** 执行前是否强制 EXPLAIN（拒绝执行 EXPLAIN 失败的计划）。 */
    private boolean explainBeforeExecute = true;

    /** JDBC 查询超时（秒）。 */
    private int queryTimeoutSeconds = 5;

    /** 单条 SQL 最大字符数，防止把超大 prompt 塞进解析器。 */
    private int maxSqlLength = 8000;

    /** 生成 SQL 后是否允许自动补 LIMIT（true）还是要求 LLM 必须自己写（false）。 */
    private boolean autoAppendLimit = true;
}
