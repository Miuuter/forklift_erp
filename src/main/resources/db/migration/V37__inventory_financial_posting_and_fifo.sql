-- Inventory and financial posting foundation.
-- Existing profile quantities remain compatibility caches; warehouse balances and posted movements are authoritative.

ALTER TABLE supplier_profile
    ADD COLUMN active BIT(1) NOT NULL DEFAULT b'1' AFTER supplier_type;

ALTER TABLE machine_inventory
    ADD COLUMN supplier_id BIGINT NULL AFTER supplier,
    ADD COLUMN supplier_name_snapshot VARCHAR(120) NULL AFTER supplier_id,
    ADD COLUMN landed_unit_cost DECIMAL(12, 2) NULL AFTER purchase_price;

ALTER TABLE part_inventory
    ADD COLUMN landed_unit_cost DECIMAL(12, 2) NULL AFTER purchase_price;

UPDATE machine_inventory m
LEFT JOIN supplier_profile s
    ON s.supplier_name COLLATE utf8mb4_unicode_ci =
       m.supplier COLLATE utf8mb4_unicode_ci
SET m.supplier_id = COALESCE(m.supplier_id, s.id),
    m.supplier_name_snapshot = COALESCE(m.supplier_name_snapshot, s.supplier_name, m.supplier)
WHERE m.supplier_id IS NULL OR m.supplier_name_snapshot IS NULL;

CREATE INDEX idx_machine_inventory_supplier ON machine_inventory (supplier_id);

ALTER TABLE stock_movement
    ADD COLUMN business_date DATE NULL AFTER source_id,
    ADD COLUMN business_type VARCHAR(50) NULL AFTER business_date,
    ADD COLUMN source_line_id BIGINT NULL AFTER source_id,
    ADD COLUMN idempotency_key VARCHAR(160) NULL AFTER remark,
    ADD COLUMN reversal_of_movement_id BIGINT NULL AFTER idempotency_key;

UPDATE stock_movement
SET business_date = DATE(created_at)
WHERE business_date IS NULL;

CREATE UNIQUE INDEX uk_stock_movement_idempotency ON stock_movement (idempotency_key);
CREATE INDEX idx_stock_movement_business_date ON stock_movement (business_date, business_type);

ALTER TABLE stock_movement_line
    ADD COLUMN unit_revenue DECIMAL(12, 2) NULL AFTER unit_cost,
    ADD COLUMN line_amount DECIMAL(14, 2) NULL AFTER unit_revenue,
    ADD COLUMN cost_amount DECIMAL(14, 2) NULL AFTER line_amount,
    ADD COLUMN stock_lot_id BIGINT NULL AFTER cost_amount,
    ADD COLUMN source_line_id BIGINT NULL AFTER stock_lot_id;

CREATE INDEX idx_stock_movement_line_stock_lot ON stock_movement_line (stock_lot_id);

ALTER TABLE outbound_order
    ADD COLUMN source_warehouse_id BIGINT NULL AFTER resource_id,
    ADD COLUMN unit_sale_price DECIMAL(12, 2) NULL AFTER settlement_price,
    ADD COLUMN line_amount DECIMAL(14, 2) NULL AFTER unit_sale_price,
    ADD COLUMN financial_posted BIT(1) NOT NULL DEFAULT b'0' AFTER stock_operation_log_id;

UPDATE outbound_order o
SET unit_sale_price = COALESCE(unit_sale_price, settlement_price, sale_price, 0),
    line_amount = COALESCE(line_amount, receivable_amount, settlement_price, sale_price, 0),
    source_warehouse_id = COALESCE(source_warehouse_id, (
        SELECT CASE
            WHEN o.resource_type = 'MACHINE' THEN (SELECT m.warehouse_id FROM machine_inventory m WHERE m.id = o.resource_id)
            WHEN o.resource_type = 'PART' THEN (SELECT p.warehouse_id FROM part_inventory p WHERE p.id = o.resource_id)
            ELSE NULL
        END
    ));

CREATE INDEX idx_outbound_order_warehouse ON outbound_order (source_warehouse_id);

ALTER TABLE purchase_order
    ADD COLUMN warehouse_id BIGINT NULL AFTER resource_type,
    ADD COLUMN resource_id BIGINT NULL AFTER warehouse_id,
    ADD COLUMN received_date DATE NULL AFTER expected_arrival_date,
    ADD COLUMN received_stock_movement_id BIGINT NULL AFTER received_date,
    ADD COLUMN stock_lot_id BIGINT NULL AFTER received_stock_movement_id,
    ADD COLUMN landed_unit_cost DECIMAL(12, 2) NULL AFTER freight_amount,
    ADD COLUMN financial_posted BIT(1) NOT NULL DEFAULT b'0' AFTER landed_unit_cost;

CREATE INDEX idx_purchase_order_warehouse ON purchase_order (warehouse_id);
CREATE INDEX idx_purchase_order_resource_id ON purchase_order (resource_type, resource_id);

ALTER TABLE repair_record
    ADD COLUMN pass_through_amount DECIMAL(12, 2) NOT NULL DEFAULT 0 AFTER repair_expense,
    ADD COLUMN receivable_amount DECIMAL(12, 2) NULL AFTER total_fee,
    ADD COLUMN financial_posted BIT(1) NOT NULL DEFAULT b'0' AFTER parts_cost;

UPDATE repair_record
SET receivable_amount = COALESCE(receivable_amount, repair_fee, 0) + COALESCE(parts_fee, 0)
WHERE receivable_amount IS NULL;

ALTER TABLE repair_part_usage
    ADD COLUMN warehouse_id BIGINT NULL AFTER quantity,
    ADD COLUMN unit_cost DECIMAL(12, 2) NULL AFTER unit_price,
    ADD COLUMN charge_unit_price DECIMAL(12, 2) NULL AFTER unit_cost,
    ADD COLUMN discount_amount DECIMAL(12, 2) NOT NULL DEFAULT 0 AFTER charge_unit_price,
    ADD COLUMN charge_amount DECIMAL(14, 2) NULL AFTER discount_amount,
    ADD COLUMN stock_lot_consumption_id BIGINT NULL AFTER stock_movement_id,
    ADD COLUMN remark VARCHAR(500) NULL AFTER stock_lot_consumption_id;

CREATE INDEX idx_repair_part_usage_warehouse ON repair_part_usage (warehouse_id);

ALTER TABLE rental_record
    ADD COLUMN warehouse_id BIGINT NULL AFTER machine_id,
    ADD COLUMN return_date DATE NULL AFTER end_date,
    ADD COLUMN financial_posted BIT(1) NOT NULL DEFAULT b'0' AFTER status;

ALTER TABLE stocktaking_record
    ADD COLUMN warehouse_id BIGINT NULL AFTER resource_id,
    ADD COLUMN book_balance_version BIGINT NULL AFTER book_quantity,
    ADD COLUMN snapshot_movement_id BIGINT NULL AFTER book_balance_version;

CREATE INDEX idx_stocktaking_warehouse ON stocktaking_record (warehouse_id);

ALTER TABLE modification_work_order
    ADD COLUMN work_order_type VARCHAR(30) NOT NULL DEFAULT 'PRE_SALE' AFTER sales_order_no,
    ADD COLUMN warehouse_id BIGINT NULL AFTER work_order_type,
    ADD COLUMN business_date DATE NULL AFTER warehouse_id,
    ADD COLUMN financial_posted BIT(1) NOT NULL DEFAULT b'0' AFTER status;

ALTER TABLE modification_work_order_line
    ADD COLUMN warehouse_id BIGINT NULL AFTER quantity,
    ADD COLUMN charge_unit_price DECIMAL(12, 2) NULL AFTER price_difference,
    ADD COLUMN discount_amount DECIMAL(12, 2) NOT NULL DEFAULT 0 AFTER charge_unit_price,
    ADD COLUMN charge_amount DECIMAL(14, 2) NULL AFTER discount_amount,
    ADD COLUMN cost_amount DECIMAL(14, 2) NULL AFTER charge_amount,
    ADD COLUMN old_part_disposition VARCHAR(30) NULL AFTER old_part_action,
    ADD COLUMN old_part_warehouse_id BIGINT NULL AFTER old_part_disposition,
    ADD COLUMN old_part_condition VARCHAR(50) NULL AFTER old_part_warehouse_id,
    ADD COLUMN old_part_valuation_source VARCHAR(100) NULL AFTER old_part_condition;

CREATE TABLE stock_lot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    resource_type VARCHAR(30) NOT NULL,
    resource_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    source_id BIGINT NULL,
    source_line_id BIGINT NULL,
    received_business_date DATE NOT NULL,
    original_quantity INT NOT NULL,
    remaining_quantity INT NOT NULL,
    unit_cost DECIMAL(12, 2) NOT NULL,
    freight_allocated DECIMAL(14, 2) NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    idempotency_key VARCHAR(160) NULL,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_stock_lot_idempotency (idempotency_key),
    KEY idx_stock_lot_fifo (resource_type, resource_id, warehouse_id, status, received_business_date, id),
    KEY idx_stock_lot_source (source_type, source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE stock_lot_consumption (
    id BIGINT NOT NULL AUTO_INCREMENT,
    stock_lot_id BIGINT NOT NULL,
    resource_type VARCHAR(30) NOT NULL,
    resource_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    source_id BIGINT NULL,
    source_line_id BIGINT NULL,
    quantity INT NOT NULL,
    unit_cost DECIMAL(12, 2) NOT NULL,
    total_cost DECIMAL(14, 2) NOT NULL,
    business_date DATE NOT NULL,
    reversal_of_consumption_id BIGINT NULL,
    idempotency_key VARCHAR(160) NULL,
    created_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_stock_lot_consumption_idempotency (idempotency_key),
    KEY idx_stock_lot_consumption_source (source_type, source_id, source_line_id),
    KEY idx_stock_lot_consumption_lot (stock_lot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE financial_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_no VARCHAR(80) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    amount DECIMAL(14, 2) NOT NULL,
    business_date DATE NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    source_id BIGINT NULL,
    source_line_id BIGINT NULL,
    counterparty_type VARCHAR(30) NULL,
    counterparty_id BIGINT NULL,
    counterparty_name VARCHAR(120) NULL,
    remark VARCHAR(500) NULL,
    idempotency_key VARCHAR(160) NULL,
    reversal_of_event_id BIGINT NULL,
    created_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_financial_event_no (event_no),
    UNIQUE KEY uk_financial_event_idempotency (idempotency_key),
    KEY idx_financial_event_period (business_date, event_type),
    KEY idx_financial_event_source (source_type, source_id, source_line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE payment_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    payment_no VARCHAR(80) NOT NULL,
    direction VARCHAR(20) NOT NULL,
    amount DECIMAL(14, 2) NOT NULL,
    payment_date DATE NOT NULL,
    account_name VARCHAR(100) NULL,
    payment_method VARCHAR(50) NULL,
    source_type VARCHAR(40) NOT NULL,
    source_id BIGINT NULL,
    financial_event_id BIGINT NULL,
    remark VARCHAR(500) NULL,
    idempotency_key VARCHAR(160) NULL,
    reversal_of_payment_id BIGINT NULL,
    created_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_record_no (payment_no),
    UNIQUE KEY uk_payment_record_idempotency (idempotency_key),
    KEY idx_payment_record_source (source_type, source_id, payment_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rental_bill (
    id BIGINT NOT NULL AUTO_INCREMENT,
    rental_id BIGINT NOT NULL,
    bill_period DATE NOT NULL,
    business_date DATE NOT NULL,
    amount DECIMAL(14, 2) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'POSTED',
    financial_event_id BIGINT NULL,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_rental_bill_period (rental_id, bill_period),
    KEY idx_rental_bill_business_date (business_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE migration_exception (
    id BIGINT NOT NULL AUTO_INCREMENT,
    exception_type VARCHAR(60) NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    source_id BIGINT NULL,
    detail VARCHAR(1000) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    created_at DATETIME(6),
    resolved_at DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_migration_exception_status (status, exception_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
