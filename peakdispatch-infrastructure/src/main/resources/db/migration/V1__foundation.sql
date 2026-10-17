CREATE TABLE app_user (
    id BINARY(16) NOT NULL PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_app_user_username UNIQUE (username),
    CONSTRAINT ck_app_user_version CHECK (version >= 0)
);

CREATE TABLE merchant (
    id BINARY(16) NOT NULL PRIMARY KEY,
    region_id VARCHAR(64) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    prep_state VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    version BIGINT NOT NULL DEFAULT 0,
    avg_prep_seconds INTEGER NOT NULL DEFAULT 900,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE delivery_order (
    id BINARY(16) NOT NULL PRIMARY KEY,
    merchant_id BINARY(16) NOT NULL,
    region_id VARCHAR(64) NOT NULL,
    pickup_lat DECIMAL(9,6) NOT NULL,
    pickup_lon DECIMAL(9,6) NOT NULL,
    dropoff_lat DECIMAL(9,6) NOT NULL,
    dropoff_lon DECIMAL(9,6) NOT NULL,
    prep_eta TIMESTAMP(3) NOT NULL,
    deadline TIMESTAMP(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    dispatch_round INTEGER NOT NULL DEFAULT 0,
    next_dispatch_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT fk_delivery_order_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (id),
    INDEX ix_order_region_status (region_id, status, next_dispatch_at),
    INDEX ix_order_merchant_status (merchant_id, status),
    CONSTRAINT ck_order_version CHECK (version >= 0),
    CONSTRAINT ck_order_dates CHECK (prep_eta < deadline),
    CONSTRAINT ck_order_status CHECK (status IN ('CREATED', 'WAITING_DISPATCH', 'OFFERED', 'ACCEPTED', 'PICKED_UP', 'DELIVERED', 'CANCELLED'))
);

CREATE TABLE courier (
    id BINARY(16) NOT NULL PRIMARY KEY,
    region_id VARCHAR(64) NULL,
    online_state VARCHAR(16) NOT NULL DEFAULT 'OFFLINE',
    presence_generation BIGINT NOT NULL DEFAULT 0,
    capacity_limit INTEGER NOT NULL DEFAULT 3,
    version BIGINT NOT NULL DEFAULT 0,
    last_position_at TIMESTAMP(3) NULL,
    INDEX ix_courier_region_online (region_id, online_state),
    CONSTRAINT ck_courier_presence_generation CHECK (presence_generation >= 0),
    CONSTRAINT ck_courier_capacity CHECK (capacity_limit > 0),
    CONSTRAINT ck_courier_online_state CHECK (online_state IN ('ONLINE', 'OFFLINE'))
);

CREATE TABLE courier_capacity (
    courier_id BINARY(16) NOT NULL PRIMARY KEY,
    capacity_limit INTEGER NOT NULL DEFAULT 3,
    committed_count INTEGER NOT NULL DEFAULT 0,
    reserved_count INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_capacity_courier FOREIGN KEY (courier_id) REFERENCES courier (id),
    CONSTRAINT ck_capacity_counts CHECK (committed_count >= 0 AND reserved_count >= 0),
    CONSTRAINT ck_capacity_within_limit CHECK (committed_count + reserved_count <= capacity_limit)
);

CREATE TABLE order_dispatch_slot (
    order_id BINARY(16) NOT NULL PRIMARY KEY,
    phase VARCHAR(16) NOT NULL DEFAULT 'IDLE',
    offer_id BINARY(16) NULL,
    courier_id BINARY(16) NULL,
    slot_version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_slot_order FOREIGN KEY (order_id) REFERENCES delivery_order (id),
    CONSTRAINT ck_slot_phase CHECK (phase IN ('IDLE', 'OFFERED', 'ASSIGNED', 'CLOSED'))
);

CREATE TABLE dispatch_offer (
    id BINARY(16) NOT NULL PRIMARY KEY,
    order_id BINARY(16) NOT NULL,
    courier_id BINARY(16) NOT NULL,
    courier_presence_generation BIGINT NOT NULL,
    round_no INTEGER NOT NULL,
    token_hash CHAR(64) NOT NULL,
    token_ciphertext LONGTEXT NULL,
    status VARCHAR(16) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    order_version BIGINT NOT NULL,
    policy_id BINARY(16) NULL,
    policy_version BIGINT NULL,
    estimator_version VARCHAR(64) NOT NULL DEFAULT 'demo-haversine-v1',
    score_detail LONGTEXT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT uk_offer_order_round UNIQUE (order_id, round_no),
    CONSTRAINT uk_offer_token_hash UNIQUE (token_hash),
    INDEX ix_offer_status_expiry (status, expires_at),
    INDEX ix_offer_courier_status (courier_id, status, expires_at),
    CONSTRAINT fk_offer_order FOREIGN KEY (order_id) REFERENCES delivery_order (id),
    CONSTRAINT fk_offer_courier FOREIGN KEY (courier_id) REFERENCES courier (id),
    CONSTRAINT ck_offer_status CHECK (status IN ('OFFERED', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'SUPERSEDED')),
    CONSTRAINT ck_offer_round CHECK (round_no > 0)
);

ALTER TABLE order_dispatch_slot
    ADD CONSTRAINT fk_slot_offer FOREIGN KEY (offer_id) REFERENCES dispatch_offer (id);

CREATE TABLE dispatch_assignment (
    id BINARY(16) NOT NULL PRIMARY KEY,
    order_id BINARY(16) NOT NULL,
    courier_id BINARY(16) NOT NULL,
    offer_id BINARY(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    accepted_at TIMESTAMP(3) NULL,
    released_at TIMESTAMP(3) NULL,
    release_reason VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_assignment_offer UNIQUE (offer_id),
    INDEX ix_assignment_courier_status (courier_id, status),
    CONSTRAINT fk_assignment_order FOREIGN KEY (order_id) REFERENCES delivery_order (id),
    CONSTRAINT fk_assignment_courier FOREIGN KEY (courier_id) REFERENCES courier (id),
    CONSTRAINT fk_assignment_offer FOREIGN KEY (offer_id) REFERENCES dispatch_offer (id)
);

CREATE TABLE dispatch_attempt (
    id BINARY(16) NOT NULL PRIMARY KEY,
    order_id BINARY(16) NOT NULL,
    round_no INTEGER NOT NULL,
    order_version BIGINT NOT NULL,
    policy_version BIGINT NULL,
    estimator_version VARCHAR(64) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    candidate_count INTEGER NOT NULL DEFAULT 0,
    selected_courier_id BINARY(16) NULL,
    exclusion_summary LONGTEXT NULL,
    started_at TIMESTAMP(3) NOT NULL,
    finished_at TIMESTAMP(3) NULL,
    trace_id VARCHAR(64) NULL,
    CONSTRAINT uk_attempt_order_round UNIQUE (order_id, round_no),
    CONSTRAINT fk_attempt_order FOREIGN KEY (order_id) REFERENCES delivery_order (id),
    CONSTRAINT ck_attempt_round CHECK (round_no > 0)
);

CREATE TABLE dispatch_candidate_decision (
    attempt_id BINARY(16) NOT NULL,
    courier_id BINARY(16) NOT NULL,
    eligible BOOLEAN NOT NULL,
    reason_code VARCHAR(64) NULL,
    rank_no INTEGER NULL,
    score DECIMAL(18,6) NULL,
    feature_snapshot LONGTEXT NULL,
    PRIMARY KEY (attempt_id, courier_id),
    CONSTRAINT fk_candidate_attempt FOREIGN KEY (attempt_id) REFERENCES dispatch_attempt (id),
    CONSTRAINT fk_candidate_courier FOREIGN KEY (courier_id) REFERENCES courier (id)
);

CREATE TABLE delivery_event (
    event_id BINARY(16) NOT NULL PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id BINARY(16) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    actor_type VARCHAR(32) NULL,
    actor_id VARCHAR(128) NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    trace_id VARCHAR(64) NULL,
    payload_json LONGTEXT NOT NULL,
    INDEX ix_delivery_event_aggregate (aggregate_type, aggregate_id, aggregate_version, occurred_at)
);

CREATE TABLE app_user_role (
    user_id BINARY(16) NOT NULL,
    role VARCHAR(32) NOT NULL,
    PRIMARY KEY (user_id, role),
    CONSTRAINT fk_app_user_role_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);

CREATE TABLE api_idempotency (
    scope VARCHAR(255) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    response_status INTEGER NULL,
    response_content_type VARCHAR(255) NULL,
    response_headers LONGTEXT NULL,
    response_body LONGBLOB NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (scope, key_hash),
    INDEX ix_api_idempotency_expiry (expires_at),
    CONSTRAINT ck_api_idempotency_status CHECK (status IN ('PROCESSING', 'COMPLETED'))
);

CREATE TABLE outbox_event (
    event_id BINARY(16) NOT NULL PRIMARY KEY,
    topic VARCHAR(128) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id BINARY(16) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    dedupe_key VARCHAR(255) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at TIMESTAMP(3) NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    claim_owner VARCHAR(128) NULL,
    claim_until TIMESTAMP(3) NULL,
    CONSTRAINT uk_outbox_dedupe UNIQUE (dedupe_key),
    INDEX ix_outbox_publish (published_at, next_attempt_at, created_at),
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0)
);

CREATE TABLE inbox_message (
    consumer_name VARCHAR(128) NOT NULL,
    event_id BINARY(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    claim_owner VARCHAR(128) NULL,
    claim_until TIMESTAMP(3) NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    processed_at TIMESTAMP(3) NULL,
    last_error_code VARCHAR(64) NULL,
    PRIMARY KEY (consumer_name, event_id),
    INDEX ix_inbox_claim (status, claim_until),
    CONSTRAINT ck_inbox_status CHECK (status IN ('PROCESSING', 'PROCESSED')),
    CONSTRAINT ck_inbox_attempts CHECK (attempts >= 0)
);

CREATE TABLE audit_log (
    id BINARY(16) NOT NULL PRIMARY KEY,
    actor_id VARCHAR(128) NOT NULL,
    action VARCHAR(128) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id VARCHAR(128) NULL,
    decision VARCHAR(32) NOT NULL,
    before_version BIGINT NULL,
    after_version BIGINT NULL,
    trace_id VARCHAR(64) NULL,
    details_redacted LONGTEXT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX ix_audit_target (target_type, target_id, created_at),
    INDEX ix_audit_actor (actor_id, created_at)
);

CREATE TABLE dispatch_policy (
    id BINARY(16) NOT NULL PRIMARY KEY,
    region_id VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL,
    config_json LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    activated_at TIMESTAMP(3) NULL,
    CONSTRAINT uk_policy_region_version UNIQUE (region_id, version),
    CONSTRAINT ck_policy_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED'))
);

CREATE TABLE agent_session (
    id BINARY(16) NOT NULL PRIMARY KEY,
    owner_id VARCHAR(128) NOT NULL,
    region_scope LONGTEXT NOT NULL,
    summary_redacted LONGTEXT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    INDEX ix_agent_session_owner (owner_id, updated_at)
);

CREATE TABLE agent_run (
    id BINARY(16) NOT NULL PRIMARY KEY,
    session_id BINARY(16) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    intent VARCHAR(64) NULL,
    checkpoint_json LONGTEXT NULL,
    model_id VARCHAR(128) NULL,
    prompt_version VARCHAR(64) NULL,
    started_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    ended_at TIMESTAMP(3) NULL,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    token_usage_json LONGTEXT NULL,
    trace_id VARCHAR(64) NULL,
    INDEX ix_agent_run_session (session_id, started_at),
    INDEX ix_agent_run_status (status, updated_at),
    CONSTRAINT fk_agent_run_session FOREIGN KEY (session_id) REFERENCES agent_session (id),
    CONSTRAINT ck_agent_run_sequence CHECK (last_sequence >= 0)
);

CREATE TABLE agent_run_event (
    run_id BINARY(16) NOT NULL,
    sequence BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_redacted LONGTEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (run_id, sequence),
    CONSTRAINT ck_agent_event_sequence CHECK (sequence > 0)
);

CREATE TABLE agent_tool_call (
    id BINARY(16) NOT NULL PRIMARY KEY,
    run_id BINARY(16) NOT NULL,
    tool_call_key VARCHAR(128) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    args_hash CHAR(64) NOT NULL,
    result_ref VARCHAR(255) NULL,
    status VARCHAR(32) NOT NULL,
    duration_ms BIGINT NULL,
    error_code VARCHAR(64) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT uk_agent_tool_call_key UNIQUE (run_id, tool_call_key),
    CONSTRAINT fk_agent_tool_call_run FOREIGN KEY (run_id) REFERENCES agent_run (id)
);

CREATE TABLE agent_action (
    id BINARY(16) NOT NULL PRIMARY KEY,
    run_id BINARY(16) NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    target_id BINARY(16) NOT NULL,
    expected_version BIGINT NOT NULL,
    params_json LONGTEXT NOT NULL,
    summary_redacted LONGTEXT NOT NULL,
    params_hash CHAR(64) NOT NULL,
    summary_hash CHAR(64) NOT NULL,
    requested_by VARCHAR(128) NOT NULL,
    confirmed_by VARCHAR(128) NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    result_ref VARCHAR(255) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT uk_agent_action_idempotency UNIQUE (idempotency_key),
    INDEX ix_agent_action_expiry (status, expires_at),
    INDEX ix_agent_action_target (target_id, status),
    CONSTRAINT fk_agent_action_run FOREIGN KEY (run_id) REFERENCES agent_run (id)
);

CREATE TABLE knowledge_document (
    id BINARY(16) NOT NULL PRIMARY KEY,
    external_key VARCHAR(255) NOT NULL,
    title VARCHAR(512) NOT NULL,
    version BIGINT NOT NULL,
    source_hash CHAR(64) NOT NULL,
    region_scope LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    effective_at TIMESTAMP(3) NULL,
    indexed_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT uk_knowledge_document_version UNIQUE (external_key, version)
);
