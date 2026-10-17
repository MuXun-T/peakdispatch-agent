-- Lets claims walk eligible rows in publish order without sorting the whole Outbox table.
CREATE INDEX ix_outbox_claim_queue
    ON outbox_event (published_at, failed_at, created_at, event_id, next_attempt_at, claim_until);
