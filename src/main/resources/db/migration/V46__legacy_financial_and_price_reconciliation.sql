-- Reconcile facts that older migrations could derive deterministically.
-- Ambiguous historical costs are deliberately cleared and surfaced through
-- migration_exception instead of retaining values inferred from mutable master
-- prices. Production upgrades must be performed with business writes stopped.

-- A payment row is only trustworthy when it points to the exact cash event
-- carrying the same direction, amount and source identity. Ambiguous rows are
-- surfaced and excluded from all automatic receipt reconstruction.
INSERT INTO migration_exception (
    exception_type, source_type, source_id, detail, status, created_at
)
SELECT
    'INVALID_PAYMENT_EVENT_IDENTITY',
    'PAYMENT_RECORD',
    payment.id,
    CONCAT(
        'direction=', payment.direction,
        ', amount=', payment.amount,
        ', source=', payment.source_type, ':', COALESCE(payment.source_id, 'NULL'),
        ', financial_event_id=', COALESCE(payment.financial_event_id, 'NULL')
    ),
    'OPEN',
    CURRENT_TIMESTAMP(6)
FROM payment_record payment
LEFT JOIN financial_event event
  ON event.id = payment.financial_event_id
 AND event.event_type = CASE payment.direction
      WHEN 'RECEIPT' THEN 'CASH_RECEIPT'
      WHEN 'PAYMENT' THEN 'CASH_PAYMENT'
      ELSE NULL
     END
 AND event.amount = payment.amount
 AND event.source_type = payment.source_type
 AND event.source_id = payment.source_id
 AND event.source_line_id IS NULL
WHERE (payment.source_id IS NULL OR event.id IS NULL)
  AND NOT EXISTS (
      SELECT 1 FROM migration_exception existing
      WHERE existing.exception_type = 'INVALID_PAYMENT_EVENT_IDENTITY'
        AND existing.source_type = 'PAYMENT_RECORD'
        AND existing.source_id = payment.id
        AND existing.status = 'OPEN'
  );

-- V19 added received_amount with DEFAULT 0 before attempting to backfill it.
-- V37 then introduced the cash ledger without migrating legacy receipts. For
-- every order that already existed when V37 ran, the legacy target is:
--   * receivable_amount when the old payment_settled flag was true;
--   * otherwise the explicitly stored received_amount.
-- Only the positive difference from the current immutable payment ledger is
-- reconstructed, so an already repaired database is not double-posted.
INSERT INTO financial_event (
    event_no,
    event_type,
    amount,
    business_date,
    source_type,
    source_id,
    counterparty_type,
    counterparty_id,
    counterparty_name,
    remark,
    idempotency_key,
    created_by,
    created_at
)
SELECT
    CONCAT('MIG-V46-RECEIPT-', o.id),
    'CASH_RECEIPT',
    targets.target_amount - COALESCE(payments.receipt_total, 0),
    COALESCE(o.sales_date, DATE(o.created_at), CURRENT_DATE()),
    'OUTBOUND_ORDER',
    o.id,
    'CUSTOMER',
    o.customer_id,
    o.customer_name,
    'Reconstructed legacy receipt from pre-ledger settlement fields',
    CONCAT('MIGRATION:V46:OUTBOUND:', o.id, ':RECEIPT:EVENT'),
    'flyway-v46',
    CURRENT_TIMESTAMP(6)
FROM outbound_order o
JOIN (
    SELECT
        id,
        CASE
            WHEN payment_settled = b'1'
                THEN COALESCE(receivable_amount, line_amount, settlement_price, sale_price, 0)
            ELSE COALESCE(received_amount, 0)
        END AS target_amount
    FROM outbound_order
) targets ON targets.id = o.id
LEFT JOIN (
    SELECT source_id, SUM(amount) AS receipt_total
    FROM payment_record
    WHERE source_type = 'OUTBOUND_ORDER'
      AND direction = 'RECEIPT'
    GROUP BY source_id
) payments ON payments.source_id = o.id
WHERE o.financial_posted = b'0'
  AND targets.target_amount > COALESCE(payments.receipt_total, 0)
  AND COALESCE(payments.receipt_total, 0) >= 0
  AND NOT EXISTS (
      SELECT 1
      FROM payment_record payment
      LEFT JOIN financial_event event
        ON event.id = payment.financial_event_id
       AND event.event_type = CASE payment.direction
            WHEN 'RECEIPT' THEN 'CASH_RECEIPT'
            WHEN 'PAYMENT' THEN 'CASH_PAYMENT'
            ELSE NULL
           END
       AND event.amount = payment.amount
       AND event.source_type = payment.source_type
       AND event.source_id = payment.source_id
       AND event.source_line_id IS NULL
      WHERE payment.source_type = 'OUTBOUND_ORDER'
        AND payment.source_id = o.id
        AND event.id IS NULL
  )
  AND NOT EXISTS (
      SELECT 1
      FROM financial_event existing
      WHERE existing.idempotency_key = CONCAT('MIGRATION:V46:OUTBOUND:', o.id, ':RECEIPT:EVENT')
  );

INSERT INTO payment_record (
    payment_no,
    request_id,
    direction,
    amount,
    payment_date,
    source_type,
    source_id,
    financial_event_id,
    remark,
    idempotency_key,
    created_by,
    created_at
)
SELECT
    CONCAT('MIG-V46-PAY-', o.id),
    CONCAT('MIGRATION:V46:OUTBOUND:', o.id, ':RECEIPT'),
    'RECEIPT',
    event.amount,
    event.business_date,
    'OUTBOUND_ORDER',
    o.id,
    event.id,
    'Reconstructed legacy receipt from pre-ledger settlement fields',
    CONCAT('MIGRATION:V46:OUTBOUND:', o.id, ':RECEIPT'),
    'flyway-v46',
    CURRENT_TIMESTAMP(6)
FROM outbound_order o
JOIN financial_event event
  ON event.idempotency_key = CONCAT('MIGRATION:V46:OUTBOUND:', o.id, ':RECEIPT:EVENT')
WHERE NOT EXISTS (
    SELECT 1
    FROM payment_record existing
    WHERE existing.idempotency_key = CONCAT('MIGRATION:V46:OUTBOUND:', o.id, ':RECEIPT')
);

UPDATE outbound_order o
LEFT JOIN (
    SELECT source_id, SUM(amount) AS receipt_total, MAX(payment_date) AS last_payment_date
    FROM payment_record
    WHERE source_type = 'OUTBOUND_ORDER'
      AND direction = 'RECEIPT'
    GROUP BY source_id
) payments ON payments.source_id = o.id
SET o.received_amount = COALESCE(payments.receipt_total, 0),
    o.last_payment_date = CASE
        WHEN COALESCE(payments.receipt_total, 0) > 0
            THEN COALESCE(o.last_payment_date, payments.last_payment_date)
        ELSE NULL
    END
WHERE o.financial_posted = b'0'
  AND NOT EXISTS (
      SELECT 1
      FROM payment_record payment
      JOIN migration_exception exception_row
        ON exception_row.exception_type = 'INVALID_PAYMENT_EVENT_IDENTITY'
       AND exception_row.source_type = 'PAYMENT_RECORD'
       AND exception_row.source_id = payment.id
       AND exception_row.status = 'OPEN'
      WHERE payment.source_type = 'OUTBOUND_ORDER'
        AND payment.source_id = o.id
  );

-- Before V37 a part order's settlement_price represented the whole line.
-- Keep line_amount authoritative and derive the effective unit price.
UPDATE outbound_order o
SET o.unit_sale_price = ROUND(
        COALESCE(o.line_amount, o.receivable_amount, o.settlement_price, o.sale_price, 0)
        / o.quantity,
        2
    ),
    o.settlement_price = ROUND(
        COALESCE(o.line_amount, o.receivable_amount, o.settlement_price, o.sale_price, 0)
        / o.quantity,
        2
    )
WHERE o.resource_type = 'PART'
  AND o.quantity > 0
  AND o.financial_posted = b'0'
  AND (
      o.unit_sale_price IS NULL
      OR ABS(
          COALESCE(o.line_amount, o.receivable_amount, o.settlement_price, o.sale_price, 0)
          - o.unit_sale_price * o.quantity
      ) >= 0.01
  );

UPDATE stock_operation_log log
JOIN outbound_order o ON o.stock_operation_log_id = log.id
SET log.unit_revenue = o.unit_sale_price
WHERE o.resource_type = 'PART'
  AND o.quantity > 0
  AND o.financial_posted = b'0';

-- V33 filled every pre-existing stock log cost from the then-current master
-- price. Such a value is not an auditable transaction snapshot, so preserve
-- the uncertainty explicitly instead of presenting it as historical truth.
INSERT INTO migration_exception (
    exception_type, source_type, source_id, detail, status, created_at
)
SELECT
    'UNVERIFIED_STOCK_LOG_COST',
    'STOCK_OPERATION_LOG',
    log.id,
    'V33 inferred unit_cost from mutable inventory master data; authoritative historical cost is unavailable',
    'OPEN',
    CURRENT_TIMESTAMP(6)
FROM stock_operation_log log
WHERE log.unit_cost IS NOT NULL
  AND (
      log.created_at IS NULL OR log.created_at <= (
          SELECT installed_on
          FROM flyway_schema_history
          WHERE version = '33' AND success = 1
          ORDER BY installed_rank DESC
          LIMIT 1
      )
      OR EXISTS (
          SELECT 1 FROM outbound_order legacy_order
          WHERE legacy_order.stock_operation_log_id = log.id
            AND legacy_order.financial_posted = b'0'
      )
  )
  AND NOT EXISTS (
      SELECT 1 FROM migration_exception existing
      WHERE existing.exception_type = 'UNVERIFIED_STOCK_LOG_COST'
        AND existing.source_type = 'STOCK_OPERATION_LOG'
        AND existing.source_id = log.id
  );

UPDATE stock_operation_log log
SET log.unit_cost = NULL
WHERE log.created_at IS NULL
   OR log.created_at <= (
       SELECT installed_on
       FROM flyway_schema_history
       WHERE version = '33' AND success = 1
       ORDER BY installed_rank DESC
       LIMIT 1
   )
   OR EXISTS (
       SELECT 1 FROM outbound_order legacy_order
       WHERE legacy_order.stock_operation_log_id = log.id
         AND legacy_order.financial_posted = b'0'
   );

-- V34 similarly inferred repair part cost from current prices and ignored
-- quantities in its CSV field. Mark those values unknown for human repair.
INSERT INTO migration_exception (
    exception_type, source_type, source_id, detail, status, created_at
)
SELECT
    'UNVERIFIED_REPAIR_PARTS_COST',
    'REPAIR_RECORD',
    repair.id,
    'V34 inferred parts_cost from mutable part prices and a quantity-free CSV list; authoritative cost is unavailable',
    'OPEN',
    CURRENT_TIMESTAMP(6)
FROM repair_record repair
WHERE repair.used_part_ids IS NOT NULL
  AND repair.used_part_ids <> ''
  AND NOT EXISTS (
      SELECT 1 FROM repair_part_usage part_usage
      WHERE part_usage.repair_id = repair.id
  )
  AND NOT EXISTS (
      SELECT 1 FROM migration_exception existing
      WHERE existing.exception_type = 'UNVERIFIED_REPAIR_PARTS_COST'
        AND existing.source_type = 'REPAIR_RECORD'
        AND existing.source_id = repair.id
  );

UPDATE repair_record repair
SET repair.parts_cost = NULL
WHERE repair.used_part_ids IS NOT NULL
  AND repair.used_part_ids <> ''
  AND NOT EXISTS (
      SELECT 1 FROM repair_part_usage part_usage
      WHERE part_usage.repair_id = repair.id
  );

-- V37 did not create complete FIFO layers for legacy balances. Record every
-- mismatch as a release-blocking data-quality item; fabricating a lot cost from
-- today's master price would repeat the historical-cost error above.
INSERT INTO migration_exception (
    exception_type, source_type, source_id, detail, status, created_at
)
SELECT
    'FIFO_BALANCE_MISMATCH',
    'STOCK_BALANCE',
    balance.id,
    CONCAT(
        'available_quantity=', balance.available_quantity,
        ', open_lot_quantity=', COALESCE(lots.remaining_quantity, 0),
        '; reconcile from immutable receiving evidence before trusting FIFO cost'
    ),
    'OPEN',
    CURRENT_TIMESTAMP(6)
FROM stock_balance balance
LEFT JOIN (
    SELECT resource_type, resource_id, warehouse_id, SUM(remaining_quantity) AS remaining_quantity
    FROM stock_lot
    GROUP BY resource_type, resource_id, warehouse_id
) lots
  ON lots.resource_type = balance.resource_type
 AND lots.resource_id = balance.resource_id
 AND lots.warehouse_id = balance.warehouse_id
WHERE balance.available_quantity <> COALESCE(lots.remaining_quantity, 0)
  AND NOT EXISTS (
      SELECT 1 FROM migration_exception existing
      WHERE existing.exception_type = 'FIFO_BALANCE_MISMATCH'
        AND existing.source_type = 'STOCK_BALANCE'
        AND existing.source_id = balance.id
        AND existing.status = 'OPEN'
  );

-- Cash can be reconstructed from legacy settlement fields, but AR/revenue and
-- COGS completeness still require explicit reconciliation. Surface the gap.
INSERT INTO migration_exception (
    exception_type, source_type, source_id, detail, status, created_at
)
SELECT
    'MISSING_LEGACY_SALES_POSTING',
    'OUTBOUND_ORDER',
    o.id,
    'Legacy outbound order has no complete AR/revenue/COGS posting; reconcile before marking financial_posted',
    'OPEN',
    CURRENT_TIMESTAMP(6)
FROM outbound_order o
WHERE o.financial_posted = b'0'
  AND (
      NOT EXISTS (
          SELECT 1 FROM financial_event event
          WHERE event.source_type = 'OUTBOUND_ORDER'
            AND event.source_id = o.id
            AND event.event_type = 'ACCOUNTS_RECEIVABLE'
      )
      OR NOT EXISTS (
          SELECT 1 FROM financial_event event
          WHERE event.source_type = 'OUTBOUND_ORDER'
            AND event.source_id = o.id
            AND event.event_type = 'REVENUE'
      )
      OR NOT EXISTS (
          SELECT 1 FROM financial_event event
          WHERE event.source_type = 'OUTBOUND_ORDER'
            AND event.source_id = o.id
            AND event.event_type = 'COST_OF_GOODS_SOLD'
      )
  )
  AND NOT EXISTS (
      SELECT 1 FROM migration_exception existing
      WHERE existing.exception_type = 'MISSING_LEGACY_SALES_POSTING'
        AND existing.source_type = 'OUTBOUND_ORDER'
        AND existing.source_id = o.id
        AND existing.status = 'OPEN'
  );
