-- Direct (non-polymorphic) identifiers must be protected by the database.
-- Polymorphic source_type/source_id pairs remain application-validated.

ALTER TABLE financial_event
    ADD UNIQUE KEY uk_financial_event_payment_identity
        (id, event_type, amount, source_type, source_id),
    ADD UNIQUE KEY uk_financial_event_reversal_link
        (id, reversal_of_event_id);

-- MySQL does not allow the referenced column definition to be changed while
-- the V42 single-column foreign key is present. Rebuild it after tightening
-- the column so the original constraint name remains available to operators.
ALTER TABLE payment_record
    DROP FOREIGN KEY fk_payment_record_financial_event;

ALTER TABLE payment_record
    ADD COLUMN reversal_of_financial_event_id BIGINT NULL
        AFTER reversal_of_payment_id;

UPDATE payment_record reversal_payment
JOIN payment_record original_payment
  ON original_payment.id = reversal_payment.reversal_of_payment_id
SET reversal_payment.reversal_of_financial_event_id =
        original_payment.financial_event_id
WHERE reversal_payment.reversal_of_payment_id IS NOT NULL;

-- Earlier application versions linked the two payment rows but did not link
-- their append-only cash events. Backfill only the exact relationship; the
-- V48 financial reversal identity FK rejects amount/source/type drift.
UPDATE financial_event reversal_event
JOIN payment_record reversal_payment
  ON reversal_payment.financial_event_id = reversal_event.id
JOIN payment_record original_payment
  ON original_payment.id = reversal_payment.reversal_of_payment_id
SET reversal_event.reversal_of_event_id = original_payment.financial_event_id
WHERE reversal_payment.reversal_of_payment_id IS NOT NULL
  AND reversal_event.reversal_of_event_id IS NULL;

ALTER TABLE payment_record
    MODIFY COLUMN source_id BIGINT NOT NULL,
    MODIFY COLUMN financial_event_id BIGINT NOT NULL,
    ADD COLUMN cash_event_type VARCHAR(40)
        GENERATED ALWAYS AS (
            CASE direction
                WHEN 'RECEIPT' THEN 'CASH_RECEIPT'
                WHEN 'PAYMENT' THEN 'CASH_PAYMENT'
                ELSE NULL
            END
        ) STORED,
    ADD COLUMN reversal_amount_match DECIMAL(14, 2)
        GENERATED ALWAYS AS (
            CASE WHEN reversal_of_payment_id IS NULL THEN NULL ELSE -amount END
        ) STORED,
    ADD COLUMN reversal_original_guard TINYINT
        GENERATED ALWAYS AS (
            CASE WHEN reversal_of_payment_id IS NULL THEN 1 ELSE 0 END
        ) STORED,
    ADD COLUMN reversal_parent_guard TINYINT
        GENERATED ALWAYS AS (
            CASE WHEN reversal_of_payment_id IS NULL THEN NULL ELSE 1 END
        ) STORED,
    ADD CONSTRAINT chk_payment_record_direction
        CHECK (direction IN ('RECEIPT', 'PAYMENT')),
    ADD CONSTRAINT chk_payment_reversal_event_pair
        CHECK (
            (reversal_of_payment_id IS NULL
                AND reversal_of_financial_event_id IS NULL)
            OR
            (reversal_of_payment_id IS NOT NULL
                AND reversal_of_financial_event_id IS NOT NULL)
        ),
    ADD CONSTRAINT chk_payment_reversal_sign
        CHECK (
            (reversal_of_payment_id IS NULL
                AND reversal_of_financial_event_id IS NULL
                AND amount > 0)
            OR
            (reversal_of_payment_id IS NOT NULL
                AND reversal_of_financial_event_id IS NOT NULL
                AND amount < 0)
        ),
    ADD UNIQUE KEY uk_payment_record_financial_event (financial_event_id),
    ADD UNIQUE KEY uk_payment_record_id_event (id, financial_event_id),
    ADD UNIQUE KEY uk_payment_reversal_identity
        (id, direction, amount, source_type, source_id, reversal_original_guard),
    ADD CONSTRAINT fk_payment_record_financial_event
        FOREIGN KEY (financial_event_id) REFERENCES financial_event (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_payment_record_event_identity
        FOREIGN KEY (
            financial_event_id, cash_event_type, amount, source_type, source_id
        )
        REFERENCES financial_event (
            id, event_type, amount, source_type, source_id
        )
        ON DELETE RESTRICT;

ALTER TABLE payment_record
    ADD CONSTRAINT fk_payment_reversal_identity
        FOREIGN KEY (
            reversal_of_payment_id, direction, reversal_amount_match,
            source_type, source_id, reversal_parent_guard
        )
        REFERENCES payment_record (
            id, direction, amount, source_type, source_id,
            reversal_original_guard
        )
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_payment_reversal_payment_event
        FOREIGN KEY (
            reversal_of_payment_id, reversal_of_financial_event_id
        )
        REFERENCES payment_record (id, financial_event_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_payment_reversal_fin_event
        FOREIGN KEY (
            financial_event_id, reversal_of_financial_event_id
        )
        REFERENCES financial_event (id, reversal_of_event_id)
        ON DELETE RESTRICT;

ALTER TABLE machine_inventory
    DROP FOREIGN KEY fk_machine_inventory_warehouse;
ALTER TABLE part_inventory
    DROP FOREIGN KEY fk_part_inventory_warehouse;

ALTER TABLE machine_inventory
    MODIFY COLUMN warehouse_id BIGINT NOT NULL,
    MODIFY COLUMN landed_unit_cost DECIMAL(18, 6) NULL,
    ADD CONSTRAINT fk_machine_inventory_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
        ON DELETE RESTRICT;
ALTER TABLE part_inventory
    MODIFY COLUMN warehouse_id BIGINT NOT NULL,
    MODIFY COLUMN landed_unit_cost DECIMAL(18, 6) NULL,
    ADD CONSTRAINT fk_part_inventory_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
        ON DELETE RESTRICT;

ALTER TABLE machine_inventory
    ADD CONSTRAINT fk_machine_inventory_supplier
        FOREIGN KEY (supplier_id) REFERENCES supplier_profile (id)
        ON DELETE RESTRICT;

ALTER TABLE part_inventory
    ADD CONSTRAINT fk_part_inventory_source_machine
        FOREIGN KEY (source_machine_id) REFERENCES machine_inventory (id)
        ON DELETE RESTRICT;

ALTER TABLE repair_record
    ADD CONSTRAINT fk_repair_record_machine
        FOREIGN KEY (machine_id) REFERENCES machine_inventory (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_repair_record_customer
        FOREIGN KEY (customer_id) REFERENCES customer_profile (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_repair_record_person
        FOREIGN KEY (repair_person_user_id) REFERENCES users (id)
        ON DELETE RESTRICT;

ALTER TABLE rental_record
    ADD CONSTRAINT fk_rental_record_customer
        FOREIGN KEY (customer_id) REFERENCES customer_profile (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_rental_record_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
        ON DELETE RESTRICT;

ALTER TABLE stock_operation_log
    MODIFY COLUMN unit_cost DECIMAL(18, 6) NULL;

ALTER TABLE outbound_order
    ADD CONSTRAINT fk_outbound_order_source_warehouse
        FOREIGN KEY (source_warehouse_id) REFERENCES warehouse (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_outbound_order_stock_operation
        FOREIGN KEY (stock_operation_log_id) REFERENCES stock_operation_log (id)
        ON DELETE SET NULL;

ALTER TABLE purchase_order
    MODIFY COLUMN landed_unit_cost DECIMAL(18, 6) NULL,
    ADD COLUMN previous_resource_purchase_price DECIMAL(12, 2) NULL
        AFTER landed_unit_cost,
    ADD COLUMN previous_resource_landed_unit_cost DECIMAL(18, 6) NULL
        AFTER previous_resource_purchase_price,
    ADD COLUMN resource_cost_snapshot_captured BIT(1) NOT NULL DEFAULT b'0'
        AFTER previous_resource_landed_unit_cost,
    ADD CONSTRAINT fk_purchase_order_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_purchase_order_received_movement
        FOREIGN KEY (received_stock_movement_id) REFERENCES stock_movement (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_purchase_order_stock_lot
        FOREIGN KEY (stock_lot_id) REFERENCES stock_lot (id)
        ON DELETE RESTRICT;

ALTER TABLE stocktaking_record
    ADD CONSTRAINT fk_stocktaking_record_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_stocktaking_snapshot_movement
        FOREIGN KEY (snapshot_movement_id) REFERENCES stock_movement (id)
        ON DELETE RESTRICT;

ALTER TABLE modification_work_order
    ADD UNIQUE KEY uk_modification_order_id_machine (id, machine_id),
    ADD CONSTRAINT fk_modification_order_machine
        FOREIGN KEY (machine_id) REFERENCES machine_inventory (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_modification_order_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
        ON DELETE RESTRICT;

ALTER TABLE modification_work_order_line
    ADD COLUMN machine_id BIGINT NULL AFTER work_order_id;

UPDATE modification_work_order_line line_row
JOIN modification_work_order work_order ON work_order.id = line_row.work_order_id
SET line_row.machine_id = work_order.machine_id;

ALTER TABLE modification_work_order_line
    MODIFY COLUMN machine_id BIGINT NOT NULL,
    MODIFY COLUMN config_item_id BIGINT NOT NULL,
    ADD CONSTRAINT chk_modification_line_config_pair
        CHECK (new_config_value_id IS NULL OR config_item_id IS NOT NULL),
    ADD CONSTRAINT fk_modification_line_machine_config
        FOREIGN KEY (machine_config_id) REFERENCES machine_config (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_modification_line_work_order_machine
        FOREIGN KEY (work_order_id, machine_id)
        REFERENCES modification_work_order (id, machine_id)
        ON DELETE CASCADE,
    ADD CONSTRAINT fk_modification_line_machine_config_machine
        FOREIGN KEY (machine_config_id, machine_id)
        REFERENCES machine_config (id, machine_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_modification_line_machine_config_item
        FOREIGN KEY (machine_config_id, config_item_id)
        REFERENCES machine_config (id, config_item_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_modification_line_config_item
        FOREIGN KEY (config_item_id) REFERENCES config_item (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_modification_line_new_config_value
        FOREIGN KEY (new_config_value_id) REFERENCES config_value (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_modification_line_new_config_value_item
        FOREIGN KEY (new_config_value_id, config_item_id)
        REFERENCES config_value (id, config_item_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_modification_line_new_part
        FOREIGN KEY (new_part_id) REFERENCES part_inventory (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_modification_line_replace_log
        FOREIGN KEY (replace_log_id) REFERENCES config_replace_log (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_modification_line_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_modification_line_old_part_warehouse
        FOREIGN KEY (old_part_warehouse_id) REFERENCES warehouse (id)
        ON DELETE RESTRICT;

ALTER TABLE config_replace_log
    ADD CONSTRAINT fk_config_replace_log_machine
        FOREIGN KEY (machine_id) REFERENCES machine_inventory (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_config_replace_log_machine_config
        FOREIGN KEY (machine_config_id, machine_id)
        REFERENCES machine_config (id, machine_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_config_replace_log_new_part
        FOREIGN KEY (new_part_id) REFERENCES part_inventory (id)
        ON DELETE RESTRICT;

ALTER TABLE stock_lot
    ADD COLUMN original_cost_amount DECIMAL(18, 2) NULL AFTER unit_cost,
    ADD COLUMN remaining_cost_amount DECIMAL(18, 2) NULL AFTER original_cost_amount;

UPDATE stock_lot
SET original_cost_amount = ROUND(original_quantity * unit_cost, 2),
    remaining_cost_amount = ROUND(remaining_quantity * unit_cost, 2);

ALTER TABLE stock_lot
    MODIFY COLUMN unit_cost DECIMAL(18, 6) NOT NULL,
    MODIFY COLUMN original_cost_amount DECIMAL(18, 2) NOT NULL,
    MODIFY COLUMN remaining_cost_amount DECIMAL(18, 2) NOT NULL,
    ADD CONSTRAINT chk_stock_lot_cost_amounts
        CHECK (
            original_cost_amount >= 0
            AND remaining_cost_amount >= 0
            AND remaining_cost_amount <= original_cost_amount
            AND (remaining_quantity <> 0 OR remaining_cost_amount = 0)
        ),
    ADD UNIQUE KEY uk_stock_lot_identity
        (id, warehouse_id, resource_type, resource_id),
    ADD CONSTRAINT fk_stock_lot_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
        ON DELETE RESTRICT;

-- Older application versions deliberately suffixed the reversal source type.
-- Normalize only that known representation. Any other identity drift remains
-- visible and makes the composite constraint installation fail safely.
UPDATE stock_lot_consumption reversal_row
JOIN stock_lot_consumption original_row
  ON original_row.id = reversal_row.reversal_of_consumption_id
SET reversal_row.source_type = original_row.source_type
WHERE reversal_row.source_type = CONCAT(original_row.source_type, '_REVERSAL');

ALTER TABLE stock_lot_consumption
    MODIFY COLUMN unit_cost DECIMAL(18, 6) NOT NULL,
    ADD COLUMN reversal_source_id_is_null TINYINT
        GENERATED ALWAYS AS (source_id IS NULL) STORED,
    ADD COLUMN reversal_source_id_value BIGINT
        GENERATED ALWAYS AS (IFNULL(source_id, 0)) STORED,
    ADD COLUMN reversal_source_line_id_is_null TINYINT
        GENERATED ALWAYS AS (source_line_id IS NULL) STORED,
    ADD COLUMN reversal_source_line_id_value BIGINT
        GENERATED ALWAYS AS (IFNULL(source_line_id, 0)) STORED,
    ADD COLUMN reversal_quantity_identity BIGINT
        GENERATED ALWAYS AS (quantity) STORED,
    ADD COLUMN reversal_quantity_match BIGINT
        GENERATED ALWAYS AS (
            CASE
                WHEN reversal_of_consumption_id IS NULL THEN NULL
                ELSE -CAST(quantity AS SIGNED)
            END
        ) STORED,
    ADD COLUMN reversal_cost_identity DECIMAL(14, 2)
        GENERATED ALWAYS AS (total_cost) STORED,
    ADD COLUMN reversal_cost_match DECIMAL(14, 2)
        GENERATED ALWAYS AS (
            CASE
                WHEN reversal_of_consumption_id IS NULL THEN NULL
                ELSE -total_cost
            END
        ) STORED,
    ADD COLUMN reversal_original_guard TINYINT
        GENERATED ALWAYS AS (
            CASE WHEN reversal_of_consumption_id IS NULL THEN 1 ELSE 0 END
        ) STORED,
    ADD COLUMN reversal_parent_guard TINYINT
        GENERATED ALWAYS AS (
            CASE WHEN reversal_of_consumption_id IS NULL THEN NULL ELSE 1 END
        ) STORED,
    ADD UNIQUE KEY uk_stock_lot_consumption_identity
        (id, warehouse_id, resource_type, resource_id),
    ADD UNIQUE KEY uk_stock_lot_consumption_full_identity
        (id, warehouse_id, resource_type, resource_id,
         source_type, source_id, source_line_id),
    ADD UNIQUE KEY uk_lot_cons_reversal_identity (
        id, stock_lot_id, resource_type, resource_id, warehouse_id, source_type,
        reversal_source_id_is_null, reversal_source_id_value,
        reversal_source_line_id_is_null, reversal_source_line_id_value,
        reversal_quantity_identity, unit_cost, reversal_cost_identity,
        reversal_original_guard
    ),
    ADD CONSTRAINT fk_stock_lot_consumption_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_stock_lot_consumption_identity
        FOREIGN KEY (stock_lot_id, warehouse_id, resource_type, resource_id)
        REFERENCES stock_lot (id, warehouse_id, resource_type, resource_id)
        ON DELETE RESTRICT;

-- Generated identity columns and their referenced unique key must exist
-- before InnoDB can resolve the composite self-reference.
ALTER TABLE stock_lot_consumption
    ADD CONSTRAINT fk_lot_cons_reversal_identity
        FOREIGN KEY (
            reversal_of_consumption_id, stock_lot_id, resource_type, resource_id,
            warehouse_id, source_type,
            reversal_source_id_is_null, reversal_source_id_value,
            reversal_source_line_id_is_null, reversal_source_line_id_value,
            reversal_quantity_match, unit_cost, reversal_cost_match,
            reversal_parent_guard
        )
        REFERENCES stock_lot_consumption (
            id, stock_lot_id, resource_type, resource_id, warehouse_id, source_type,
            reversal_source_id_is_null, reversal_source_id_value,
            reversal_source_line_id_is_null, reversal_source_line_id_value,
            reversal_quantity_identity, unit_cost, reversal_cost_identity,
            reversal_original_guard
        )
        ON DELETE RESTRICT;

-- total_cost is authoritative. A six-decimal average cannot reproduce every
-- cent for arbitrarily large integer quantities, so retain the explicit
-- quantity-dependent envelope below while rejecting sign errors and ordinary
-- small-quantity one-cent drift.
ALTER TABLE stock_lot_consumption
    DROP CHECK chk_stock_lot_consumption_cost_arithmetic,
    ADD CONSTRAINT chk_stock_lot_consumption_cost_arithmetic
        CHECK (
            unit_cost >= 0
            AND (
                (reversal_of_consumption_id IS NULL
                    AND quantity > 0
                    AND total_cost >= 0)
                OR (reversal_of_consumption_id IS NOT NULL
                    AND quantity < 0
                    AND total_cost <= 0)
            )
            AND ABS(total_cost - (quantity * unit_cost))
                <= (ABS(quantity) * 0.0000005) + 0.005
        );

ALTER TABLE stock_movement_line
    MODIFY COLUMN unit_cost DECIMAL(18, 6) NULL,
    ADD CONSTRAINT fk_stock_movement_line_lot_identity
        FOREIGN KEY (stock_lot_id, warehouse_id, resource_type, resource_id)
        REFERENCES stock_lot (id, warehouse_id, resource_type, resource_id)
        ON DELETE RESTRICT;

ALTER TABLE repair_part_usage
    MODIFY COLUMN unit_cost DECIMAL(18, 6) NULL,
    ADD COLUMN stock_resource_type VARCHAR(30)
        GENERATED ALWAYS AS ('PART') STORED,
    ADD COLUMN stock_source_type VARCHAR(40)
        GENERATED ALWAYS AS ('REPAIR') STORED,
    ADD CONSTRAINT chk_repair_usage_consumption_warehouse
        CHECK (stock_lot_consumption_id IS NULL OR warehouse_id IS NOT NULL),
    ADD CONSTRAINT fk_repair_part_usage_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_repair_usage_consumption_identity
        FOREIGN KEY (
            stock_lot_consumption_id, warehouse_id, stock_resource_type, part_id,
            stock_source_type, repair_id, id
        )
        REFERENCES stock_lot_consumption (
            id, warehouse_id, resource_type, resource_id,
            source_type, source_id, source_line_id
        )
        ON DELETE RESTRICT;
