ALTER TABLE agent_session ADD COLUMN role_scope LONGTEXT NULL;

ALTER TABLE agent_run ADD COLUMN client_request_key VARCHAR(128) NULL;
ALTER TABLE agent_run ADD COLUMN input_redacted LONGTEXT NULL;
ALTER TABLE agent_run ADD COLUMN input_json LONGTEXT NULL;
ALTER TABLE agent_run ADD COLUMN input_hash CHAR(64) NULL;
CREATE UNIQUE INDEX uk_agent_run_request ON agent_run (owner_id, client_request_key);

ALTER TABLE agent_run_event ADD COLUMN dedupe_key VARCHAR(128) NULL;
CREATE UNIQUE INDEX uk_agent_run_event_dedupe ON agent_run_event (run_id, dedupe_key);

ALTER TABLE agent_tool_call ADD COLUMN result_json LONGTEXT NULL;
CREATE INDEX ix_agent_action_run ON agent_action (run_id, status, created_at);

ALTER TABLE agent_run ADD CONSTRAINT ck_agent_run_status
    CHECK (status IN ('RUNNING', 'WAITING_CONFIRMATION', 'COMPLETED', 'FAILED', 'CANCELLED'));
ALTER TABLE agent_action ADD CONSTRAINT ck_agent_action_status
    CHECK (status IN ('PENDING_CONFIRMATION', 'CLAIMED', 'COMPLETED', 'FAILED', 'REJECTED', 'EXPIRED', 'INVALIDATED'));
