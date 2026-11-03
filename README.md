# my_project · Java 后端项目集

> 周子航（2026 届 · Java 后端开发工程师）的两个可运行项目。
> 每个项目都是**独立可构建、可运行、有测试、有实测数据**的完整工程，README 里写了设计取舍与已知限制。

| 项目 | 一句话 | 技术栈 | 测试 |
|---|---|---|---|
| [exam-ai](./exam-ai) | 智能在线考试系统：MediaPipe 视觉防作弊 + LLM 自动出题 | Java 17 · Spring Boot 2.7 · MyBatis · Redis/Redisson · WebSocket | 14 passed |
| [devflow](./devflow) | AI 开发者效能平台：LLM 抽象 + NL2SQL 护栏 + 自动 Code Review | Java 21 · Spring Boot 3.5 · MyBatis-Plus · SSE · PostgreSQL | 20 passed |

---

## 1. exam-ai — 智能在线考试系统

**做了什么**

- JWT + 注解拦截器实现 **管理员 / 教师 / 学生 三级 RBAC**，PBKDF2 密码哈希；
- 考试全流程：发卷（幂等）→ 交卷（**幂等**：快路径 + Redisson 锁 + 唯一键兜底）→ 成绩解析；
- **AI 防作弊**：前端 MediaPipe（WASM）计算头部姿态 / 视线 / 睁眼度 → 后端滑窗风险引擎（加权分 + 5s 滑窗占比 + **L3 连续 3 帧确认** + 3s 去抖）→ 三级告警经 **WebSocket** 推送 + 截图存证；
- **LLM 出题**：`LlmClient` 端口 + Mock 降级 + OpenAI 兼容实现，token / 延迟 / 降级率可观测。

**快速开始**

```bash
cd exam-ai
mvn test                                  # 14 个用例
mvn -Pboot spring-boot:run                # 联网环境：8081
# 离线（~/.m2 已预热）：powershell -ExecutionPolicy Bypass -File scripts/run.ps1 -Offline
powershell -ExecutionPolicy Bypass -File scripts/smoke-test.ps1   # 12 步端到端冒烟
```

演示账号：`teacher1 / teacher123`、`student1 / student123`（demo profile 自动建表灌数，H2 内存库）。

详见 [exam-ai/README.md](./exam-ai/README.md)

---

## 2. devflow — AI 开发者效能平台

**做了什么**

- **LLM 统一抽象**（语义对齐 Spring AI）：`LlmClient` 端口 + Mock / OpenAI / Anthropic 实现 + `ModelRouter` 降级链；
- **Prompt 模板管理**：变量注入 + 渲染校验（缺变量 422）+ 版本管理（改动即 +1，可回滚）；
- **自然语言转 SQL + 四道护栏**：注释清理 → 单语句 → 仅 SELECT → 表白名单 → 强制 LIMIT → EXPLAIN 预检；
- **Git Diff 自动 Code Review**：解析新增行号 → 分段截断 → 结构化 findings；GitHub Webhook **HMAC-SHA256 验签 + delivery 幂等**；
- SSE 流式对话（心跳 / 断开取消上游 / error 事件），按模型与功能统计 token 成本；
- 自带零构建演示页 `http://127.0.0.1:8082/index.html`。

**快速开始**

```bash
cd devflow
mvn test                                  # 20 个用例
mvn -Pboot spring-boot:run                # 联网环境：8082
# 离线：powershell -ExecutionPolicy Bypass -File scripts/run.ps1 -Offline
powershell -ExecutionPolicy Bypass -File scripts/smoke-test.ps1   # 13 步端到端冒烟
```

详见 [devflow/README.md](./devflow/README.md)

---

## 目录结构

```
my_project/
├── exam-ai/            智能在线考试系统（Spring Boot 2.7）
│   ├── src/main/java/com/zhouzihang/exam/{proctor,llm,service,ws,...}
│   ├── db/             H2 / MySQL 建表与种子数据
│   └── scripts/        启动脚本、冒烟测试、离线 Maven settings
└── devflow/            AI 开发者效能平台（Spring Boot 3.5）
    ├── src/main/java/com/zhouzihang/devflow/{llm,prompt,sql,review,chat}
    ├── src/main/resources/static/index.html   零构建演示页
    └── scripts/        启动脚本、冒烟测试、离线 Maven settings
```

## 关于两个项目

- 都是**个人项目**（与公司业务无关），用于展示后端工程能力：分层设计、幂等、并发控制、可观测性、以及 LLM 工程化落地；
- 关键类顶部有中文 `面试讲解点` 注释，说明"为什么这么设计、替代方案、踩过的坑"；
- **LLM 默认使用 Mock 实现**（无 API Key 也能完整演示），配置环境变量即可切真实模型；
- 两套 POM 都刻意不挂 `spring-boot-starter-parent`、用 BOM + 精确版本，保证**纯离线环境**也能构建；需要标准启动方式时用 `-Pboot` profile。

## License

[MIT](./LICENSE)
