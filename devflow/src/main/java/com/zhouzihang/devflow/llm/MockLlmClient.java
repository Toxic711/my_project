package com.zhouzihang.devflow.llm;

import com.zhouzihang.devflow.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 离线确定性 Mock LLM。
 *
 * <p>面试讲解点（重点）：Mock 不是"随便返回字符串"，而是<b>可被测试断言的确定性实现</b>。
 * 它按 {@code LlmRequest.feature} 分三条确定性路径：</p>
 * <ul>
 *   <li>{@code sql-generate}：按问题关键词产出 SELECT；问句包含删除/修改类意图时
 *       <b>故意产出违规 SQL</b>，用来演示"护栏真的拦得住 LLM"（这是面试里最能证明
 *       你不是把 LLM 输出直接丢数据库的证据）。</li>
 *   <li>{@code code-review}：直接解析 prompt 里嵌入的 unified diff，对危险模式
 *       （硬编码密钥、字符串拼接 SQL、吞异常、调试输出、TODO）产出结构化 findings，
 *       行号来自 diff 的新文件行号，因此 finding 的 file/line 是真实可定位的。</li>
 *   <li>其余：生成引用用户问题的多句回答，保证 SSE 至少能推出多帧。</li>
 * </ul>
 *
 * <p>为什么 Mock 要读 diff？因为演示/测试需要一个"有真实行号"的结构化输出；
 * 真 provider 用自然语言理解 diff，Mock 用规则模拟同一接口——对外契约一致。</p>
 */
@Component
@Order(100)
public class MockLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(MockLlmClient.class);

    /** diff 中新增行的行号跟踪：@@ -a,b +c,d @@ 里的 c。 */
    private static final Pattern HUNK = Pattern.compile("^@@\\s+-\\d+(?:,\\d+)?\\s+\\+(\\d+)(?:,\\d+)?\\s+@@");

    private final LlmProperties props;

    public MockLlmClient(LlmProperties props) {
        this.props = props;
    }

    @Override
    public String providerName() {
        return "mock";
    }

    @Override
    public String model() {
        return "mock-deterministic";
    }

    @Override
    public boolean available() {
        // Mock 永远可用：它是降级链的终点，保证 demo 零外部依赖
        return true;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        long start = System.currentTimeMillis();
        String content = generate(request);
        LlmResponse resp = buildResponse(request, content, System.currentTimeMillis() - start);
        log.debug("mock chat feature={} chars={} tokens≈{}", request.getFeature(), content.length(), resp.getTotalTokens());
        return resp;
    }

    @Override
    public void stream(LlmRequest request, LlmStreamListener listener) {
        long start = System.currentTimeMillis();
        String content = generate(request);
        listener.onStart(request.getRequestId(), providerName(), model());
        StringBuilder acc = new StringBuilder();
        try {
            for (String chunk : splitForStream(content)) {
                acc.append(chunk);
                listener.onToken(chunk);
                long delay = props.getMockStreamDelayMs();
                if (delay > 0) {
                    Thread.sleep(delay);
                }
            }
            LlmResponse resp = buildResponse(request, acc.toString(), System.currentTimeMillis() - start);
            listener.onComplete(resp);
        } catch (InterruptedException e) {
            // 客户端断开时上层会中断线程，这里必须恢复中断标记并退出，不能吞掉
            Thread.currentThread().interrupt();
            listener.onError(e);
        } catch (RuntimeException e) {
            listener.onError(e);
        }
    }

    private LlmResponse buildResponse(LlmRequest request, String content, long latency) {
        int promptTokens = TokenEstimator.estimate(joinPrompt(request));
        int completionTokens = TokenEstimator.estimate(content);
        return LlmResponse.builder()
                .requestId(request.getRequestId())
                .provider(providerName())
                .model(request.getModel() == null ? model() : request.getModel())
                .content(content)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(promptTokens + completionTokens)
                .finishReason("stop")
                .latencyMs(latency)
                .degraded(false)
                .build();
    }

    private String joinPrompt(LlmRequest request) {
        StringBuilder sb = new StringBuilder();
        if (request.getSystemPrompt() != null) {
            sb.append(request.getSystemPrompt());
        }
        for (LlmMessage m : request.getMessages()) {
            sb.append('\n').append(m.content());
        }
        return sb.toString();
    }

    /** 按"接近单词边界"切块，模拟真实 token 流；保证 >= 3 个分片以便 SSE 演示。 */
    private List<String> splitForStream(String content) {
        List<String> chunks = new ArrayList<>();
        int size = 8;
        for (int i = 0; i < content.length(); i += size) {
            chunks.add(content.substring(i, Math.min(content.length(), i + size)));
        }
        if (chunks.isEmpty()) {
            chunks.add("");
        }
        return chunks;
    }

    // ------------------------------------------------------------------
    // 确定性内容生成
    // ------------------------------------------------------------------

    private String generate(LlmRequest request) {
        String feature = request.getFeature() == null ? "chat" : request.getFeature();
        return switch (feature) {
            case "sql-generate" -> mockSql(request);
            case "code-review" -> mockReview(request);
            default -> mockChat(request);
        };
    }

    private String mockChat(LlmRequest request) {
        String q = str(request.getMetadata().get("mock.question"));
        if (q.isBlank()) {
            q = request.lastUserContent();
        }
        if (q.length() > 120) {
            q = q.substring(0, 120) + "...";
        }
        String model = request.getModel() == null ? props.getDefaultModel() : request.getModel();
        return "关于「" + q + "」，DevFlow 的回答如下。\n"
                + "第一，这个问题的核心是把 LLM 调用收敛到一个可替换的端口（LlmClient），"
                + "业务只依赖抽象，不依赖某一家厂商的 SDK。\n"
                + "第二，真实 provider 不可用时走降级链，最终回落到 Mock，保证离线与演示环境可用。\n"
                + "第三，每次调用都会记录 requestId、模型、prompt/completion token 与估算成本，"
                + "便于做预算与用量分析。\n"
                + "（当前响应由离线 Mock 生成，模型标记为 " + model + "，非真实模型输出。）";
    }

    /**
     * 生成 SQL。问句含"删除/修改/清空"等破坏性意图时故意输出违规 SQL，
     * 用于证明 SqlGuard 能拦截 LLM 输出。
     */
    private String mockSql(LlmRequest request) {
        String q = str(request.getMetadata().get("mock.question"));
        if (q.isBlank()) {
            q = request.lastUserContent();
        }
        String lower = q.toLowerCase(Locale.ROOT);
        if (lower.contains("delete") || lower.contains("drop") || lower.contains("update")
                || q.contains("删除") || q.contains("清空") || q.contains("修改") || q.contains("移除")) {
            // 故意越权：正常情况下护栏必须把它拦下来
            return "```sql\nDELETE FROM users WHERE id > 0\n```";
        }
        String table = "users";
        if (lower.contains("order") || q.contains("订单") || q.contains("下单")) {
            table = "orders";
        } else if (lower.contains("product") || q.contains("商品") || q.contains("产品")) {
            table = "products";
        }
        String sql;
        if (lower.contains("count") || q.contains("统计") || q.contains("多少") || q.contains("总数")) {
            sql = "SELECT COUNT(*) AS total FROM " + table;
        } else if (lower.contains("sum") || q.contains("金额") || q.contains("总额")) {
            sql = "SELECT id, user_id, amount FROM orders ORDER BY id DESC LIMIT 50";
        } else {
            sql = "SELECT * FROM " + table + " ORDER BY id DESC LIMIT 50";
        }
        return "```sql\n" + sql + "\n```";
    }

    /**
     * 解析 prompt 里嵌入的 unified diff，对新增行做规则化"审查"，输出结构化 findings JSON。
     * 行号取 diff 的新文件行号，所以前端可以直接跳到真实行。
     */
    private String mockReview(LlmRequest request) {
        List<String> lines = List.of(request.lastUserContent().split("\n", -1));
        List<Finding> findings = new ArrayList<>();
        String file = "unknown";
        int newLineNo = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (String line : lines) {
            Matcher hunk = HUNK.matcher(line);
            if (hunk.find()) {
                newLineNo = Integer.parseInt(hunk.group(1));
                continue;
            }
            if (line.startsWith("+++ ")) {
                file = line.substring(4).trim();
                if (file.startsWith("b/")) {
                    file = file.substring(2);
                }
                continue;
            }
            if (line.startsWith("--- ") || line.startsWith("diff --git")) {
                continue;
            }
            if (line.startsWith("+")) {
                String added = line.substring(1);
                String key = file + ":" + newLineNo;
                if (seen.add(key)) {
                    findings.addAll(analyzeAddedLine(file, newLineNo, added));
                }
                newLineNo++;
            } else if (line.startsWith(" ")) {
                newLineNo++;
            }
            // "-" 行不递增新行号
        }
        // 最多 8 条，避免 mock 输出过长
        if (findings.size() > 8) {
            findings = new ArrayList<>(findings.subList(0, 8));
        }
        StringBuilder sb = new StringBuilder("```json\n[\n");
        for (int i = 0; i < findings.size(); i++) {
            Finding f = findings.get(i);
            sb.append("  {\"file\":\"").append(escapeJson(f.file)).append("\",")
                    .append("\"line\":").append(f.line).append(',')
                    .append("\"severity\":\"").append(f.severity).append("\",")
                    .append("\"message\":\"").append(escapeJson(f.message)).append("\",")
                    .append("\"suggestion\":\"").append(escapeJson(f.suggestion)).append("\"}");
            if (i < findings.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append("]\n```");
        return sb.toString();
    }

    private List<Finding> analyzeAddedLine(String file, int lineNo, String code) {
        List<Finding> out = new ArrayList<>();
        String lower = code.toLowerCase(Locale.ROOT);
        boolean looksLikeAssignment = lower.contains("=") || lower.contains(":");
        boolean looksLikeSecret = lower.contains("password") || lower.contains("passwd")
                || lower.contains("secret") || lower.contains("apikey") || lower.contains("api_key")
                || lower.contains("access_key") || lower.contains("private_key")
                || lower.contains("sk-live-") || lower.contains("sk-test-");
        if (looksLikeSecret && looksLikeAssignment) {
            out.add(new Finding(file, lineNo, "HIGH", "疑似硬编码口令或密钥，会随代码库泄露",
                    "改为通过环境变量 / 密钥管理服务注入，并立即轮换该凭据"));
        }
        if ((lower.contains("select ") || lower.contains("insert ") || lower.contains("delete "))
                && (lower.contains("+ ") || lower.contains("+\"") || lower.contains("\" +"))) {
            out.add(new Finding(file, lineNo, "HIGH", "SQL 由字符串拼接构造，存在注入风险",
                    "改用 PreparedStatement 占位符 / MyBatis #{} 参数绑定"));
        }
        if (lower.contains("delete from")) {
            out.add(new Finding(file, lineNo, "HIGH", "代码中存在 DELETE FROM 语句，需确认权限与条件校验",
                    "改为软删除并校验当前用户是否具备删除权限"));
        }
        if (lower.contains("catch (exception") || lower.contains("catch(exception")) {
            out.add(new Finding(file, lineNo, "MEDIUM", "捕获宽泛异常，容易吞掉真实故障",
                    "捕获具体异常类型；至少记录 context 与 cause 后再降级"));
        }
        if (lower.contains("system.out.print") || lower.contains("printstacktrace")) {
            out.add(new Finding(file, lineNo, "LOW", "生产代码残留调试输出",
                    "改用 SLF4J 日志并对敏感字段脱敏"));
        }
        if (lower.contains("todo") || lower.contains("fixme")) {
            out.add(new Finding(file, lineNo, "LOW", "遗留 TODO/FIXME",
                    "补充 issue 编号或在本迭代内收敛"));
        }
        if (lower.contains("thread.sleep")) {
            out.add(new Finding(file, lineNo, "MEDIUM", "在请求链路中阻塞睡眠，影响吞吐",
                    "改用调度器 / 异步等待，避免占用业务线程"));
        }
        return out;
    }

    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private record Finding(String file, int line, String severity, String message, String suggestion) {
    }
}
