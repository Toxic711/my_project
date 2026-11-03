-- =====================================================================
-- 智能在线考试系统 · MySQL 8.0 生产建表脚本（本 demo 不执行）
-- 与 db/schema-h2.sql 字段语义保持一致；差异：
--   1) InnoDB + utf8mb4，显式字符集，避免 emoji/中文乱码；
--   2) 不使用 DROP，由 Flyway/Liquibase 管理版本演进；
--   3) DATETIME 精度到毫秒，便于事件时间排序；
--   4) 生产建议把 evidence_file.rel_path 指向对象存储 key，并把
--      proctor_event 按月分区/归档（高频表）。
-- 执行：mysql -uroot -p123456 exam_ai < db/schema-mysql.sql
-- =====================================================================

CREATE DATABASE IF NOT EXISTS exam_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE exam_ai;

CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(256) NOT NULL,
    real_name     VARCHAR(64)  DEFAULT NULL,
    role          VARCHAR(16)  NOT NULL COMMENT 'ADMIN/TEACHER/STUDENT',
    enabled       TINYINT      NOT NULL DEFAULT 1,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表（角色合并为 role 列）';

CREATE TABLE IF NOT EXISTS exam (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    title            VARCHAR(128) NOT NULL,
    description      VARCHAR(512) DEFAULT NULL,
    question_count   INT          NOT NULL DEFAULT 0,
    duration_minutes INT          NOT NULL DEFAULT 60,
    total_score      INT          NOT NULL DEFAULT 100,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PUBLISHED',
    created_by       BIGINT       NOT NULL,
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_exam_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '考试表';

CREATE TABLE IF NOT EXISTS question (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    exam_id         BIGINT        DEFAULT NULL COMMENT 'NULL=题库公共题',
    question_type   VARCHAR(16)   NOT NULL,
    stem            VARCHAR(1024) NOT NULL,
    knowledge_point VARCHAR(64)   DEFAULT NULL,
    difficulty      VARCHAR(16)   DEFAULT NULL,
    score           INT           NOT NULL DEFAULT 5,
    correct_option  VARCHAR(16)   NOT NULL,
    analysis        VARCHAR(1024) DEFAULT NULL,
    source          VARCHAR(16)   NOT NULL DEFAULT 'BANK',
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_question_exam (exam_id),
    KEY idx_question_kp (knowledge_point)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '题目表';

CREATE TABLE IF NOT EXISTS question_option (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    question_id BIGINT       NOT NULL,
    option_key  VARCHAR(8)   NOT NULL,
    content     VARCHAR(512) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_question_option (question_id, option_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '选项表';

CREATE TABLE IF NOT EXISTS exam_record (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    exam_id           BIGINT      NOT NULL,
    student_id        BIGINT      NOT NULL,
    status            VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS',
    score             INT         DEFAULT NULL,
    started_at        DATETIME(3) NOT NULL,
    deadline_at       DATETIME(3) DEFAULT NULL,
    submitted_at      DATETIME(3) DEFAULT NULL,
    submit_request_id VARCHAR(64) DEFAULT NULL,
    last_event_at     DATETIME(3) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_exam_record (exam_id, student_id),
    KEY idx_exam_record_student (student_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '考试记录表';

CREATE TABLE IF NOT EXISTS answer (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    exam_record_id BIGINT      NOT NULL,
    question_id    BIGINT      NOT NULL,
    answer_value   VARCHAR(32) DEFAULT NULL,
    correct_flag   TINYINT     NOT NULL DEFAULT 0,
    score          INT         NOT NULL DEFAULT 0,
    answered_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_answer_record_question (exam_record_id, question_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '作答明细表（唯一键保证交卷幂等）';

CREATE TABLE IF NOT EXISTS proctor_event (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    exam_record_id   BIGINT      NOT NULL,
    session_id       VARCHAR(64) DEFAULT NULL,
    face_detected    TINYINT     NOT NULL DEFAULT 1,
    face_count       INT         NOT NULL DEFAULT 1,
    yaw              DOUBLE      DEFAULT NULL,
    pitch            DOUBLE      DEFAULT NULL,
    roll             DOUBLE      DEFAULT NULL,
    gaze_off_screen  TINYINT     NOT NULL DEFAULT 0,
    ear              DOUBLE      DEFAULT NULL,
    risk_score       INT         NOT NULL DEFAULT 0,
    risk_level       VARCHAR(8)  DEFAULT NULL,
    event_ts         BIGINT      NOT NULL COMMENT '客户端毫秒时间戳',
    evidence_file_id BIGINT      DEFAULT NULL,
    created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_proctor_event_record_ts (exam_record_id, event_ts)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '防作弊原始事件表（高频，建议按月归档）';

CREATE TABLE IF NOT EXISTS proctor_alert (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    exam_record_id   BIGINT      NOT NULL,
    alert_level      VARCHAR(8)  NOT NULL,
    risk_score       INT         NOT NULL,
    reason           VARCHAR(256) DEFAULT NULL,
    evidence_file_id BIGINT      DEFAULT NULL,
    created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_proctor_alert_record (exam_record_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '防作弊告警表';

CREATE TABLE IF NOT EXISTS evidence_file (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    exam_record_id BIGINT       NOT NULL,
    rel_path       VARCHAR(256) NOT NULL,
    sha256         VARCHAR(64)  NOT NULL,
    size_bytes     BIGINT       NOT NULL DEFAULT 0,
    mime_type      VARCHAR(32)  NOT NULL DEFAULT 'image/png',
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_evidence_record (exam_record_id),
    KEY idx_evidence_sha256 (sha256)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '截图存证表（只存相对路径+摘要）';

CREATE TABLE IF NOT EXISTS llm_task (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    provider          VARCHAR(32)  NOT NULL,
    model             VARCHAR(64)  DEFAULT NULL,
    question_type     VARCHAR(16)  DEFAULT NULL,
    knowledge_point   VARCHAR(64)  DEFAULT NULL,
    question_count    INT          NOT NULL DEFAULT 0,
    prompt_tokens     INT          NOT NULL DEFAULT 0,
    completion_tokens INT          NOT NULL DEFAULT 0,
    total_tokens      INT          NOT NULL DEFAULT 0,
    latency_ms        BIGINT       NOT NULL DEFAULT 0,
    status            VARCHAR(16)  NOT NULL,
    request_digest    VARCHAR(64)  DEFAULT NULL,
    created_by        BIGINT       DEFAULT NULL,
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_llm_task_provider (provider),
    KEY idx_llm_task_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'LLM 出题任务与 token 统计表';
