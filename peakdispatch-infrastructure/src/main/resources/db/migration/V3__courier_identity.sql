ALTER TABLE courier ADD COLUMN username VARCHAR(64) NULL;
CREATE UNIQUE INDEX uk_courier_username ON courier (username);
