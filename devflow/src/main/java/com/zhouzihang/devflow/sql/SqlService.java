package com.zhouzihang.devflow.sql;

import com.zhouzihang.devflow.common.ApiException;
import com.zhouzihang.devflow.common.ErrorCodes;
import com.zhouzihang.devflow.config.SqlGuardProperties;
import com.zhouzihang.devflow.llm.LlmMessage;
import com.zhouzihang.devflow.llm.LlmRequest;
import com.zhouzihang.devflow.llm.LlmResponse;
import com.zhouzihang.devflow.llm.ModelRouter;
import com.zhouzihang.devflow.prompt.PromptRenderer;
import com.zhouzihang.devflow.prompt.PromptSeeder;
import com.zhouzihang.devflow.prompt.PromptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自然语言 → SQL → 护栏 → 只读执行。
 *
 * <p>面试讲解点（数据流与安全边界）：</p>
 * <pre>
 *   question ──render(prompt+真实schema)──▶ LLM ──提取SQL──▶ SqlGuard ──EXPLAIN──▶ 只读执行
 *                                        (不可信输出)      (确定性校验)   (成本预估)   (setReadOnly)
 * </pre>
 * <p>三个关键决策：</p>
 * <ol>
 *   <li><b>LLM 输出视为不可信输入</b>：即便 prompt 里写了"只生成 SELECT"，
 *       模型仍可能被注入；护栏是确定性的最后一道闸。</li>
 *   <li><b>EXPLAIN 前置</b>：先让数据库给出执行计划，能提前发现"全表扫描/笛卡尔积"，
 *       也便于把计划返回给用户解释"为什么这条查询慢"。执行时再用
 *       {@code Statement.setMaxRows + setQueryTimeout} 做第二重资源保护。</li>
 *   <li><b>只读连接 + 回滚</b>：{@code setReadOnly(true)} 之后即使护栏被绕过，
 *       写操作也会被驱动/数据库拒绝（生产应配只读账号，见 DESIGN.md）。</li>
 * </ol>
 */
@Service
public class SqlService {

    private static final Logger log = LoggerFactory.getLogger(SqlService.class);
    private static final Pattern FENCE = Pattern.compile("```(?:sql|SQL)?\\s*(.*?)```", Pattern.DOTALL);

    private final SqlGuard guard;
    private final SqlGuardProperties props;
    private final ModelRouter router;
    private final PromptService promptService;
    private final SchemaProvider schemaProvider;
    private final DataSource dataSource;

    public SqlService(SqlGuard guard, SqlGuardProperties props, ModelRouter router,
                      PromptService promptService, SchemaProvider schemaProvider, DataSource dataSource) {
        this.guard = guard;
        this.props = props;
        this.router = router;
        this.promptService = promptService;
        this.schemaProvider = schemaProvider;
        this.dataSource = dataSource;
    }

    /** 自然语言 → 已通过护栏的 SQL。 */
    public GenerateResult generate(String question, String providerOverride) {
        if (question == null || question.isBlank()) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "question 不能为空");
        }
        String schema = schemaProvider.summary();
        String rendered = renderOrDefault("sql-generate", PromptSeeder.SQL_TEMPLATE, Map.of(
                "schema", schema,
                "maxRows", String.valueOf(props.getMaxRows()),
                "question", question
        ));
        Map<String, Object> metadata = new LinkedHashMap<>();
        // Mock 客户端用它做确定性输出；真实 provider 忽略该字段
        metadata.put("mock.question", question);
        if (providerOverride != null && !providerOverride.isBlank()) {
            metadata.put("provider", providerOverride);
        }
        LlmRequest request = LlmRequest.builder()
                .requestId("sql-" + UUID.randomUUID())
                .feature("sql-generate")
                .messages(List.of(LlmMessage.user(rendered)))
                .maxTokens(400)
                .temperature(0.1)
                .metadata(metadata)
                .build();
        LlmResponse response = router.chat(request);
        String candidate = extractSql(response.getContent());
        SqlGuard.GuardResult result;
        try {
            result = guard.validate(candidate);
        } catch (SqlGuardException e) {
            // 把 LLM 原始输出一并回传：让用户看到"模型想干什么、被什么规则拦下"
            throw new ApiException(ErrorCodes.SQL_GUARD_VIOLATION, HttpStatus.UNPROCESSABLE_ENTITY,
                    e.getMessage() + "；LLM 原始输出=" + oneLine(candidate), e);
        }
        List<String> notes = new ArrayList<>();
        if (result.limitAppended()) {
            notes.add("护栏自动追加 LIMIT " + result.appliedLimit());
        }
        notes.add("已通过护栏校验：单条 SELECT、表在白名单内、LIMIT ≤ " + props.getMaxRows());
        return new GenerateResult(
                request.getRequestId(),
                result.sql(),
                result.tables(),
                result.appliedLimit(),
                result.limitAppended(),
                candidate,
                response.getProvider(),
                response.getModel(),
                response.isDegraded(),
                response.getPromptTokens(),
                response.getCompletionTokens(),
                response.getCostUsd() == null ? BigDecimal.ZERO : response.getCostUsd(),
                schema,
                notes
        );
    }

    /** 执行（再校验一次 + EXPLAIN + 只读连接）。 */
    public ExecuteResult execute(String rawSql) {
        SqlGuard.GuardResult guarded = guard.validate(rawSql);
        String sql = guarded.sql();
        long start = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection()) {
            // 纵深防御：驱动层面标只读，避免护栏被绕过时发生写操作
            conn.setReadOnly(true);
            conn.setAutoCommit(false);
            try {
                List<String> plan = props.isExplainBeforeExecute() ? explain(conn, sql) : List.of();
                List<String> columns = new ArrayList<>();
                List<List<Object>> rows = new ArrayList<>();
                try (Statement st = conn.createStatement()) {
                    st.setMaxRows(props.getMaxRows());
                    st.setQueryTimeout(Math.max(1, props.getQueryTimeoutSeconds()));
                    try (ResultSet rs = st.executeQuery(sql)) {
                        ResultSetMetaData md = rs.getMetaData();
                        int count = md.getColumnCount();
                        for (int i = 1; i <= count; i++) {
                            columns.add(md.getColumnLabel(i));
                        }
                        while (rs.next()) {
                            List<Object> row = new ArrayList<>(count);
                            for (int i = 1; i <= count; i++) {
                                row.add(convertValue(rs.getObject(i)));
                            }
                            rows.add(row);
                        }
                    }
                }
                long elapsed = System.currentTimeMillis() - start;
                return new ExecuteResult(sql, guarded.tables(), plan, columns, rows, rows.size(),
                        rows.size() >= props.getMaxRows(), elapsed, props.isExplainBeforeExecute());
            } finally {
                // 只读事务，回滚即可，不提交任何东西
                try {
                    conn.rollback();
                } catch (SQLException ignore) {
                    // 回滚失败不影响返回结果，连接关闭时会释放
                }
            }
        } catch (SQLException e) {
            log.warn("SQL 执行失败 sql={} err={}", oneLine(sql), e.getMessage());
            throw new ApiException(ErrorCodes.SQL_EXECUTION_ERROR, HttpStatus.BAD_REQUEST,
                    "SQL 执行失败: " + e.getMessage(), e);
        }
    }

    /** 仅校验不执行（前端"安全检查"按钮 / 测试用）。 */
    public GuardCheck check(String rawSql) {
        try {
            SqlGuard.GuardResult r = guard.validate(rawSql);
            return new GuardCheck(true, null, r.sql(), r.tables(), r.appliedLimit(), r.limitAppended(), null);
        } catch (SqlGuardException e) {
            return new GuardCheck(false, e.getReason().name(), rawSql, List.of(), null, false, e.getMessage());
        }
    }

    public Map<String, Object> guardInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("allowedTables", props.getAllowedTables());
        m.put("maxRows", props.getMaxRows());
        m.put("explainBeforeExecute", props.isExplainBeforeExecute());
        m.put("autoAppendLimit", props.isAutoAppendLimit());
        m.put("queryTimeoutSeconds", props.getQueryTimeoutSeconds());
        m.put("schemaSummary", schemaProvider.summary());
        return m;
    }

    private List<String> explain(Connection conn, String sql) throws SQLException {
        List<String> plan = new ArrayList<>();
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(Math.max(1, props.getQueryTimeoutSeconds()));
            try (ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
                while (rs.next()) {
                    plan.add(String.valueOf(rs.getObject(1)));
                }
            }
        }
        // EXPLAIN 失败会抛 SQLException → 上层转成 400，绝不"跳过校验继续执行"
        return plan;
    }

    /** 从模型输出中提取 SQL：优先取 ```sql 代码块，否则从第一个 select 处截取。 */
    static String extractSql(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        Matcher m = FENCE.matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("select");
        if (idx >= 0) {
            String tail = text.substring(idx).trim();
            int fence = tail.indexOf("```");
            if (fence > 0) {
                tail = tail.substring(0, fence).trim();
            }
            return tail;
        }
        return text;
    }

    /** 渲染指定 code 的模板；模板缺失时用内置模板兜底（保证 demo 不因数据缺失而 500）。 */
    private String renderOrDefault(String code, String fallbackTemplate, Map<String, String> vars) {
        PromptService.RenderedPrompt rendered;
        try {
            rendered = promptService.render(code, vars);
        } catch (ApiException e) {
            log.warn("prompt[{}] 不可用（{}），回退到内置模板", code, e.getMessage());
            return PromptRenderer.render(fallbackTemplate, vars);
        }
        return rendered.text();
    }

    private Object convertValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            return "<binary " + bytes.length + " bytes>";
        }
        return String.valueOf(value);
    }

    private String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 300 ? t.substring(0, 300) + "..." : t;
    }

    /** 生成结果。 */
    public record GenerateResult(String requestId, String sql, List<String> tables, int appliedLimit,
                                 boolean limitAppended, String rawModelOutput, String provider, String model,
                                 boolean degraded, int promptTokens, int completionTokens,
                                 BigDecimal costUsd, String schemaSummary, List<String> notes) {
    }

    /** 执行结果。 */
    public record ExecuteResult(String sql, List<String> tables, List<String> plan, List<String> columns,
                                List<List<Object>> rows, int rowCount, boolean truncated,
                                long elapsedMs, boolean explainChecked) {
    }

    /** 护栏检查结果。 */
    public record GuardCheck(boolean allowed, String reason, String sql, List<String> tables,
                             Integer appliedLimit, boolean limitAppended, String message) {
    }
}
