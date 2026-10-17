CREATE TABLE retention_cleanup_run (
    run_id BINARY(16) NOT NULL PRIMARY KEY,
    task_name VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    cutoff_at TIMESTAMP(3) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    cleaned_rows BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    started_at TIMESTAMP(3) NOT NULL,
    completed_at TIMESTAMP(3) NULL,
    INDEX ix_retention_task_started (task_name, started_at),
    CONSTRAINT ck_retention_status CHECK (status IN ('RUNNING', 'RETRYING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_retention_attempt CHECK (attempt_count >= 0),
    CONSTRAINT ck_retention_cleaned CHECK (cleaned_rows >= 0)
);
