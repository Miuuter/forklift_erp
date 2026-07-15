CREATE INDEX idx_stock_movement_line_reporting
    ON stock_movement_line (movement_id, resource_type, quantity_delta, cost_amount, line_amount);

CREATE INDEX idx_financial_event_reporting
    ON financial_event (business_date, event_type, source_type, amount);
