ALTER TABLE inbox_message
    ADD COLUMN dead_lettered_at TIMESTAMP(3) NULL;

ALTER TABLE inbox_message
    ADD COLUMN dlq_stream_id VARCHAR(64) NULL;

ALTER TABLE inbox_message DROP CONSTRAINT ck_inbox_status;
ALTER TABLE inbox_message ADD CONSTRAINT ck_inbox_status
    CHECK (status IN ('PROCESSING', 'PROCESSED', 'DEAD_LETTER'));
