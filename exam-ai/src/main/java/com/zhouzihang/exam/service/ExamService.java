package com.zhouzihang.exam.service;

import com.zhouzihang.exam.common.ApiException;
import com.zhouzihang.exam.domain.Answer;
import com.zhouzihang.exam.domain.Exam;
import com.zhouzihang.exam.domain.ExamRecord;
import com.zhouzihang.exam.domain.Question;
import com.zhouzihang.exam.domain.QuestionOption;
import com.zhouzihang.exam.infra.AppCacheService;
import com.zhouzihang.exam.infra.IdempotencyGuard;
import com.zhouzihang.exam.mapper.AnswerMapper;
import com.zhouzihang.exam.mapper.ExamMapper;
import com.zhouzihang.exam.mapper.ExamRecordMapper;
import com.zhouzihang.exam.mapper.QuestionMapper;
import com.zhouzihang.exam.mapper.QuestionOptionMapper;
import com.zhouzihang.exam.proctor.ProctorRiskEngine;
import com.zhouzihang.exam.security.AuthPrincipal;
import com.zhouzihang.exam.web.dto.ExamDtos;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 面试讲解点（考试主流程 + 幂等）：创建考试、开始考试、提交答卷。
 *
 * <p><b>开始考试的幂等</b>：exam_record 上 (exam_id, student_id) 唯一键。
 * 并发双击时后到者 insert 抛 DuplicateKeyException，服务端回查返回已有记录，
 * 前端始终看到同一条记录（不会出现两份卷子/两次计时）。</p>
 *
 * <p><b>交卷的幂等（重点）</b>，三道防线：</p>
 * <ol>
 *   <li>快路径：已 SUBMITTED 直接返回原结果，标 idempotent=true；</li>
 *   <li>Redisson 分布式锁（Redis 不可用时降级 JVM 锁）把同一份卷子的提交串行化，
 *       锁 key 精确到 recordId，不同考生互不阻塞；</li>
 *   <li>answer 唯一键 (exam_record_id, question_id) 做数据库兜底，
 *       即使锁失效也不会写出两条同一题的作答。</li>
 * </ol>
 *
 * <p><b>为什么 submit 不加 @Transactional？</b> 这是踩过的坑：
 * 如果方法级事务包住“加锁 -> 写库”，锁会在事务提交前释放，
 * 并发请求可能读到未提交的 IN_PROGRESS 状态后重复提交。
 * 这里采用“无事务 + 锁内逐条 upsert + 幂等覆盖”的方式：
 * 中途崩溃最多留下部分作答（状态仍 IN_PROGRESS），重试会覆盖，最终一致。
 * 代价是提交过程非原子，但换来了锁释放顺序正确，且交卷场景可重试。</p>
 */
@Slf4j
@Service
public class ExamService {

    private static final Set<String> EXAM_STATUS = Set.of("DRAFT", "PUBLISHED", "CLOSED");

    private final ExamMapper examMapper;
    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper optionMapper;
    private final ExamRecordMapper recordMapper;
    private final AnswerMapper answerMapper;
    private final AppCacheService cacheService;
    private final IdempotencyGuard idempotencyGuard;
    private final ProctorRiskEngine riskEngine;

    public ExamService(ExamMapper examMapper, QuestionMapper questionMapper,
                       QuestionOptionMapper optionMapper, ExamRecordMapper recordMapper,
                       AnswerMapper answerMapper, AppCacheService cacheService,
                       IdempotencyGuard idempotencyGuard, ProctorRiskEngine riskEngine) {
        this.examMapper = examMapper;
        this.questionMapper = questionMapper;
        this.optionMapper = optionMapper;
        this.recordMapper = recordMapper;
        this.answerMapper = answerMapper;
        this.cacheService = cacheService;
        this.idempotencyGuard = idempotencyGuard;
        this.riskEngine = riskEngine;
    }

    public List<ExamDtos.ExamView> listExams(AuthPrincipal principal) {
        List<Exam> exams = principal.isStudent() ? examMapper.findPublished() : examMapper.findAll();
        return exams.stream().map(ExamDtos.ExamView::of).toList();
    }

    /** 考试详情走 Caffeine 缓存（60s TTL），并做学生可见性校验。 */
    public ExamDtos.ExamDetailResponse getExamDetail(long examId, AuthPrincipal principal) {
        ExamDtos.ExamDetailResponse detail = cacheService.get(AppCacheService.EXAM_DETAIL,
                "exam:" + examId, () -> loadDetail(examId));
        if (principal.isStudent() && !"PUBLISHED".equals(detail.exam().status())) {
            throw ApiException.notFound("考试不存在或未发布");
        }
        return detail;
    }

    @Transactional
    public ExamDtos.ExamView createExam(ExamDtos.CreateExamRequest request, AuthPrincipal principal) {
        if (!principal.isTeacher() && !principal.isAdmin()) {
            throw ApiException.forbidden("只有教师/管理员可以创建考试");
        }
        String status = request.status() == null || request.status().isBlank()
                ? "PUBLISHED" : request.status().toUpperCase(Locale.ROOT);
        if (!EXAM_STATUS.contains(status)) {
            throw ApiException.badRequest("status 只能是 DRAFT/PUBLISHED/CLOSED");
        }

        List<Question> selected = new ArrayList<>();
        if (request.questionIds() != null && !request.questionIds().isEmpty()) {
            selected = questionMapper.findByIds(request.questionIds());
            if (selected.size() != request.questionIds().size()) {
                throw ApiException.badRequest("部分题目不存在");
            }
        }
        int totalScore = selected.isEmpty()
                ? (request.totalScore() == null ? 100 : request.totalScore())
                : selected.stream().mapToInt(q -> q.getScore() == null ? 0 : q.getScore()).sum();

        Exam exam = new Exam();
        exam.setTitle(request.title().trim());
        exam.setDescription(request.description());
        exam.setQuestionCount(selected.size());
        exam.setDurationMinutes(request.durationMinutes() == null ? 60 : request.durationMinutes());
        exam.setTotalScore(totalScore);
        exam.setStatus(status);
        exam.setCreatedBy(principal.userId());
        examMapper.insert(exam);

        for (Question question : selected) {
            questionMapper.attachToExam(question.getId(), exam.getId());
        }
        cacheService.invalidateAll(AppCacheService.QUESTION_BANK);
        log.info("创建考试 id={} title={} 题目数={} by={}", exam.getId(), exam.getTitle(),
                selected.size(), principal.username());
        return ExamDtos.ExamView.of(exam);
    }

    /** 开始考试：发卷 + 记录开始时间；同一学生对同一考试重复调用返回同一条记录。 */
    public ExamDtos.StartExamResponse startExam(long examId, AuthPrincipal principal) {
        Exam exam = examMapper.findById(examId);
        if (exam == null) {
            throw ApiException.notFound("考试不存在");
        }
        if (!"PUBLISHED".equals(exam.getStatus())) {
            throw ApiException.badRequest("考试未发布，不能开始");
        }
        ExamRecord existing = recordMapper.findByExamAndStudent(examId, principal.userId());
        if (existing != null) {
            if ("SUBMITTED".equals(existing.getStatus())) {
                throw ApiException.conflict("该考试已提交，不能重复开始");
            }
            return buildStartResponse(exam, existing, true);
        }

        List<Question> questions = loadQuestionSet(exam);
        if (questions.isEmpty()) {
            throw ApiException.badRequest("试卷没有题目，无法开始考试");
        }
        LocalDateTime now = LocalDateTime.now();
        ExamRecord record = new ExamRecord();
        record.setExamId(examId);
        record.setStudentId(principal.userId());
        record.setStatus("IN_PROGRESS");
        record.setStartedAt(now);
        record.setDeadlineAt(now.plusMinutes(exam.getDurationMinutes() == null ? 60 : exam.getDurationMinutes()));
        try {
            recordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发双击：唯一键兜底后回查，返回先到者创建的记录
            ExamRecord concurrent = recordMapper.findByExamAndStudent(examId, principal.userId());
            if (concurrent == null) {
                throw e;
            }
            if ("SUBMITTED".equals(concurrent.getStatus())) {
                throw ApiException.conflict("该考试已提交，不能重复开始");
            }
            return buildStartResponse(exam, concurrent, true);
        }
        log.info("开始考试 recordId={} examId={} student={}", record.getId(), examId, principal.username());
        return buildStartResponse(exam, record, false);
    }

    /** 提交答卷（幂等）。 */
    public ExamDtos.SubmitResponse submit(long recordId, ExamDtos.SubmitRequest request,
                                          AuthPrincipal principal) {
        ExamRecord record = recordMapper.findById(recordId);
        if (record == null) {
            throw ApiException.notFound("答卷不存在");
        }
        assertOwner(record, principal);
        Exam exam = examMapper.findById(record.getExamId());
        if (exam == null) {
            throw ApiException.notFound("考试不存在");
        }
        // 快速路径：已提交直接返回原结果（幂等）
        if ("SUBMITTED".equals(record.getStatus())) {
            return buildSubmitResponse(exam, record, principal, true);
        }

        try (IdempotencyGuard.LockHandle lock = idempotencyGuard.tryLock(
                "submit:record:" + recordId, 3000, 10_000)) {
            if (!lock.acquired()) {
                throw ApiException.conflict("同一份答卷正在提交中，请稍后重试");
            }
            // 双检：拿锁后重新读，可能已被其他请求提交
            record = recordMapper.findById(recordId);
            if ("SUBMITTED".equals(record.getStatus())) {
                return buildSubmitResponse(exam, record, principal, true);
            }

            List<Question> questions = loadQuestionSet(exam);
            Map<Long, Question> questionMap = questions.stream()
                    .collect(Collectors.toMap(Question::getId, q -> q));
            Map<Long, String> incoming = normalizeAnswers(request.answers(), questionMap);

            LocalDateTime now = LocalDateTime.now();
            int score = 0;
            int correctCount = 0;
            for (Question question : questions) {
                String raw = incoming.get(question.getId());
                if (raw == null) {
                    continue;
                }
                boolean correct = answersMatch(raw, question.getCorrectOption());
                int got = correct ? (question.getScore() == null ? 0 : question.getScore()) : 0;
                upsertAnswer(recordId, question.getId(), raw, correct, got, now);
                if (correct) {
                    correctCount++;
                    score += got;
                }
            }
            recordMapper.updateSubmit(recordId, "SUBMITTED", score, now, request.requestId());
            record.setStatus("SUBMITTED");
            record.setScore(score);
            record.setSubmittedAt(now);
            record.setSubmitRequestId(request.requestId());
            // 交卷后清空该考生的防作弊滑窗，避免影响后续考试/重考
            riskEngine.reset(recordId);
            log.info("交卷完成 recordId={} score={} correct={}/{} idempotent=false",
                    recordId, score, correctCount, questions.size());
            return new ExamDtos.SubmitResponse(recordId, exam.getId(), "SUBMITTED", score,
                    totalScore(questions), correctCount, questions.size(), now, false,
                    record.getDeadlineAt() != null && now.isAfter(record.getDeadlineAt()));
        }
    }

    /** 查询答卷结果（含正确答案与解析，仅提交后可见）。 */
    public ExamDtos.ResultResponse result(long recordId, AuthPrincipal principal) {
        ExamRecord record = recordMapper.findById(recordId);
        if (record == null) {
            throw ApiException.notFound("答卷不存在");
        }
        assertOwner(record, principal);
        if (!"SUBMITTED".equals(record.getStatus())) {
            throw ApiException.conflict("答卷尚未提交，暂不能查看结果");
        }
        Exam exam = examMapper.findById(record.getExamId());
        List<Question> questions = loadQuestionSet(exam);
        Map<Long, List<QuestionOption>> options = optionsOf(questions);
        Map<Long, Answer> answers = answerMapper.findByRecordId(recordId).stream()
                .collect(Collectors.toMap(Answer::getQuestionId, a -> a));

        List<ExamDtos.ResultItem> items = new ArrayList<>();
        for (Question question : questions) {
            Answer answer = answers.get(question.getId());
            boolean correct = answer != null && answer.getCorrectFlag() != null && answer.getCorrectFlag() == 1;
            items.add(new ExamDtos.ResultItem(
                    question.getId(), question.getQuestionType(), question.getStem(),
                    toOptionViews(options.get(question.getId())),
                    question.getCorrectOption(),
                    answer == null ? null : answer.getAnswerValue(),
                    correct,
                    answer == null || answer.getScore() == null ? 0 : answer.getScore(),
                    question.getScore() == null ? 0 : question.getScore(),
                    question.getAnalysis()));
        }
        int correctCount = (int) items.stream().filter(ExamDtos.ResultItem::correct).count();
        return new ExamDtos.ResultResponse(recordId, exam.getId(), exam.getTitle(), record.getStatus(),
                record.getScore() == null ? 0 : record.getScore(), totalScore(questions), correctCount,
                questions.size(), record.getStartedAt(), record.getSubmittedAt(), items);
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    private ExamDtos.ExamDetailResponse loadDetail(long examId) {
        Exam exam = examMapper.findById(examId);
        if (exam == null) {
            throw ApiException.notFound("考试不存在");
        }
        List<Question> questions = loadQuestionSet(exam);
        Map<Long, List<QuestionOption>> options = optionsOf(questions);
        List<ExamDtos.QuestionView> views = questions.stream()
                .map(q -> toQuestionView(q, options.get(q.getId())))
                .toList();
        return new ExamDtos.ExamDetailResponse(ExamDtos.ExamView.of(exam), views);
    }

    /** 试卷自有题优先；没有则用题库公共题兜底（数量取 question_count）。 */
    private List<Question> loadQuestionSet(Exam exam) {
        List<Question> questions = questionMapper.findByExamId(exam.getId());
        if (!questions.isEmpty()) {
            return questions;
        }
        int limit = exam.getQuestionCount() != null && exam.getQuestionCount() > 0
                ? exam.getQuestionCount() : 20;
        return questionMapper.findBank(limit);
    }

    private Map<Long, List<QuestionOption>> optionsOf(List<Question> questions) {
        if (questions.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = questions.stream().map(Question::getId).toList();
        return optionMapper.findByQuestionIds(ids).stream()
                .collect(Collectors.groupingBy(QuestionOption::getQuestionId, LinkedHashMap::new, Collectors.toList()));
    }

    private ExamDtos.StartExamResponse buildStartResponse(Exam exam, ExamRecord record, boolean resumed) {
        List<Question> questions = loadQuestionSet(exam);
        Map<Long, List<QuestionOption>> options = optionsOf(questions);
        List<ExamDtos.QuestionView> views = questions.stream()
                .map(q -> toQuestionView(q, options.get(q.getId())))
                .toList();
        return new ExamDtos.StartExamResponse(record.getId(), exam.getId(), exam.getTitle(),
                record.getStatus(), exam.getDurationMinutes() == null ? 0 : exam.getDurationMinutes(),
                record.getStartedAt(), record.getDeadlineAt(), questions.size(),
                totalScore(questions), views, resumed);
    }

    private ExamDtos.SubmitResponse buildSubmitResponse(Exam exam, ExamRecord record,
                                                        AuthPrincipal principal, boolean idempotent) {
        List<Question> questions = loadQuestionSet(exam);
        List<Answer> answers = answerMapper.findByRecordId(record.getId());
        int correctCount = (int) answers.stream()
                .filter(a -> a.getCorrectFlag() != null && a.getCorrectFlag() == 1).count();
        LocalDateTime submittedAt = record.getSubmittedAt();
        boolean late = submittedAt != null && record.getDeadlineAt() != null
                && submittedAt.isAfter(record.getDeadlineAt());
        return new ExamDtos.SubmitResponse(record.getId(), exam.getId(), record.getStatus(),
                record.getScore() == null ? 0 : record.getScore(), totalScore(questions),
                correctCount, questions.size(), submittedAt, idempotent, late);
    }

    private ExamDtos.QuestionView toQuestionView(Question q, List<QuestionOption> options) {
        return new ExamDtos.QuestionView(q.getId(), q.getQuestionType(), q.getStem(),
                q.getKnowledgePoint(), q.getDifficulty(), q.getScore() == null ? 0 : q.getScore(),
                toOptionViews(options));
    }

    private List<ExamDtos.OptionView> toOptionViews(List<QuestionOption> options) {
        if (options == null) {
            return List.of();
        }
        return options.stream()
                .sorted((a, b) -> a.getOptionKey().compareTo(b.getOptionKey()))
                .map(o -> new ExamDtos.OptionView(o.getOptionKey(), o.getContent()))
                .toList();
    }

    private int totalScore(List<Question> questions) {
        return questions.stream().mapToInt(q -> q.getScore() == null ? 0 : q.getScore()).sum();
    }

    private Map<Long, String> normalizeAnswers(List<ExamDtos.AnswerItem> items,
                                               Map<Long, Question> questionMap) {
        if (items == null || items.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>();
        for (ExamDtos.AnswerItem item : items) {
            if (item.questionId() == null) {
                throw ApiException.badRequest("questionId 不能为空");
            }
            if (!questionMap.containsKey(item.questionId())) {
                throw ApiException.badRequest("题目 " + item.questionId() + " 不属于本场考试");
            }
            if (result.putIfAbsent(item.questionId(), item.answer()) != null) {
                throw ApiException.badRequest("题目 " + item.questionId() + " 重复作答");
            }
        }
        return result;
    }

    private void upsertAnswer(long recordId, long questionId, String raw, boolean correct,
                              int got, LocalDateTime now) {
        Answer existing = answerMapper.findByRecordAndQuestion(recordId, questionId);
        if (existing == null) {
            Answer answer = new Answer();
            answer.setExamRecordId(recordId);
            answer.setQuestionId(questionId);
            answer.setAnswerValue(raw);
            answer.setCorrectFlag(correct ? 1 : 0);
            answer.setScore(got);
            answer.setAnsweredAt(now);
            try {
                answerMapper.insert(answer);
            } catch (DuplicateKeyException e) {
                // 唯一键兜底：极端并发下改为覆盖更新
                Answer concurrent = answerMapper.findByRecordAndQuestion(recordId, questionId);
                if (concurrent == null) {
                    throw e;
                }
                concurrent.setAnswerValue(raw);
                concurrent.setCorrectFlag(correct ? 1 : 0);
                concurrent.setScore(got);
                concurrent.setAnsweredAt(now);
                answerMapper.update(concurrent);
            }
        } else {
            existing.setAnswerValue(raw);
            existing.setCorrectFlag(correct ? 1 : 0);
            existing.setScore(got);
            existing.setAnsweredAt(now);
            answerMapper.update(existing);
        }
    }

    /**
     * 答案比较：大小写不敏感、忽略分隔符、多选按字符排序后比较。
     * 例如 "CA" 与 "A,C" 都等价于 "AC"；判断题兼容 TRUE/正确/T。
     */
    static boolean answersMatch(String given, String correct) {
        String a = normalizeOption(given);
        String b = normalizeOption(correct);
        return a != null && a.equals(b);
    }

    static String normalizeOption(String value) {
        if (value == null) {
            return null;
        }
        String upper = value.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[,\\s;，；、|]", "");
        if (upper.equals("TRUE") || upper.equals("正确") || upper.equals("√")) {
            upper = "T";
        } else if (upper.equals("FALSE") || upper.equals("错误") || upper.equals("×")) {
            upper = "F";
        }
        char[] chars = upper.toCharArray();
        Arrays.sort(chars);
        return new String(chars);
    }

    private void assertOwner(ExamRecord record, AuthPrincipal principal) {
        if (principal.isStudent() && !record.getStudentId().equals(principal.userId())) {
            throw ApiException.forbidden("不能操作其他考生的答卷");
        }
    }

    /** 供测试/演示脚本使用：清理缓存（生成题目后失效）。 */
    public void invalidateExamCache(long examId) {
        cacheService.invalidate(AppCacheService.EXAM_DETAIL, "exam:" + examId);
    }

    /** 工具方法：判断题目集合是否包含某 id（避免测试构造错误数据）。 */
    static Set<Long> idsOf(List<Question> questions) {
        return questions.stream().map(Question::getId).collect(Collectors.toCollection(HashSet::new));
    }
}
