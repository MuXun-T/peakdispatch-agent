ALTER TABLE outbox_event
    ADD COLUMN failed_at TIMESTAMP(3) NULL;

ALTER TABLE outbox_event
    ADD COLUMN last_error_code VARCHAR(64) NULL;

CREATE INDEX ix_outbox_claim
    ON outbox_event (published_at, failed_at, next_attempt_at, created_at);
