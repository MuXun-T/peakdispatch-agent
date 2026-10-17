ALTER TABLE inbox_message
    ADD COLUMN last_attempt_at TIMESTAMP(3) NULL;

CREATE INDEX ix_inbox_recovery
    ON inbox_message (consumer_name, status, claim_until, processed_at);

CREATE TABLE redis_projection_reconciliation (
    id BINARY(16) NOT NULL PRIMARY KEY,
    courier_id BINARY(16) NOT NULL,
    presence_generation BIGINT NOT NULL,
    capacity_version BIGINT NOT NULL,
    db_committed INTEGER NOT NULL,
    db_reserved INTEGER NOT NULL,
    db_limit INTEGER NOT NULL,
    redis_committed INTEGER NULL,
    redis_reserved INTEGER NULL,
    redis_limit INTEGER NULL,
    action VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    INDEX ix_reconciliation_courier (courier_id, created_at),
    INDEX ix_reconciliation_created (created_at),
    CONSTRAINT fk_reconciliation_courier FOREIGN KEY (courier_id) REFERENCES courier (id),
    CONSTRAINT ck_reconciliation_action CHECK (action IN ('CAPACITY_REBUILT', 'PRESENCE_REBUILT'))
);
