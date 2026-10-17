ALTER TABLE dispatch_attempt ADD INDEX ix_attempt_order_round (order_id, round_no, started_at);
ALTER TABLE dispatch_attempt DROP INDEX uk_attempt_order_round;
ALTER TABLE dispatch_attempt ADD COLUMN policy_snapshot LONGTEXT NULL;

ALTER TABLE dispatch_offer ADD COLUMN policy_snapshot LONGTEXT NULL;

CREATE TABLE dispatch_policy_region (
    region_id VARCHAR(64) NOT NULL PRIMARY KEY,
    active_policy_id BINARY(16) NULL,
    next_version BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT fk_policy_region_active FOREIGN KEY (active_policy_id) REFERENCES dispatch_policy (id),
    CONSTRAINT ck_policy_region_version CHECK (next_version > 0)
);
