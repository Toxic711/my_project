package com.zhouzihang.devflow.prompt;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zhouzihang.devflow.common.ApiException;
import com.zhouzihang.devflow.common.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Prompt 模板服务：新建 / 版本化更新 / 渲染，带 Caffeine 缓存。
 *
 * <p>面试讲解点（版本化 + 缓存一致性）：</p>
 * <ul>
 *   <li><b>写时新增版本而不是 UPDATE 原行</b>：UPDATE 会丢掉历史，且并发下
 *       "读-改-写"会丢更新；插入新版本行让 DB 的唯一键 {@code (code, version)}
 *       承担并发冲突的裁决（冲突→409，客户端重试）。</li>
 *   <li><b>缓存失效用"写后主动 invalidate"</b>，而不是依赖 TTL 过期：
 *       用户改完 prompt 立刻对话，必须立刻生效；TTL 只作为兜底（30 分钟）。</li>
 *   <li><b>缓存 key 是 code</b>（指向最新版本），不是 id——因为读路径永远要
 *       "最新版本"，按 id 缓存会让"改了不生效"成为必然。</li>
 * </ul>
 */
@Service
public class PromptService {

    private static final Logger log = LoggerFactory.getLogger(PromptService.class);
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{2,64}$");

    private final PromptTemplateMapper mapper;
    private final Clock clock;
    private final Cache<String, PromptTemplate> latestCache;

    public PromptService(PromptTemplateMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
        this.latestCache = Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(Duration.ofMinutes(30))
                .build();
    }

    /** 新建模板 → version=1。code 已存在则 409（引导调用方用 PUT 建新版本）。 */
    @Transactional
    public PromptTemplate create(String code, String name, String content) {
        validateCode(code);
        if (content == null || content.isBlank()) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "模板内容不能为空");
        }
        PromptTemplate exists = mapper.selectLatest(code);
        if (exists != null) {
            throw ApiException.conflict("prompt code 已存在: " + code + "（当前最新版本 v"
                    + exists.getVersion() + "），请使用 PUT /api/prompts/" + exists.getId() + " 生成新版本");
        }
        PromptTemplate t = newPrompt(code, name, content, 1);
        try {
            mapper.insert(t);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("并发创建同一 prompt code 冲突，请重试: " + code);
        }
        latestCache.invalidate(code);
        log.info("prompt 创建 code={} version=1 id={}", code, t.getId());
        return t;
    }

    /** 基于 id 生成新版本：version = maxVersion + 1，content 为空时继承上一版。 */
    @Transactional
    public PromptTemplate createVersion(Long id, String name, String content) {
        PromptTemplate base = mapper.selectById(id);
        if (base == null) {
            throw ApiException.notFound("prompt 不存在: id=" + id);
        }
        String newContent = (content == null || content.isBlank()) ? base.getContent() : content;
        String newName = (name == null || name.isBlank()) ? base.getName() : name;
        int nextVersion = mapper.maxVersion(base.getCode()) + 1;
        PromptTemplate t = newPrompt(base.getCode(), newName, newContent, nextVersion);
        try {
            mapper.insert(t);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("版本号并发冲突（code=" + base.getCode() + ", version="
                    + nextVersion + "），请重试");
        }
        latestCache.invalidate(base.getCode());
        log.info("prompt 新版本 code={} version={} id={}", base.getCode(), nextVersion, t.getId());
        return t;
    }

    /** 最新启用版本（带缓存）。 */
    public PromptTemplate latest(String code) {
        PromptTemplate cached = latestCache.getIfPresent(code);
        if (cached != null) {
            return cached;
        }
        PromptTemplate t = mapper.selectLatest(code);
        if (t == null) {
            throw ApiException.notFound("prompt 不存在: code=" + code);
        }
        latestCache.put(code, t);
        return t;
    }

    /** 最新版本可选（不存在返回 null，不抛异常）。 */
    public PromptTemplate latestOrNull(String code) {
        try {
            return latest(code);
        } catch (ApiException e) {
            return null;
        }
    }

    /** 列表：每个 code 只返回最新版本。 */
    public List<PromptTemplate> listLatest() {
        QueryWrapper<PromptTemplate> qw = new QueryWrapper<>();
        qw.orderByDesc("code").orderByDesc("version");
        List<PromptTemplate> all = mapper.selectList(qw);
        Map<String, PromptTemplate> latestByCode = new LinkedHashMap<>();
        for (PromptTemplate t : all) {
            latestByCode.putIfAbsent(t.getCode(), t);
        }
        return new ArrayList<>(latestByCode.values());
    }

    /** 某 code 的全部版本（新→旧）。 */
    public List<PromptTemplate> versions(String code) {
        List<PromptTemplate> list = mapper.selectVersions(code);
        if (list.isEmpty()) {
            throw ApiException.notFound("prompt 不存在: code=" + code);
        }
        return list;
    }

    public PromptTemplate getById(Long id) {
        PromptTemplate t = mapper.selectById(id);
        if (t == null) {
            throw ApiException.notFound("prompt 不存在: id=" + id);
        }
        return t;
    }

    /** 渲染最新版本。 */
    public RenderedPrompt render(String code, Map<String, String> variables) {
        PromptTemplate t = latest(code);
        String text = PromptRenderer.render(t.getContent(), variables);
        return new RenderedPrompt(code, t.getVersion(), text, List.copyOf(PromptRenderer.extractVariables(t.getContent())));
    }

    /** 启动灌数据用：不存在才创建，幂等。 */
    @Transactional
    public PromptTemplate ensure(String code, String name, String content) {
        PromptTemplate exists = mapper.selectLatest(code);
        if (exists != null) {
            return exists;
        }
        return create(code, name, content);
    }

    public void invalidateCache(String code) {
        latestCache.invalidate(code);
    }

    private PromptTemplate newPrompt(String code, String name, String content, int version) {
        PromptTemplate t = new PromptTemplate();
        t.setCode(code);
        t.setName((name == null || name.isBlank()) ? code : name);
        t.setContent(content);
        t.setVariables(PromptRenderer.joinVariables(content));
        t.setVersion(version);
        t.setEnabled(1);
        t.setDeleted(0);
        LocalDateTime now = LocalDateTime.now(clock);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return t;
    }

    private void validateCode(String code) {
        if (code == null || !CODE_PATTERN.matcher(code).matches()) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR,
                    "code 必须匹配 " + CODE_PATTERN.pattern() + "，实际=" + code);
        }
    }

    /** 渲染结果。 */
    public record RenderedPrompt(String code, int version, String text, List<String> variables) {
    }
}
