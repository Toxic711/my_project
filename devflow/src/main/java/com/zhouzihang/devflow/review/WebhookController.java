package com.zhouzihang.devflow.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhouzihang.devflow.common.ApiError;
import com.zhouzihang.devflow.common.ApiException;
import com.zhouzihang.devflow.common.ErrorCodes;
import com.zhouzihang.devflow.config.GitHubProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * GitHub Webhook 入口（签名校验 + 幂等 + 事件过滤）。
 *
 * <p>面试讲解点（一条请求要被"审"几遍）：</p>
 * <ol>
 *   <li><b>原始体大小</b>：超过 {@code app.github.max-payload-bytes} 直接 413，
 *       避免签名计算/JSON 解析前就吃满内存（先验大小再验签名，成本低者先做）；</li>
 *   <li><b>签名</b>：{@code X-Hub-Signature-256} 用 HMAC-SHA256 校验原始 body，
 *       失败 401 且不解析 body（解析未验证的 JSON 是攻击面）；</li>
 *   <li><b>幂等</b>：{@code X-GitHub-Delivery} 作为主键先 INSERT，冲突即重复投递，
 *       直接返回既有结果（GitHub 会重投，这是必答题）；</li>
 *   <li><b>事件过滤</b>：只处理 {@code pull_request} 且 action 在白名单内，
 *       其余返回 200 ignored（返回 4xx 会让 GitHub 反复重投）。</li>
 * </ol>
 *
 * <p>已知限制：离线环境无法调用 GitHub API 拉取 PR 的真实 diff。本实现从
 * payload 的 {@code pull_request.diff}（演示扩展字段）取 diff，缺失时回退内置示例，
 * 并在响应里用 {@code diffSource} 明确标注——<b>不假装审查了真实 PR</b>。
 * 生产应改为 GitHub App token + {@code Accept: application/vnd.github.v3.diff}。</p>
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookSignatureVerifier verifier;
    private final WebhookDeliveryMapper deliveryMapper;
    private final ReviewService reviewService;
    private final GitHubProperties props;
    private final ObjectMapper mapper;
    private final Clock clock;

    public WebhookController(WebhookSignatureVerifier verifier, WebhookDeliveryMapper deliveryMapper,
                             ReviewService reviewService, GitHubProperties props,
                             ObjectMapper mapper, Clock clock) {
        this.verifier = verifier;
        this.deliveryMapper = deliveryMapper;
        this.reviewService = reviewService;
        this.props = props;
        this.mapper = mapper;
        this.clock = clock;
    }

    @PostMapping("/github")
    public ResponseEntity<?> github(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestBody(required = false) String rawBody,
            HttpServletRequest request) {

        String body = rawBody == null ? "" : rawBody;
        if (body.length() > props.getMaxPayloadBytes()) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCodes.VALIDATION_ERROR,
                    "payload 超过上限 " + props.getMaxPayloadBytes() + " 字节", request);
        }
        if (!verifier.verify(body, signature)) {
            log.warn("webhook 签名校验失败 delivery={} event={} sig={}", deliveryId, event, signature);
            return error(HttpStatus.UNAUTHORIZED, ErrorCodes.WEBHOOK_SIGNATURE_INVALID,
                    "X-Hub-Signature-256 缺失或与 body 的 HMAC-SHA256 不匹配", request);
        }
        if (deliveryId == null || deliveryId.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR,
                    "缺少 X-GitHub-Delivery 头（幂等键），无法安全处理", request);
        }

        // 幂等：主键插入即抢锁；冲突说明重复投递
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setDeliveryId(deliveryId);
        delivery.setEventType(event);
        delivery.setStatus("PROCESSING");
        delivery.setReceivedAt(LocalDateTime.now(clock));
        try {
            deliveryMapper.insert(delivery);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // DuplicateKeyException 是 DataIntegrityViolationException 的子类，这里统一按"重复投递"处理
            WebhookDelivery existing = deliveryMapper.selectById(deliveryId);
            Map<String, Object> dup = new LinkedHashMap<>();
            dup.put("status", "duplicate");
            dup.put("deliveryId", deliveryId);
            dup.put("runId", existing == null ? null : existing.getRunId());
            dup.put("message", "该 delivery 已处理过（按 X-GitHub-Delivery 幂等去重）");
            log.info("webhook 重复投递 delivery={} 原状态={}", deliveryId, existing == null ? "?" : existing.getStatus());
            return ResponseEntity.ok(dup);
        }

        if (!"pull_request".equalsIgnoreCase(event)) {
            updateStatus(deliveryId, "IGNORED", null, event, null);
            Map<String, Object> ignored = new LinkedHashMap<>();
            ignored.put("status", "ignored");
            ignored.put("event", event);
            ignored.put("message", "仅处理 pull_request 事件");
            return ResponseEntity.ok(ignored);
        }

        JsonNode payload;
        try {
            payload = mapper.readTree(body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            updateStatus(deliveryId, "FAILED", null, event, null);
            return error(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR,
                    "签名通过但 payload 不是合法 JSON: " + e.getMessage(), request);
        }
        String action = payload.path("action").asText("");
        boolean allowedAction = Arrays.stream(props.getReviewActions())
                .anyMatch(a -> a.equalsIgnoreCase(action));
        if (!allowedAction) {
            updateStatus(deliveryId, "IGNORED", null, event, action);
            Map<String, Object> ignored = new LinkedHashMap<>();
            ignored.put("status", "ignored");
            ignored.put("action", action);
            ignored.put("message", "action 不在审查白名单内: " + Arrays.toString(props.getReviewActions()));
            return ResponseEntity.ok(ignored);
        }

        String repo = payload.path("repository").path("full_name").asText(null);
        Integer prNumber = payload.path("pull_request").path("number").isMissingNode()
                ? null : payload.path("pull_request").path("number").asInt();
        String diffFromPayload = payload.path("pull_request").path("diff").asText("");
        String diffSource = "payload.pull_request.diff";
        String diff = diffFromPayload;
        if (diff.isBlank()) {
            // 离线环境无法访问 GitHub API 拉 diff，用内置示例兜底并如实标注
            diff = SampleDiff.TEXT;
            diffSource = "builtin-sample (GitHub API 不可达/未提供 diff)";
        }
        try {
            ReviewService.ReviewResult result = reviewService.review(diff, "webhook", repo, prNumber, deliveryId);
            updateStatus(deliveryId, "PROCESSED", result.runId(), event, action);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("status", "accepted");
            ok.put("deliveryId", deliveryId);
            ok.put("runId", result.runId());
            ok.put("action", action);
            ok.put("repo", repo);
            ok.put("prNumber", prNumber);
            ok.put("diffSource", diffSource);
            ok.put("findings", result.totalFindings());
            ok.put("high", result.high());
            ok.put("medium", result.medium());
            ok.put("low", result.low());
            ok.put("reviewStatus", result.status());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(ok);
        } catch (RuntimeException e) {
            updateStatus(deliveryId, "FAILED", null, event, action);
            log.error("webhook 审查失败 delivery={} err={}", deliveryId, e.getMessage(), e);
            if (e instanceof ApiException api) {
                throw api;
            }
            throw new ApiException(ErrorCodes.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR,
                    "webhook 审查失败: " + e.getMessage(), e);
        }
    }

    private void updateStatus(String deliveryId, String status, Long runId, String event, String action) {
        WebhookDelivery d = deliveryMapper.selectById(deliveryId);
        if (d == null) {
            return;
        }
        d.setStatus(status);
        d.setRunId(runId);
        if (event != null) {
            d.setEventType(event);
        }
        d.setAction(action);
        deliveryMapper.updateById(d);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message,
                                           HttpServletRequest request) {
        // 与 GlobalExceptionHandler 保持同一错误体结构，客户端只需一套解析
        return ResponseEntity.status(status)
                .body(new ApiError(code, message, request.getRequestURI(),
                        UUID.randomUUID().toString().substring(0, 8)));
    }
}
