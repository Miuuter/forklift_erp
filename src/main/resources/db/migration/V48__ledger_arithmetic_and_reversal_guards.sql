-- A ledger row whose before/after values do not add up is corrupt even when
-- every individual value is non-negative. MySQL validates existing rows while
-- adding these constraints; run scripts/mysql-upgrade-preflight-v40.sql before
-- deployment so any offending primary keys are repaired explicitly.

-- V37-V47 linked payment reversals to payment rows, but older versions did not
-- link their cash events. Backfill only a fully matching pair before the sign
-- and reversal constraints below are installed. Any mismatch remains visible
-- and is rejected by the later checks instead of being guessed into a ledger.
UPDATE financial_event reversal_event
JOIN payment_record reversal_payment
  ON reversal_payment.financial_event_id = reversal_event.id
JOIN payment_record original_payment
  ON original_payment.id = reversal_payment.reversal_of_payment_id
JOIN financial_event original_event
  ON original_event.id = original_payment.financial_event_id
LEFT JOIN financial_event occupied_event
  ON occupied_event.reversal_of_event_id = original_event.id
 AND occupied_event.id <> reversal_event.id
SET reversal_event.reversal_of_event_id = original_event.id
WHERE reversal_payment.reversal_of_payment_id IS NOT NULL
  AND original_payment.reversal_of_payment_id IS NULL
  AND reversal_event.reversal_of_event_id IS NULL
  AND original_event.reversal_of_event_id IS NULL
  AND reversal_payment.direction = original_payment.direction
  AND reversal_payment.amount = -original_payment.amount
  AND reversal_payment.source_type <=> original_payment.source_type
  AND reversal_payment.source_id <=> original_payment.source_id
  AND reversal_event.event_type = CASE reversal_payment.direction
      WHEN 'RECEIPT' THEN 'CASH_RECEIPT'
      WHEN 'PAYMENT' THEN 'CASH_PAYMENT'
      ELSE NULL
      END
  AND reversal_event.amount = reversal_payment.amount
  AND reversal_event.source_type <=> reversal_payment.source_type
  AND reversal_event.source_id <=> reversal_payment.source_id
  AND reversal_event.source_line_id IS NULL
  AND original_event.event_type = CASE original_payment.direction
      WHEN 'RECEIPT' THEN 'CASH_RECEIPT'
      WHEN 'PAYMENT' THEN 'CASH_PAYMENT'
      ELSE NULL
      END
  AND original_event.amount = original_payment.amount
  AND original_event.source_type <=> original_payment.source_type
  AND original_event.source_id <=> original_payment.source_id
  AND original_event.source_line_id IS NULL
  AND reversal_event.counterparty_type <=> original_event.counterparty_type
  AND reversal_event.counterparty_id <=> original_event.counterparty_id
  AND reversal_event.counterparty_name <=> original_event.counterparty_name
  AND occupied_event.id IS NULL;

ALTER TABLE stock_movement_line
    ADD CONSTRAINT chk_stock_movement_line_arithmetic
        CHECK (after_quantity = before_quantity + quantity_delta);

ALTER TABLE stock_lot_consumption
    ADD CONSTRAINT chk_stock_lot_consumption_cost_arithmetic
        CHECK (total_cost = ROUND(quantity * unit_cost, 2));

ALTER TABLE stocktaking_record
    ADD CONSTRAINT chk_stocktaking_difference_arithmetic
        CHECK (difference_quantity = actual_quantity - book_quantity);

-- An immutable fact may be reversed at most once. Repeated reversals are not a
-- correction; they duplicate the economic effect.
ALTER TABLE financial_event
    ADD UNIQUE KEY uk_financial_event_reversal (reversal_of_event_id);

-- A reversal must negate one original event with exactly the same event type,
-- source identity and counterparty identity. The parent/original guards also
-- reject self-references and chains that attempt to reverse another reversal.
ALTER TABLE financial_event
    ADD COLUMN reversal_source_id_is_null TINYINT
        GENERATED ALWAYS AS (source_id IS NULL) STORED,
    ADD COLUMN reversal_source_id_value BIGINT
        GENERATED ALWAYS AS (IFNULL(source_id, 0)) STORED,
    ADD COLUMN reversal_source_line_id_is_null TINYINT
        GENERATED ALWAYS AS (source_line_id IS NULL) STORED,
    ADD COLUMN reversal_source_line_id_value BIGINT
        GENERATED ALWAYS AS (IFNULL(source_line_id, 0)) STORED,
    ADD COLUMN reversal_counterparty_type_is_null TINYINT
        GENERATED ALWAYS AS (counterparty_type IS NULL) STORED,
    ADD COLUMN reversal_counterparty_type_value VARCHAR(30)
        GENERATED ALWAYS AS (IFNULL(counterparty_type, '')) STORED,
    ADD COLUMN reversal_counterparty_id_is_null TINYINT
        GENERATED ALWAYS AS (counterparty_id IS NULL) STORED,
    ADD COLUMN reversal_counterparty_id_value BIGINT
        GENERATED ALWAYS AS (IFNULL(counterparty_id, 0)) STORED,
    ADD COLUMN reversal_counterparty_name_is_null TINYINT
        GENERATED ALWAYS AS (counterparty_name IS NULL) STORED,
    ADD COLUMN reversal_counterparty_name_value VARCHAR(120)
        GENERATED ALWAYS AS (IFNULL(counterparty_name, '')) STORED,
    ADD COLUMN reversal_amount_match DECIMAL(14, 2)
        GENERATED ALWAYS AS (
            CASE WHEN reversal_of_event_id IS NULL THEN NULL ELSE -amount END
        ) STORED,
    ADD COLUMN reversal_original_guard TINYINT
        GENERATED ALWAYS AS (
            CASE WHEN reversal_of_event_id IS NULL THEN 1 ELSE 0 END
        ) STORED,
    ADD COLUMN reversal_parent_guard TINYINT
        GENERATED ALWAYS AS (
            CASE WHEN reversal_of_event_id IS NULL THEN NULL ELSE 1 END
        ) STORED,
    ADD CONSTRAINT chk_financial_event_type
        CHECK (event_type IN (
            'ACCOUNTS_RECEIVABLE', 'ACCOUNTS_PAYABLE', 'REVENUE',
            'COST_OF_GOODS_SOLD', 'OPERATING_COST', 'INVENTORY_GAIN',
            'INVENTORY_LOSS', 'CASH_RECEIPT', 'CASH_PAYMENT'
        )),
    ADD CONSTRAINT chk_financial_event_sign
        CHECK (
            (reversal_of_event_id IS NULL AND amount >= 0)
            OR (reversal_of_event_id IS NOT NULL AND amount <= 0)
        ),
    ADD CONSTRAINT chk_financial_cash_source_line
        CHECK (
            event_type NOT IN ('CASH_RECEIPT', 'CASH_PAYMENT')
            OR source_line_id IS NULL
        ),
    ADD UNIQUE KEY uk_fin_event_reversal_identity (
        id, event_type, amount, source_type,
        reversal_source_id_is_null, reversal_source_id_value,
        reversal_source_line_id_is_null, reversal_source_line_id_value,
        reversal_counterparty_type_is_null, reversal_counterparty_type_value,
        reversal_counterparty_id_is_null, reversal_counterparty_id_value,
        reversal_counterparty_name_is_null, reversal_counterparty_name_value,
        reversal_original_guard
    );

-- InnoDB cannot resolve a foreign key against generated columns added by the
-- same ALTER TABLE statement. Install it only after those stored columns and
-- their referenced unique index exist.
ALTER TABLE financial_event
    ADD CONSTRAINT fk_fin_event_reversal_identity
        FOREIGN KEY (
            reversal_of_event_id, event_type, reversal_amount_match, source_type,
            reversal_source_id_is_null, reversal_source_id_value,
            reversal_source_line_id_is_null, reversal_source_line_id_value,
            reversal_counterparty_type_is_null, reversal_counterparty_type_value,
            reversal_counterparty_id_is_null, reversal_counterparty_id_value,
            reversal_counterparty_name_is_null, reversal_counterparty_name_value,
            reversal_parent_guard
        )
        REFERENCES financial_event (
            id, event_type, amount, source_type,
            reversal_source_id_is_null, reversal_source_id_value,
            reversal_source_line_id_is_null, reversal_source_line_id_value,
            reversal_counterparty_type_is_null, reversal_counterparty_type_value,
            reversal_counterparty_id_is_null, reversal_counterparty_id_value,
            reversal_counterparty_name_is_null, reversal_counterparty_name_value,
            reversal_original_guard
        )
        ON DELETE RESTRICT;

ALTER TABLE stock_lot_consumption
    ADD UNIQUE KEY uk_stock_lot_consumption_reversal (reversal_of_consumption_id);

ALTER TABLE stock_movement
    ADD UNIQUE KEY uk_stock_movement_reversal (reversal_of_movement_id),
    ADD CONSTRAINT fk_stock_movement_reversal
        FOREIGN KEY (reversal_of_movement_id) REFERENCES stock_movement (id)
        ON DELETE RESTRICT;

-- Movement headers have no monetary amount, but a reversal still belongs to
-- the same resource and nullable source identity and may target only an
-- original movement.
ALTER TABLE stock_movement
    ADD COLUMN reversal_source_type_is_null TINYINT
        GENERATED ALWAYS AS (source_type IS NULL) STORED,
    ADD COLUMN reversal_source_type_value VARCHAR(40)
        GENERATED ALWAYS AS (IFNULL(source_type, '')) STORED,
    ADD COLUMN reversal_source_id_is_null TINYINT
        GENERATED ALWAYS AS (source_id IS NULL) STORED,
    ADD COLUMN reversal_source_id_value BIGINT
        GENERATED ALWAYS AS (IFNULL(source_id, 0)) STORED,
    ADD COLUMN reversal_source_line_id_is_null TINYINT
        GENERATED ALWAYS AS (source_line_id IS NULL) STORED,
    ADD COLUMN reversal_source_line_id_value BIGINT
        GENERATED ALWAYS AS (IFNULL(source_line_id, 0)) STORED,
    ADD COLUMN reversal_original_guard TINYINT
        GENERATED ALWAYS AS (
            CASE WHEN reversal_of_movement_id IS NULL THEN 1 ELSE 0 END
        ) STORED,
    ADD COLUMN reversal_parent_guard TINYINT
        GENERATED ALWAYS AS (
            CASE WHEN reversal_of_movement_id IS NULL THEN NULL ELSE 1 END
        ) STORED,
    ADD UNIQUE KEY uk_stock_move_reversal_identity (
        id, resource_type,
        reversal_source_type_is_null, reversal_source_type_value,
        reversal_source_id_is_null, reversal_source_id_value,
        reversal_source_line_id_is_null, reversal_source_line_id_value,
        reversal_original_guard
    );

ALTER TABLE stock_movement
    ADD CONSTRAINT fk_stock_move_reversal_identity
        FOREIGN KEY (
            reversal_of_movement_id, resource_type,
            reversal_source_type_is_null, reversal_source_type_value,
            reversal_source_id_is_null, reversal_source_id_value,
            reversal_source_line_id_is_null, reversal_source_line_id_value,
            reversal_parent_guard
        )
        REFERENCES stock_movement (
            id, resource_type,
            reversal_source_type_is_null, reversal_source_type_value,
            reversal_source_id_is_null, reversal_source_id_value,
            reversal_source_line_id_is_null, reversal_source_line_id_value,
            reversal_original_guard
        )
        ON DELETE RESTRICT;
