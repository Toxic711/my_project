package com.zhouzihang.devflow.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhouzihang.devflow.common.ApiException;
import com.zhouzihang.devflow.common.ErrorCodes;
import com.zhouzihang.devflow.config.LlmProperties;
import com.zhouzihang.devflow.llm.LlmMessage;
import com.zhouzihang.devflow.llm.LlmRequest;
import com.zhouzihang.devflow.llm.LlmResponse;
import com.zhouzihang.devflow.llm.ModelRouter;
import com.zhouzihang.devflow.prompt.PromptRenderer;
import com.zhouzihang.devflow.prompt.PromptSeeder;
import com.zhouzihang.devflow.prompt.PromptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Git Diff 自动 Code Review。
 *
 * <p>面试讲解点（把"能跑"做到"可信"的四个细节）：</p>
 * <ol>
 *   <li><b>先解析再送模型</b>：{@link DiffParser} 把 diff 拆成"文件 + 新增行 + 行号"，
 *       送给模型的也是重新拼的精简 diff——只保留新增行，省 token 且让模型聚焦。</li>
 *   <li><b>大 diff 截断 + 分段</b>：按 {@code app.llm.max-diff-chars} 做字符预算截断，
 *       再按 {@code max-review-segments} 切段，每段独立调用。截断/丢弃的行数会如实
 *       写进结果（{@code truncated / droppedFiles}），绝不假装"全量审查过"。</li>
 *   <li><b>结构化输出 + 容错解析</b>：要求模型输出 JSON 数组；解析失败不抛 500，
 *       而是把 run 标记为 PARTIAL 并附解析错误说明——LLM 输出不稳定是常态，
 *       接口必须能表达"这次结果不完整"。</li>
 *   <li><b>幂等与可追溯</b>：webhook 触发的审查用 deliveryId 落库，重复投递直接返回
 *       既有结果；每次运行都存 findings JSON，支持 {@code GET /api/review/runs/{id}} 回看。</li>
 * </ol>
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);
    private static final int CHUNK_LINES = 40;
    private static final int MAX_FINDINGS = 60;

    private final ModelRouter router;
    private final PromptService promptService;
    private final ReviewRunMapper runMapper;
    private final ObjectMapper mapper;
    private final LlmProperties llmProps;
    private final Clock clock;

    public ReviewService(ModelRouter router, PromptService promptService, ReviewRunMapper runMapper,
                         ObjectMapper mapper, LlmProperties llmProps, Clock clock) {
        this.router = router;
        this.promptService = promptService;
        this.runMapper = runMapper;
        this.mapper = mapper;
        this.llmProps = llmProps;
        this.clock = clock;
    }

    /**
     * 审查一段 unified diff。
     *
     * @param diffText   原始 diff
     * @param source     sample | manual | webhook
     * @param repo       仓库名（可空）
     * @param prNumber   PR 号（可空）
     * @param deliveryId GitHub delivery id（可空）
     */
    public ReviewResult review(String diffText, String source, String repo, Integer prNumber, String deliveryId) {
        if (diffText == null || diffText.isBlank()) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "diff 不能为空");
        }
        long start = System.currentTimeMillis();
        DiffParser.ParsedDiff parsed = DiffParser.parse(diffText);
        if (parsed.isEmpty()) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR,
                    "未从输入中解析出任何新增行（请确认是 unified diff 格式：包含 @@ hunk 与 + 新增行）");
        }
        int budget = Math.max(1000, llmProps.getMaxDiffChars());
        int maxSegments = Math.max(1, llmProps.getMaxReviewSegments());
        int segmentBudget = Math.max(1000, budget / maxSegments);

        BuildResult build = buildSegments(parsed, budget, segmentBudget, maxSegments);

        List<Finding> findings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Set<String> dedupe = new LinkedHashSet<>();
        boolean parseError = false;
        String provider = null;
        String model = null;
        boolean degraded = false;
        int index = 0;
        for (Segment segment : build.segments()) {
            index++;
            String rendered;
            try {
                rendered = promptService.render("code-review", Map.of("diff", segment.text())).text();
            } catch (ApiException e) {
                rendered = PromptRenderer.render(PromptSeeder.REVIEW_TEMPLATE, Map.of("diff", segment.text()));
                notes.add("prompt[code-review] 不可用，已回退内置模板");
            }
            LlmRequest request = LlmRequest.builder()
                    .requestId("review-" + UUID.randomUUID())
                    .feature("code-review")
                    .messages(List.of(LlmMessage.user(rendered)))
                    .maxTokens(900)
                    .temperature(0.1)
                    .metadata(Map.of("segment", index, "files", segment.files()))
                    .build();
            LlmResponse response = router.chat(request);
            provider = response.getProvider();
            model = response.getModel();
            degraded = degraded || response.isDegraded();
            try {
                for (Finding f : parseFindings(response.getContent())) {
                    String key = f.file() + "|" + f.line() + "|" + f.message();
                    if (dedupe.add(key)) {
                        findings.add(f);
                    }
                }
            } catch (RuntimeException e) {
                parseError = true;
                notes.add("第 " + index + " 段 LLM 输出无法解析为 JSON 数组：" + abbrev(e.getMessage())
                        + "；原始输出片段：" + abbrev(response.getContent()));
                log.warn("code-review 解析失败 segment={} err={}", index, e.getMessage());
            }
        }
        if (findings.size() > MAX_FINDINGS) {
            notes.add("findings 超过 " + MAX_FINDINGS + " 条，已截断");
            findings = new ArrayList<>(findings.subList(0, MAX_FINDINGS));
        }
        if (build.truncated()) {
            notes.add("diff 超过 " + budget + " 字符预算，已截断：丢弃 " + build.droppedAddedLines()
                    + " 行新增内容（" + build.droppedFiles() + " 个文件未完全审查）");
        }
        int high = count(findings, "HIGH");
        int medium = count(findings, "MEDIUM");
        int low = count(findings, "LOW");
        long duration = System.currentTimeMillis() - start;

        ReviewRun run = new ReviewRun();
        run.setDeliveryId(deliveryId);
        run.setSource(source == null ? "manual" : source);
        run.setRepo(repo);
        run.setPrNumber(prNumber);
        run.setStatus(parseError ? "PARTIAL" : "COMPLETED");
        run.setFilesChanged(parsed.files().size());
        run.setAddLines(parsed.totalAdded());
        run.setFindingsCount(findings.size());
        run.setHighCount(high);
        run.setMediumCount(medium);
        run.setLowCount(low);
        run.setTruncated(build.truncated() ? 1 : 0);
        run.setDiffChars(diffText.length());
        run.setSegments(build.segments().size());
        run.setDurationMs((int) Math.min(Integer.MAX_VALUE, duration));
        run.setCreatedAt(LocalDateTime.now(clock));
        try {
            run.setFindingsJson(mapper.writeValueAsString(findings));
        } catch (Exception e) {
            run.setFindingsJson("[]");
            notes.add("findings 序列化失败：" + abbrev(e.getMessage()));
        }
        runMapper.insert(run);

        return new ReviewResult(run.getId(), run.getStatus(), run.getSource(), repo, prNumber,
                parsed.files().size(), parsed.totalAdded(), parsed.totalRemoved(),
                build.segments().size(), build.truncated(), build.droppedFiles(),
                findings.size(), high, medium, low, provider, model, degraded,
                duration, findings, notes);
    }

    /** 按 id 查询历史审查结果。 */
    public ReviewResult getRun(long id) {
        ReviewRun run = runMapper.selectById(id);
        if (run == null) {
            throw ApiException.notFound("review run 不存在: id=" + id);
        }
        List<Finding> findings = new ArrayList<>();
        if (run.getFindingsJson() != null && !run.getFindingsJson().isBlank()) {
            try {
                findings = parseFindings(run.getFindingsJson());
            } catch (RuntimeException e) {
                log.warn("历史 findings 解析失败 id={} err={}", id, e.getMessage());
            }
        }
        return new ReviewResult(run.getId(), run.getStatus(), run.getSource(), run.getRepo(),
                run.getPrNumber(), nz(run.getFilesChanged()), nz(run.getAddLines()), 0,
                nz(run.getSegments()), nz(run.getTruncated()) == 1, 0,
                nz(run.getFindingsCount()), nz(run.getHighCount()), nz(run.getMediumCount()),
                nz(run.getLowCount()), null, null, false, nz(run.getDurationMs()), findings, List.of());
    }

    /** 最近运行记录（简表，不含 findings 明细）。 */
    public List<ReviewRun> recentRuns(int limit) {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ReviewRun> qw =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        qw.orderByDesc("id").last("LIMIT " + Math.max(1, Math.min(limit, 50)));
        return runMapper.selectList(qw);
    }

    // ------------------------------------------------------------------
    // 分段与解析
    // ------------------------------------------------------------------

    /** 按字符预算切段；超出预算即截断并记录丢弃量。 */
    BuildResult buildSegments(DiffParser.ParsedDiff parsed, int totalBudget, int segmentBudget, int maxSegments) {
        List<Segment> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        List<String> currentFiles = new ArrayList<>();
        int currentAdded = 0;
        int used = 0;
        int includedAdded = 0;
        int droppedAdded = 0;
        int droppedFiles = 0;
        boolean truncated = false;
        boolean stop = false;
        for (DiffParser.FileDiff file : parsed.files()) {
            int idx = 0;
            int fileIncluded = 0;
            while (idx < file.addedLines().size()) {
                String chunk = DiffParser.rebuild(file, idx, CHUNK_LINES);
                int remainingInFile = file.addedLines().size() - idx;
                int chunkLines = Math.min(CHUNK_LINES, remainingInFile);
                if (used + chunk.length() > totalBudget) {
                    truncated = true;
                    droppedAdded += remainingInFile;
                    stop = true;
                    break;
                }
                if (current.length() > 0 && current.length() + chunk.length() > segmentBudget) {
                    segments.add(new Segment(current.toString(), List.copyOf(currentFiles), currentAdded));
                    current = new StringBuilder();
                    currentFiles = new ArrayList<>();
                    currentAdded = 0;
                }
                current.append(chunk);
                if (!currentFiles.contains(file.path())) {
                    currentFiles.add(file.path());
                }
                currentAdded += chunkLines;
                fileIncluded += chunkLines;
                includedAdded += chunkLines;
                used += chunk.length();
                idx += CHUNK_LINES;
            }
            if (fileIncluded == 0 && file.addedLines().size() > 0) {
                droppedFiles++;
            }
            if (stop) {
                break;
            }
        }
        if (current.length() > 0) {
            segments.add(new Segment(current.toString(), List.copyOf(currentFiles), currentAdded));
        }
        // 兜底：段数不超过配置上限（把尾部合并进最后一段）
        while (segments.size() > maxSegments && segments.size() > 1) {
            Segment last = segments.remove(segments.size() - 1);
            Segment prev = segments.remove(segments.size() - 1);
            List<String> files = new ArrayList<>(prev.files());
            last.files().forEach(f -> {
                if (!files.contains(f)) {
                    files.add(f);
                }
            });
            segments.add(new Segment(prev.text() + last.text(), List.copyOf(files), prev.addedLines() + last.addedLines()));
        }
        if (!truncated) {
            droppedAdded = parsed.totalAdded() - includedAdded;
            if (droppedAdded > 0) {
                truncated = true;
            }
        }
        return new BuildResult(segments, truncated, droppedAdded, droppedFiles);
    }

    /** 从 LLM 输出里抠出 JSON 数组并转成 Finding。 */
    List<Finding> parseFindings(String content) {
        if (content == null) {
            throw new IllegalStateException("empty output");
        }
        int left = content.indexOf('[');
        int right = content.lastIndexOf(']');
        if (left < 0 || right <= left) {
            throw new IllegalStateException("no json array in output");
        }
        String json = content.substring(left, right + 1);
        JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("invalid json: " + e.getMessage());
        }
        if (!node.isArray()) {
            throw new IllegalStateException("json root is not array");
        }
        List<Finding> findings = new ArrayList<>();
        for (JsonNode item : node) {
            String message = item.path("message").asText("");
            if (message.isBlank()) {
                continue;
            }
            String file = item.path("file").asText("unknown");
            int line = item.path("line").asInt(0);
            String severity = Finding.normalizeSeverity(item.path("severity").asText("LOW"));
            String suggestion = item.path("suggestion").asText("");
            findings.add(new Finding(file, line, severity, message, suggestion));
        }
        return findings;
    }

    private int count(List<Finding> findings, String severity) {
        return (int) findings.stream().filter(f -> severity.equals(f.severity())).count();
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private String abbrev(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() > 200 ? t.substring(0, 200) + "..." : t;
    }

    /** 分段单元。 */
    record Segment(String text, List<String> files, int addedLines) {
    }

    /** 分段结果。 */
    record BuildResult(List<Segment> segments, boolean truncated, int droppedAddedLines, int droppedFiles) {
    }

    /** 审查结果（HTTP 响应体）。 */
    public record ReviewResult(Long runId, String status, String source, String repo, Integer prNumber,
                               int filesChanged, int addedLines, int removedLines, int segments,
                               boolean truncated, int droppedFiles, int totalFindings, int high, int medium,
                               int low, String provider, String model, boolean degraded, long durationMs,
                               List<Finding> findings, List<String> notes) {
    }
}
