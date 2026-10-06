CREATE TABLE scripture_copies (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    version VARCHAR(40) NOT NULL,
    book_code VARCHAR(40) NOT NULL,
    book_name VARCHAR(100) NOT NULL,
    chapter INTEGER NOT NULL CHECK (chapter > 0),
    verse INTEGER NOT NULL CHECK (verse > 0),
    source_text TEXT NOT NULL,
    typed_text TEXT NOT NULL DEFAULT '',
    reflection TEXT NOT NULL DEFAULT '',
    revision BIGINT NOT NULL CHECK (revision >= 1),
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT', 'COMPLETED')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CHECK (length(typed_text) <= 10000),
    CHECK (length(reflection) <= 1000),
    CHECK ((status = 'DRAFT' AND completed_at IS NULL) OR
           (status = 'COMPLETED' AND completed_at IS NOT NULL))
);

CREATE INDEX idx_scripture_copies_user_updated
    ON scripture_copies(user_id, updated_at DESC, id DESC);
