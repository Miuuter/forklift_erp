-- Direct relationships that are safe to enforce after the historical repair
-- dry-run reports no blocking exceptions. Polymorphic source_type/source_id
-- pairs intentionally remain unconstrained.

ALTER TABLE payment_record
    ADD CONSTRAINT fk_payment_record_financial_event
        FOREIGN KEY (financial_event_id) REFERENCES financial_event (id),
    ADD CONSTRAINT fk_payment_record_reversal
        FOREIGN KEY (reversal_of_payment_id) REFERENCES payment_record (id);

ALTER TABLE financial_event
    ADD CONSTRAINT fk_financial_event_reversal
        FOREIGN KEY (reversal_of_event_id) REFERENCES financial_event (id);

ALTER TABLE rental_bill
    ADD CONSTRAINT fk_rental_bill_rental
        FOREIGN KEY (rental_id) REFERENCES rental_record (id),
    ADD CONSTRAINT fk_rental_bill_financial_event
        FOREIGN KEY (financial_event_id) REFERENCES financial_event (id);

ALTER TABLE stock_lot_consumption
    ADD CONSTRAINT fk_stock_lot_consumption_lot
        FOREIGN KEY (stock_lot_id) REFERENCES stock_lot (id),
    ADD CONSTRAINT fk_stock_lot_consumption_reversal
        FOREIGN KEY (reversal_of_consumption_id) REFERENCES stock_lot_consumption (id);

ALTER TABLE stock_lot_cost_adjustment
    ADD CONSTRAINT fk_stock_lot_cost_adjustment_lot
        FOREIGN KEY (stock_lot_id) REFERENCES stock_lot (id);

ALTER TABLE stock_movement_line
    ADD CONSTRAINT fk_stock_movement_line_stock_lot
        FOREIGN KEY (stock_lot_id) REFERENCES stock_lot (id);

ALTER TABLE repair_part_usage
    ADD CONSTRAINT fk_repair_part_usage_lot_consumption
        FOREIGN KEY (stock_lot_consumption_id) REFERENCES stock_lot_consumption (id);

ALTER TABLE data_import_row
    ADD CONSTRAINT fk_data_import_row_job
        FOREIGN KEY (import_job_id) REFERENCES data_import_job (id);
