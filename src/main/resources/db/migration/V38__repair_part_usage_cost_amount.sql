ALTER TABLE repair_part_usage
    ADD COLUMN cost_amount DECIMAL(14, 2) NULL AFTER charge_amount;
