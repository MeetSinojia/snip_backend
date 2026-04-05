-- V1__create_urls_table.sql
-- Initial schema: URL mapping table

CREATE TABLE urls (
    id           BIGSERIAL PRIMARY KEY,
    original_url TEXT         NOT NULL,
    short_code   VARCHAR(10)  NOT NULL UNIQUE,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMP,
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE
);

-- Index for fast lookup by short code (primary query path)
CREATE UNIQUE INDEX idx_urls_short_code ON urls (short_code);

-- Index for cleanup job querying expired URLs
CREATE INDEX idx_urls_expires_at ON urls (expires_at) WHERE expires_at IS NOT NULL;
