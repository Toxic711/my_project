package com.zhouzihang.exam.service;

import com.zhouzihang.exam.common.ApiException;
import com.zhouzihang.exam.domain.Exam;
import com.zhouzihang.exam.domain.LlmTask;
import com.zhouzihang.exam.domain.Question;
import com.zhouzihang.exam.domain.QuestionOption;
import com.zhouzihang.exam.infra.AppCacheService;
import com.zhouzihang.exam.llm.GeneratedQuestion;
import com.zhouzihang.exam.llm.LlmClientRouter;
import com.zhouzihang.exam.llm.LlmGeneration;
import com.zhouzihang.exam.llm.LlmRequest;
import com.zhouzihang.exam.mapper.ExamMapper;
import com.zhouzihang.exam.mapper.LlmTaskMapper;
import com.zhouzihang.exam.mapper.QuestionMapper;
import com.zhouzihang.exam.mapper.QuestionOptionMapper;
import com.zhouzihang.exam.security.AuthPrincipal;
import com.zhouzihang.exam.web.dto.LlmDtos;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 面试讲解点（LLM 出题落地）：把模型输出变成可判分的题库数据，并记录成本。
 *
 * <p>关键设计：</p>
 * <ol>
 *   <li><b>结构化校验</b>：模型输出逐题校验（题干非空、选项数、答案合法），
 *       非法题目直接丢弃而不是入库 —— 宁可少几题，不可污染题库；</li>
 *   <li><b>可追溯</b>：题目 source=LLM，任务表记录 provider/model/token/latency/
 *       prompt 摘要，面试时能回答“这道题怎么来的、花了多少 token”；</li>
 *   <li><b>缓存失效</b>：入题后清题库缓存/考试详情缓存，避免读到旧题量；</li>
 *   <li><b>降级可见</b>：status=SUCCESS/FALLBACK 落库，/api/llm/usage 可查降级率。</li>
 * </ol>
 */
@Slf4j
@Service
public class QuestionService {

    private static final Set<String> TYPES = Set.of("SINGLE", "MULTIPLE", "JUDGE");
    private static final Set<String> DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD");

    private final LlmClientRouter llmRouter;
    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper optionMapper;
    private final LlmTaskMapper llmTaskMapper;
    private final ExamMapper examMapper;
    private final AppCacheService cacheService;

    public QuestionService(LlmClientRouter llmRouter, QuestionMapper questionMapper,
                           QuestionOptionMapper optionMapper, LlmTaskMapper llmTaskMapper,
                           ExamMapper examMapper, AppCacheService cacheService) {
        this.llmRouter = llmRouter;
        this.questionMapper = questionMapper;
        this.optionMapper = optionMapper;
        this.llmTaskMapper = llmTaskMapper;
        this.examMapper = examMapper;
        this.cacheService = cacheService;
    }

    @Transactional
    public LlmDtos.GenerateQuestionsResponse generate(LlmDtos.GenerateQuestionsRequest request,
                                                      AuthPrincipal principal) {
        String type = normalizeType(request.type());
        String difficulty = request.difficulty() == null || request.difficulty().isBlank()
                ? "MEDIUM" : request.difficulty().trim().toUpperCase(Locale.ROOT);
        if (!DIFFICULTIES.contains(difficulty)) {
            throw ApiException.badRequest("difficulty 只能是 EASY/MEDIUM/HARD");
        }
        int count = request.count() == null ? 1 : request.count();
        String knowledgePoint = request.knowledgePoint().trim();
        if (knowledgePoint.length() > 60) {
            knowledgePoint = knowledgePoint.substring(0, 60);
        }
        Exam exam = null;
        if (request.examId() != null) {
            exam = examMapper.findById(request.examId());
            if (exam == null) {
                throw ApiException.notFound("考试不存在");
            }
        }

        LlmGeneration generation = llmRouter.generate(
                new LlmRequest(type, knowledgePoint, count, difficulty, request.extraInstruction()));

        List<LlmDtos.GeneratedQuestionView> persisted = new ArrayList<>();
        for (GeneratedQuestion generated : generation.getQuestions()) {
            LlmDtos.GeneratedQuestionView view = persistQuestion(generated, request, type,
                    knowledgePoint, difficulty, principal);
            if (view != null) {
                persisted.add(view);
            }
        }
        if (persisted.isEmpty()) {
            throw new IllegalStateException("LLM 未返回有效题目（已丢弃全部非法输出）");
        }

        LlmTask task = new LlmTask();
        task.setProvider(generation.getProvider());
        task.setModel(generation.getModel());
        task.setQuestionType(type);
        task.setKnowledgePoint(knowledgePoint);
        task.setQuestionCount(persisted.size());
        task.setPromptTokens(generation.getPromptTokens());
        task.setCompletionTokens(generation.getCompletionTokens());
        task.setTotalTokens(generation.getPromptTokens() + generation.getCompletionTokens());
        task.setLatencyMs(generation.getLatencyMs());
        task.setStatus(generation.getStatus());
        task.setRequestDigest(sha256(generation.getPrompt()));
        task.setCreatedBy(principal.userId());
        llmTaskMapper.insert(task);

        cacheService.invalidateAll(AppCacheService.QUESTION_BANK);
        if (exam != null) {
            recalcExamStats(exam);
            cacheService.invalidate(AppCacheService.EXAM_DETAIL, "exam:" + exam.getId());
        }
        log.info("LLM 出题完成 taskId={} provider={} count={} status={} tokens={}",
                task.getId(), generation.getProvider(), persisted.size(), generation.getStatus(),
                task.getTotalTokens());

        LlmDtos.UsageView usage = new LlmDtos.UsageView(generation.getPromptTokens(),
                generation.getCompletionTokens(), task.getTotalTokens(), generation.getLatencyMs());
        return new LlmDtos.GenerateQuestionsResponse(task.getId(), generation.getProvider(),
                generation.getModel(), generation.isFallback(), usage, persisted);
    }

    /** 题库/考试用量统计。 */
    public LlmDtos.LlmUsageResponse usage() {
        LlmTaskMapper.LlmUsageTotals totals = llmTaskMapper.usageTotals();
        long taskCount = totals == null || totals.getTaskCount() == null ? 0 : totals.getTaskCount();
        long fallback = totals == null || totals.getFallbackCount() == null ? 0 : totals.getFallbackCount();
        List<LlmDtos.ProviderUsage> byProvider = llmTaskMapper.usageByProvider().stream()
                .map(p -> new LlmDtos.ProviderUsage(p.getProvider(), p.getTaskCount(),
                        p.getTotalTokens(), p.getFallbackCount()))
                .toList();
        double avgLatency = totals == null || totals.getAvgLatencyMs() == null
                ? 0 : Math.round(totals.getAvgLatencyMs() * 100.0) / 100.0;
        return new LlmDtos.LlmUsageResponse(taskCount, Math.max(0, taskCount - fallback), fallback,
                totals == null || totals.getPromptTokens() == null ? 0 : totals.getPromptTokens(),
                totals == null || totals.getCompletionTokens() == null ? 0 : totals.getCompletionTokens(),
                totals == null || totals.getTotalTokens() == null ? 0 : totals.getTotalTokens(),
                avgLatency, byProvider);
    }

    // ------------------------------------------------------------------

    private LlmDtos.GeneratedQuestionView persistQuestion(GeneratedQuestion generated,
                                                          LlmDtos.GenerateQuestionsRequest request,
                                                          String requestType, String knowledgePoint,
                                                          String difficulty, AuthPrincipal principal) {
        String type = generated.getType() == null || generated.getType().isBlank()
                ? requestType : generated.getType().trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            log.warn("丢弃非法题目：type={}", generated.getType());
            return null;
        }
        String stem = generated.getStem() == null ? "" : generated.getStem().trim();
        if (stem.isEmpty()) {
            log.warn("丢弃非法题目：题干为空");
            return null;
        }
        if (stem.length() > 1000) {
            stem = stem.substring(0, 1000);
        }
        Map<String, String> options = sanitizeOptions(type, generated.getOptions());
        if (options == null) {
            log.warn("丢弃非法题目：选项不合法 type={}", type);
            return null;
        }
        String correct = normalizeCorrectOption(type, generated.getCorrectOption(), options.keySet());
        if (correct == null) {
            log.warn("丢弃非法题目：正确答案不合法 type={} correct={}", type, generated.getCorrectOption());
            return null;
        }
        String kp = generated.getKnowledgePoint() == null || generated.getKnowledgePoint().isBlank()
                ? knowledgePoint : truncate(generated.getKnowledgePoint().trim(), 60);
        String diff = generated.getDifficulty() == null || generated.getDifficulty().isBlank()
                ? difficulty : generated.getDifficulty().trim().toUpperCase(Locale.ROOT);
        if (!DIFFICULTIES.contains(diff)) {
            diff = difficulty;
        }
        int score = generated.getScore() > 0 ? generated.getScore() : 5;

        Question question = new Question();
        question.setExamId(request.examId());
        question.setQuestionType(type);
        question.setStem(stem);
        question.setKnowledgePoint(kp);
        question.setDifficulty(diff);
        question.setScore(score);
        question.setCorrectOption(correct);
        question.setAnalysis(generated.getAnalysis() == null ? "" : truncate(generated.getAnalysis(), 1000));
        question.setSource("LLM");
        questionMapper.insert(question);

        List<Map.Entry<String, String>> sorted = new ArrayList<>(options.entrySet());
        sorted.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, String> entry : sorted) {
            QuestionOption option = new QuestionOption();
            option.setQuestionId(question.getId());
            option.setOptionKey(entry.getKey());
            option.setContent(truncate(entry.getValue(), 500));
            optionMapper.insert(option);
        }
        log.debug("LLM 题目已入库 id={} type={} by={}", question.getId(), type, principal.username());
        return new LlmDtos.GeneratedQuestionView(question.getId(), type, stem,
                new LinkedHashMap<>(options), correct, question.getAnalysis(), diff, kp, score);
    }

    private Map<String, String> sanitizeOptions(String type, Map<String, String> raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if ("JUDGE".equals(type)) {
            if (raw == null || raw.isEmpty()) {
                result.put("T", "正确");
                result.put("F", "错误");
                return result;
            }
            String t = raw.getOrDefault("T", raw.getOrDefault("t", null));
            String f = raw.getOrDefault("F", raw.getOrDefault("f", null));
            if (t == null || f == null) {
                result.put("T", "正确");
                result.put("F", "错误");
            } else {
                result.put("T", t);
                result.put("F", f);
            }
            return result;
        }
        if (raw == null || raw.size() < 2) {
            return null;
        }
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim().toUpperCase(Locale.ROOT);
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (key.length() == 1 && "ABCDE".contains(key) && !value.isEmpty()) {
                result.put(key, value);
            }
        }
        return result.size() >= 2 ? result : null;
    }

    private String normalizeCorrectOption(String type, String correct, Set<String> optionKeys) {
        if (correct == null) {
            return null;
        }
        String normalized = correct.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[,\\s;，；]", "");
        if ("JUDGE".equals(type)) {
            if (normalized.equals("TRUE") || normalized.equals("正确")) {
                normalized = "T";
            } else if (normalized.equals("FALSE") || normalized.equals("错误")) {
                normalized = "F";
            }
            return ("T".equals(normalized) || "F".equals(normalized)) && optionKeys.contains(normalized)
                    ? normalized : null;
        }
        if (normalized.isEmpty()) {
            return null;
        }
        char[] chars = normalized.toCharArray();
        java.util.Arrays.sort(chars);
        String sorted = new String(chars);
        for (char c : chars) {
            if (!optionKeys.contains(String.valueOf(c))) {
                return null;
            }
        }
        if ("SINGLE".equals(type) && sorted.length() != 1) {
            return null;
        }
        if ("MULTIPLE".equals(type) && sorted.length() < 2) {
            return null;
        }
        return sorted;
    }

    private void recalcExamStats(Exam exam) {
        int count = questionMapper.countByExamId(exam.getId());
        int total = questionMapper.findByExamId(exam.getId()).stream()
                .mapToInt(q -> q.getScore() == null ? 0 : q.getScore()).sum();
        examMapper.updateStats(exam.getId(), count, total, exam.getStatus());
    }

    private static String normalizeType(String type) {
        String normalized = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(normalized)) {
            throw ApiException.badRequest("type 只能是 SINGLE/MULTIPLE/JUDGE");
        }
        return normalized;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
