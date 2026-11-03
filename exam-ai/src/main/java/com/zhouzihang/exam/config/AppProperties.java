package com.zhouzihang.exam.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 应用配置聚合（app.*）。
 *
 * <p>面试讲解点：阈值、去抖窗口、LLM Key 全部外置到配置，而不是 hardcode。
 * 这样同一份代码可以按考试场次/学校策略调整（例如四六级更严格），
 * 也方便在单测里手工构造一个 AppProperties 做纯单元测试，不启动 Spring。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Jwt jwt = new Jwt();
    private Proctor proctor = new Proctor();
    private Llm llm = new Llm();
    private Storage storage = new Storage();
    private Redis redis = new Redis();

    @Data
    public static class Jwt {
        /** HS256 密钥，长度必须 >= 32 字节（jjwt 会强校验）。生产用环境变量注入。 */
        private String secret = "exam-ai-dev-secret-key-please-change-in-prod-0123456789";
        private int expireMinutes = 720;
    }

    @Data
    public static class Proctor {
        /** 三级风险阈值，与简历口径一致：L1>=40 提示、L2>=65 警告、L3>=85 严重。 */
        private int level1Risk = 40;
        private int level2Risk = 65;
        private int level3Risk = 85;
        /** 同考生同级别告警去抖窗口（毫秒）。窗口内只发一次，升级到更高级别会放行。 */
        private long dedupWindowMs = 3000L;
        /** 滑窗长度（毫秒），用于统计持续异常比例，抑制单帧误判。 */
        private long windowMs = 5000L;
        /** 单会话窗口最多保留的事件数，防止长考试内存膨胀。 */
        private int maxWindowEvents = 50;
        /** L3 需要连续命中的帧数（防单帧误判）。 */
        private int level3Consecutive = 3;
        /** 人脸数量 >= 该值判定为多人脸。 */
        private int multiFaceThreshold = 2;
        /** 是否落盘截图存证。 */
        private boolean evidenceEnabled = true;
        /** 单张截图最大字节数（默认 512KB）。 */
        private int evidenceMaxBytes = 512 * 1024;
    }

    @Data
    public static class Llm {
        /** mock | openai。openai 需要同时配置 api-key，否则自动降级 mock。 */
        private String provider = "mock";
        private String model = "gpt-4o-mini";
        private String apiKey = "";
        private String baseUrl = "https://api.openai.com/v1";
        private int timeoutSeconds = 30;
    }

    @Data
    public static class Storage {
        /** 截图存证根目录，相对或绝对路径均可。 */
        private String evidenceDir = "./data/evidence";
    }

    @Data
    public static class Redis {
        /** 关闭后一切走本地内存实现（单机 demo 可用）。 */
        private boolean enabled = true;
        private String address = "redis://127.0.0.1:6379";
    }
}
