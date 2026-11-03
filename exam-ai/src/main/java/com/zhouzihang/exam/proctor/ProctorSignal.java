package com.zhouzihang.exam.proctor;

/**
 * 归一化后的视觉特征输入（与 HTTP DTO 解耦，方便纯单元测试）。
 *
 * <p>字段含义来自 MediaPipe Face Mesh / Pose 的前端计算结果：
 * yaw/pitch/roll 为头部三轴欧拉角（度），ear 为眼睛纵横比（眨眼/疲劳指标），
 * faceCount 为画面中检出的人脸数，gazeOffScreen 为前端基于虹膜/瞳孔位置
 * 判断的“视线是否离开屏幕”。</p>
 */
public record ProctorSignal(
        long examRecordId,
        String sessionId,
        boolean faceDetected,
        int faceCount,
        double yaw,
        double pitch,
        double roll,
        boolean gazeOffScreen,
        double ear,
        long timestamp) {
}
