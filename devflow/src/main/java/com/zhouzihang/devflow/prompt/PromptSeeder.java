package com.zhouzihang.devflow.prompt;

import com.zhouzihang.devflow.config.SeedProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 演示用 Prompt 模板灌入（幂等：已存在则跳过）。
 *
 * <p>面试讲解点：把"内置模板"当代码资产管理（版本随仓库发布），
 * 而不是让用户手写 SQL 灌数据。这样 demo / 测试 / 生产可以用同一套
 * 初始模板，且模板文本有 diff、可 review。</p>
 */
@Component
public class PromptSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PromptSeeder.class);

    /** 通用对话模板。 */
    public static final String CHAT_TEMPLATE = """
            你是 DevFlow 的 AI 研发效能助手。
            回答要求：
            1. 先给结论，再给理由；
            2. 涉及工程实践时给出可执行的具体做法，不要泛泛而谈；
            3. 不确定的信息要明确说"不确定"，禁止编造。

            用户问题：
            ${question}
            """;

    /** 自然语言 → SQL 模板。 */
    public static final String SQL_TEMPLATE = """
            你是资深数据工程师，把用户的问题翻译成一条只读 SQL 查询。

            数据库 schema（只有下列表可访问）：
            ${schema}

            生成规则（必须全部满足）：
            1. 只生成单条 SELECT 语句，禁止任何写操作（INSERT/UPDATE/DELETE/DDL）；
            2. 表名和字段名必须来自上面的 schema，禁止臆造；
            3. 必须带 LIMIT，且不超过 ${maxRows} 行；
            4. 只输出一个 ```sql 代码块，不要任何解释文字。

            用户问题：
            ${question}
            """;

    /** 代码审查模板。 */
    public static final String REVIEW_TEMPLATE = """
            你是严格但务实的代码审查者，只针对新增行提出可执行的问题。

            审查优先级：
            1. 安全：硬编码密钥、字符串拼接 SQL、未校验的外部输入；
            2. 正确性：吞异常、资源泄漏、竞态、边界条件；
            3. 可维护性：调试残留、TODO、重复代码。

            输出要求：只输出 JSON 数组，不要任何解释文字，元素结构如下：
            {"file":"path/to/file","line":123,"severity":"HIGH","message":"问题描述","suggestion":"修改建议"}
            severity 取值只能是 HIGH / MEDIUM / LOW。没有问题时输出空数组 [].

            待审查的 unified diff：
            ${diff}
            """;

    private final PromptService promptService;
    private final SeedProperties seedProperties;

    public PromptSeeder(PromptService promptService, SeedProperties seedProperties) {
        this.promptService = promptService;
        this.seedProperties = seedProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!seedProperties.isEnabled()) {
            log.info("app.seed.enabled=false，跳过内置模板灌入");
            return;
        }
        promptService.ensure("chat", "通用对话", CHAT_TEMPLATE);
        promptService.ensure("sql-generate", "自然语言转 SQL", SQL_TEMPLATE);
        promptService.ensure("code-review", "Diff 代码审查", REVIEW_TEMPLATE);
        log.info("内置 prompt 模板已就绪（chat / sql-generate / code-review）");
    }
}
