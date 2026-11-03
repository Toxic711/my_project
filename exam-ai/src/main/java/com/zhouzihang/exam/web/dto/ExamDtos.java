package com.zhouzihang.exam.web.dto;

import com.zhouzihang.exam.domain.Exam;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

/** 考试/作答相关 DTO。 */
public final class ExamDtos {

    private ExamDtos() {
    }

    public record CreateExamRequest(
            @NotBlank(message = "考试标题不能为空") String title,
            String description,
            @Min(value = 1, message = "考试时长至少 1 分钟") @Max(value = 300, message = "考试时长最多 300 分钟")
            Integer durationMinutes,
            List<Long> questionIds,
            Integer totalScore,
            String status) {
    }

    public record ExamView(long id, String title, String description, int questionCount,
                           int durationMinutes, int totalScore, String status) {
        public static ExamView of(Exam exam) {
            return new ExamView(exam.getId(),
                    exam.getTitle(),
                    exam.getDescription(),
                    exam.getQuestionCount() == null ? 0 : exam.getQuestionCount(),
                    exam.getDurationMinutes() == null ? 0 : exam.getDurationMinutes(),
                    exam.getTotalScore() == null ? 0 : exam.getTotalScore(),
                    exam.getStatus());
        }
    }

    public record OptionView(String key, String content) {
    }

    /** 学生视角题目：不含 correctOption / analysis（防止前端直接看到答案）。 */
    public record QuestionView(long id, String type, String stem, String knowledgePoint,
                               String difficulty, int score, List<OptionView> options) {
    }

    public record ExamDetailResponse(ExamView exam, List<QuestionView> questions) {
    }

    public record StartExamResponse(long recordId, long examId, String title, String status,
                                    int durationMinutes, LocalDateTime startedAt, LocalDateTime deadlineAt,
                                    int questionCount, int totalScore, List<QuestionView> questions,
                                    boolean resumed) {
    }

    public record AnswerItem(@NotNull(message = "questionId 不能为空") Long questionId, String answer) {
    }

    public record SubmitRequest(String requestId, @NotNull(message = "answers 不能为空") List<AnswerItem> answers) {
    }

    public record SubmitResponse(long recordId, long examId, String status, int score, int totalScore,
                                 int correctCount, int questionCount, LocalDateTime submittedAt,
                                 boolean idempotent, boolean late) {
    }

    public record ResultItem(long questionId, String type, String stem, List<OptionView> options,
                             String correctOption, String answerValue, boolean correct,
                             int score, int maxScore, String analysis) {
    }

    public record ResultResponse(long recordId, long examId, String title, String status,
                                 int score, int totalScore, int correctCount, int questionCount,
                                 LocalDateTime startedAt, LocalDateTime submittedAt,
                                 List<ResultItem> items) {
    }
}
