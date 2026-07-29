-- Recover the canonical default only when the database has none. Multiple
-- defaults are ambiguous and intentionally make the unique guard fail; the
-- preflight report lists those warehouse IDs for an explicit operator choice.
UPDATE warehouse target
JOIN (
    SELECT COUNT(*) AS default_count
    FROM warehouse
    WHERE is_default = b'1'
) current_defaults
SET target.is_default = b'1'
WHERE target.warehouse_code = 'DEFAULT'
  AND current_defaults.default_count = 0;

ALTER TABLE warehouse
    ADD COLUMN default_guard TINYINT
        GENERATED ALWAYS AS (CASE WHEN is_default = b'1' THEN 1 ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_warehouse_single_default (default_guard);

ALTER TABLE config_value
    ADD COLUMN default_item_guard BIGINT
        GENERATED ALWAYS AS (
            CASE WHEN is_default = b'1' THEN config_item_id ELSE NULL END
        ) STORED,
    ADD UNIQUE KEY uk_config_value_single_default (default_item_guard),
    ADD UNIQUE KEY uk_config_value_id_item (id, config_item_id),
    ADD CONSTRAINT fk_config_value_item
        FOREIGN KEY (config_item_id) REFERENCES config_item (id)
        ON DELETE RESTRICT;

ALTER TABLE machine_config
    ADD UNIQUE KEY uk_machine_config_machine_item (machine_id, config_item_id),
    ADD UNIQUE KEY uk_machine_config_id_item (id, config_item_id),
    ADD UNIQUE KEY uk_machine_config_id_machine (id, machine_id),
    ADD CONSTRAINT fk_machine_config_machine
        FOREIGN KEY (machine_id) REFERENCES machine_inventory (id)
        ON DELETE CASCADE,
    ADD CONSTRAINT fk_machine_config_item
        FOREIGN KEY (config_item_id) REFERENCES config_item (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_machine_config_value_item
        FOREIGN KEY (config_value_id, config_item_id)
        REFERENCES config_value (id, config_item_id)
        ON DELETE RESTRICT;

ALTER TABLE vehicle_config_value
    ADD CONSTRAINT fk_vehicle_config_value_value_item
        FOREIGN KEY (config_value_id, config_item_id)
        REFERENCES config_value (id, config_item_id)
        ON DELETE RESTRICT;

ALTER TABLE purchase_order
    ADD CONSTRAINT chk_purchase_order_config_pair
        CHECK (config_value_id IS NULL OR config_item_id IS NOT NULL),
    ADD CONSTRAINT fk_purchase_order_config_item
        FOREIGN KEY (config_item_id) REFERENCES config_item (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_purchase_order_config_value
        FOREIGN KEY (config_value_id) REFERENCES config_value (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_purchase_order_config_value_item
        FOREIGN KEY (config_value_id, config_item_id)
        REFERENCES config_value (id, config_item_id)
        ON DELETE RESTRICT;
