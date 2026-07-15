-- Separate the removed-part valuation from the replacement-part FIFO cost,
-- and retain an immutable trail when pre-sale modification cost is added to
-- a serialized vehicle's open FIFO layer.

ALTER TABLE modification_work_order_line
    ADD COLUMN old_part_unit_cost DECIMAL(12, 2) NULL AFTER old_part_valuation_source;

CREATE TABLE stock_lot_cost_adjustment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    stock_lot_id BIGINT NOT NULL,
    amount DECIMAL(14, 2) NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    source_id BIGINT NULL,
    source_line_id BIGINT NULL,
    business_date DATE NOT NULL,
    idempotency_key VARCHAR(160) NULL,
    created_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_stock_lot_cost_adjustment_idempotency (idempotency_key),
    KEY idx_stock_lot_cost_adjustment_lot (stock_lot_id),
    KEY idx_stock_lot_cost_adjustment_source (source_type, source_id, source_line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
