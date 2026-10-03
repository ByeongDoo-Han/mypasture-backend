ALTER TABLE billing_purchases ADD COLUMN source_key VARCHAR(64);
CREATE INDEX ix_billing_purchase_source ON billing_purchases(store, environment, source_key);
CREATE TABLE billing_accounts (
    user_id UUID PRIMARY KEY REFERENCES users(id),
    account_token UUID NOT NULL UNIQUE,
    revision BIGINT NOT NULL DEFAULT 0,
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ
);
CREATE TABLE billing_verification_jobs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    request_id UUID NOT NULL,
    store VARCHAR(20) NOT NULL CHECK (store IN ('APPLE', 'GOOGLE')),
    environment VARCHAR(20) NOT NULL CHECK (environment IN ('SANDBOX', 'PRODUCTION')),
    product_id VARCHAR(200) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    encrypted_proof TEXT NOT NULL,
    phase VARCHAR(30) NOT NULL CHECK (phase IN ('VERIFY', 'ACKNOWLEDGE', 'COMPLETE', 'REJECTED', 'DEAD_LETTER')),
    attempts INTEGER NOT NULL DEFAULT 0,
    recurring BOOLEAN NOT NULL DEFAULT false,
    refresh_enabled BOOLEAN NOT NULL DEFAULT false,
    last_error VARCHAR(80),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(user_id, request_id)
);
CREATE INDEX ix_billing_job_due ON billing_verification_jobs(phase, next_attempt_at);
CREATE INDEX ix_billing_job_user_created ON billing_verification_jobs(user_id, created_at);
CREATE INDEX ix_billing_job_refresh ON billing_verification_jobs(user_id, request_hash, refresh_enabled);
CREATE TABLE billing_sources (
    id VARCHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    superseded_by VARCHAR(64)
);
