-- Optimistic locking must never operate on NULL database versions. JDBC
-- imports and JSON restore paths bypass entity lifecycle callbacks, so enforce
-- the invariant at the storage boundary as well as in JPA.
UPDATE rental_record SET version = 0 WHERE version IS NULL;
UPDATE resource_attachment SET version = 0 WHERE version IS NULL;
UPDATE data_import_job SET version = 0 WHERE version IS NULL;
UPDATE vehicle_config_item SET version = 0 WHERE version IS NULL;
UPDATE vehicle_config_value SET version = 0 WHERE version IS NULL;

ALTER TABLE rental_record
    MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE resource_attachment
    MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE data_import_job
    MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE vehicle_config_item
    MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE vehicle_config_value
    MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Technical identifiers are byte strings, not natural-language text. A
-- case/accent-insensitive collation can collapse two distinct requests and,
-- when tables use different collations, can make an atomic claim impossible to
-- find afterward. Use one binary collation across every authoritative key.
ALTER TABLE stock_movement
    MODIFY COLUMN idempotency_key VARCHAR(160)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL;
ALTER TABLE stock_lot
    MODIFY COLUMN idempotency_key VARCHAR(160)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL;
ALTER TABLE stock_lot_consumption
    MODIFY COLUMN idempotency_key VARCHAR(160)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL;
ALTER TABLE stock_lot_cost_adjustment
    MODIFY COLUMN idempotency_key VARCHAR(160)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL;
ALTER TABLE financial_event
    MODIFY COLUMN idempotency_key VARCHAR(160)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL;
ALTER TABLE payment_record
    MODIFY COLUMN request_id VARCHAR(160)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY COLUMN idempotency_key VARCHAR(160)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL;
ALTER TABLE data_import_row
    MODIFY COLUMN idempotency_key VARCHAR(320)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY COLUMN business_key VARCHAR(240)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE request_idempotency
    MODIFY COLUMN scope VARCHAR(50)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY COLUMN request_id VARCHAR(120)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;
