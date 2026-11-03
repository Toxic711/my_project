-- DevFlow 建表脚本（H2 PostgreSQL 兼容模式 与 真实 PostgreSQL 共用）
-- 约定：不使用 AUTO_INCREMENT / TINYINT / CLOB；
--       主键统一 BIGSERIAL，小整数用 SMALLINT，大文本用 TEXT。
--       这样同一份 DDL 在 demo(H2) 与 pg(PostgreSQL) 两个 profile 下都能执行。

-- 探针表：验证持久层装配（保留自骨架）
CREATE TABLE IF NOT EXISTS probe (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(64)
);

-- Prompt 模板：版本化，唯一键 (code, version) 由数据库保证并发安全
CREATE TABLE IF NOT EXISTS prompt_template (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    content     TEXT         NOT NULL,
    variables   VARCHAR(512),
    version     INT          NOT NULL DEFAULT 1,
    enabled     SMALLINT     NOT NULL DEFAULT 1,
    deleted     SMALLINT     NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_prompt_code_version UNIQUE (code, version)
);

-- LLM 用量：append-only，每次调用一行
CREATE TABLE IF NOT EXISTS llm_usage (
    id                BIGSERIAL PRIMARY KEY,
    request_id        VARCHAR(64)  NOT NULL,
    feature           VARCHAR(64),
    provider          VARCHAR(32),
    model             VARCHAR(64),
    prompt_tokens     INT          NOT NULL DEFAULT 0,
    completion_tokens INT          NOT NULL DEFAULT 0,
    total_tokens      INT          NOT NULL DEFAULT 0,
    cost_usd          NUMERIC(14, 6) NOT NULL DEFAULT 0,
    latency_ms        INT,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_llm_usage_created ON llm_usage (created_at);
CREATE INDEX IF NOT EXISTS idx_llm_usage_feature ON llm_usage (feature);

-- 代码审查运行记录
CREATE TABLE IF NOT EXISTS review_run (
    id             BIGSERIAL PRIMARY KEY,
    delivery_id    VARCHAR(128),
    source         VARCHAR(32)  NOT NULL,
    repo           VARCHAR(256),
    pr_number      INT,
    status         VARCHAR(32)  NOT NULL,
    files_changed  INT          NOT NULL DEFAULT 0,
    add_lines      INT          NOT NULL DEFAULT 0,
    findings_count INT          NOT NULL DEFAULT 0,
    high_count     INT          NOT NULL DEFAULT 0,
    medium_count   INT          NOT NULL DEFAULT 0,
    low_count      INT          NOT NULL DEFAULT 0,
    truncated      SMALLINT     NOT NULL DEFAULT 0,
    diff_chars     INT          NOT NULL DEFAULT 0,
    segments       INT          NOT NULL DEFAULT 0,
    duration_ms    INT          NOT NULL DEFAULT 0,
    findings_json  TEXT,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- GitHub webhook 投递记录：delivery_id 主键 = 幂等键
CREATE TABLE IF NOT EXISTS webhook_delivery (
    delivery_id VARCHAR(128) PRIMARY KEY,
    event_type  VARCHAR(64),
    action      VARCHAR(64),
    status      VARCHAR(32) NOT NULL,
    run_id      BIGINT,
    received_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============ 演示业务表：供"自然语言生成 SQL"演示查询 ============
CREATE TABLE IF NOT EXISTS users (
    id         BIGSERIAL PRIMARY KEY,
    username   VARCHAR(64)  NOT NULL,
    email      VARCHAR(128),
    status     SMALLINT     NOT NULL DEFAULT 1,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS orders (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    amount     NUMERIC(12, 2) NOT NULL DEFAULT 0,
    status     VARCHAR(32)  NOT NULL DEFAULT 'PAID',
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS products (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(128) NOT NULL,
    price      NUMERIC(12, 2) NOT NULL DEFAULT 0,
    stock      INT          NOT NULL DEFAULT 0,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
