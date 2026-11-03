package com.zhouzihang.exam.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * 面试讲解点：离线确定性 Mock LLM。
 *
 * <p>为什么需要它？① 环境完全离线，不能依赖任何外部 API；② 集成测试必须可重复，
 * 如果每次题目内容都变，断言和排障会非常痛苦；③ 演示时没有 API Key 也能跑通
 * “出题 -> 落库 -> token 统计”全链路。</p>
 *
 * <p>确定性怎么做？seed = hash(题型, 知识点, 数量, 难度)，同一请求永远生成同一批题。
 * token 用量按字符数估算（约 4 字符/token），与真实调用同一套统计口径，
 * 这样 /api/llm/usage 在 mock 模式下也有数据可看。</p>
 *
 * <p><b>诚实说明：</b> mock 题目是模板拼装，只验证工程链路，不代表真实教研质量；
 * 配置 app.llm.provider=openai + api-key 后才会走真实模型。</p>
 */
@Slf4j
@Component
public class MockLlmClient implements LlmClient {

    private static final String PROVIDER = "mock";

    private final ObjectMapper objectMapper;

    public MockLlmClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public LlmGeneration generate(LlmRequest request) {
        int count = Math.max(1, request.count());
        long seed = Objects.hash(request.type().toUpperCase(), request.knowledgePoint(), count,
                request.difficulty() == null ? "MEDIUM" : request.difficulty().toUpperCase());
        Random random = new Random(seed);
        String prompt = LlmPrompts.build(request);

        List<GeneratedQuestion> questions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            questions.add(buildQuestion(request, i, random));
        }
        String rawContent;
        try {
            rawContent = objectMapper.writeValueAsString(Map.of("questions", questions));
        } catch (Exception e) {
            throw new LlmException("mock 结果序列化失败", e);
        }
        int promptTokens = prompt.length() / 4 + 12;
        int completionTokens = rawContent.length() / 4 + 8;
        long latencyMs = 10L + random.nextInt(25);
        log.debug("MockLlmClient 生成 {} 题，type={} kp={}", count, request.type(), request.knowledgePoint());
        return LlmGeneration.builder()
                .provider(PROVIDER)
                .model("mock-deterministic")
                .prompt(prompt)
                .rawContent(rawContent)
                .questions(questions)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .latencyMs(latencyMs)
                .fallback(false)
                .status("SUCCESS")
                .build();
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    private GeneratedQuestion buildQuestion(LlmRequest request, int index, Random random) {
        String type = request.type().toUpperCase();
        String kp = request.knowledgePoint();
        String difficulty = request.difficulty() == null || request.difficulty().isBlank()
                ? "MEDIUM" : request.difficulty().toUpperCase();
        String[] correctTemplates = {kp + " 需要在具体业务场景下做权衡", kp + " 的实现通常要考虑边界条件"};
        String[] wrongTemplates = {kp + " 的性能开销可以忽略不计", kp + " 只在单机环境下才有意义"};
        Map<String, String> options = new LinkedHashMap<>();
        String stem;
        String correct;
        String analysis;

        if ("JUDGE".equals(type)) {
            boolean trueStatement = random.nextBoolean();
            options.put("T", "正确");
            options.put("F", "错误");
            stem = "（第 " + (index + 1) + " 题）判断：" + kp
                    + (trueStatement ? " 在工程实现中需要同时考虑正确性与性能。" : " 只能在单线程环境中使用。");
            correct = trueStatement ? "T" : "F";
            analysis = "本题目为离线 Mock 生成，用于验证出题链路；正确答案 " + correct + "。";
        } else if ("MULTIPLE".equals(type)) {
            boolean variant = random.nextBoolean();
            String[] letters = {"A", "B", "C", "D"};
            int correctIdx1 = variant ? 0 : 1;
            int correctIdx2 = variant ? 2 : 3;
            for (int i = 0; i < 4; i++) {
                boolean isCorrect = i == correctIdx1 || i == correctIdx2;
                options.put(letters[i], isCorrect ? correctTemplates[i % 2] : wrongTemplates[i % 2]);
            }
            correct = letters[correctIdx1] + letters[correctIdx2];
            stem = "（第 " + (index + 1) + " 题）以下关于「" + kp + "」的说法，正确的有（ ）";
            analysis = "本题目为离线 Mock 生成；正确选项为 " + correct + "，其余选项措辞绝对化。";
        } else {
            int correctIdx = random.nextInt(4);
            String[] letters = {"A", "B", "C", "D"};
            for (int i = 0; i < 4; i++) {
                options.put(letters[i], i == correctIdx ? correctTemplates[i % 2] : wrongTemplates[i % 2]);
            }
            correct = letters[correctIdx];
            stem = "（第 " + (index + 1) + " 题）关于「" + kp + "」，下列说法正确的是（ ）";
            analysis = "本题目为离线 Mock 生成；正确选项为 " + correct + "。";
        }

        return GeneratedQuestion.builder()
                .type(type)
                .stem(stem)
                .options(options)
                .correctOption(correct)
                .analysis(analysis)
                .difficulty(difficulty)
                .knowledgePoint(kp)
                .score(5)
                .build();
    }
}
