-- Control-plane schema (§12). api_key is plaintext until step 7 adds AES-GCM at rest.

CREATE TABLE pools (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       TEXT NOT NULL UNIQUE,
    strategy   TEXT NOT NULL DEFAULT 'p2c'
               CHECK (strategy IN ('p2c', 'least_conn', 'round_robin'))
);

CREATE TABLE providers (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                   TEXT NOT NULL UNIQUE,
    type                   TEXT NOT NULL,
    base_url               TEXT,
    api_key                TEXT,
    stream_idle_timeout_ms BIGINT,
    pool_id                UUID REFERENCES pools (id),
    status                 TEXT NOT NULL DEFAULT 'active'
                           CHECK (status IN ('active', 'disabled')),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE aliases (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    alias              TEXT NOT NULL UNIQUE,
    target_provider_id UUID NOT NULL REFERENCES providers (id),
    target_model       TEXT NOT NULL,
    fallback_chain     JSONB
);

CREATE TABLE api_keys (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key_hash       TEXT NOT NULL UNIQUE,
    name           TEXT NOT NULL,
    rate_limit_rpm INTEGER,
    spend_cap_usd  NUMERIC(12, 4),
    log_content    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at   TIMESTAMPTZ
);

CREATE TABLE request_logs (
    id                UUID NOT NULL DEFAULT gen_random_uuid(),
    api_key_id        UUID,
    provider_id       UUID,
    model             TEXT,
    ts                TIMESTAMPTZ NOT NULL DEFAULT now(),
    latency_ms        BIGINT,
    ttft_ms           BIGINT,
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    outcome           TEXT,
    error_code        TEXT,
    PRIMARY KEY (id, ts)
) PARTITION BY RANGE (ts);

-- Daily partition management arrives with the telemetry sink; until then the
-- default partition guarantees inserts never fail.
CREATE TABLE request_logs_default PARTITION OF request_logs DEFAULT;

CREATE INDEX idx_request_logs_ts ON request_logs (ts);
CREATE INDEX idx_aliases_target_provider ON aliases (target_provider_id);
