-- 0.2.0 beta consistency metadata. V37-V40 are immutable.

ALTER TABLE data_import_job
    ADD COLUMN error_details INT NOT NULL DEFAULT 0 AFTER error_rows;

UPDATE data_import_job
SET error_details = error_rows
WHERE error_details = 0 AND error_rows > 0;

ALTER TABLE payment_record
    ADD COLUMN request_id VARCHAR(160) NULL AFTER payment_no,
    ADD COLUMN created_by VARCHAR(50) NULL AFTER reversal_of_payment_id;

UPDATE payment_record
SET request_id = CONCAT('LEGACY-PAYMENT:', id)
WHERE request_id IS NULL OR request_id = '';

ALTER TABLE payment_record
    MODIFY COLUMN request_id VARCHAR(160) NOT NULL,
    ADD UNIQUE KEY uk_payment_record_request_id (request_id),
    ADD UNIQUE KEY uk_payment_record_reversal (reversal_of_payment_id);

ALTER TABLE financial_event
    ADD COLUMN created_by VARCHAR(50) NULL AFTER reversal_of_event_id;

ALTER TABLE part_inventory
    ADD COLUMN reorder_point INT NOT NULL DEFAULT 5 AFTER quantity;

UPDATE part_inventory
SET reorder_point = 5
WHERE reorder_point IS NULL OR reorder_point < 0;

ALTER TABLE part_inventory
    ADD CONSTRAINT chk_part_inventory_reorder_point CHECK (reorder_point >= 0);

CREATE INDEX idx_part_inventory_reorder_point
    ON part_inventory (is_locked, quantity, reorder_point, updated_at);

ALTER TABLE migration_exception
    ADD COLUMN resolved_by VARCHAR(50) NULL AFTER resolved_at;
