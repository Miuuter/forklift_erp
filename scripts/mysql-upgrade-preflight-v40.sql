-- Forklift ERP V40+ read-only upgrade preflight.
--
-- Run while business writes are stopped, before deploying migrations V41+:
--   mysql --database=forklift_erp --table < scripts/mysql-upgrade-preflight-v40.sql
--
-- Every returned row is a blocking item unless the release runbook explicitly
-- identifies it as a deterministic V46 repair. The script intentionally never
-- updates data. It is compatible with a V40 schema and therefore does not
-- reference columns introduced by V41 or later.

SELECT issue_type, table_name, row_id, detail
FROM (
    SELECT 'DUPLICATE_PAYMENT_REVERSAL' AS issue_type, 'payment_record' AS table_name,
           CAST(reversal_of_payment_id AS CHAR) AS row_id,
           -- This explicit collation anchors the UNION detail column to the
           -- V40 schema collation. Without it, connection-collation literals
           -- can conflict with later CONCAT results inherited from columns.
           CONVERT(CONCAT('reversal rows=', COUNT(*)) USING utf8mb4)
               COLLATE utf8mb4_unicode_ci AS detail
    FROM payment_record
    WHERE reversal_of_payment_id IS NOT NULL
    GROUP BY reversal_of_payment_id
    HAVING COUNT(*) > 1

    UNION ALL
    SELECT 'DUPLICATE_FINANCIAL_REVERSAL', 'financial_event',
           CAST(reversal_of_event_id AS CHAR), CONCAT('reversal rows=', COUNT(*))
    FROM financial_event
    WHERE reversal_of_event_id IS NOT NULL
    GROUP BY reversal_of_event_id
    HAVING COUNT(*) > 1

    UNION ALL
    SELECT 'DUPLICATE_LOT_CONSUMPTION_REVERSAL', 'stock_lot_consumption',
           CAST(reversal_of_consumption_id AS CHAR), CONCAT('reversal rows=', COUNT(*))
    FROM stock_lot_consumption
    WHERE reversal_of_consumption_id IS NOT NULL
    GROUP BY reversal_of_consumption_id
    HAVING COUNT(*) > 1

    UNION ALL
    SELECT 'DUPLICATE_STOCK_MOVEMENT_REVERSAL', 'stock_movement',
           CAST(reversal_of_movement_id AS CHAR), CONCAT('reversal rows=', COUNT(*))
    FROM stock_movement
    WHERE reversal_of_movement_id IS NOT NULL
    GROUP BY reversal_of_movement_id
    HAVING COUNT(*) > 1

    UNION ALL
    SELECT 'ORPHAN_PAYMENT_EVENT', 'payment_record', CAST(child.id AS CHAR),
           CONCAT('financial_event_id=', child.financial_event_id)
    FROM payment_record child
    LEFT JOIN financial_event parent ON parent.id = child.financial_event_id
    WHERE child.financial_event_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'NULL_PAYMENT_IDENTITY', 'payment_record', CAST(id AS CHAR),
           CONCAT('source_id=', COALESCE(CAST(source_id AS CHAR), 'NULL'),
                  ', financial_event_id=', COALESCE(CAST(financial_event_id AS CHAR), 'NULL'))
    FROM payment_record
    WHERE source_id IS NULL OR financial_event_id IS NULL

    UNION ALL
    SELECT 'DUPLICATE_PAYMENT_EVENT_LINK', 'payment_record',
           CAST(financial_event_id AS CHAR), CONCAT('payment rows=', COUNT(*))
    FROM payment_record
    WHERE financial_event_id IS NOT NULL
    GROUP BY financial_event_id
    HAVING COUNT(*) > 1

    UNION ALL
    SELECT 'INVALID_PAYMENT_EVENT_IDENTITY', 'payment_record', CAST(payment.id AS CHAR),
           CONCAT('direction=', payment.direction, ', amount=', payment.amount,
                  ', source=', payment.source_type, ':', payment.source_id,
                  ', event=', payment.financial_event_id)
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
    WHERE payment.financial_event_id IS NOT NULL AND event.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_PAYMENT_REVERSAL', 'payment_record', CAST(child.id AS CHAR),
           CONCAT('reversal_of_payment_id=', child.reversal_of_payment_id)
    FROM payment_record child
    LEFT JOIN payment_record parent ON parent.id = child.reversal_of_payment_id
    WHERE child.reversal_of_payment_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'INVALID_LINKED_PAYMENT_SIGN', 'payment_record', CAST(child.id AS CHAR),
           CONCAT('amount=', child.amount,
                  ', reversal_of_payment_id=', child.reversal_of_payment_id)
    FROM payment_record child
    WHERE child.reversal_of_payment_id IS NOT NULL
      AND child.amount >= 0

    UNION ALL
    SELECT 'INVALID_PAYMENT_REVERSAL_IDENTITY', 'payment_record', CAST(child.id AS CHAR),
           CONCAT('parent_payment_id=', child.reversal_of_payment_id,
                  ', child_event_id=', child.financial_event_id,
                  ', parent_event_id=', parent.financial_event_id)
    FROM payment_record child
    JOIN payment_record parent ON parent.id = child.reversal_of_payment_id
    LEFT JOIN financial_event child_event ON child_event.id = child.financial_event_id
    LEFT JOIN financial_event parent_event ON parent_event.id = parent.financial_event_id
    WHERE child.id = parent.id
       OR parent.reversal_of_payment_id IS NOT NULL
       OR child.direction <> parent.direction
       OR child.amount <> -parent.amount
       OR NOT (child.source_type <=> parent.source_type)
       OR NOT (child.source_id <=> parent.source_id)
       OR child_event.id IS NULL
       OR parent_event.id IS NULL
       OR parent_event.reversal_of_event_id IS NOT NULL
       OR NOT (child_event.source_line_id <=> parent_event.source_line_id)
       OR NOT (child_event.counterparty_type <=> parent_event.counterparty_type)
       OR NOT (child_event.counterparty_id <=> parent_event.counterparty_id)
       OR NOT (child_event.counterparty_name <=> parent_event.counterparty_name)
       OR (child_event.reversal_of_event_id IS NOT NULL
           AND child_event.reversal_of_event_id <> parent_event.id)
       OR EXISTS (
           SELECT 1
           FROM financial_event occupied_event
           WHERE occupied_event.reversal_of_event_id = parent_event.id
             AND occupied_event.id <> child_event.id
       )

    UNION ALL
    SELECT 'UNLINKED_NON_POSITIVE_PAYMENT', 'payment_record', CAST(id AS CHAR),
           CONCAT('amount=', amount,
                  ', reversal_of_payment_id=', COALESCE(CAST(reversal_of_payment_id AS CHAR), 'NULL'))
    FROM payment_record
    WHERE amount <= 0 AND reversal_of_payment_id IS NULL

    UNION ALL
    SELECT 'ORPHAN_FINANCIAL_REVERSAL', 'financial_event', CAST(child.id AS CHAR),
           CONCAT('reversal_of_event_id=', child.reversal_of_event_id)
    FROM financial_event child
    LEFT JOIN financial_event parent ON parent.id = child.reversal_of_event_id
    WHERE child.reversal_of_event_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'INVALID_FINANCIAL_REVERSAL_IDENTITY', 'financial_event', CAST(child.id AS CHAR),
           CONCAT('parent_event_id=', child.reversal_of_event_id,
                  ', child_amount=', child.amount,
                  ', parent_amount=', parent.amount)
    FROM financial_event child
    JOIN financial_event parent ON parent.id = child.reversal_of_event_id
    WHERE child.id = parent.id
       OR parent.reversal_of_event_id IS NOT NULL
       OR child.event_type <> parent.event_type
       OR child.amount <> -parent.amount
       OR NOT (child.source_type <=> parent.source_type)
       OR NOT (child.source_id <=> parent.source_id)
       OR NOT (child.source_line_id <=> parent.source_line_id)
       OR NOT (child.counterparty_type <=> parent.counterparty_type)
       OR NOT (child.counterparty_id <=> parent.counterparty_id)
       OR NOT (child.counterparty_name <=> parent.counterparty_name)

    UNION ALL
    SELECT 'INVALID_FINANCIAL_EVENT_SIGN', 'financial_event', CAST(event_row.id AS CHAR),
           CONCAT('amount=', event_row.amount,
                  ', reversal_of_event_id=',
                  COALESCE(CAST(event_row.reversal_of_event_id AS CHAR), 'NULL'))
    FROM financial_event event_row
    WHERE (
          (event_row.reversal_of_event_id IS NULL AND event_row.amount < 0)
       OR (event_row.reversal_of_event_id IS NOT NULL AND event_row.amount > 0)
    )
      -- V48 deterministically fills this link for an exact legacy payment
      -- reversal before installing the sign constraint. Identity mismatches
      -- are still reported by INVALID_PAYMENT_REVERSAL_IDENTITY.
      AND NOT EXISTS (
          SELECT 1
          FROM payment_record reversal_payment
          WHERE reversal_payment.financial_event_id = event_row.id
            AND reversal_payment.reversal_of_payment_id IS NOT NULL
      )

    UNION ALL
    SELECT 'UNKNOWN_FINANCIAL_EVENT_TYPE', 'financial_event', CAST(id AS CHAR),
           CONCAT('event_type=', event_type)
    FROM financial_event
    WHERE event_type NOT IN (
        'ACCOUNTS_RECEIVABLE', 'ACCOUNTS_PAYABLE', 'REVENUE',
        'COST_OF_GOODS_SOLD', 'OPERATING_COST', 'INVENTORY_GAIN',
        'INVENTORY_LOSS', 'CASH_RECEIPT', 'CASH_PAYMENT'
    )

    UNION ALL
    SELECT 'CASH_EVENT_SOURCE_LINE', 'financial_event', CAST(id AS CHAR),
           CONCAT('event_type=', event_type, ', source_line_id=', source_line_id)
    FROM financial_event
    WHERE event_type IN ('CASH_RECEIPT', 'CASH_PAYMENT')
      AND source_line_id IS NOT NULL

    UNION ALL
    SELECT 'ORPHAN_STOCK_MOVEMENT_REVERSAL', 'stock_movement', CAST(child.id AS CHAR),
           CONCAT('reversal_of_movement_id=', child.reversal_of_movement_id)
    FROM stock_movement child
    LEFT JOIN stock_movement parent ON parent.id = child.reversal_of_movement_id
    WHERE child.reversal_of_movement_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'INVALID_STOCK_MOVEMENT_REVERSAL_IDENTITY', 'stock_movement', CAST(child.id AS CHAR),
           CONCAT('parent_movement_id=', child.reversal_of_movement_id,
                  ', resource_type=', child.resource_type,
                  ', source_type=', child.source_type,
                  ', source_id=', child.source_id,
                  ', source_line_id=', child.source_line_id)
    FROM stock_movement child
    JOIN stock_movement parent ON parent.id = child.reversal_of_movement_id
    WHERE child.id = parent.id
       OR parent.reversal_of_movement_id IS NOT NULL
       OR child.resource_type <> parent.resource_type
       OR NOT (child.source_type <=> parent.source_type)
       OR NOT (child.source_id <=> parent.source_id)
       OR NOT (child.source_line_id <=> parent.source_line_id)

    UNION ALL
    SELECT 'INVALID_STOCK_MOVEMENT_REVERSAL_LINES', 'stock_movement',
           CAST(child.id AS CHAR),
           CONCAT('parent_movement_id=', child.reversal_of_movement_id,
                  ', reversal_lines=', (
                      SELECT COUNT(*) FROM stock_movement_line child_line
                      WHERE child_line.movement_id = child.id
                  ),
                  ', original_lines=', (
                      SELECT COUNT(*) FROM stock_movement_line parent_line
                      WHERE parent_line.movement_id = parent.id
                  ))
    FROM stock_movement child
    JOIN stock_movement parent ON parent.id = child.reversal_of_movement_id
    WHERE NOT EXISTS (
              SELECT 1
              FROM stock_movement_line parent_line
              WHERE parent_line.movement_id = parent.id
          )
       OR (
              SELECT COUNT(*)
              FROM stock_movement_line child_line
              WHERE child_line.movement_id = child.id
          ) <> (
              SELECT COUNT(*)
              FROM stock_movement_line parent_line
              WHERE parent_line.movement_id = parent.id
          )
       OR EXISTS (
              SELECT 1
              FROM stock_movement_line parent_line
              WHERE parent_line.movement_id = parent.id
                AND (
                    SELECT COUNT(*)
                    FROM stock_movement_line parent_same
                    WHERE parent_same.movement_id = parent.id
                      AND parent_same.resource_type = parent_line.resource_type
                      AND parent_same.resource_id = parent_line.resource_id
                      AND parent_same.warehouse_id = parent_line.warehouse_id
                      AND parent_same.stock_lot_id <=> parent_line.stock_lot_id
                      AND parent_same.source_line_id <=> parent_line.source_line_id
                      AND parent_same.quantity_delta = parent_line.quantity_delta
                      AND parent_same.before_quantity = parent_line.before_quantity
                      AND parent_same.after_quantity = parent_line.after_quantity
                      AND parent_same.unit_cost <=> parent_line.unit_cost
                      AND parent_same.unit_revenue <=> parent_line.unit_revenue
                      AND parent_same.line_amount <=> parent_line.line_amount
                      AND parent_same.cost_amount <=> parent_line.cost_amount
                ) <> (
                    SELECT COUNT(*)
                    FROM stock_movement_line child_line
                    WHERE child_line.movement_id = child.id
                      AND child_line.resource_type = parent_line.resource_type
                      AND child_line.resource_id = parent_line.resource_id
                      AND child_line.warehouse_id = parent_line.warehouse_id
                      AND child_line.stock_lot_id <=> parent_line.stock_lot_id
                      AND child_line.source_line_id <=> parent_line.source_line_id
                      AND child_line.quantity_delta = -parent_line.quantity_delta
                      AND child_line.before_quantity = parent_line.after_quantity
                      AND child_line.after_quantity = parent_line.before_quantity
                      AND child_line.unit_cost <=> parent_line.unit_cost
                      AND child_line.unit_revenue <=> parent_line.unit_revenue
                      AND child_line.line_amount <=> parent_line.line_amount
                      AND child_line.cost_amount <=> parent_line.cost_amount
                )
          )

    UNION ALL
    SELECT 'ORPHAN_RENTAL_BILL_RENTAL', 'rental_bill', CAST(child.id AS CHAR),
           CONCAT('rental_id=', child.rental_id)
    FROM rental_bill child
    LEFT JOIN rental_record parent ON parent.id = child.rental_id
    WHERE parent.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_RENTAL_BILL_EVENT', 'rental_bill', CAST(child.id AS CHAR),
           CONCAT('financial_event_id=', child.financial_event_id)
    FROM rental_bill child
    LEFT JOIN financial_event parent ON parent.id = child.financial_event_id
    WHERE child.financial_event_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_LOT_CONSUMPTION_LOT', 'stock_lot_consumption', CAST(child.id AS CHAR),
           CONCAT('stock_lot_id=', child.stock_lot_id)
    FROM stock_lot_consumption child
    LEFT JOIN stock_lot parent ON parent.id = child.stock_lot_id
    WHERE parent.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_LOT_CONSUMPTION_REVERSAL', 'stock_lot_consumption', CAST(child.id AS CHAR),
           CONCAT('reversal_of_consumption_id=', child.reversal_of_consumption_id)
    FROM stock_lot_consumption child
    LEFT JOIN stock_lot_consumption parent ON parent.id = child.reversal_of_consumption_id
    WHERE child.reversal_of_consumption_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'INVALID_LINKED_LOT_CONSUMPTION_SIGN', 'stock_lot_consumption', CAST(child.id AS CHAR),
           CONCAT('quantity=', child.quantity, ', total_cost=', child.total_cost,
                  ', reversal_of_consumption_id=', child.reversal_of_consumption_id)
    FROM stock_lot_consumption child
    WHERE child.reversal_of_consumption_id IS NOT NULL
      AND (child.quantity >= 0 OR child.total_cost > 0)

    UNION ALL
    SELECT 'UNLINKED_NON_POSITIVE_LOT_CONSUMPTION', 'stock_lot_consumption', CAST(child.id AS CHAR),
           CONCAT('quantity=', child.quantity, ', total_cost=', child.total_cost)
    FROM stock_lot_consumption child
    WHERE child.reversal_of_consumption_id IS NULL
      AND (child.quantity <= 0 OR child.total_cost < 0)

    UNION ALL
    SELECT 'ORPHAN_LOT_COST_ADJUSTMENT', 'stock_lot_cost_adjustment', CAST(child.id AS CHAR),
           CONCAT('stock_lot_id=', child.stock_lot_id)
    FROM stock_lot_cost_adjustment child
    LEFT JOIN stock_lot parent ON parent.id = child.stock_lot_id
    WHERE parent.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_MOVEMENT_LINE_LOT', 'stock_movement_line', CAST(child.id AS CHAR),
           CONCAT('stock_lot_id=', child.stock_lot_id)
    FROM stock_movement_line child
    LEFT JOIN stock_lot parent ON parent.id = child.stock_lot_id
    WHERE child.stock_lot_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_REPAIR_USAGE_CONSUMPTION', 'repair_part_usage', CAST(child.id AS CHAR),
           CONCAT('stock_lot_consumption_id=', child.stock_lot_consumption_id)
    FROM repair_part_usage child
    LEFT JOIN stock_lot_consumption parent ON parent.id = child.stock_lot_consumption_id
    WHERE child.stock_lot_consumption_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_IMPORT_ROW_JOB', 'data_import_row', CAST(child.id AS CHAR),
           CONCAT('import_job_id=', child.import_job_id)
    FROM data_import_row child
    LEFT JOIN data_import_job parent ON parent.id = child.import_job_id
    WHERE parent.id IS NULL

    UNION ALL
    SELECT 'INVALID_STOCK_BALANCE', 'stock_balance', CAST(id AS CHAR),
           CONCAT('available=', available_quantity, ', reserved=', reserved_quantity,
                  ', locked=', locked_quantity)
    FROM stock_balance
    WHERE available_quantity < 0 OR reserved_quantity < 0 OR locked_quantity < 0

    UNION ALL
    SELECT 'ORPHAN_LEDGER_RESOURCE', 'stock_balance', CAST(ledger.id AS CHAR),
           CONCAT('resource=', ledger.resource_type, ':', ledger.resource_id)
    FROM stock_balance ledger
    LEFT JOIN machine_inventory machine
      ON ledger.resource_type = 'MACHINE' AND machine.id = ledger.resource_id
    LEFT JOIN part_inventory part
      ON ledger.resource_type = 'PART' AND part.id = ledger.resource_id
    WHERE (ledger.resource_type = 'MACHINE' AND machine.id IS NULL)
       OR (ledger.resource_type = 'PART' AND part.id IS NULL)

    UNION ALL
    SELECT 'ORPHAN_LEDGER_RESOURCE', 'stock_lot', CAST(ledger.id AS CHAR),
           CONCAT('resource=', ledger.resource_type, ':', ledger.resource_id)
    FROM stock_lot ledger
    LEFT JOIN machine_inventory machine
      ON ledger.resource_type = 'MACHINE' AND machine.id = ledger.resource_id
    LEFT JOIN part_inventory part
      ON ledger.resource_type = 'PART' AND part.id = ledger.resource_id
    WHERE (ledger.resource_type = 'MACHINE' AND machine.id IS NULL)
       OR (ledger.resource_type = 'PART' AND part.id IS NULL)

    UNION ALL
    SELECT 'ORPHAN_LEDGER_RESOURCE', 'stock_movement_line', CAST(ledger.id AS CHAR),
           CONCAT('resource=', ledger.resource_type, ':', ledger.resource_id)
    FROM stock_movement_line ledger
    LEFT JOIN machine_inventory machine
      ON ledger.resource_type = 'MACHINE' AND machine.id = ledger.resource_id
    LEFT JOIN part_inventory part
      ON ledger.resource_type = 'PART' AND part.id = ledger.resource_id
    WHERE (ledger.resource_type = 'MACHINE' AND machine.id IS NULL)
       OR (ledger.resource_type = 'PART' AND part.id IS NULL)

    UNION ALL
    SELECT 'ORPHAN_LEDGER_RESOURCE', 'stock_operation_log', CAST(ledger.id AS CHAR),
           CONCAT('resource=', ledger.resource_type, ':', ledger.resource_id)
    FROM stock_operation_log ledger
    LEFT JOIN machine_inventory machine
      ON ledger.resource_type = 'MACHINE' AND machine.id = ledger.resource_id
    LEFT JOIN part_inventory part
      ON ledger.resource_type = 'PART' AND part.id = ledger.resource_id
    WHERE (ledger.resource_type = 'MACHINE' AND machine.id IS NULL)
       OR (ledger.resource_type = 'PART' AND part.id IS NULL)

    UNION ALL
    SELECT 'INVALID_STOCK_LOT', 'stock_lot', CAST(id AS CHAR),
           CONCAT('original=', original_quantity, ', remaining=', remaining_quantity,
                  ', unit_cost=', unit_cost)
    FROM stock_lot
    WHERE original_quantity <= 0 OR remaining_quantity < 0
       OR remaining_quantity > original_quantity OR unit_cost < 0

    UNION ALL
    SELECT 'STOCK_LOT_COST_AMOUNT_OVERFLOW', 'stock_lot', CAST(id AS CHAR),
           CONCAT('original_cost=',
                  ROUND(CAST(original_quantity AS DECIMAL(20, 0))
                        * CAST(unit_cost AS DECIMAL(18, 6)), 2),
                  ', remaining_cost=',
                  ROUND(CAST(remaining_quantity AS DECIMAL(20, 0))
                        * CAST(unit_cost AS DECIMAL(18, 6)), 2),
                  ', max=9999999999999999.99')
    FROM stock_lot
    WHERE ABS(ROUND(CAST(original_quantity AS DECIMAL(20, 0))
                    * CAST(unit_cost AS DECIMAL(18, 6)), 2))
              > CAST('9999999999999999.99' AS DECIMAL(18, 2))
       OR ABS(ROUND(CAST(remaining_quantity AS DECIMAL(20, 0))
                    * CAST(unit_cost AS DECIMAL(18, 6)), 2))
              > CAST('9999999999999999.99' AS DECIMAL(18, 2))

    UNION ALL
    SELECT 'INVALID_LOT_CONSUMPTION', 'stock_lot_consumption', CAST(id AS CHAR),
           CONCAT('quantity=', quantity, ', unit_cost=', unit_cost, ', total_cost=', total_cost)
    FROM stock_lot_consumption
    WHERE quantity = 0 OR unit_cost < 0
       OR total_cost <> ROUND(quantity * unit_cost, 2)

    UNION ALL
    SELECT 'INVALID_MOVEMENT_ARITHMETIC', 'stock_movement_line', CAST(id AS CHAR),
           CONCAT('before=', before_quantity, ', delta=', quantity_delta, ', after=', after_quantity)
    FROM stock_movement_line
    WHERE before_quantity < 0 OR after_quantity < 0
       OR after_quantity <> before_quantity + quantity_delta

    UNION ALL
    SELECT 'INVALID_STOCKTAKING_ARITHMETIC', 'stocktaking_record', CAST(id AS CHAR),
           CONCAT('book=', book_quantity, ', actual=', actual_quantity,
                  ', difference=', difference_quantity)
    FROM stocktaking_record
    WHERE difference_quantity <> actual_quantity - book_quantity

    UNION ALL
    SELECT 'INVALID_RENTAL_BILL_AMOUNT', 'rental_bill', CAST(id AS CHAR),
           CONCAT('amount=', amount)
    FROM rental_bill
    WHERE amount < 0

    UNION ALL
    SELECT 'INVALID_IMPORT_COUNTS', 'data_import_job', CAST(id AS CHAR),
           CONCAT('total=', total_rows, ', valid=', valid_rows, ', errors=', error_rows,
                  ', imported=', imported_rows, ', skipped=', skipped_rows)
    FROM data_import_job
    WHERE total_rows < 0 OR valid_rows < 0 OR error_rows < 0
       OR imported_rows < 0 OR skipped_rows < 0
       OR valid_rows + error_rows > total_rows

    UNION ALL
    SELECT 'NULL_OPTIMISTIC_VERSION', 'rental_record', CAST(id AS CHAR), 'version is NULL'
    FROM rental_record WHERE version IS NULL
    UNION ALL
    SELECT 'NULL_OPTIMISTIC_VERSION', 'resource_attachment', CAST(id AS CHAR), 'version is NULL'
    FROM resource_attachment WHERE version IS NULL
    UNION ALL
    SELECT 'NULL_OPTIMISTIC_VERSION', 'data_import_job', CAST(id AS CHAR), 'version is NULL'
    FROM data_import_job WHERE version IS NULL
    UNION ALL
    SELECT 'NULL_OPTIMISTIC_VERSION', 'vehicle_config_item', CAST(id AS CHAR), 'version is NULL'
    FROM vehicle_config_item WHERE version IS NULL
    UNION ALL
    SELECT 'NULL_OPTIMISTIC_VERSION', 'vehicle_config_value', CAST(id AS CHAR), 'version is NULL'
    FROM vehicle_config_value WHERE version IS NULL

    UNION ALL
    SELECT 'DEFAULT_WAREHOUSE_COUNT', 'warehouse', 'ALL',
           CONCAT('default warehouses=', COUNT(*), '; exactly one is required')
    FROM warehouse
    WHERE is_default = b'1'
    HAVING COUNT(*) <> 1

    UNION ALL
    SELECT 'DUPLICATE_CONFIG_DEFAULT', 'config_value', CAST(config_item_id AS CHAR),
           CONCAT('default values=', COUNT(*))
    FROM config_value
    WHERE is_default = b'1'
    GROUP BY config_item_id
    HAVING COUNT(*) > 1

    UNION ALL
    SELECT 'ORPHAN_CONFIG_VALUE_ITEM', 'config_value', CAST(value.id AS CHAR),
           CONCAT('config_item_id=', value.config_item_id)
    FROM config_value value
    LEFT JOIN config_item item ON item.id = value.config_item_id
    WHERE item.id IS NULL

    UNION ALL
    SELECT 'DUPLICATE_MACHINE_CONFIG_ITEM', 'machine_config',
           CONCAT(machine_id, ':', config_item_id), CONCAT('rows=', COUNT(*))
    FROM machine_config
    GROUP BY machine_id, config_item_id
    HAVING COUNT(*) > 1

    UNION ALL
    SELECT 'INVALID_MACHINE_CONFIG_REFERENCE', 'machine_config', CAST(config.id AS CHAR),
           CONCAT('machine=', config.machine_id, ', item=', config.config_item_id,
                  ', value=', config.config_value_id)
    FROM machine_config config
    LEFT JOIN machine_inventory machine ON machine.id = config.machine_id
    LEFT JOIN config_item item ON item.id = config.config_item_id
    LEFT JOIN config_value value
      ON value.id = config.config_value_id AND value.config_item_id = config.config_item_id
    WHERE machine.id IS NULL OR item.id IS NULL OR value.id IS NULL

    UNION ALL
    SELECT 'INVALID_VEHICLE_CONFIG_REFERENCE', 'vehicle_config_value', CAST(config.id AS CHAR),
           CONCAT('item=', config.config_item_id, ', value=', config.config_value_id)
    FROM vehicle_config_value config
    LEFT JOIN config_value value
      ON value.id = config.config_value_id AND value.config_item_id = config.config_item_id
    WHERE value.id IS NULL

    UNION ALL
    SELECT 'INVALID_PURCHASE_CONFIG_REFERENCE', 'purchase_order', CAST(purchase.id AS CHAR),
           CONCAT('item=', purchase.config_item_id, ', value=', purchase.config_value_id)
    FROM purchase_order purchase
    LEFT JOIN config_value value
      ON value.id = purchase.config_value_id AND value.config_item_id = purchase.config_item_id
    WHERE purchase.config_value_id IS NOT NULL
      AND purchase.config_item_id IS NOT NULL
      AND value.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_PURCHASE_CONFIG_ITEM', 'purchase_order', CAST(child.id AS CHAR),
           CONCAT('config_item_id=', child.config_item_id)
    FROM purchase_order child
    LEFT JOIN config_item parent ON parent.id = child.config_item_id
    WHERE child.config_item_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_PURCHASE_CONFIG_VALUE', 'purchase_order', CAST(child.id AS CHAR),
           CONCAT('config_value_id=', child.config_value_id)
    FROM purchase_order child
    LEFT JOIN config_value parent ON parent.id = child.config_value_id
    WHERE child.config_value_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'PARTIAL_PURCHASE_CONFIG_REFERENCE', 'purchase_order', CAST(id AS CHAR),
           CONCAT('config_value_id=', config_value_id, ', config_item_id is NULL')
    FROM purchase_order
    WHERE config_value_id IS NOT NULL AND config_item_id IS NULL

    UNION ALL
    SELECT 'NULL_INVENTORY_WAREHOUSE', 'machine_inventory', CAST(id AS CHAR), 'warehouse_id is NULL'
    FROM machine_inventory WHERE warehouse_id IS NULL
    UNION ALL
    SELECT 'NULL_INVENTORY_WAREHOUSE', 'part_inventory', CAST(id AS CHAR), 'warehouse_id is NULL'
    FROM part_inventory WHERE warehouse_id IS NULL

    UNION ALL
    SELECT 'ORPHAN_INVENTORY_WAREHOUSE', 'machine_inventory', CAST(child.id AS CHAR),
           CONCAT('warehouse_id=', child.warehouse_id)
    FROM machine_inventory child
    LEFT JOIN warehouse parent ON parent.id = child.warehouse_id
    WHERE child.warehouse_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_INVENTORY_WAREHOUSE', 'part_inventory', CAST(child.id AS CHAR),
           CONCAT('warehouse_id=', child.warehouse_id)
    FROM part_inventory child
    LEFT JOIN warehouse parent ON parent.id = child.warehouse_id
    WHERE child.warehouse_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_MACHINE_SUPPLIER', 'machine_inventory', CAST(child.id AS CHAR),
           CONCAT('supplier_id=', child.supplier_id)
    FROM machine_inventory child
    LEFT JOIN supplier_profile parent ON parent.id = child.supplier_id
    WHERE child.supplier_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_PART_SOURCE_MACHINE', 'part_inventory', CAST(child.id AS CHAR),
           CONCAT('source_machine_id=', child.source_machine_id)
    FROM part_inventory child
    LEFT JOIN machine_inventory parent ON parent.id = child.source_machine_id
    WHERE child.source_machine_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_DIRECT_WAREHOUSE', 'outbound_order', CAST(child.id AS CHAR),
           CONCAT('source_warehouse_id=', child.source_warehouse_id)
    FROM outbound_order child LEFT JOIN warehouse parent ON parent.id = child.source_warehouse_id
    WHERE child.source_warehouse_id IS NOT NULL AND parent.id IS NULL
    UNION ALL
    SELECT 'ORPHAN_DIRECT_WAREHOUSE', 'purchase_order', CAST(child.id AS CHAR),
           CONCAT('warehouse_id=', child.warehouse_id)
    FROM purchase_order child LEFT JOIN warehouse parent ON parent.id = child.warehouse_id
    WHERE child.warehouse_id IS NOT NULL AND parent.id IS NULL
    UNION ALL
    SELECT 'ORPHAN_DIRECT_WAREHOUSE', 'repair_part_usage', CAST(child.id AS CHAR),
           CONCAT('warehouse_id=', child.warehouse_id)
    FROM repair_part_usage child LEFT JOIN warehouse parent ON parent.id = child.warehouse_id
    WHERE child.warehouse_id IS NOT NULL AND parent.id IS NULL
    UNION ALL
    SELECT 'ORPHAN_DIRECT_WAREHOUSE', 'rental_record', CAST(child.id AS CHAR),
           CONCAT('warehouse_id=', child.warehouse_id)
    FROM rental_record child LEFT JOIN warehouse parent ON parent.id = child.warehouse_id
    WHERE child.warehouse_id IS NOT NULL AND parent.id IS NULL
    UNION ALL
    SELECT 'ORPHAN_DIRECT_WAREHOUSE', 'stocktaking_record', CAST(child.id AS CHAR),
           CONCAT('warehouse_id=', child.warehouse_id)
    FROM stocktaking_record child LEFT JOIN warehouse parent ON parent.id = child.warehouse_id
    WHERE child.warehouse_id IS NOT NULL AND parent.id IS NULL
    UNION ALL
    SELECT 'ORPHAN_DIRECT_WAREHOUSE', 'modification_work_order', CAST(child.id AS CHAR),
           CONCAT('warehouse_id=', child.warehouse_id)
    FROM modification_work_order child LEFT JOIN warehouse parent ON parent.id = child.warehouse_id
    WHERE child.warehouse_id IS NOT NULL AND parent.id IS NULL
    UNION ALL
    SELECT 'ORPHAN_DIRECT_WAREHOUSE', 'modification_work_order_line', CAST(child.id AS CHAR),
           CONCAT('warehouse_id=', child.warehouse_id, ', old_part_warehouse_id=',
                  child.old_part_warehouse_id)
    FROM modification_work_order_line child
    LEFT JOIN warehouse current_wh ON current_wh.id = child.warehouse_id
    LEFT JOIN warehouse old_wh ON old_wh.id = child.old_part_warehouse_id
    WHERE (child.warehouse_id IS NOT NULL AND current_wh.id IS NULL)
       OR (child.old_part_warehouse_id IS NOT NULL AND old_wh.id IS NULL)
    UNION ALL
    SELECT 'ORPHAN_DIRECT_WAREHOUSE', 'stock_lot', CAST(child.id AS CHAR),
           CONCAT('warehouse_id=', child.warehouse_id)
    FROM stock_lot child LEFT JOIN warehouse parent ON parent.id = child.warehouse_id
    WHERE parent.id IS NULL
    UNION ALL
    SELECT 'ORPHAN_DIRECT_WAREHOUSE', 'stock_lot_consumption', CAST(child.id AS CHAR),
           CONCAT('warehouse_id=', child.warehouse_id)
    FROM stock_lot_consumption child LEFT JOIN warehouse parent ON parent.id = child.warehouse_id
    WHERE parent.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_DIRECT_CUSTOMER', 'repair_record', CAST(child.id AS CHAR),
           CONCAT('customer_id=', child.customer_id)
    FROM repair_record child LEFT JOIN customer_profile parent ON parent.id = child.customer_id
    WHERE child.customer_id IS NOT NULL AND parent.id IS NULL
    UNION ALL
    SELECT 'ORPHAN_DIRECT_CUSTOMER', 'rental_record', CAST(child.id AS CHAR),
           CONCAT('customer_id=', child.customer_id)
    FROM rental_record child LEFT JOIN customer_profile parent ON parent.id = child.customer_id
    WHERE child.customer_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_REPAIR_MACHINE', 'repair_record', CAST(child.id AS CHAR),
           CONCAT('machine_id=', child.machine_id)
    FROM repair_record child
    LEFT JOIN machine_inventory parent ON parent.id = child.machine_id
    WHERE child.machine_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_REPAIR_PERSON', 'repair_record', CAST(child.id AS CHAR),
           CONCAT('repair_person_user_id=', child.repair_person_user_id)
    FROM repair_record child
    LEFT JOIN users parent ON parent.id = child.repair_person_user_id
    WHERE child.repair_person_user_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_OUTBOUND_STOCK_OPERATION', 'outbound_order', CAST(child.id AS CHAR),
           CONCAT('stock_operation_log_id=', child.stock_operation_log_id)
    FROM outbound_order child
    LEFT JOIN stock_operation_log parent ON parent.id = child.stock_operation_log_id
    WHERE child.stock_operation_log_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_PURCHASE_RECEIPT_MOVEMENT', 'purchase_order', CAST(child.id AS CHAR),
           CONCAT('received_stock_movement_id=', child.received_stock_movement_id)
    FROM purchase_order child
    LEFT JOIN stock_movement parent ON parent.id = child.received_stock_movement_id
    WHERE child.received_stock_movement_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_PURCHASE_STOCK_LOT', 'purchase_order', CAST(child.id AS CHAR),
           CONCAT('stock_lot_id=', child.stock_lot_id)
    FROM purchase_order child
    LEFT JOIN stock_lot parent ON parent.id = child.stock_lot_id
    WHERE child.stock_lot_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_STOCKTAKING_MOVEMENT', 'stocktaking_record', CAST(child.id AS CHAR),
           CONCAT('snapshot_movement_id=', child.snapshot_movement_id)
    FROM stocktaking_record child
    LEFT JOIN stock_movement parent ON parent.id = child.snapshot_movement_id
    WHERE child.snapshot_movement_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_MODIFICATION_MACHINE', 'modification_work_order', CAST(child.id AS CHAR),
           CONCAT('machine_id=', child.machine_id)
    FROM modification_work_order child
    LEFT JOIN machine_inventory parent ON parent.id = child.machine_id
    WHERE parent.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_MODIFICATION_MACHINE_CONFIG', 'modification_work_order_line', CAST(child.id AS CHAR),
           CONCAT('machine_config_id=', child.machine_config_id)
    FROM modification_work_order_line child
    LEFT JOIN machine_config parent ON parent.id = child.machine_config_id
    WHERE parent.id IS NULL

    UNION ALL
    SELECT 'INVALID_MODIFICATION_CONFIG_MACHINE', 'modification_work_order_line',
           CAST(line_row.id AS CHAR),
           CONCAT('work_order_machine=', work_order.machine_id,
                  ', config_machine=', machine_config.machine_id,
                  ', machine_config_id=', line_row.machine_config_id)
    FROM modification_work_order_line line_row
    JOIN modification_work_order work_order ON work_order.id = line_row.work_order_id
    JOIN machine_config machine_config ON machine_config.id = line_row.machine_config_id
    WHERE machine_config.machine_id <> work_order.machine_id

    UNION ALL
    SELECT 'INVALID_MODIFICATION_MACHINE_CONFIG_ITEM', 'modification_work_order_line',
           CAST(child.id AS CHAR),
           CONCAT('machine_config_id=', child.machine_config_id,
                  ', config_item_id=', child.config_item_id)
    FROM modification_work_order_line child
    LEFT JOIN machine_config parent
      ON parent.id = child.machine_config_id
     AND parent.config_item_id = child.config_item_id
    WHERE child.config_item_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_MODIFICATION_CONFIG_ITEM', 'modification_work_order_line', CAST(child.id AS CHAR),
           CONCAT('config_item_id=', child.config_item_id)
    FROM modification_work_order_line child
    LEFT JOIN config_item parent ON parent.id = child.config_item_id
    WHERE child.config_item_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'NULL_MODIFICATION_CONFIG_ITEM', 'modification_work_order_line', CAST(id AS CHAR),
           'config_item_id is NULL'
    FROM modification_work_order_line
    WHERE config_item_id IS NULL

    UNION ALL
    SELECT 'INVALID_MODIFICATION_CONFIG_VALUE', 'modification_work_order_line', CAST(child.id AS CHAR),
           CONCAT('item=', child.config_item_id, ', value=', child.new_config_value_id)
    FROM modification_work_order_line child
    LEFT JOIN config_value parent
      ON parent.id = child.new_config_value_id
     AND (child.config_item_id IS NULL OR parent.config_item_id = child.config_item_id)
    WHERE child.new_config_value_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'PARTIAL_MODIFICATION_CONFIG_REFERENCE', 'modification_work_order_line', CAST(id AS CHAR),
           CONCAT('new_config_value_id=', new_config_value_id, ', config_item_id is NULL')
    FROM modification_work_order_line
    WHERE new_config_value_id IS NOT NULL AND config_item_id IS NULL

    UNION ALL
    SELECT 'ORPHAN_MODIFICATION_NEW_PART', 'modification_work_order_line', CAST(child.id AS CHAR),
           CONCAT('new_part_id=', child.new_part_id)
    FROM modification_work_order_line child
    LEFT JOIN part_inventory parent ON parent.id = child.new_part_id
    WHERE child.new_part_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_MODIFICATION_REPLACE_LOG', 'modification_work_order_line', CAST(child.id AS CHAR),
           CONCAT('replace_log_id=', child.replace_log_id)
    FROM modification_work_order_line child
    LEFT JOIN config_replace_log parent ON parent.id = child.replace_log_id
    WHERE child.replace_log_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_REPLACE_LOG_MACHINE', 'config_replace_log', CAST(child.id AS CHAR),
           CONCAT('machine_id=', child.machine_id)
    FROM config_replace_log child
    LEFT JOIN machine_inventory parent ON parent.id = child.machine_id
    WHERE parent.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_REPLACE_LOG_MACHINE_CONFIG', 'config_replace_log', CAST(child.id AS CHAR),
           CONCAT('machine_config_id=', child.machine_config_id)
    FROM config_replace_log child
    LEFT JOIN machine_config parent ON parent.id = child.machine_config_id
    WHERE child.machine_config_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'INVALID_REPLACE_LOG_MACHINE_CONFIG', 'config_replace_log', CAST(child.id AS CHAR),
           CONCAT('machine_id=', child.machine_id,
                  ', machine_config_id=', child.machine_config_id)
    FROM config_replace_log child
    LEFT JOIN machine_config parent
      ON parent.id = child.machine_config_id
     AND parent.machine_id = child.machine_id
    WHERE child.machine_config_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'ORPHAN_REPLACE_LOG_NEW_PART', 'config_replace_log', CAST(child.id AS CHAR),
           CONCAT('new_part_id=', child.new_part_id)
    FROM config_replace_log child
    LEFT JOIN part_inventory parent ON parent.id = child.new_part_id
    WHERE child.new_part_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'INVALID_LOT_CONSUMPTION_IDENTITY', 'stock_lot_consumption', CAST(child.id AS CHAR),
           CONCAT('lot=', child.stock_lot_id, ', warehouse=', child.warehouse_id,
                  ', resource=', child.resource_type, ':', child.resource_id)
    FROM stock_lot_consumption child
    LEFT JOIN stock_lot parent
      ON parent.id = child.stock_lot_id
     AND parent.warehouse_id = child.warehouse_id
     AND parent.resource_type = child.resource_type
      AND parent.resource_id = child.resource_id
    WHERE parent.id IS NULL

    UNION ALL
    SELECT 'INVALID_LOT_CONSUMPTION_REVERSAL_IDENTITY',
           'stock_lot_consumption', CAST(child.id AS CHAR),
           CONCAT('parent_consumption_id=', child.reversal_of_consumption_id,
                  ', child_quantity=', child.quantity,
                  ', parent_quantity=', parent.quantity,
                  ', child_total_cost=', child.total_cost,
                  ', parent_total_cost=', parent.total_cost)
    FROM stock_lot_consumption child
    JOIN stock_lot_consumption parent
      ON parent.id = child.reversal_of_consumption_id
    WHERE child.id = parent.id
       OR parent.reversal_of_consumption_id IS NOT NULL
       OR child.stock_lot_id <> parent.stock_lot_id
       OR child.resource_type <> parent.resource_type
       OR child.resource_id <> parent.resource_id
       OR child.warehouse_id <> parent.warehouse_id
       -- V50 normalizes this one known legacy spelling before adding the FK.
       OR NOT (
           child.source_type <=> parent.source_type
           OR child.source_type <=> CONCAT(parent.source_type, '_REVERSAL')
       )
       OR NOT (child.source_id <=> parent.source_id)
       OR NOT (child.source_line_id <=> parent.source_line_id)
       OR child.quantity <> -parent.quantity
       OR child.unit_cost <> parent.unit_cost
       OR child.total_cost <> -parent.total_cost

    UNION ALL
    SELECT 'INVALID_MOVEMENT_LINE_LOT_IDENTITY', 'stock_movement_line', CAST(child.id AS CHAR),
           CONCAT('lot=', child.stock_lot_id, ', warehouse=', child.warehouse_id,
                  ', resource=', child.resource_type, ':', child.resource_id)
    FROM stock_movement_line child
    LEFT JOIN stock_lot parent
      ON parent.id = child.stock_lot_id
     AND parent.warehouse_id = child.warehouse_id
     AND parent.resource_type = child.resource_type
     AND parent.resource_id = child.resource_id
    WHERE child.stock_lot_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'INVALID_REPAIR_CONSUMPTION_IDENTITY', 'repair_part_usage', CAST(child.id AS CHAR),
           CONCAT('consumption=', child.stock_lot_consumption_id,
                  ', warehouse=', child.warehouse_id, ', part=', child.part_id)
    FROM repair_part_usage child
    LEFT JOIN stock_lot_consumption parent
      ON parent.id = child.stock_lot_consumption_id
     AND parent.warehouse_id = child.warehouse_id
     AND parent.resource_type = 'PART'
     AND parent.resource_id = child.part_id
     AND parent.source_type = 'REPAIR'
     AND parent.source_id = child.repair_id
     AND parent.source_line_id = child.id
    WHERE child.stock_lot_consumption_id IS NOT NULL AND parent.id IS NULL

    UNION ALL
    SELECT 'PARTIAL_REPAIR_CONSUMPTION_REFERENCE', 'repair_part_usage', CAST(id AS CHAR),
           CONCAT('stock_lot_consumption_id=', stock_lot_consumption_id,
                  ', warehouse_id is NULL')
    FROM repair_part_usage
    WHERE stock_lot_consumption_id IS NOT NULL AND warehouse_id IS NULL

    UNION ALL
    SELECT 'MISSING_LEGACY_SALES_POSTING', 'outbound_order', CAST(outbound.id AS CHAR),
           'financial_posted=0 and at least one AR/revenue/COGS event type is missing'
    FROM outbound_order outbound
    WHERE outbound.financial_posted = b'0'
      AND (
        NOT EXISTS (
            SELECT 1 FROM financial_event event
            WHERE event.source_type = 'OUTBOUND_ORDER'
              AND event.source_id = outbound.id
              AND event.event_type = 'ACCOUNTS_RECEIVABLE'
        )
        OR NOT EXISTS (
            SELECT 1 FROM financial_event event
            WHERE event.source_type = 'OUTBOUND_ORDER'
              AND event.source_id = outbound.id
              AND event.event_type = 'REVENUE'
        )
        OR NOT EXISTS (
            SELECT 1 FROM financial_event event
            WHERE event.source_type = 'OUTBOUND_ORDER'
              AND event.source_id = outbound.id
              AND event.event_type = 'COST_OF_GOODS_SOLD'
        )
      )

    UNION ALL
    SELECT 'LEGACY_SETTLEMENT_RECEIPT_GAP', 'outbound_order', CAST(id AS CHAR),
           CONCAT('receivable=', receivable_amount, ', received=', received_amount)
    FROM outbound_order
    WHERE payment_settled = b'1'
      AND COALESCE(receivable_amount, 0) > COALESCE(received_amount, 0)

    UNION ALL
    SELECT 'LEGACY_PART_UNIT_PRICE_GAP', 'outbound_order', CAST(id AS CHAR),
           CONCAT('quantity=', quantity, ', unit_sale_price=', unit_sale_price,
                  ', line_amount=', line_amount)
    FROM outbound_order
    WHERE resource_type = 'PART' AND quantity > 1
      AND line_amount IS NOT NULL
      AND unit_sale_price IS NOT NULL
      AND ABS(line_amount - unit_sale_price * quantity) >= 0.01

    UNION ALL
    SELECT 'FIFO_BALANCE_MISMATCH', 'stock_balance', CAST(balance.id AS CHAR),
           CONCAT('available=', balance.available_quantity,
                  ', open_lots=', COALESCE(lots.remaining_quantity, 0))
    FROM stock_balance balance
    LEFT JOIN (
        SELECT resource_type, resource_id, warehouse_id,
               SUM(remaining_quantity) AS remaining_quantity
        FROM stock_lot
        GROUP BY resource_type, resource_id, warehouse_id
    ) lots
      ON lots.resource_type = balance.resource_type
     AND lots.resource_id = balance.resource_id
     AND lots.warehouse_id = balance.warehouse_id
    WHERE balance.available_quantity <> COALESCE(lots.remaining_quantity, 0)
) findings
ORDER BY issue_type, table_name, row_id;
