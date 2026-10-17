ALTER TABLE knowledge_document ADD COLUMN category VARCHAR(64) NOT NULL DEFAULT 'SOP';
ALTER TABLE knowledge_document ADD COLUMN section VARCHAR(512) NOT NULL DEFAULT '';
ALTER TABLE knowledge_document ADD COLUMN content LONGTEXT NULL;
ALTER TABLE knowledge_document ADD COLUMN submitted_by VARCHAR(128) NULL;
ALTER TABLE knowledge_document ADD COLUMN updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);

CREATE INDEX ix_knowledge_document_status ON knowledge_document (status, external_key, version);

CREATE TABLE knowledge_document_counter (
    external_key VARCHAR(255) NOT NULL PRIMARY KEY,
    next_version BIGINT NOT NULL,
    CONSTRAINT ck_knowledge_document_counter_version CHECK (next_version > 0)
);

INSERT INTO knowledge_document_counter (external_key, next_version)
SELECT external_key, MAX(version) + 1 FROM knowledge_document GROUP BY external_key;

CREATE TABLE knowledge_index_version (
    index_version BIGINT NOT NULL PRIMARY KEY,
    physical_index VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    activated_at TIMESTAMP(3) NULL,
    failure_code VARCHAR(64) NULL,
    CONSTRAINT uk_knowledge_physical_index UNIQUE (physical_index),
    CONSTRAINT ck_knowledge_index_status CHECK (status IN ('BUILDING', 'ACTIVE', 'RETIRED', 'FAILED')),
    CONSTRAINT ck_knowledge_chunk_count CHECK (chunk_count >= 0)
);

CREATE TABLE knowledge_index_counter (
    counter_id TINYINT NOT NULL PRIMARY KEY,
    next_version BIGINT NOT NULL,
    CONSTRAINT ck_knowledge_index_counter_id CHECK (counter_id = 1),
    CONSTRAINT ck_knowledge_index_counter_version CHECK (next_version > 0)
);

INSERT INTO knowledge_index_counter (counter_id, next_version)
SELECT 1, COALESCE(MAX(index_version), 0) + 1 FROM knowledge_index_version;

CREATE TABLE operations_replay (
    id BINARY(16) NOT NULL PRIMARY KEY,
    region_id VARCHAR(64) NOT NULL,
    scenario_id VARCHAR(64) NOT NULL,
    seed BIGINT NOT NULL,
    policy_id BINARY(16) NOT NULL,
    policy_version BIGINT NOT NULL,
    config_snapshot LONGTEXT NOT NULL,
    result_json LONGTEXT NOT NULL,
    result_hash CHAR(64) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    INDEX ix_operations_replay_actor (created_by, created_at),
    CONSTRAINT fk_operations_replay_policy FOREIGN KEY (policy_id) REFERENCES dispatch_policy (id)
);
