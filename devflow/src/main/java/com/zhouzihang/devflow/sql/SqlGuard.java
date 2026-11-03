package com.zhouzihang.devflow.sql;

import com.zhouzihang.devflow.config.SqlGuardProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 只读护栏（本项目最值得讲的安全组件）。
 *
 * <p>面试讲解点（核心）：<b>绝不能把 LLM 输出直接丢给数据库</b>。
 * LLM 会（1）被 prompt 注入诱导生成 DELETE/DROP；（2）幻觉出不存在的表；
 * （3）生成全表扫描把库拖死。所以执行前必须过一道"白名单 + 语法结构 + 行数"
 * 的确定性校验。这里是"先词法分析、再结构校验"的两段式：</p>
 *
 * <ol>
 *   <li><b>词法层</b>：先按字符串字面量感知的方式扫描注释标记（{@code --}、{@code /*}、
 *       {@code #}）。必须先感知引号，否则 {@code WHERE note='--' } 会被误判为注释；
 *       而反过来，<b>凡是出现注释就一律拒绝</b>——注释是"绕过检查"最经典的载体
 *       （如 {@code SELECT 1; --} 之后接写操作）。</li>
 *   <li><b>结构层</b>：去掉字面量后做三件事——只允许单条 SELECT、危险关键字/函数
 *       黑名单、表名白名单（含禁止跨 schema）。</li>
 *   <li><b>行数层</b>：LIMIT 必须存在且 ≤ {@code app.sql-guard.max-rows}；
 *       超过直接拒绝（而不是静默改小，避免"我以为查了全部其实被截断"的误导）。</li>
 * </ol>
 *
 * <p>为什么不引入 JSqlParser 之类的完整解析器？离线仓库不一定有；
 * 而且"保守的词法 + 白名单"对"只读查询"这个极窄场景已经足够——
 * 护栏的正确姿势是<b>宁可误杀不可放过</b>，误杀会让用户改问法，放过会丢数据。
 * 生产上再加一层：数据库侧用只读账号 + {@code SET TRANSACTION READ ONLY}，
 * 做到"即使护栏被绕过也写不进去"（纵深防御）。</p>
 */
@Component
public class SqlGuard {

    /** 危险语句关键字（出现在任何位置都拒绝）。 */
    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of(
            "insert", "update", "delete", "merge", "upsert", "replace",
            "drop", "alter", "create", "truncate", "rename",
            "grant", "revoke", "call", "exec", "execute", "do",
            "copy", "vacuum", "analyze", "reindex", "cluster",
            "attach", "detach", "pragma",
            "begin", "commit", "rollback", "savepoint", "lock",
            "listen", "notify", "prepare", "deallocate", "declare", "fetch", "close",
            "into", "outfile", "dumpfile", "load"
    );

    /** 危险函数（SQL 注入常用：延时盲注、读文件、命令执行、连接外库）。 */
    private static final Set<String> FORBIDDEN_FUNCTIONS = Set.of(
            "pg_sleep", "sleep", "benchmark", "dblink", "dblink_connect",
            "pg_read_file", "pg_read_binary_file", "pg_ls_dir", "pg_stat_file",
            "lo_import", "lo_export", "load_file", "xp_cmdshell",
            "set_config", "current_setting", "pg_terminate_backend", "pg_cancel_backend",
            "pg_reload_conf", "pg_rotate_logfile"
    );

    /** 允许的 schema 前缀（表名可以省略 schema，或显式写 public）。 */
    private static final Set<String> ALLOWED_SCHEMAS = Set.of("public");

    /** 词法单元：带点号的标识符 / 数字 / 单字符符号。 */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z_][A-Za-z0-9_$.]*|\\d+(?:\\.\\d+)?|\\S");

    private final SqlGuardProperties props;

    public SqlGuard(SqlGuardProperties props) {
        this.props = props;
    }

    /**
     * 校验并规范化 SQL。
     *
     * @return 校验通过的结果（可能自动补了 LIMIT）
     * @throws SqlGuardException 任一规则不满足，422 + 明确原因
     */
    public GuardResult validate(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            throw new SqlGuardException(SqlGuardException.Reason.EMPTY, "SQL 为空");
        }
        if (rawSql.length() > props.getMaxSqlLength()) {
            throw new SqlGuardException(SqlGuardException.Reason.TOO_LONG,
                    "SQL 长度 " + rawSql.length() + " 超过上限 " + props.getMaxSqlLength());
        }
        // 1) 注释：感知字符串字面量后再判断，出现即拒绝
        int commentAt = findCommentStart(rawSql);
        if (commentAt >= 0) {
            throw new SqlGuardException(SqlGuardException.Reason.COMMENT_NOT_ALLOWED,
                    "SQL 中不允许出现注释（--、/* */、#），位置 " + commentAt
                            + "，注释常被用于绕过单语句检查");
        }
        // 2) 去掉尾部分号（LLM 常带一个分号），剩余的 ';' 意味着多语句
        String sql = rawSql.trim();
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        String sanitized = stripStringLiterals(sql);
        List<String> tokens = tokenize(sanitized);
        if (sanitized.contains(";")) {
            throw new SqlGuardException(SqlGuardException.Reason.MULTI_STATEMENT,
                    "只允许单条语句，检测到语句分隔符 ';'");
        }
        // 3) 必须是 SELECT 开头
        String head = sanitized.trim().toLowerCase(Locale.ROOT);
        if (!(head.startsWith("select") && (head.length() == 6
                || !Character.isLetterOrDigit(head.charAt(6))))) {
            throw new SqlGuardException(SqlGuardException.Reason.NOT_SELECT,
                    "只允许 SELECT 查询，实际语句开头: " + firstWord(sanitized));
        }
        // 4) 关键字/函数黑名单
        for (String token : tokens) {
            String lower = token.toLowerCase(Locale.ROOT);
            if (FORBIDDEN_KEYWORDS.contains(lower)) {
                throw new SqlGuardException(SqlGuardException.Reason.FORBIDDEN_KEYWORD,
                        "检测到写操作/危险关键字 " + token.toUpperCase(Locale.ROOT)
                                + "（护栏只允许只读 SELECT）");
            }
            if (FORBIDDEN_FUNCTIONS.contains(lower)) {
                throw new SqlGuardException(SqlGuardException.Reason.FORBIDDEN_FUNCTION,
                        "检测到危险函数 " + token + "（可用于注入/读取文件/命令执行）");
            }
        }
        // 5) 表名白名单
        List<String> tables = extractTables(tokens);
        Set<String> allowed = new LinkedHashSet<>();
        for (String t : props.getAllowedTables()) {
            allowed.add(t.toLowerCase(Locale.ROOT));
        }
        for (String table : tables) {
            String plain = table;
            int dot = plain.indexOf('.');
            if (dot > 0) {
                String schema = plain.substring(0, dot).toLowerCase(Locale.ROOT);
                plain = plain.substring(dot + 1);
                if (!ALLOWED_SCHEMAS.contains(schema)) {
                    throw new SqlGuardException(SqlGuardException.Reason.SCHEMA_NOT_ALLOWED,
                            "禁止访问 schema " + schema + "（仅允许 public 或省略 schema）");
                }
            }
            if (!allowed.contains(plain.toLowerCase(Locale.ROOT))) {
                throw new SqlGuardException(SqlGuardException.Reason.TABLE_NOT_ALLOWED,
                        "表 " + plain + " 不在白名单中，允许的表: " + allowed);
            }
        }
        if (tables.isEmpty() && sanitized.toLowerCase(Locale.ROOT).contains("from")) {
            throw new SqlGuardException(SqlGuardException.Reason.UNPARSEABLE,
                    "无法从 FROM 子句解析出表名，已拒绝（可能是引号标识符或子查询语法异常）");
        }
        // 6) LIMIT 校验：必须存在且不超过上限
        boolean limitAppended = false;
        Integer limit = readLimit(tokens);
        if (limit == null) {
            if (!props.isAutoAppendLimit()) {
                throw new SqlGuardException(SqlGuardException.Reason.LIMIT_INVALID,
                        "SQL 必须显式包含 LIMIT（auto-append-limit=false）");
            }
            limit = props.getMaxRows();
            sql = sql + " LIMIT " + limit;
            limitAppended = true;
        } else if (limit > props.getMaxRows()) {
            throw new SqlGuardException(SqlGuardException.Reason.LIMIT_EXCEEDED,
                    "LIMIT " + limit + " 超过最大允许行数 " + props.getMaxRows()
                            + "（防止一次查询打爆连接与内存）");
        } else if (limit <= 0) {
            throw new SqlGuardException(SqlGuardException.Reason.LIMIT_INVALID,
                    "LIMIT 必须为正整数，实际 " + limit);
        }
        return new GuardResult(sql, List.copyOf(tables), limit, limitAppended);
    }

    // ------------------------------------------------------------------
    // 词法工具
    // ------------------------------------------------------------------

    /** 感知字符串字面量，返回第一个注释起始下标；无注释返回 -1。 */
    private int findCommentStart(String sql) {
        char quote = 0;
        int n = sql.length();
        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    if (i + 1 < n && sql.charAt(i + 1) == quote) {
                        i++; // '' 或 "" 转义
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                return i;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                return i;
            }
            if (c == '#') {
                return i;
            }
        }
        return -1;
    }

    /** 把字符串字面量替换为空格，避免关键字/表名扫描被字面量内容干扰。 */
    private String stripStringLiterals(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        char quote = 0;
        int n = sql.length();
        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    if (i + 1 < n && sql.charAt(i + 1) == quote) {
                        i++;
                    } else {
                        quote = 0;
                    }
                }
                out.append(' ');
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                out.append(' ');
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private List<String> tokenize(String sanitized) {
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(sanitized);
        while (m.find()) {
            tokens.add(m.group());
        }
        return tokens;
    }

    /** 解析 FROM / JOIN 后的表引用（支持 schema.table 与逗号分隔的多表）。 */
    private List<String> extractTables(List<String> tokens) {
        List<String> tables = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i).toLowerCase(Locale.ROOT);
            if (!t.equals("from") && !t.equals("join")) {
                continue;
            }
            int j = i + 1;
            String table = readTableRef(tokens, j);
            if (table == null) {
                continue; // 子查询 / 函数调用 / 异常结构，交给后续规则处理
            }
            tables.add(table);
            int k = j + 1;
            // FROM a, b, c —— 只对 from 列表继续读逗号（join 后接逗号属于列清单）
            if (t.equals("from")) {
                while (k < tokens.size() && ",".equals(tokens.get(k))) {
                    String next = readTableRef(tokens, k + 1);
                    if (next == null) {
                        break;
                    }
                    tables.add(next);
                    k = k + 2;
                }
            }
        }
        return tables;
    }

    /** 读一个表引用；不是简单标识符（如 '(' 开头）返回 null。 */
    private String readTableRef(List<String> tokens, int index) {
        if (index >= tokens.size()) {
            return null;
        }
        String token = tokens.get(index);
        if (token.equals("(") || token.equals("select")) {
            return null;
        }
        if (!token.matches("[A-Za-z_][A-Za-z0-9_$.]*")) {
            return null;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        // 排除把函数名/关键字当表名的情况
        if (lower.equals("select") || lower.equals("lateral") || lower.equals("only")) {
            return null;
        }
        return token;
    }

    /** 读取 LIMIT 值；无 LIMIT 返回 null，写法非法抛 LIMIT_INVALID。 */
    private Integer readLimit(List<String> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            if (!tokens.get(i).equalsIgnoreCase("limit")) {
                continue;
            }
            if (i + 1 >= tokens.size()) {
                throw new SqlGuardException(SqlGuardException.Reason.LIMIT_INVALID, "LIMIT 后缺少行数");
            }
            String num = tokens.get(i + 1);
            if (!num.matches("\\d+")) {
                throw new SqlGuardException(SqlGuardException.Reason.LIMIT_INVALID,
                        "LIMIT 后必须是正整数，实际 " + num);
            }
            try {
                return Integer.parseInt(num);
            } catch (NumberFormatException e) {
                throw new SqlGuardException(SqlGuardException.Reason.LIMIT_INVALID,
                        "LIMIT 数值溢出: " + num);
            }
        }
        return null;
    }

    private String firstWord(String sql) {
        String s = sql.trim();
        int idx = 0;
        while (idx < s.length() && Character.isLetter(s.charAt(idx))) {
            idx++;
        }
        return idx == 0 ? s.substring(0, Math.min(16, s.length())) : s.substring(0, idx);
    }

    /**
     * 校验通过的结果。
     *
     * @param sql           规范化后的 SQL（可能已补 LIMIT）
     * @param tables        解析出的表名（含 schema 前缀，如有）
     * @param appliedLimit  生效的 LIMIT
     * @param limitAppended 是否由护栏自动补的 LIMIT（用于前端提示）
     */
    public record GuardResult(String sql, List<String> tables, int appliedLimit, boolean limitAppended) {
    }
}
