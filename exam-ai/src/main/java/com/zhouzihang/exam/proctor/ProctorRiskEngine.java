package com.zhouzihang.exam.proctor;

import com.zhouzihang.exam.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 面试讲解点（核心）：防作弊风险引擎 —— 滑窗 + 加权风险分 + 去抖 + L3 连续帧确认。
 *
 * <p><b>为什么是规则模型而不是“训练一个检测模型”？</b>
 * 视觉事件是低维、强语义的特征（人脸丢失、头部偏转、视线离开），
 * 规则+权重在离线环境可解释、可调参、可回放；训练模型需要标注数据与推理框架，
 * 且误判后无法向考生/老师解释。工程上先把规则做到低误报，再考虑模型融合。</p>
 *
 * <p><b>风险分怎么来的？</b>
 * 单帧加权求和（各因子权重见 {@link #scoreFrame}），再做两层“时间维度”修正：</p>
 * <ol>
 *   <li><b>滑窗持续异常加成</b>：最近 {@code window-ms} 内异常帧占比 >= 50% 时 +8。
 *       单帧头部偏转可能只是转头看题，持续偏转才是异常；</li>
 *   <li><b>人脸丢失占比加成</b>：窗口内人脸丢失占比 >= 60% 时再 +10，
 *       区分“低头记笔记”和“离开座位/换人”。</li>
 * </ol>
 * 最终封顶 100 分，按阈值映射 L1/L2/L3。</p>
 *
 * <p><b>误报控制（面试必问）—— 这里有三道闸：</b></p>
 * <ol>
 *   <li><b>滑窗平滑</b>：单帧尖峰会被窗口内其他正常帧稀释，不会立刻升级；</li>
 *   <li><b>L3 连续 N 帧确认</b>：rawScore 达到 85 后不立即报 L3，而是降级为 L2
 *       并累计 {@code consecutiveCritical}，连续 {@code level3-consecutive} 帧才真正升级。
 *       这是防“单帧误判”（如突然低头捡笔）的关键；</li>
 *   <li><b>同级别去抖</b>：同一考生在 {@code dedup-window-ms} 内同级别只发一次告警，
 *       避免每秒几十条事件把教师端刷爆。<b>升级（更高级别）必须放行</b>，
 *       否则会漏掉真正的严重事件 —— 这是“宁可多报一次重警，不可漏报”的取舍。</li>
 * </ol>
 *
 * <p><b>状态放在哪？</b> 当前为单实例内存 {@link ConcurrentHashMap}（key=examRecordId）。
 * 多实例部署时需换成 Redis（滑窗用 ZSET/Lua 原子脚本，或 Redisson 的 RMap），
 * 否则负载均衡到不同实例会导致窗口/去抖状态割裂。见 docs/DESIGN.md 已知限制。</p>
 */
@Slf4j
@Component
public class ProctorRiskEngine {

    private final int level1;
    private final int level2;
    private final int level3;
    private final long dedupWindowMs;
    private final long windowMs;
    private final int maxWindowEvents;
    private final int level3Consecutive;
    private final int multiFaceThreshold;

    /** key = examRecordId；同一场考试的多个 WebSocket 会话共享一个滑窗。 */
    private final Map<Long, SessionState> states = new ConcurrentHashMap<>();

    public ProctorRiskEngine(AppProperties properties) {
        AppProperties.Proctor p = properties.getProctor();
        this.level1 = p.getLevel1Risk();
        this.level2 = p.getLevel2Risk();
        this.level3 = p.getLevel3Risk();
        this.dedupWindowMs = p.getDedupWindowMs();
        this.windowMs = p.getWindowMs();
        this.maxWindowEvents = p.getMaxWindowEvents();
        this.level3Consecutive = p.getLevel3Consecutive();
        this.multiFaceThreshold = p.getMultiFaceThreshold();
    }

    /**
     * 评估一帧视觉特征，并更新该考生的滑窗/去抖状态。
     * 方法对同一 examRecordId 加锁串行化，保证“计数-判断-更新”原子性。
     */
    public RiskAssessment evaluate(ProctorSignal signal) {
        long ts = signal.timestamp() > 0 ? signal.timestamp() : System.currentTimeMillis();
        SessionState state = states.computeIfAbsent(signal.examRecordId(), key -> new SessionState());
        synchronized (state) {
            // 客户端时间戳可能乱序（弱网/重试），强制单调不减，避免窗口错乱
            ts = Math.max(ts, state.lastTs);
            state.lastTs = ts;

            long cutoff = ts - windowMs;
            while (!state.window.isEmpty() && state.window.peekFirst().ts() < cutoff) {
                state.window.pollFirst();
            }

            Frame frame = scoreFrame(signal, ts);
            state.window.addLast(frame);
            while (state.window.size() > maxWindowEvents) {
                state.window.pollFirst();
            }

            int score = frame.score();
            List<String> factors = new ArrayList<>(frame.factors());

            // ---- 滑窗修正 1：持续异常占比 ----
            if (state.window.size() >= 3) {
                long abnormal = state.window.stream().filter(f -> f.score() >= level1).count();
                double ratio = abnormal * 1.0 / state.window.size();
                if (ratio >= 0.5) {
                    score += 8;
                    factors.add(String.format("滑窗异常占比 %.0f%%(+8)", ratio * 100));
                }
            }
            // ---- 滑窗修正 2：人脸丢失占比（区分短暂遮挡 vs 长时间离座）----
            if (state.window.size() >= 3) {
                long missing = state.window.stream().filter(Frame::faceMissing).count();
                double missRatio = missing * 1.0 / state.window.size();
                if (missRatio >= 0.6) {
                    score += 10;
                    factors.add(String.format("人脸丢失占比 %.0f%%(+10)", missRatio * 100));
                }
            }
            score = Math.min(100, score);

            String rawLevel = levelOf(score);

            // ---- 闸门 2：L3 连续帧确认 ----
            if ("L3".equals(rawLevel)) {
                state.consecutiveCritical++;
            } else {
                state.consecutiveCritical = 0;
            }
            String level = rawLevel;
            String escalationNote = "";
            if ("L3".equals(rawLevel) && state.consecutiveCritical < level3Consecutive) {
                // 未满足连续帧数：降级为 L2 告警（既不漏，也不误报 L3）
                level = "L2";
                escalationNote = String.format("（L3 待确认 %d/%d）", state.consecutiveCritical, level3Consecutive);
            }

            // ---- 闸门 3：同级别去抖；升级放行 ----
            boolean alertRequired = false;
            String dedupNote = "";
            int rank = rankOf(level);
            if (rank > 0) {
                boolean withinDedup = state.lastAlertAt > 0 && ts - state.lastAlertAt < dedupWindowMs;
                if (withinDedup && rank <= rankOf(state.lastAlertLevel)) {
                    dedupNote = String.format("（去抖窗口内，%s 已告警，抑制重复）", state.lastAlertLevel);
                } else {
                    alertRequired = true;
                    state.lastAlertAt = ts;
                    state.lastAlertLevel = level;
                }
            }

            StringBuilder reason = new StringBuilder(String.join("; ", factors));
            if (!escalationNote.isEmpty()) {
                reason.append(escalationNote);
            }
            if (!dedupNote.isEmpty()) {
                reason.append(dedupNote);
            }
            return new RiskAssessment(score, level, rawLevel, alertRequired, reason.toString(), factors,
                    state.consecutiveCritical);
        }
    }

    /**
     * 单帧加权打分。权重设计原则：
     * “人不在/不是本人”（人脸丢失、多人脸）> “看别处”（视线离开、大幅偏转）> “姿势异常”（倾斜、闭眼）。
     */
    private Frame scoreFrame(ProctorSignal s, long ts) {
        int score = 0;
        List<String> factors = new ArrayList<>();

        if (!s.faceDetected()) {
            score += 30;
            factors.add("人脸丢失(+30)");
        } else if (s.faceCount() >= multiFaceThreshold) {
            score += 25;
            factors.add("多人脸(" + s.faceCount() + ")(+25)");
        }

        double absYaw = Math.abs(s.yaw());
        if (absYaw >= 40) {
            score += 25;
            factors.add(String.format("头部大幅偏转 yaw=%.0f(+25)", s.yaw()));
        } else if (absYaw >= 25) {
            score += 15;
            factors.add(String.format("头部偏转 yaw=%.0f(+15)", s.yaw()));
        } else if (absYaw >= 15) {
            // 轻微偏转只记分不记因子，避免日志噪音
            score += 6;
        }

        double absPitch = Math.abs(s.pitch());
        if (absPitch >= 30) {
            score += 15;
            factors.add(String.format("低头/仰头 pitch=%.0f(+15)", s.pitch()));
        } else if (absPitch >= 20) {
            score += 8;
        }

        if (Math.abs(s.roll()) >= 25) {
            score += 10;
            factors.add(String.format("头部倾斜 roll=%.0f(+10)", s.roll()));
        }

        if (s.gazeOffScreen()) {
            score += 20;
            factors.add("视线离开屏幕(+20)");
        }

        if (s.ear() > 0 && s.ear() < 0.16) {
            score += 10;
            factors.add(String.format("闭眼/疲劳 ear=%.2f(+10)", s.ear()));
        }

        return new Frame(ts, Math.min(100, score), !s.faceDetected(), List.copyOf(factors));
    }

    private String levelOf(int score) {
        if (score >= level3) {
            return "L3";
        }
        if (score >= level2) {
            return "L2";
        }
        if (score >= level1) {
            return "L1";
        }
        return "NONE";
    }

    private int rankOf(String level) {
        if ("L3".equals(level)) {
            return 3;
        }
        if ("L2".equals(level)) {
            return 2;
        }
        if ("L1".equals(level)) {
            return 1;
        }
        return 0;
    }

    /** 交卷/重考时清理会话状态，避免旧窗口影响下一场。 */
    public void reset(long examRecordId) {
        states.remove(examRecordId);
    }

    public int activeSessions() {
        return states.size();
    }

    /** 单会话状态：滑窗 + 去抖 + 连续高危计数。 */
    private static final class SessionState {
        private final ArrayDeque<Frame> window = new ArrayDeque<>();
        private long lastTs = 0L;
        private long lastAlertAt = 0L;
        private String lastAlertLevel = "NONE";
        private int consecutiveCritical = 0;
    }

    /** 窗口内的一帧（只保留打分必需的信息，控制内存）。 */
    private record Frame(long ts, int score, boolean faceMissing, List<String> factors) {
    }
}
