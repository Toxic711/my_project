# 智能在线考试系统（MediaPipe + LLM）

[![ci](../../actions/workflows/ci.yml/badge.svg)](../../actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-17-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.18-6DB33F)
![License](https://img.shields.io/badge/License-MIT-green)

一个**在线考试后端**：支持 JWT 三级权限、考试全流程、**AI 实时防作弊**（前端 MediaPipe 计算视觉特征，后端风险引擎判定三级告警并 WebSocket 推送）、**GPT-4o 自动出题**（无 key 时自动降级 Mock，离线也能完整演示）。

> 设计目标：不是"能跑通的 demo"，而是**每一处设计都能讲清楚为什么**——所以关键类顶部都有 `面试讲解点` 注释，README 也给出实测数据与已知限制。

---

## 功能

- **认证授权**：JWT + 注解拦截器，ADMIN / TEACHER / STUDENT 三级 RBAC；密码 PBKDF2-HmacSHA256（60k 迭代 + 随机盐）落库，绝不明文。
- **考试主流程**：创建考试 → 发卷（幂等）→ 提交答卷（**幂等**，重复提交返回同一结果）→ 查看成绩与解析。
- **AI 防作弊**：
  - 前端 MediaPipe（WASM）计算头部姿态 / 视线 / 睁眼度，后端只接收**结构化特征**（省带宽）；
  - 后端 `ProctorRiskEngine`：单帧加权打分 + 5s 滑窗占比修正 + **L3 连续 3 帧确认** + 3s 同级别去抖；
  - 三级告警（L1≥40 / L2≥65 / L3≥85）通过 **WebSocket** 实时推送给监考教师，并落盘截图存证。
- **LLM 出题**：`LlmClient` 端口化，Mock 与 OpenAI 兼容实现同接口，无 key 自动降级；token / 延迟 / 降级率全部落库可查。
- **缓存**：Caffeine 本地缓存考试详情与题库，暴露命中率统计端点。
- **可观测**：`/api/stats/cache`、`/api/llm/usage` 直接看到锁后端、风险引擎形态、token 成本。

---

## 技术栈

| 层次 | 选型 |
|---|---|
| 语言 / 框架 | Java 17、Spring Boot 2.7.18、Spring MVC |
| 持久层 | MyBatis（**手动装配** SqlSessionFactory）、H2（demo）/ MySQL 8（prod） |
| 缓存 / 锁 | Caffeine、Redis + Redisson（不可用时自动降级 JVM 锁） |
| 实时通信 | Spring WebSocket（`TextWebSocketHandler`） |
| 安全 | JWT（jjwt 0.12）、PBKDF2 密码哈希 |
| AI | MediaPipe FaceLandmarker（前端 WASM）+ OpenAI 兼容 API（后端） |
| 测试 | JUnit 5、Spring Boot Test |

> 依赖版本刻意**不挂 starter-parent**：POM 用 `spring-boot-dependencies` BOM 导入 + 精确版本，
> 保证在**纯离线、本地 Maven 缓存已预热**的环境也能构建（原因见 `pom.xml` 顶部注释）。

---

## 快速开始

### 前置条件
- JDK 17+、Maven 3.8+
- 可选：Redis（`127.0.0.1:6379`，无密码）——不启动也能跑，会自动降级
- 默认 `demo` profile：**H2 内存库 + 自动建表灌数 + LLM Mock**，零外部依赖启动

### 运行

```bash
# 方式 1（推荐给联网环境）：标准 Spring Boot 启动
mvn -Pboot spring-boot:run

# 方式 2（离线/本地 m2 已预热）：脚本自动解析 classpath 并启动
powershell -ExecutionPolicy Bypass -File scripts/run.ps1

# 方式 3：打成可执行 jar（需要联网，boot profile 会引入 spring-boot-maven-plugin）
mvn -Pboot package && java -jar target/exam-ai.jar
```

启动后访问 **<http://127.0.0.1:8081/>** 就是 Web 界面（见下）。

### Web 界面（零构建，Spring Boot 直接托管）

| 路径 | 作用 |
|---|---|
| `/` | 登录页，按角色跳转 |
| `/student.html` | **学生端**：考试列表 → 答题（倒计时 / 答题卡 / 草稿自动保存）→ 交卷 → 逐题解析；右侧为**摄像头监考**（真实视频 + 本地检测 + 截图存证） |
| `/teacher.html` | **教师端**：WebSocket 实时告警流（L1/L2/L3 统计）、考试列表、LLM 出题、用量与缓存统计 |
| `/console.html` | 接口调试台：逐个按钮调所有 API，适合演示/联调 |

> 前端是原生 HTML/CSS/JS（无 npm、无构建），与后端同源，所以不需要配 CORS 也能跑。

### 摄像头监考（真实视频，不是假数据）

学生端考试页右侧「摄像头监考」：

1. 点「开启摄像头」→ 浏览器授权 → 视频实时预览（镜像）；
2. 每 **400ms** 采一帧到 96×72 离屏画布，**YCbCr 肤色判定 → 3×3 膨胀 → 连通域**，再用宽高比/边框过滤挑出"像脸"的块；
3. 产出与生产**同构**的特征：`faceDetected / faceCount / yaw / pitch / gazeOffScreen / ear`；
4. **三道防误报**：
   - YCbCr 比 RGB 规则稳（冷光/暖光/戴眼镜都覆盖，实测同一张脸：RGB 2% vs YCbCr 22%）；
   - **连续 N 帧异常**才上报（中灵敏度 3 帧 ≈1.2s），单帧抖动不告警；
   - 「**校准我的位置**」采 8 帧基线，之后判断的是"相对你自己"的偏移，而不是绝对居中；
   - 「**灵敏度 低/中/高**」可切换面积/角度阈值与确认帧数；
5. 触发告警时附带 **320×240 JPEG 缩略图**（base64）→ 后端落盘存证 → 教师端告警里点「查看截图」（带 JWT 拉 blob，`Cache-Control: private, no-store`）。

实测覆盖：正常答题（风险 0）、偏头/视线离开（L2）、遮挡摄像头（画面过暗）、离开座位（未检测到人脸）、两人同框（多人脸）、切出标签页（自动补报一次"人脸丢失"）。

**已知限制（面试主动说）**：这里的检测是**肤色 + 运动的轻量启发式**，精度不如 MediaPipe FaceLandmarker；
但上报协议与风险引擎完全一致，**换成 MediaPipe 只需替换 `analyzeFrame()` 一个函数**（本机离线拿不到
`.task` 模型资源，所以用轻量方案保证链路可跑）。


> 真实环境的 MediaPipe 在浏览器 WASM 里算特征后上报，这里用按钮模拟三档事件，链路与线上一致。

### 演示账号（demo 种子数据）

| 账号 | 密码 | 角色 |
|---|---|---|
| `admin` | `admin123` | ADMIN |
| `teacher1` | `teacher123` | TEACHER |
| `student1` / `student2` | `student123` | STUDENT |

### 三分钟体验流程

1. 浏览器 A：登录 `student1` → 开始一场考试 → 随便作答 → **交卷** → 看解析；
2. 浏览器 B（或隐身窗口）：登录 `teacher1` → 打开监考台；
3. 回到 A 的考试页，点右侧「**偏转/视线离开**」→ B 立刻收到 L2/L3 告警（等级色标 + 风险分 + 截图链接）；
4. 回 A 再点一次「交卷」→ 返回 `idempotent=true`，分数不变（幂等）。

### 一键冒烟（12 步全流程，含幂等与三级告警断言）

```bash
powershell -ExecutionPolicy Bypass -File scripts/smoke-test.ps1
```

### 切换生产库

```bash
# MySQL：先执行 db/schema-mysql.sql 建表，再用环境变量注入连接信息
export DB_HOST=127.0.0.1 DB_PORT=3306 DB_NAME=exam_ai DB_USER=root DB_PASSWORD=***
export EXAM_JWT_SECRET=<至少32字节的随机串>
java -jar target/exam-ai.jar --spring.profiles.active=mysql
```

---

## 接口

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| POST | `/api/auth/login` | 登录，返回 JWT | 匿名 |
| GET | `/api/auth/me` | 当前登录人 | 登录 |
| GET | `/api/exams` | 考试列表（学生仅 PUBLISHED） | 登录 |
| GET | `/api/exams/{id}` | 考试详情（走缓存） | 登录 |
| POST | `/api/exams` | 创建考试 | TEACHER |
| POST | `/api/exams/{id}/start` | 开始考试 / 发卷（幂等） | STUDENT |
| POST | `/api/exams/records/{id}/submit` | 交卷（**幂等**） | STUDENT |
| GET | `/api/exams/records/{id}/result` | 成绩与解析 | STUDENT |
| POST | `/api/proctor/events` | 上报视觉特征事件 | STUDENT |
| GET | `/api/proctor/events` | 原始事件审计 | TEACHER |
| GET | `/api/proctor/alerts` | 告警查询（按等级聚合） | TEACHER |
| WS | `/ws/proctor?token=` | 订阅三级告警 | TEACHER |
| POST | `/api/questions/generate` | LLM 出题 | TEACHER |
| GET | `/api/llm/usage` | token / 延迟 / 降级率 | TEACHER |
| GET | `/api/stats/cache` | 缓存命中率 + 锁后端 | TEACHER |
| GET | `/api/evidence/{id}` | 受控读取截图存证 | TEACHER |

---

## 设计要点

### 1. 防作弊风险引擎（核心）
`src/main/java/com/zhouzihang/exam/proctor/ProctorRiskEngine.java`

单帧加权（人脸丢失 +30、多人脸 +25、大幅偏转 +25、视线离屏 +20、闭眼 +10）→ 滑窗占比修正（异常占比 ≥50% +8、人脸丢失占比 ≥60% +10）→ 分级。

**三道误报闸门**：
1. 滑窗平滑：单帧尖峰被稀释，不立即升级；
2. **L3 连续 3 帧确认**：原始分够 85 先降级报 L2，连续命中才升级（防低头捡笔误判）；
3. 同级别去抖 3s，**升级放行**（宁可多报一次重警，不可漏报）。

### 2. 交卷幂等（三道防线）
`src/main/java/com/zhouzihang/exam/service/ExamService.java#submit`

① 已提交快路径直接返回原结果 → ② Redisson 分布式锁 `submit:record:{id}` 串行化 → ③ `answer(exam_record_id, question_id)` 唯一键兜底。
> `submit` 刻意**不加 `@Transactional`**：方法级事务会让锁在事务提交前释放，并发下可能读到未提交状态而重复提交；改为"无事务 + 锁内 upsert"，崩溃可重试。

### 3. LLM 端口化与降级
`src/main/java/com/zhouzihang/exam/llm/`

`LlmClient` 接口 + `MockLlmClient`（确定性输出）+ `OpenAiCompatibleClient`（java.net.http）。
无 `LLM_API_KEY` 时自动降级 Mock，**本地开发/CI 不花一分钱**；每次调用落 `llm_task`（provider/model/token/延迟/状态），降级率可观测。

### 4. WebSocket 告警推送
`src/main/java/com/zhouzihang/exam/ws/`

握手拦截器校验 JWT 并解析角色，按 `examId` 注册教师会话；事件触发告警后定向推送。
**已知限制**：会话表为单机内存，多副本需 Redis pub/sub 或 STOMP + broker。

---

## 测试与验证

```bash
mvn test          # 14 个用例
```

覆盖：风险引擎 7 个（正常帧 / 单帧 L2 / 去抖 / L3 连续帧 / 多人脸 / 状态隔离 / reset）、JWT 3 个（往返 / 篡改 / 过期）、判分归一化 4 个（单选 / 多选乱序 / 判断题 / 空值）。

端到端冒烟（`scripts/smoke-test.ps1`，真实 HTTP 调用）实测结果：

```
教师登录 role=TEACHER → 学生调出题接口 403 → 3 场考试
开始考试 recordId=2 题目数=2 总分=100
防作弊三档：正常 risk=0 NONE / 大幅偏转 risk=80 L2 alert=True / 人脸丢失 risk=30 NONE
告警聚合：total=1 {"L2":1}
提交 得分=0/100 → 重复提交 idempotent=True        ← 幂等生效
LLM 出题：provider=mock fallback=False tokens=248
运行态：lockBackend=redisson riskEngine=rule-based:sliding-window+...
```

---

## 目录结构

```
src/main/java/com/zhouzihang/exam/
├── common/      统一异常与全局处理
├── config/      AppProperties / MyBatis / Web / WebSocket 配置
├── domain/      10 张表对应实体
├── mapper/      MyBatis Mapper（注解 SQL）
├── proctor/     风险引擎（ProctorRiskEngine / ProctorSignal / RiskAssessment）
├── llm/         LLM 端口与两种实现 + 路由
├── service/     认证 / 考试 / 防作弊 / 出题 / 存证
├── security/    JWT / 拦截器 / 角色注解 / PBKDF2
├── storage/     截图存证落盘
├── web/         Controller 与 DTO
└── ws/          WebSocket 握手与推送
db/          H2 与 MySQL 建表脚本、种子数据
scripts/     启动脚本、离线 Maven settings、冒烟测试
```

## 配置（环境变量）

| 变量 | 默认 | 说明 |
|---|---|---|
| `EXAM_JWT_SECRET` | 内置 dev 串 | JWT 密钥，生产必须覆盖（≥32 字节） |
| `LLM_API_KEY` / `LLM_BASE_URL` | 空 / OpenAI | 配了才走真实模型，否则 Mock |
| `DB_HOST` `DB_PORT` `DB_NAME` `DB_USER` `DB_PASSWORD` | 127.0.0.1 / 3306 / exam_ai / root / 空 | 仅 `mysql` profile 使用 |
| `app.redis.enabled` | true | 设 `false` 或 Redis 不可用时降级本地锁 |

## 已知限制

- 风险引擎与 WS 会话状态在**单机内存**（多副本需 Redis 化）；
- 截图存证落本地磁盘（生产换 OSS/S3）；
- 前端 MediaPipe 页面不在本仓库，接口契约见 `ProctorDtos.ProctorEventRequest`；
- `demo` 数据含演示账号，**不要直接用于生产**。

## License

[MIT](LICENSE)
