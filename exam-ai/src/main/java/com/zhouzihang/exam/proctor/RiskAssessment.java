package com.zhouzihang.exam.proctor;

import java.util.List;

/**
 * 一次风险评估的结果。
 *
 * @param score               0~100 风险分（含滑窗持续异常加成，封顶 100）
 * @param level               最终对外等级 NONE/L1/L2/L3
 * @param rawLevel            按分数直接映射的等级（未做 L3 连续帧确认）
 * @param alertRequired       本次是否应该真正发告警（已过去抖）
 * @param reason              可读的原因串（落到告警表 + WebSocket 推送）
 * @param factors             命中的因子明细（面试时可逐条解释）
 * @param consecutiveCritical 当前连续高危帧计数（L3 升级依据）
 */
public record RiskAssessment(
        int score,
        String level,
        String rawLevel,
        boolean alertRequired,
        String reason,
        List<String> factors,
        int consecutiveCritical) {

    public boolean isAlert() {
        return alertRequired;
    }
}
