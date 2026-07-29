-- A stock-movement reversal is trustworthy only when its detail rows form the
-- exact economic inverse of the original movement. V48 protects the header;
-- this migration binds every reversal line to one original line.

-- Fail before persistent DDL if an existing reversal cannot be paired as a
-- complete multiset. This keeps a skipped production preflight from leaving a
-- partially altered V51 schema on MySQL's non-transactional DDL path.
CREATE TEMPORARY TABLE v51_stock_move_reversal_preflight (
    valid_row TINYINT NOT NULL,
    CONSTRAINT chk_v51_stock_move_reversal_lines CHECK (valid_row = 1)
) ENGINE=InnoDB;

INSERT INTO v51_stock_move_reversal_preflight (valid_row)
SELECT 0
FROM stock_movement reversal_header
JOIN stock_movement original_header
  ON original_header.id = reversal_header.reversal_of_movement_id
WHERE NOT EXISTS (
          SELECT 1
          FROM stock_movement_line original_line
          WHERE original_line.movement_id = original_header.id
      )
   OR (
          SELECT COUNT(*)
          FROM stock_movement_line reversal_line
          WHERE reversal_line.movement_id = reversal_header.id
      ) <> (
          SELECT COUNT(*)
          FROM stock_movement_line original_line
          WHERE original_line.movement_id = original_header.id
      )
   OR EXISTS (
          SELECT 1
          FROM stock_movement_line original_line
          WHERE original_line.movement_id = original_header.id
            AND (
                SELECT COUNT(*)
                FROM stock_movement_line original_same
                WHERE original_same.movement_id = original_header.id
                  AND original_same.resource_type = original_line.resource_type
                  AND original_same.resource_id = original_line.resource_id
                  AND original_same.warehouse_id = original_line.warehouse_id
                  AND original_same.stock_lot_id <=> original_line.stock_lot_id
                  AND original_same.source_line_id <=> original_line.source_line_id
                  AND original_same.quantity_delta = original_line.quantity_delta
                  AND original_same.before_quantity = original_line.before_quantity
                  AND original_same.after_quantity = original_line.after_quantity
                  AND original_same.unit_cost <=> original_line.unit_cost
                  AND original_same.unit_revenue <=> original_line.unit_revenue
                  AND original_same.line_amount <=> original_line.line_amount
                  AND original_same.cost_amount <=> original_line.cost_amount
            ) <> (
                SELECT COUNT(*)
                FROM stock_movement_line reversal_line
                WHERE reversal_line.movement_id = reversal_header.id
                  AND reversal_line.resource_type = original_line.resource_type
                  AND reversal_line.resource_id = original_line.resource_id
                  AND reversal_line.warehouse_id = original_line.warehouse_id
                  AND reversal_line.stock_lot_id <=> original_line.stock_lot_id
                  AND reversal_line.source_line_id <=> original_line.source_line_id
                  AND reversal_line.quantity_delta = -original_line.quantity_delta
                  AND reversal_line.before_quantity = original_line.after_quantity
                  AND reversal_line.after_quantity = original_line.before_quantity
                  AND reversal_line.unit_cost <=> original_line.unit_cost
                  AND reversal_line.unit_revenue <=> original_line.unit_revenue
                  AND reversal_line.line_amount <=> original_line.line_amount
                  AND reversal_line.cost_amount <=> original_line.cost_amount
            )
      )
LIMIT 1;

DROP TEMPORARY TABLE v51_stock_move_reversal_preflight;

-- Stored NULL markers make the header relationship enforceable for ordinary
-- as well as reversal movements; a nullable composite FK alone would skip the
-- check for every ordinary line.
ALTER TABLE stock_movement
    ADD COLUMN line_header_reversal_is_null TINYINT
        GENERATED ALWAYS AS (reversal_of_movement_id IS NULL) STORED,
    ADD COLUMN line_header_reversal_value BIGINT
        GENERATED ALWAYS AS (IFNULL(reversal_of_movement_id, 0)) STORED,
    ADD UNIQUE KEY uk_stock_move_line_header (
        id, line_header_reversal_is_null, line_header_reversal_value
    );

ALTER TABLE stock_movement_line
    ADD COLUMN reversal_of_movement_id BIGINT NULL AFTER movement_id,
    ADD COLUMN reversal_of_movement_line_id BIGINT NULL
        AFTER reversal_of_movement_id;

-- The preflight proves that each signature has the same multiplicity on both
-- sides. Pair equal duplicates by stable id order; no economic identity is
-- guessed or collapsed.
CREATE TEMPORARY TABLE v51_stock_move_line_pair (
    reversal_line_id BIGINT NOT NULL,
    original_movement_id BIGINT NOT NULL,
    original_line_id BIGINT NOT NULL,
    PRIMARY KEY (reversal_line_id),
    UNIQUE KEY uk_v51_stock_move_original_line (original_line_id)
) ENGINE=InnoDB;

INSERT INTO v51_stock_move_line_pair (
    reversal_line_id, original_movement_id, original_line_id
)
SELECT reversal_line.id,
       original_line.movement_id,
       original_line.id
FROM (
    SELECT line_row.*,
           ROW_NUMBER() OVER (
               PARTITION BY
                   movement_id, resource_type, resource_id, warehouse_id,
                   stock_lot_id, source_line_id, quantity_delta,
                   before_quantity, after_quantity, unit_cost, unit_revenue,
                   line_amount, cost_amount
               ORDER BY id
           ) AS pair_ordinal
    FROM stock_movement_line line_row
) reversal_line
JOIN stock_movement reversal_header
  ON reversal_header.id = reversal_line.movement_id
 AND reversal_header.reversal_of_movement_id IS NOT NULL
JOIN (
    SELECT line_row.*,
           ROW_NUMBER() OVER (
               PARTITION BY
                   movement_id, resource_type, resource_id, warehouse_id,
                   stock_lot_id, source_line_id, quantity_delta,
                   before_quantity, after_quantity, unit_cost, unit_revenue,
                   line_amount, cost_amount
               ORDER BY id
           ) AS pair_ordinal
    FROM stock_movement_line line_row
) original_line
  ON original_line.movement_id = reversal_header.reversal_of_movement_id
 AND original_line.resource_type = reversal_line.resource_type
 AND original_line.resource_id = reversal_line.resource_id
 AND original_line.warehouse_id = reversal_line.warehouse_id
 AND original_line.stock_lot_id <=> reversal_line.stock_lot_id
 AND original_line.source_line_id <=> reversal_line.source_line_id
 AND original_line.quantity_delta = -reversal_line.quantity_delta
 AND original_line.before_quantity = reversal_line.after_quantity
 AND original_line.after_quantity = reversal_line.before_quantity
 AND original_line.unit_cost <=> reversal_line.unit_cost
 AND original_line.unit_revenue <=> reversal_line.unit_revenue
 AND original_line.line_amount <=> reversal_line.line_amount
 AND original_line.cost_amount <=> reversal_line.cost_amount
 AND original_line.pair_ordinal = reversal_line.pair_ordinal;

UPDATE stock_movement_line reversal_line
JOIN v51_stock_move_line_pair line_pair
  ON line_pair.reversal_line_id = reversal_line.id
SET reversal_line.reversal_of_movement_id = line_pair.original_movement_id,
    reversal_line.reversal_of_movement_line_id = line_pair.original_line_id;

DROP TEMPORARY TABLE v51_stock_move_line_pair;

-- Split the exact identity into core and monetary keys because MySQL permits
-- at most 16 columns per index. Both foreign keys start with the same original
-- line id, so together they describe one exact row rather than two candidates.
-- Reporting treats line_amount/cost_amount as unsigned snapshots and derives
-- direction from quantity_delta, so the reversal preserves those amounts.
ALTER TABLE stock_movement_line
    ADD COLUMN header_reversal_is_null TINYINT
        GENERATED ALWAYS AS (reversal_of_movement_id IS NULL) STORED,
    ADD COLUMN header_reversal_value BIGINT
        GENERATED ALWAYS AS (IFNULL(reversal_of_movement_id, 0)) STORED,
    ADD COLUMN reversal_stock_lot_is_null TINYINT
        GENERATED ALWAYS AS (stock_lot_id IS NULL) STORED,
    ADD COLUMN reversal_stock_lot_value BIGINT
        GENERATED ALWAYS AS (IFNULL(stock_lot_id, 0)) STORED,
    ADD COLUMN reversal_source_line_is_null TINYINT
        GENERATED ALWAYS AS (source_line_id IS NULL) STORED,
    ADD COLUMN reversal_source_line_value BIGINT
        GENERATED ALWAYS AS (IFNULL(source_line_id, 0)) STORED,
    ADD COLUMN reversal_quantity_match INT
        GENERATED ALWAYS AS (
            CASE
                WHEN reversal_of_movement_line_id IS NULL THEN NULL
                ELSE -quantity_delta
            END
        ) STORED,
    ADD COLUMN reversal_before_match INT
        GENERATED ALWAYS AS (
            CASE
                WHEN reversal_of_movement_line_id IS NULL THEN NULL
                ELSE after_quantity
            END
        ) STORED,
    ADD COLUMN reversal_after_match INT
        GENERATED ALWAYS AS (
            CASE
                WHEN reversal_of_movement_line_id IS NULL THEN NULL
                ELSE before_quantity
            END
        ) STORED,
    ADD COLUMN reversal_unit_cost_is_null TINYINT
        GENERATED ALWAYS AS (unit_cost IS NULL) STORED,
    ADD COLUMN reversal_unit_cost_value DECIMAL(18, 6)
        GENERATED ALWAYS AS (IFNULL(unit_cost, 0)) STORED,
    ADD COLUMN reversal_unit_revenue_is_null TINYINT
        GENERATED ALWAYS AS (unit_revenue IS NULL) STORED,
    ADD COLUMN reversal_unit_revenue_value DECIMAL(12, 2)
        GENERATED ALWAYS AS (IFNULL(unit_revenue, 0)) STORED,
    ADD COLUMN reversal_line_amount_is_null TINYINT
        GENERATED ALWAYS AS (line_amount IS NULL) STORED,
    ADD COLUMN reversal_line_amount_value DECIMAL(14, 2)
        GENERATED ALWAYS AS (IFNULL(line_amount, 0)) STORED,
    ADD COLUMN reversal_cost_amount_is_null TINYINT
        GENERATED ALWAYS AS (cost_amount IS NULL) STORED,
    ADD COLUMN reversal_cost_amount_value DECIMAL(14, 2)
        GENERATED ALWAYS AS (IFNULL(cost_amount, 0)) STORED,
    ADD COLUMN reversal_original_guard TINYINT
        GENERATED ALWAYS AS (
            CASE WHEN reversal_of_movement_line_id IS NULL THEN 1 ELSE 0 END
        ) STORED,
    ADD COLUMN reversal_parent_guard TINYINT
        GENERATED ALWAYS AS (
            CASE WHEN reversal_of_movement_line_id IS NULL THEN NULL ELSE 1 END
        ) STORED,
    ADD CONSTRAINT chk_stock_move_line_reversal_pair
        CHECK (
            (reversal_of_movement_id IS NULL
                AND reversal_of_movement_line_id IS NULL)
            OR
            (reversal_of_movement_id IS NOT NULL
                AND reversal_of_movement_line_id IS NOT NULL)
        ),
    ADD UNIQUE KEY uk_stock_move_line_reversal
        (reversal_of_movement_line_id),
    ADD UNIQUE KEY uk_stock_move_line_reversal_core (
        id, movement_id, resource_type, resource_id, warehouse_id,
        reversal_stock_lot_is_null, reversal_stock_lot_value,
        reversal_source_line_is_null, reversal_source_line_value,
        quantity_delta, before_quantity, after_quantity,
        reversal_original_guard
    ),
    ADD UNIQUE KEY uk_stock_move_line_reversal_money (
        id,
        reversal_unit_cost_is_null, reversal_unit_cost_value,
        reversal_unit_revenue_is_null, reversal_unit_revenue_value,
        reversal_line_amount_is_null, reversal_line_amount_value,
        reversal_cost_amount_is_null, reversal_cost_amount_value,
        reversal_original_guard
    );

ALTER TABLE stock_movement_line
    ADD CONSTRAINT fk_stock_move_line_header_reversal
        FOREIGN KEY (
            movement_id, header_reversal_is_null, header_reversal_value
        )
        REFERENCES stock_movement (
            id, line_header_reversal_is_null, line_header_reversal_value
        )
        ON DELETE CASCADE,
    ADD CONSTRAINT fk_stock_move_line_reversal_core
        FOREIGN KEY (
            reversal_of_movement_line_id, reversal_of_movement_id,
            resource_type, resource_id, warehouse_id,
            reversal_stock_lot_is_null, reversal_stock_lot_value,
            reversal_source_line_is_null, reversal_source_line_value,
            reversal_quantity_match, reversal_before_match,
            reversal_after_match, reversal_parent_guard
        )
        REFERENCES stock_movement_line (
            id, movement_id, resource_type, resource_id, warehouse_id,
            reversal_stock_lot_is_null, reversal_stock_lot_value,
            reversal_source_line_is_null, reversal_source_line_value,
            quantity_delta, before_quantity, after_quantity,
            reversal_original_guard
        )
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_stock_move_line_reversal_money
        FOREIGN KEY (
            reversal_of_movement_line_id,
            reversal_unit_cost_is_null, reversal_unit_cost_value,
            reversal_unit_revenue_is_null, reversal_unit_revenue_value,
            reversal_line_amount_is_null, reversal_line_amount_value,
            reversal_cost_amount_is_null, reversal_cost_amount_value,
            reversal_parent_guard
        )
        REFERENCES stock_movement_line (
            id,
            reversal_unit_cost_is_null, reversal_unit_cost_value,
            reversal_unit_revenue_is_null, reversal_unit_revenue_value,
            reversal_line_amount_is_null, reversal_line_amount_value,
            reversal_cost_amount_is_null, reversal_cost_amount_value,
            reversal_original_guard
        )
        ON DELETE RESTRICT;
