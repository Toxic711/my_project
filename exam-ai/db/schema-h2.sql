-- =====================================================================
-- 智能在线考试系统 · H2 建表脚本（demo：内存库，每次启动重建）
-- 方言：H2 2.2.224，MODE=MySQL + DATABASE_TO_LOWER=TRUE
-- 与 db/schema-mysql.sql 的差异：
--   1) demo 用 DROP + CREATE，保证反复启动/测试时数据干净；
--   2) 生产脚本不 DROP，用 Flyway/Liquibase 管理版本。
-- 共 10 张表（对应简历“设计 10 张数据表”）：
--   sys_user / exam / question / question_option / exam_record /
--   answer / proctor_event / proctor_alert / evidence_file / llm_task
-- =====================================================================

-- 依赖顺序：先删子表，再删父表
DROP TABLE IF EXISTS proctor_alert;
DROP TABLE IF EXISTS proctor_event;
DROP TABLE IF EXISTS evidence_file;
DROP TABLE IF EXISTS answer;
DROP TABLE IF EXISTS exam_record;
DROP TABLE IF EXISTS llm_task;
DROP TABLE IF EXISTS question_option;
DROP TABLE IF EXISTS question;
DROP TABLE IF EXISTS exam;
DROP TABLE IF EXISTS sys_user;

-- 1) 用户：角色合并为 role 单列（ADMIN / TEACHER / STUDENT），等价于 sys_user + sys_role
CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(256) NOT NULL,          -- PBKDF2，绝不明文
    real_name     VARCHAR(64),
    role          VARCHAR(16)  NOT NULL,          -- ADMIN / TEACHER / STUDENT
    enabled       TINYINT      NOT NULL DEFAULT 1,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_user_username UNIQUE (username)
);

-- 2) 考试：question_count/total_score 是快照冗余字段
CREATE TABLE IF NOT EXISTS exam (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    title            VARCHAR(128) NOT NULL,
    description      VARCHAR(512),
    question_count   INT          NOT NULL DEFAULT 0,
    duration_minutes INT          NOT NULL DEFAULT 60,
    total_score      INT          NOT NULL DEFAULT 100,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PUBLISHED',  -- DRAFT / PUBLISHED / CLOSED
    created_by       BIGINT       NOT NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 3) 题目：exam_id 为 NULL 表示题库公共题（省掉 exam_question 中间表）
CREATE TABLE IF NOT EXISTS question (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    exam_id         BIGINT,
    question_type   VARCHAR(16)   NOT NULL,       -- SINGLE / MULTIPLE / JUDGE
    stem            VARCHAR(1024) NOT NULL,
    knowledge_point VARCHAR(64),
    difficulty      VARCHAR(16),                  -- EASY / MEDIUM / HARD
    score           INT           NOT NULL DEFAULT 5,
    correct_option  VARCHAR(16)   NOT NULL,       -- "A" / "AC" / "T"
    analysis        VARCHAR(1024),
    source          VARCHAR(16)   NOT NULL DEFAULT 'BANK',  -- BANK / LLM
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_question_exam ON question (exam_id);

-- 4) 选项
CREATE TABLE IF NOT EXISTS question_option (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    question_id BIGINT       NOT NULL,
    option_key  VARCHAR(8)   NOT NULL,            -- A/B/C/D，判断题 T/F
    content     VARCHAR(512) NOT NULL,
    CONSTRAINT uk_question_option UNIQUE (question_id, option_key)
);

-- 5) 考试记录：唯一键 (exam_id, student_id) 是“开始考试幂等”的数据库兜底
CREATE TABLE IF NOT EXISTS exam_record (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    exam_id           BIGINT      NOT NULL,
    student_id        BIGINT      NOT NULL,
    status            VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS',  -- IN_PROGRESS / SUBMITTED
    score             INT,
    started_at        TIMESTAMP   NOT NULL,
    deadline_at       TIMESTAMP,
    submitted_at      TIMESTAMP,
    submit_request_id VARCHAR(64),
    last_event_at     TIMESTAMP,
    CONSTRAINT uk_exam_record UNIQUE (exam_id, student_id)
);
CREATE INDEX IF NOT EXISTS idx_exam_record_student ON exam_record (student_id);

-- 6) 作答明细：唯一键 (exam_record_id, question_id) 是“交卷幂等”的最后防线
CREATE TABLE IF NOT EXISTS answer (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    exam_record_id BIGINT      NOT NULL,
    question_id    BIGINT      NOT NULL,
    answer_value   VARCHAR(32),
    correct_flag   TINYINT     NOT NULL DEFAULT 0,
    score          INT         NOT NULL DEFAULT 0,
    answered_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_answer_record_question UNIQUE (exam_record_id, question_id)
);

-- 7) 防作弊原始事件（高频，每秒级）
CREATE TABLE IF NOT EXISTS proctor_event (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    exam_record_id BIGINT      NOT NULL,
    session_id     VARCHAR(64),
    face_detected  TINYINT     NOT NULL DEFAULT 1,
    face_count     INT         NOT NULL DEFAULT 1,
    yaw            DOUBLE,
    pitch          DOUBLE,
    roll           DOUBLE,
    gaze_off_screen TINYINT    NOT NULL DEFAULT 0,
    ear            DOUBLE,
    risk_score     INT         NOT NULL DEFAULT 0,
    risk_level     VARCHAR(8),
    event_ts       BIGINT      NOT NULL,
    evidence_file_id BIGINT,
    created_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_proctor_event_record_ts ON proctor_event (exam_record_id, event_ts);

-- 8) 告警（低频，去抖后每个窗口最多一条）
CREATE TABLE IF NOT EXISTS proctor_alert (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    exam_record_id  BIGINT      NOT NULL,
    alert_level     VARCHAR(8)  NOT NULL,        -- L1 / L2 / L3
    risk_score      INT         NOT NULL,
    reason          VARCHAR(256),
    evidence_file_id BIGINT,
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_proctor_alert_record ON proctor_alert (exam_record_id, created_at);

-- 9) 截图存证：只存相对路径 + 摘要，不存 base64
CREATE TABLE IF NOT EXISTS evidence_file (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    exam_record_id BIGINT       NOT NULL,
    rel_path       VARCHAR(256) NOT NULL,
    sha256         VARCHAR(64)  NOT NULL,
    size_bytes     BIGINT       NOT NULL DEFAULT 0,
    mime_type      VARCHAR(32)  NOT NULL DEFAULT 'image/png',
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_evidence_record ON evidence_file (exam_record_id);

-- 10) LLM 出题任务与 token 统计
CREATE TABLE IF NOT EXISTS llm_task (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider          VARCHAR(32) NOT NULL,
    model             VARCHAR(64),
    question_type     VARCHAR(16),
    knowledge_point   VARCHAR(64),
    question_count    INT         NOT NULL DEFAULT 0,
    prompt_tokens     INT         NOT NULL DEFAULT 0,
    completion_tokens INT         NOT NULL DEFAULT 0,
    total_tokens      INT         NOT NULL DEFAULT 0,
    latency_ms        BIGINT      NOT NULL DEFAULT 0,
    status            VARCHAR(16) NOT NULL,      -- SUCCESS / FALLBACK / FAILED
    request_digest    VARCHAR(64),
    created_by        BIGINT,
    created_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_llm_task_provider ON llm_task (provider);
