package com.zhouzihang.devflow.llm;

/**
 * token 估算器。
 *
 * <p>面试讲解点：真实 token 数只有 provider 知道；估算器的作用是
 * "没有 usage 返回时（Mock / 流式被截断 / 降级）仍能给出可比较的成本口径"。
 * 这里用业界经验值：英文约 4 字符/token，中文约 1.5 字符/token，
 * 按非 ASCII 字符占比加权。误差 ±30% 是可接受范围，因为成本统计用于
 * 趋势与预算告警，不用于结算。</p>
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int ascii = 0;
        int nonAscii = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 128) {
                ascii++;
            } else {
                nonAscii++;
            }
        }
        // 英文 4 字符 ≈ 1 token；CJK 约 1.5 字符 ≈ 1 token
        double tokens = ascii / 4.0 + nonAscii / 1.5;
        return Math.max(1, (int) Math.ceil(tokens));
    }
}
