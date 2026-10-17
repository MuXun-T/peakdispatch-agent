ALTER TABLE courier
    ADD COLUMN heartbeat_sequence BIGINT NOT NULL DEFAULT 0;

ALTER TABLE courier ADD COLUMN latitude DECIMAL(9,6) NULL;

ALTER TABLE courier ADD COLUMN longitude DECIMAL(9,6) NULL;

ALTER TABLE courier
    ADD CONSTRAINT ck_courier_heartbeat_sequence CHECK (heartbeat_sequence >= 0);
