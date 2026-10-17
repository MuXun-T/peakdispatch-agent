ALTER TABLE dispatch_attempt ADD COLUMN offer_id BINARY(16) NULL;
ALTER TABLE dispatch_attempt ADD CONSTRAINT fk_attempt_offer
    FOREIGN KEY (offer_id) REFERENCES dispatch_offer (id);
CREATE INDEX ix_attempt_offer ON dispatch_attempt (offer_id, outcome);
