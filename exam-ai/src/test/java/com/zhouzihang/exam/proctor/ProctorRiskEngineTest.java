package com.zhouzihang.exam.proctor;

import com.zhouzihang.exam.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 防作弊风险引擎单元测试（纯单元，不启动 Spring）。
 *
 * <p>覆盖点：单帧打分、分级阈值、同级别去抖、升级放行、L3 连续帧确认、
 * 滑窗状态隔离（不同考生互不影响）。这些是面试里"怎么控误报"的证据。</p>
 */
class ProctorRiskEngineTest {

    private AppProperties props;
    private ProctorRiskEngine engine;

    @BeforeEach
    void setUp() {
        props = new AppProperties(); // 使用 application.yml 中的默认阈值：40/65/85、去抖 3s、窗口 5s、L3 连续 3 帧
        engine = new ProctorRiskEngine(props);
    }

    private ProctorSignal frame(long recordId, boolean face, int faceCount,
                                double yaw, double pitch, double roll,
                                boolean gazeOff, double ear, long ts) {
        return new ProctorSignal(recordId, "s-" + recordId, face, faceCount, yaw, pitch, roll, gazeOff, ear, ts);
    }

    @Test
    @DisplayName("正常答题帧：风险分 0，不产生告警")
    void normalFrame() {
        RiskAssessment r = engine.evaluate(frame(1L, true, 1, 3, 2, 1, false, 0.28, 1000));
        assertEquals(0, r.score());
        assertEquals("NONE", r.level());
        assertFalse(r.alertRequired());
    }

    @Test
    @DisplayName("大幅偏转+视线离开+闭眼：单帧即达 L2（80 分），并触发告警")
    void strongFrameTriggersL2() {
        RiskAssessment r = engine.evaluate(frame(9L, true, 1, 46, 35, 30, true, 0.14, 1000));
        assertTrue(r.score() >= 65, "分数应达到 L2 阈值，实际=" + r.score());
        assertEquals("L2", r.level());
        assertTrue(r.alertRequired());
        assertTrue(r.reason().contains("视线离开屏幕"), "原因里应包含关键因子：" + r.reason());
    }

    @Test
    @DisplayName("同级别去抖：去抖窗口内第二次同级别告警被抑制")
    void dedupSuppressesSameLevel() {
        RiskAssessment first = engine.evaluate(frame(2L, true, 1, 46, 35, 30, true, 0.14, 1000));
        assertTrue(first.alertRequired());

        // 1.5s 后再次同级别：窗口 3s 内 → 抑制
        RiskAssessment second = engine.evaluate(frame(2L, true, 1, 46, 35, 30, true, 0.14, 2500));
        assertFalse(second.alertRequired(), "去抖窗口内同级别应被抑制");
        assertTrue(second.reason().contains("去抖") || second.reason().contains("抑制"));
    }

    @Test
    @DisplayName("L3 连续帧确认：前两帧只报 L2，连续第 3 帧才升级 L3")
    void l3RequiresConsecutiveFrames() {
        // 人脸丢失 + 大幅偏转 + 视线离开 + 闭眼 → 单帧原始分 >= 85
        long ts = 1000;
        RiskAssessment a = engine.evaluate(frame(3L, false, 0, 46, 35, 30, true, 0.14, ts));
        RiskAssessment b = engine.evaluate(frame(3L, false, 0, 46, 35, 30, true, 0.14, ts + 1000));
        RiskAssessment c = engine.evaluate(frame(3L, false, 0, 46, 35, 30, true, 0.14, ts + 2000));

        assertEquals("L3", a.rawLevel(), "单帧原始等级应为 L3（分数足够）");
        assertEquals("L2", a.level(), "第 1 帧未达连续帧数，降级为 L2");
        assertEquals("L2", b.level(), "第 2 帧仍未达连续帧数");
        assertEquals("L3", c.level(), "连续第 3 帧才真正升级 L3");
        assertTrue(c.alertRequired(), "L3 升级应放行（不受同级别去抖影响）");
        assertEquals(3, c.consecutiveCritical());
    }

    @Test
    @DisplayName("多人脸只记 25 分，不到 L1 阈值不告警")
    void multiFaceBelowThreshold() {
        RiskAssessment r = engine.evaluate(frame(4L, true, 2, 0, 0, 0, false, 0.3, 1000));
        assertEquals(25, r.score());
        assertEquals("NONE", r.level());
        assertFalse(r.alertRequired());
    }

    @Test
    @DisplayName("不同考生状态隔离：A 的去抖不影响 B 告警")
    void stateIsolationBetweenRecords() {
        RiskAssessment a = engine.evaluate(frame(5L, true, 1, 46, 35, 30, true, 0.14, 1000));
        RiskAssessment b = engine.evaluate(frame(6L, true, 1, 46, 35, 30, true, 0.14, 1100));
        assertTrue(a.alertRequired());
        assertTrue(b.alertRequired(), "另一名考生的首帧告警不应被 A 的去抖抑制");
        assertEquals(2, engine.activeSessions());
    }

    @Test
    @DisplayName("交卷后清理状态：reset 后同一考生重新计数")
    void resetClearsState() {
        engine.evaluate(frame(7L, true, 1, 46, 35, 30, true, 0.14, 1000));
        engine.reset(7L);
        assertEquals(0, engine.activeSessions());

        RiskAssessment again = engine.evaluate(frame(7L, true, 1, 46, 35, 30, true, 0.14, 1200));
        assertTrue(again.alertRequired(), "reset 后应视为新会话，告警放行");
    }
}
