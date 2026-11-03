package com.zhouzihang.devflow.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unified diff 解析器。
 *
 * <p>面试讲解点：自动代码审查的第一个真问题不是"调 LLM"，而是<b>把 diff 解析对</b>。
 * 行号错了，模型给出的 "第 42 行有注入" 就指到别处，整个功能失去信任。这里的规则：</p>
 * <ul>
 *   <li>{@code @@ -a,b +c,d @@} 的 {@code c} 是新文件起始行号；{@code +} 行与空格上下文行
 *       都让新行号 +1，{@code -} 行不动（它属于旧文件）；</li>
 *   <li>{@code +++ b/path} 定位当前文件，去掉 {@code b/} 前缀；{@code /dev/null} 表示删除；</li>
 *   <li>{@code \ No newline at end of file} 直接忽略；</li>
 *   <li>没有 hunk header 的裸 diff 也尽量兜底（首行按行号 1 起算）。</li>
 * </ul>
 */
public final class DiffParser {

    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@\\s+-\\d+(?:,\\d+)?\\s+\\+(\\d+)(?:,\\d+)?\\s+@@");

    private DiffParser() {
    }

    /** 新增行（新文件行号 + 内容）。 */
    public record AddedLine(int line, String content) {
    }

    /** 单个文件的变更摘要。 */
    public record FileDiff(String path, List<AddedLine> addedLines, int addedCount, int removedCount) {

        /** 该文件在 diff 中的字符量（用于截断预算）。 */
        public int chars() {
            int c = path.length() + 16;
            for (AddedLine l : addedLines) {
                c += l.content().length() + 2;
            }
            return c;
        }
    }

    /** 解析结果。 */
    public record ParsedDiff(List<FileDiff> files, int totalAdded, int totalRemoved, int chars) {

        public boolean isEmpty() {
            return files.isEmpty() && totalAdded == 0;
        }
    }

    /** 可变中间态。 */
    private static final class Builder {
        final String path;
        final List<AddedLine> added = new ArrayList<>();
        int removed;
        int lastLine = -1;

        Builder(String path) {
            this.path = path;
        }

        void add(int line, String content) {
            added.add(new AddedLine(line, content));
            lastLine = line;
        }

        FileDiff build() {
            return new FileDiff(path, List.copyOf(added), added.size(), removed);
        }
    }

    /** 解析 unified diff 文本。 */
    public static ParsedDiff parse(String diffText) {
        Map<String, Builder> byPath = new LinkedHashMap<>();
        if (diffText == null || diffText.isBlank()) {
            return new ParsedDiff(List.of(), 0, 0, 0);
        }
        Builder current = null;
        int newLine = 0;
        for (String line : diffText.split("\n", -1)) {
            String stripped = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (stripped.startsWith("diff --git")) {
                current = null;
                continue;
            }
            if (stripped.startsWith("+++ ")) {
                String path = normalizePath(stripped.substring(4).trim());
                if ("/dev/null".equals(path)) {
                    current = null;
                    continue;
                }
                current = byPath.computeIfAbsent(path, Builder::new);
                newLine = 0;
                continue;
            }
            if (stripped.startsWith("--- ")) {
                continue;
            }
            Matcher hunk = HUNK_HEADER.matcher(stripped);
            if (hunk.find()) {
                newLine = Integer.parseInt(hunk.group(1));
                if (current == null) {
                    // 没看到 +++ 的裸 diff：用一个占位文件承载，保证不丢内容
                    current = byPath.computeIfAbsent("unknown.diff", Builder::new);
                }
                continue;
            }
            if (stripped.startsWith("\\")) {
                continue; // \ No newline at end of file
            }
            if (stripped.startsWith("+")) {
                if (current == null) {
                    current = byPath.computeIfAbsent("unknown.diff", Builder::new);
                }
                if (newLine <= 0) {
                    newLine = 1; // 裸 diff 兜底
                }
                current.add(newLine, stripped.substring(1));
                newLine++;
            } else if (stripped.startsWith("-")) {
                if (current != null) {
                    current.removed++;
                }
            } else if (stripped.startsWith(" ") || stripped.isEmpty()) {
                if (stripped.startsWith(" ") && newLine > 0) {
                    newLine++; // 上下文行也推进新文件行号
                }
            }
        }
        List<FileDiff> files = new ArrayList<>();
        int totalAdded = 0;
        int totalRemoved = 0;
        int chars = 0;
        for (Builder b : byPath.values()) {
            FileDiff f = b.build();
            files.add(f);
            totalAdded += f.addedCount();
            totalRemoved += f.removedCount();
            chars += f.chars();
        }
        return new ParsedDiff(List.copyOf(files), totalAdded, totalRemoved, chars);
    }

    private static String normalizePath(String raw) {
        String p = raw;
        int tab = p.indexOf('\t');
        if (tab > 0) {
            p = p.substring(0, tab);
        }
        if (p.startsWith("b/") || p.startsWith("a/")) {
            p = p.substring(2);
        }
        return p;
    }

    /**
     * 把选中的新增行重新拼成"精简 unified diff"（用于喂给 LLM）。
     * 行号连续的行合成一个 hunk，保证 LLM/行号推算都不迷路。
     */
    public static String rebuild(FileDiff file, int startIndex, int maxLines) {
        StringBuilder sb = new StringBuilder();
        sb.append("diff --git a/").append(file.path()).append(" b/").append(file.path()).append('\n');
        sb.append("--- a/").append(file.path()).append('\n');
        sb.append("+++ b/").append(file.path()).append('\n');
        List<AddedLine> lines = file.addedLines();
        int from = Math.max(0, startIndex);
        int to = Math.min(lines.size(), from + maxLines);
        int i = from;
        while (i < to) {
            AddedLine first = lines.get(i);
            sb.append("@@ -0,0 +").append(first.line()).append(" @@\n");
            int prev = first.line();
            while (i < to && lines.get(i).line() == prev) {
                sb.append('+').append(lines.get(i).content()).append('\n');
                prev++;
                i++;
            }
        }
        return sb.toString();
    }
}
