CREATE TABLE dispatch_runtime_gate (
    gate_name VARCHAR(64) NOT NULL PRIMARY KEY,
    ready BOOLEAN NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(3) NOT NULL
);

INSERT INTO dispatch_runtime_gate (gate_name, ready, version, updated_at)
VALUES ('redis-projection', TRUE, 0, CURRENT_TIMESTAMP(3));
