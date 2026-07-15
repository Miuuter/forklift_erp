ALTER TABLE stock_balance
    ADD CONSTRAINT chk_stock_balance_available CHECK (available_quantity >= 0),
    ADD CONSTRAINT chk_stock_balance_reserved CHECK (reserved_quantity >= 0),
    ADD CONSTRAINT chk_stock_balance_locked CHECK (locked_quantity >= 0);

ALTER TABLE stock_lot
    ADD CONSTRAINT chk_stock_lot_original_quantity CHECK (original_quantity > 0),
    ADD CONSTRAINT chk_stock_lot_remaining_quantity
        CHECK (remaining_quantity >= 0 AND remaining_quantity <= original_quantity),
    ADD CONSTRAINT chk_stock_lot_unit_cost CHECK (unit_cost >= 0);

ALTER TABLE stock_lot_consumption
    ADD CONSTRAINT chk_stock_lot_consumption_quantity CHECK (quantity <> 0),
    ADD CONSTRAINT chk_stock_lot_consumption_unit_cost CHECK (unit_cost >= 0);

ALTER TABLE stock_movement_line
    ADD CONSTRAINT chk_stock_movement_line_balances
        CHECK (before_quantity >= 0 AND after_quantity >= 0);

ALTER TABLE rental_bill
    ADD CONSTRAINT chk_rental_bill_amount CHECK (amount >= 0);

ALTER TABLE data_import_job
    ADD CONSTRAINT chk_data_import_job_row_counts
        CHECK (
            total_rows >= 0
            AND valid_rows >= 0
            AND error_rows >= 0
            AND error_details >= 0
            AND imported_rows >= 0
            AND skipped_rows >= 0
            AND valid_rows + error_rows <= total_rows
        );

CREATE INDEX idx_financial_event_source_period
    ON financial_event (source_type, source_id, business_date, event_type);

CREATE INDEX idx_payment_record_request_source
    ON payment_record (request_id, source_type, source_id);

CREATE INDEX idx_rental_record_active_billing
    ON rental_record (status, start_date, return_date, id);
