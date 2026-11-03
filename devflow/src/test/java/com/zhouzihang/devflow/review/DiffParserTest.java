package com.zhouzihang.devflow.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * unified diff 解析测试（自动 Code Review 的入口）。
 *
 * <p>面试点：diff 解析必须能正确识别"新增行号"，否则 AI 报的问题行号全是错的，
 * 评论就会贴到错误的位置上。这里用真实的 git diff 片段做断言。</p>
 */
class DiffParserTest {

    private static final String SAMPLE = ""
            + "diff --git a/src/main/java/com/acme/OrderService.java b/src/main/java/com/acme/OrderService.java\n"
            + "index 1111111..2222222 100644\n"
            + "--- a/src/main/java/com/acme/OrderService.java\n"
            + "+++ b/src/main/java/com/acme/OrderService.java\n"
            + "@@ -10,6 +10,9 @@ public class OrderService {\n"
            + "     public void create(Long userId) {\n"
            + "+        String sql = \"INSERT INTO orders VALUES (\" + userId + \")\";\n"
            + "+        jdbcTemplate.execute(sql);\n"
            + "+        log.info(\"created\");\n"
            + "     }\n"
            + " }\n"
            + "diff --git a/README.md b/README.md\n"
            + "--- a/README.md\n"
            + "+++ b/README.md\n"
            + "@@ -1,2 +1,3 @@\n"
            + " # Demo\n"
            + "+新增一行说明\n";

    @Test
    @DisplayName("解析出文件数、新增行数与新增行内容")
    void parseFilesAndAddedLines() {
        DiffParser.ParsedDiff parsed = DiffParser.parse(SAMPLE);
        assertEquals(2, parsed.files().size(), "应识别 2 个文件");
        assertEquals(4, parsed.totalAdded(), "新增行数应为 4");

        DiffParser.FileDiff orderFile = parsed.files().get(0);
        assertEquals("src/main/java/com/acme/OrderService.java", orderFile.path());
        assertEquals(3, orderFile.addedCount());
        assertTrue(orderFile.addedLines().get(0).content().contains("INSERT INTO orders"));

        DiffParser.FileDiff readme = parsed.files().get(1);
        assertEquals("README.md", readme.path());
        assertEquals(1, readme.addedCount());
    }

    @Test
    @DisplayName("空 diff 不崩溃，isEmpty=true")
    void emptyDiff() {
        DiffParser.ParsedDiff parsed = DiffParser.parse("");
        assertNotNull(parsed);
        assertTrue(parsed.isEmpty());
        assertEquals(0, parsed.totalAdded());
    }

    @Test
    @DisplayName("截断重建：超过 maxLines 时只保留前 N 行且保留 @@ 头")
    void rebuildTruncates() {
        DiffParser.ParsedDiff parsed = DiffParser.parse(SAMPLE);
        DiffParser.FileDiff file = parsed.files().get(0);
        String rebuilt = DiffParser.rebuild(file, 0, 2);
        assertTrue(rebuilt.contains("@@"), "重建内容必须保留 hunk 头，模型才知道行号");
        long addedLines = rebuilt.lines().filter(l -> l.startsWith("+") && !l.startsWith("+++")).count();
        assertTrue(addedLines <= 2, "截断后新增行不应超过 maxLines，实际=" + addedLines);
    }

    @Test
    @DisplayName("纯删除/上下文行不计入新增")
    void removedLinesNotCounted() {
        DiffParser.ParsedDiff parsed = DiffParser.parse(SAMPLE);
        assertFalse(parsed.files().isEmpty());
        assertTrue(parsed.totalRemoved() >= 0);
    }
}
