# DevFlow — AI 开发者效能平台

[![ci](../../actions/workflows/ci.yml/badge.svg)](../../actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.16-6DB33F)
![License](https://img.shields.io/badge/License-MIT-green)

把 LLM 接进研发流程的**后端平台**：Prompt 模板版本管理、SSE 流式对话、**自然语言转 SQL（带四道护栏）**、**Git Diff 自动 Code Review（GitHub Webhook 验签 + 幂等）**、按模型/功能的 token 与成本统计。

> 核心原则：**LLM 的输出一律不可信**。生成的 SQL 要过静态护栏 + EXPLAIN 才允许执行；审查结论只做人工前置过滤，不做门禁。

---

## 功能

- **LLM 统一抽象（自研，语义对齐 Spring AI）**
  `LlmClient` 端口 → `MockLlmClient`（确定性、降级链终点）/ `OpenAiCompatibleClient` / `AnthropicClient`；
  `ModelRouter` 负责首选选择与**降级链**（preferred → openai → anthropic → mock），带超时与重试。
- **Prompt 模板管理**：`${var}` 注入 + 渲染后校验（缺变量直接 422）+ **版本管理**（改动即 version+1、可回滚）+ 本地缓存失效。
- **SSE 流式对话**：心跳保活、`X-Accel-Buffering: no`、客户端断开取消上游、异常发 `event: error`。
- **NL2SQL + 四道护栏**：注释清理 → 单语句 → 仅 SELECT → 表白名单 → 强制 LIMIT →（执行前）EXPLAIN 预检。
- **自动 Code Review**：解析 unified diff 新增行号 → 超长分段截断 → 结构化 findings（文件/行号/严重级/建议）；GitHub Webhook 接入，**HMAC-SHA256 验签 + delivery 幂等**。
- **成本可观测**：按 model / feature 记录 token 与美元成本，`/api/usage/summary` 直接看。
- **零构建演示页**：`src/main/resources/static/index.html`，原生 JS 打通模板 / SSE / SQL / Review / 用量。

---

## 技术栈

| 层次 | 选型 |
|---|---|
| 语言 / 框架 | Java 21、Spring Boot 3.5.16、Spring MVC |
| 持久层 | MyBatis-Plus 3.5.5、H2（demo）/ PostgreSQL（prod） |
| 缓存 | Caffeine（`CachePort` 抽象，生产可换 Redis） |
| 流式 | Spring `SseEmitter` |
| LLM | 自研抽象层：OpenAI 兼容 API、Anthropic Messages API、Mock |
| 测试 | JUnit 5、Spring Boot Test |

> POM 用 `spring-boot-dependencies` BOM + 精确版本，不挂 starter-parent，保证纯离线环境可构建；
> 联网环境可用 `mvn -Pboot spring-boot:run` 走标准启动方式。

---

## 快速开始

### 前置条件
- JDK 21+、Maven 3.8+
- 默认 `demo` profile：**H2 内存库 + 自动建表灌数 + LLM Mock**，零外部依赖

### 运行

```bash
# 方式 1（联网环境，标准 Spring Boot）
mvn -Pboot spring-boot:run

# 方式 2（离线，自动解析 classpath）
powershell -ExecutionPolicy Bypass -File scripts/run.ps1

# 方式 3（联网，可执行 jar）
mvn -Pboot package && java -jar target/devflow.jar
```

打开 <http://127.0.0.1:8082/index.html> 即演示页；`/ping` 返回 `pong` 表示服务正常。

### 一键冒烟（13 步，含 SSE / Webhook 验签 / SQL 护栏）

```bash
powershell -ExecutionPolicy Bypass -File scripts/smoke-test.ps1
```

### 切真实模型 / 生产库

```bash
export OPENAI_API_KEY=sk-***            # 配了才启用；不配则自动走 mock
export ANTHROPIC_API_KEY=sk-ant-***
export GITHUB_WEBHOOK_SECRET=<自定义密钥>
export DB_HOST=127.0.0.1 DB_PORT=5432 DB_NAME=devflow DB_USER=postgres DB_PASSWORD=***
java -jar target/devflow.jar --spring.profiles.active=pg
```

---

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET / POST | `/api/prompts` | 模板列表 / 新建 |
| PUT | `/api/prompts/{id}` | 修改 → **版本 +1**，历史保留 |
| GET | `/api/prompts/{code}`、`/versions` | 取最新 / 全部版本 |
| POST | `/api/prompts/{code}/render` | 渲染校验（缺变量 422） |
| POST / GET | `/api/chat/stream` | **SSE 流式对话** |
| POST | `/api/sql/generate` | 自然语言 → SQL（schema 注入 + 强制 LIMIT） |
| POST | `/api/sql/execute` | 护栏 → EXPLAIN → 只读执行 |
| POST | `/api/sql/guard-check` | 仅护栏校验，返回 allowed/reason |
| POST | `/api/review` | unified diff → 结构化 findings |
| GET | `/api/review/sample`、`/runs[/{id}]` | 示例 diff / 审查历史 |
| POST | `/api/webhooks/github` | **验签 + delivery 幂等 + 事件过滤** |
| GET | `/api/usage/summary`、`/recent` | token / 成本统计 |
| GET | `/api/llm/info` | provider 链、可用性、定价表 |

---

## 设计要点

### 1. LLM 抽象与降级链
`src/main/java/com/zhouzihang/devflow/llm/`

`LlmClient` 提供 `chat()` / `stream()` / `providerName()` / `model()`；`ModelRouter` 按"请求级覆盖 > 配置 > mock"选首选，再按 `preferred → openai → anthropic → mock` 逐级降级。
**流式一旦吐出首个 token 就不再切 provider**，避免内容重复。

### 2. Prompt 模板：缺变量必须报错
`prompt/PromptRenderer.java`

缺变量静默留空会让模型对着空上下文"编内容"（幻觉），比直接 4xx 更危险 → 渲染时缺变量抛 422；渲染后再检查是否残留 `${...}`（防变量值注入污染）。

### 3. SQL 护栏（全项目最危险的地方）
`sql/SqlGuard.java`

清理注释 → 拒绝多语句 → 必须 SELECT → 表白名单 → 强制 LIMIT（≤200）→ 长度上限 → 执行前 EXPLAIN。
测试覆盖：`UPDATE/DELETE/INSERT/DROP`、多语句、非白名单表、注释绕过、超长 SQL、已带 LIMIT。

### 4. Webhook 安全
`review/WebhookSignatureVerifier.java` + `review/WebhookDelivery`

HMAC-SHA256 + **常量时间比较**；`X-GitHub-Delivery` 主键插入即抢锁实现幂等（重复投递返回 `duplicate`）；只处理 `pull_request` 事件。
坑：必须用**原始 body 字节**验签，框架先反序列化再序列化会签名不匹配。

### 5. SSE 的四个坑
`chat/ChatController.java`

代理缓冲（`X-Accel-Buffering: no`）、空闲超时（心跳）、客户端断开要取消上游（`onCompletion/onTimeout`）、异常要发 `event: error` 再关闭。

---

## 测试与验证

```bash
mvn test          # 20 个用例
```

覆盖：PromptRenderer 5（提取 / 渲染 / 缺变量 / 注入污染 / 无变量）、DiffParser 4（解析 / 空 diff / 截断 / 删除行）、Webhook 签名 4（正确 / 篡改 / 缺失 / 错密钥）、SQL 护栏 7（合法+补 LIMIT / 写操作 / 多语句 / 非白名单 / 注释绕过 / 超长 / 已有 LIMIT）。

端到端冒烟（`scripts/smoke-test.ps1`）实测结果：

```
内置模板 3 个（chat / sql-generate / code-review）→ 新建→升级 历史版本数=2
渲染 OK；缺变量 → 422
生成 SQL: SELECT COUNT(*) AS total FROM orders LIMIT 200 → 执行 列=total 行数=1
护栏 4 条危险 SQL 全被拒：NOT_SELECT / NOT_SELECT / TABLE_NOT_ALLOWED / MULTI_STATEMENT
Code Review: runId=1 COMPLETED 发现数=6（HIGH 3 / MEDIUM 2 / LOW 1）
Webhook：正确签名 accepted / 重复投递 duplicate / 错误签名 401
SSE：76 帧（curl -N）
用量：requests=6 totalTokens=2812（code-review 2133 / sql-generate 484 / chat 195）
```

---

## 目录结构

```
src/main/java/com/zhouzihang/devflow/
├── chat/       SSE 对话（Controller / Service / PreparedChat）
├── common/     统一错误码与异常
├── config/     LlmProperties / SqlGuardProperties / GitHubProperties / AppConfig
├── llm/        LlmClient 端口、Mock/OpenAI/Anthropic 实现、ModelRouter、TokenAccounting
├── prompt/     模板实体、版本服务、渲染校验、种子数据
├── review/     Diff 解析、审查服务、Webhook 验签与幂等
├── sql/        SQL 护栏、执行服务、schema 提供
└── web/        全局异常处理
src/main/resources/
├── static/index.html   零构建演示页
└── db/                 H2 / PG 建表与种子数据
scripts/     启动脚本、离线 Maven settings、冒烟测试
```

## 配置（环境变量）

| 变量 | 默认 | 说明 |
|---|---|---|
| `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` | 空 | 为空则该 provider 不可用，自动跳过/降级到 mock |
| `GITHUB_WEBHOOK_SECRET` | 内置 dev 值 | Webhook 验签密钥，生产必须覆盖 |
| `DB_HOST` `DB_PORT` `DB_NAME` `DB_USER` `DB_PASSWORD` | 127.0.0.1 / 5432 / devflow / postgres / 空 | 仅 `pg` profile 使用 |

## 已知限制

- 默认 mock provider：审查结论为规则化模拟输出，**不代表真实模型效果**；要评估精度需自建标注集；
- 演示页为原生 JS（不是 Vue3+TS），API 与正式前端一致；
- 缓存为 Caffeine（`CachePort` 已留好 Redis 实现位）；
- Webhook 仅处理 PR 事件。

## License

[MIT](LICENSE)
