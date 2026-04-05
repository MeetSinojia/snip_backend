-- V2__add_click_tracking.sql
-- Click analytics persistence (Redis is source of truth for real-time, this is for historical)

CREATE TABLE click_events (
    id          BIGSERIAL PRIMARY KEY,
    short_code  VARCHAR(10)  NOT NULL,
    clicked_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    ip_address  VARCHAR(45),
    user_agent  TEXT,

    CONSTRAINT fk_click_short_code
        FOREIGN KEY (short_code)
        REFERENCES urls (short_code)
        ON DELETE CASCADE
);

-- Index for analytics queries by short code
CREATE INDEX idx_click_events_short_code ON click_events (short_code);
CREATE INDEX idx_click_events_clicked_at ON click_events (clicked_at);
