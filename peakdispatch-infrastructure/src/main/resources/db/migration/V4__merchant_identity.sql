ALTER TABLE merchant ADD COLUMN username VARCHAR(64) NULL;
CREATE UNIQUE INDEX uk_merchant_username ON merchant (username);
