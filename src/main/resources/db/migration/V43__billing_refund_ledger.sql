-- Store verification/webhook adapters must be implemented before these internal ledgers grant live access.
CREATE TABLE billing_purchases (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    store VARCHAR(20) NOT NULL CHECK (store IN ('APPLE', 'GOOGLE')),
    environment VARCHAR(20) NOT NULL CHECK (environment IN ('SANDBOX', 'PRODUCTION')),
    transaction_key VARCHAR(200) NOT NULL,
    product VARCHAR(40) NOT NULL CHECK (product IN ('MONTHLY', 'YEARLY', 'DEEP_THREE_SESSIONS')),
    financial_status VARCHAR(30) NOT NULL CHECK (financial_status IN ('PAID', 'PARTIALLY_REFUNDED', 'REFUNDED')),
    access_revoked BOOLEAN NOT NULL,
    access_starts_at TIMESTAMPTZ,
    access_expires_at TIMESTAMPTZ,
    auto_renew BOOLEAN NOT NULL,
    entitled_units INTEGER NOT NULL CHECK (entitled_units BETWEEN 0 AND 3),
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_billing_purchase_store_transaction UNIQUE (store, environment, transaction_key),
    CONSTRAINT ck_billing_purchase_revoked CHECK (NOT access_revoked OR entitled_units = 0),
    CONSTRAINT ck_billing_purchase_shape CHECK (
        (product = 'DEEP_THREE_SESSIONS' AND access_starts_at IS NULL AND access_expires_at IS NULL AND NOT auto_renew)
        OR (product IN ('MONTHLY', 'YEARLY') AND entitled_units = 0 AND access_starts_at IS NOT NULL
            AND access_expires_at IS NOT NULL AND access_expires_at > access_starts_at)
    )
);
CREATE INDEX ix_billing_purchase_user ON billing_purchases(user_id, environment, created_at, id);
CREATE INDEX ix_billing_purchase_expiry ON billing_purchases(access_expires_at) WHERE access_revoked = false;

CREATE TABLE billing_purchase_events (
    id UUID PRIMARY KEY,
    purchase_id UUID NOT NULL REFERENCES billing_purchases(id),
    event_key VARCHAR(200) NOT NULL,
    snapshot_hash VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    financial_status VARCHAR(30) NOT NULL CHECK (financial_status IN ('PAID', 'PARTIALLY_REFUNDED', 'REFUNDED')),
    access_revoked BOOLEAN NOT NULL,
    access_starts_at TIMESTAMPTZ,
    access_expires_at TIMESTAMPTZ,
    auto_renew BOOLEAN NOT NULL,
    entitled_units INTEGER NOT NULL CHECK (entitled_units BETWEEN 0 AND 3),
    result VARCHAR(20) NOT NULL CHECK (result IN ('APPLIED', 'STALE')),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_billing_event_purchase UNIQUE (purchase_id, event_key)
);

CREATE TABLE billing_credit_reservations (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    session_id UUID NOT NULL,
    purchase_id UUID NOT NULL REFERENCES billing_purchases(id),
    status VARCHAR(20) NOT NULL CHECK (status IN ('RESERVED', 'COMPLETED', 'RELEASED')),
    release_reason VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_billing_credit_user_session UNIQUE (user_id, session_id)
);
CREATE INDEX ix_billing_credit_purchase_status ON billing_credit_reservations(purchase_id, status);
