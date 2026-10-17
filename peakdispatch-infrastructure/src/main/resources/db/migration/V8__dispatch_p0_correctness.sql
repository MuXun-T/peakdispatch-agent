ALTER TABLE delivery_order DROP CONSTRAINT ck_order_status;
ALTER TABLE delivery_order ADD CONSTRAINT ck_order_status
    CHECK (status IN ('CREATED', 'WAITING_DISPATCH', 'OFFERED', 'ACCEPTED', 'PICKED_UP', 'DELIVERED', 'CANCELLED', 'OPS_ESCALATED'));

ALTER TABLE dispatch_attempt ADD COLUMN policy_id BINARY(16) NULL;
ALTER TABLE dispatch_offer ADD COLUMN invalidation_reason VARCHAR(64) NULL;
CREATE INDEX ix_offer_courier_generation ON dispatch_offer(courier_id, courier_presence_generation, status);
