package com.zhouzihang.exam.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 答案比对规则测试（交卷判分的基础正确性）。
 *
 * <p>覆盖：大小写、分隔符、多选乱序、判断题 T/F 与中文"正确/错误"的兼容。</p>
 */
class ExamAnswerMatchTest {

    @Test
    @DisplayName("单选题：忽略大小写与空白")
    void singleChoice() {
        assertTrue(ExamService.answersMatch(" a ", "A"));
        assertFalse(ExamService.answersMatch("B", "A"));
    }

    @Test
    @DisplayName("多选题：顺序无关、分隔符无关（CA == A,C == AC）")
    void multipleChoiceOrderInsensitive() {
        assertTrue(ExamService.answersMatch("CA", "AC"));
        assertTrue(ExamService.answersMatch("A,C", "AC"));
        assertTrue(ExamService.answersMatch("a、c;", "AC"));
        assertFalse(ExamService.answersMatch("AB", "AC"));
    }

    @Test
    @DisplayName("判断题：TRUE/正确/√ 归一化为 T，FALSE/错误/× 归一化为 F")
    void judgeNormalization() {
        assertTrue(ExamService.answersMatch("TRUE", "T"));
        assertTrue(ExamService.answersMatch("正确", "T"));
        assertTrue(ExamService.answersMatch("√", "T"));
        assertTrue(ExamService.answersMatch("false", "F"));
        assertTrue(ExamService.answersMatch("错误", "F"));
        assertFalse(ExamService.answersMatch("T", "F"));
    }

    @Test
    @DisplayName("空答案与 null 不等价于任何正确选项")
    void nullAndEmpty() {
        assertFalse(ExamService.answersMatch(null, "A"));
        assertFalse(ExamService.answersMatch("A", null));
        assertFalse(ExamService.answersMatch("", "A"));
    }
}
