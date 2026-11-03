package com.zhouzihang.devflow.sql;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zhouzihang.devflow.config.SqlGuardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 从真实数据源读取 schema 摘要，注入到"自然语言转 SQL"的 prompt 里。
 *
 * <p>面试讲解点：schema 必须来自 {@code DatabaseMetaData} 而不是硬编码，
 * 否则表结构一变 prompt 就骗模型。同时只暴露<b>白名单表</b>：既控制 prompt 体积
 * （token 成本），也避免把敏感表结构喂给第三方模型。
 * 读元数据是慢操作，用 Caffeine 缓存 5 分钟（改表结构后最迟 5 分钟生效）。</p>
 */
@Component
public class SchemaProvider {

    private static final Logger log = LoggerFactory.getLogger(SchemaProvider.class);

    /** 元数据读取失败时的兜底描述（保证 demo 不因权限/方言差异而空 prompt）。 */
    private static final String FALLBACK = """
            TABLE users (id BIGINT, username VARCHAR, email VARCHAR, status SMALLINT, created_at TIMESTAMP)
            TABLE orders (id BIGINT, user_id BIGINT, amount NUMERIC, status VARCHAR, created_at TIMESTAMP)
            TABLE products (id BIGINT, name VARCHAR, price NUMERIC, stock INT, created_at TIMESTAMP)
            """;

    private final DataSource dataSource;
    private final SqlGuardProperties props;
    private final Cache<String, String> cache;

    public SchemaProvider(DataSource dataSource, SqlGuardProperties props) {
        this.dataSource = dataSource;
        this.props = props;
        this.cache = Caffeine.newBuilder()
                .maximumSize(4)
                .expireAfterWrite(Duration.ofMinutes(5))
                .build();
    }

    /** 供 prompt 注入的 schema 摘要（每行一张表）。 */
    public String summary() {
        String cached = cache.getIfPresent("summary");
        if (cached != null) {
            return cached;
        }
        String loaded = load();
        cache.put("summary", loaded);
        return loaded;
    }

    /** 允许的表名（大写原样返回给前端展示）。 */
    public List<String> allowedTables() {
        return props.getAllowedTables();
    }

    public void invalidate() {
        cache.invalidateAll();
    }

    private String load() {
        StringBuilder sb = new StringBuilder();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            for (String table : props.getAllowedTables()) {
                List<String> columns = readColumns(meta, table);
                if (columns.isEmpty()) {
                    log.warn("schema 摘要：表 {} 未在数据库中找到（白名单配置与实际库不一致？）", table);
                    continue;
                }
                sb.append("TABLE ").append(table).append(" (").append(String.join(", ", columns)).append(")\n");
            }
        } catch (SQLException e) {
            log.warn("读取数据库元数据失败，使用兜底 schema：{}", e.getMessage());
            return FALLBACK;
        }
        if (sb.length() == 0) {
            return FALLBACK;
        }
        return sb.toString();
    }

    private List<String> readColumns(DatabaseMetaData meta, String table) {
        // H2/PG 在 DATABASE_TO_LOWER / 默认小写的情况下表名为小写，这里两种大小写都试
        for (String candidate : new String[]{table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT)}) {
            List<String> columns = new ArrayList<>();
            try (ResultSet rs = meta.getColumns(null, null, candidate, null)) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    String type = rs.getString("TYPE_NAME");
                    int size = rs.getInt("COLUMN_SIZE");
                    String col = name + " " + type;
                    if (size > 0 && (type != null && (type.contains("CHAR") || type.contains("DECIMAL")
                            || type.contains("NUMERIC")))) {
                        col += "(" + size + ")";
                    }
                    columns.add(col);
                }
            } catch (SQLException e) {
                log.debug("读取表 {} 元数据失败: {}", candidate, e.getMessage());
            }
            if (!columns.isEmpty()) {
                return columns;
            }
        }
        return List.of();
    }
}
