package com.example.forklift_erp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public class DailyReconciliationProjectionRepository {

    private final JdbcTemplate jdbcTemplate;

    public DailyReconciliationProjectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<BalanceRow> balances() {
        return jdbcTemplate.query("""
                SELECT resource_type,
                       resource_id,
                       warehouse_id,
                       available_quantity,
                       reserved_quantity,
                       locked_quantity
                FROM stock_balance
                """, (resultSet, rowNumber) -> new BalanceRow(
                resultSet.getString("resource_type"),
                resultSet.getLong("resource_id"),
                resultSet.getObject("warehouse_id", Long.class),
                resultSet.getInt("available_quantity"),
                resultSet.getInt("reserved_quantity"),
                resultSet.getInt("locked_quantity")
        ));
    }

    public List<FifoRow> fifoTotals() {
        return jdbcTemplate.query("""
                SELECT resource_type,
                       resource_id,
                       warehouse_id,
                       COALESCE(SUM(remaining_quantity), 0) AS fifo_quantity
                FROM stock_lot
                WHERE status <> 'REVERSED'
                GROUP BY resource_type, resource_id, warehouse_id
                """, (resultSet, rowNumber) -> new FifoRow(
                resultSet.getString("resource_type"),
                resultSet.getLong("resource_id"),
                resultSet.getObject("warehouse_id", Long.class),
                resultSet.getInt("fifo_quantity")
        ));
    }

    public List<MovementStateRow> movementStates(LocalDate activityDate) {
        return jdbcTemplate.query("""
                SELECT balance.resource_type,
                       balance.resource_id,
                       balance.warehouse_id,
                       (
                           SELECT movement_line.after_quantity
                           FROM stock_movement_line movement_line
                           WHERE movement_line.resource_type = balance.resource_type
                             AND movement_line.resource_id = balance.resource_id
                             AND movement_line.warehouse_id = balance.warehouse_id
                           ORDER BY movement_line.id DESC
                           LIMIT 1
                       ) AS after_quantity,
                       COALESCE(daily.daily_delta, 0) AS daily_delta
                FROM stock_balance balance
                LEFT JOIN (
                    SELECT movement_line.resource_type,
                           movement_line.resource_id,
                           movement_line.warehouse_id,
                           SUM(movement_line.quantity_delta) AS daily_delta
                    FROM stock_movement movement
                    JOIN stock_movement_line movement_line
                      ON movement_line.movement_id = movement.id
                    WHERE movement.business_date = ?
                    GROUP BY movement_line.resource_type,
                             movement_line.resource_id,
                             movement_line.warehouse_id
                ) daily
                  ON daily.resource_type = balance.resource_type
                 AND daily.resource_id = balance.resource_id
                 AND daily.warehouse_id = balance.warehouse_id
                """, (resultSet, rowNumber) -> new MovementStateRow(
                resultSet.getString("resource_type"),
                resultSet.getLong("resource_id"),
                resultSet.getObject("warehouse_id", Long.class),
                resultSet.getObject("after_quantity", Integer.class),
                resultSet.getInt("daily_delta")
        ), activityDate);
    }

    public List<ResourceProfileRow> resourceProfiles() {
        return jdbcTemplate.query("""
                SELECT 'MACHINE' AS resource_type,
                       id AS resource_id,
                       vehicle_number AS resource_code,
                       name AS resource_name,
                       CASE WHEN model_only = 0 THEN inventory_count ELSE NULL END AS profile_quantity,
                       stock_status
                FROM machine_inventory
                UNION ALL
                SELECT 'PART' AS resource_type,
                       id AS resource_id,
                       part_code AS resource_code,
                       part_name AS resource_name,
                       quantity AS profile_quantity,
                       NULL AS stock_status
                FROM part_inventory
                """, (resultSet, rowNumber) -> new ResourceProfileRow(
                resultSet.getString("resource_type"),
                resultSet.getLong("resource_id"),
                resultSet.getString("resource_code"),
                resultSet.getString("resource_name"),
                resultSet.getObject("profile_quantity", Integer.class),
                resultSet.getString("stock_status")
        ));
    }

    public List<SalesRow> sales(LocalDate activityDate) {
        return jdbcTemplate.query("""
                SELECT financial.source_id AS outbound_order_id,
                       orders.order_no,
                       orders.customer_name,
                       COALESCE(SUM(CASE
                           WHEN financial.event_type = 'ACCOUNTS_RECEIVABLE' THEN financial.amount
                           ELSE 0
                       END), 0) AS receivable,
                       COALESCE(SUM(CASE
                           WHEN financial.event_type = 'CASH_RECEIPT' THEN financial.amount
                           ELSE 0
                       END), 0) AS receipts,
                       COALESCE(SUM(CASE
                           WHEN financial.event_type = 'ACCOUNTS_RECEIVABLE'
                            AND financial.business_date = ? THEN financial.amount
                           ELSE 0
                       END), 0) AS activity_receivable,
                       COALESCE(SUM(CASE
                           WHEN financial.event_type = 'CASH_RECEIPT'
                            AND financial.business_date = ? THEN financial.amount
                           ELSE 0
                       END), 0) AS activity_receipts
                FROM financial_event financial
                LEFT JOIN outbound_order orders ON orders.id = financial.source_id
                WHERE financial.source_type = 'OUTBOUND_ORDER'
                  AND financial.source_id IS NOT NULL
                  AND financial.business_date <= ?
                  AND financial.event_type IN ('ACCOUNTS_RECEIVABLE', 'CASH_RECEIPT')
                GROUP BY financial.source_id, orders.order_no, orders.customer_name
                ORDER BY financial.source_id
                """, (resultSet, rowNumber) -> new SalesRow(
                resultSet.getLong("outbound_order_id"),
                resultSet.getString("order_no"),
                resultSet.getString("customer_name"),
                money(resultSet.getBigDecimal("receivable")),
                money(resultSet.getBigDecimal("receipts")),
                money(resultSet.getBigDecimal("activity_receivable")),
                money(resultSet.getBigDecimal("activity_receipts"))
        ), activityDate, activityDate, activityDate);
    }

    public List<ActiveRentalRow> activeRentals() {
        return jdbcTemplate.query("""
                SELECT rental.id AS rental_id,
                       rental.rental_no,
                       rental.machine_id,
                       rental.vehicle_number,
                       rental.warehouse_id,
                       machine.stock_status,
                       machine.inventory_count,
                       COALESCE(balance.available_quantity, 0) AS available_quantity,
                       COALESCE(balance.locked_quantity, 0) AS locked_quantity
                FROM rental_record rental
                LEFT JOIN machine_inventory machine ON machine.id = rental.machine_id
                LEFT JOIN stock_balance balance
                       ON balance.resource_type = 'MACHINE'
                      AND balance.resource_id = rental.machine_id
                      AND balance.warehouse_id = rental.warehouse_id
                WHERE rental.status = 'ACTIVE'
                """, (resultSet, rowNumber) -> new ActiveRentalRow(
                resultSet.getLong("rental_id"),
                resultSet.getString("rental_no"),
                resultSet.getObject("machine_id", Long.class),
                resultSet.getString("vehicle_number"),
                resultSet.getObject("warehouse_id", Long.class),
                resultSet.getString("stock_status"),
                resultSet.getObject("inventory_count", Integer.class),
                resultSet.getInt("available_quantity"),
                resultSet.getInt("locked_quantity")
        ));
    }

    public List<UnmatchedRentalLockRow> unmatchedRentalLocks() {
        return jdbcTemplate.query("""
                SELECT balance.resource_id AS machine_id,
                       balance.warehouse_id,
                       balance.available_quantity,
                       balance.locked_quantity,
                       machine.vehicle_number,
                       machine.stock_status
                FROM stock_balance balance
                LEFT JOIN machine_inventory machine ON machine.id = balance.resource_id
                WHERE balance.resource_type = 'MACHINE'
                  AND balance.locked_quantity > 0
                  AND NOT EXISTS (
                      SELECT 1
                      FROM rental_record rental
                      WHERE rental.status = 'ACTIVE'
                        AND rental.machine_id = balance.resource_id
                        AND rental.warehouse_id = balance.warehouse_id
                  )
                """, (resultSet, rowNumber) -> new UnmatchedRentalLockRow(
                resultSet.getLong("machine_id"),
                resultSet.getObject("warehouse_id", Long.class),
                resultSet.getInt("available_quantity"),
                resultSet.getInt("locked_quantity"),
                resultSet.getString("vehicle_number"),
                resultSet.getString("stock_status")
        ));
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record BalanceRow(
            String resourceType,
            Long resourceId,
            Long warehouseId,
            int availableQuantity,
            int reservedQuantity,
            int lockedQuantity
    ) {
    }

    public record FifoRow(String resourceType, Long resourceId, Long warehouseId, int fifoQuantity) {
    }

    public record MovementStateRow(
            String resourceType,
            Long resourceId,
            Long warehouseId,
            Integer latestAfterQuantity,
            int dailyDelta
    ) {
    }

    public record ResourceProfileRow(
            String resourceType,
            Long resourceId,
            String resourceCode,
            String resourceName,
            Integer profileQuantity,
            String stockStatus
    ) {
    }

    public record SalesRow(
            Long outboundOrderId,
            String orderNo,
            String customerName,
            BigDecimal receivable,
            BigDecimal receipts,
            BigDecimal activityReceivable,
            BigDecimal activityReceipts
    ) {
    }

    public record ActiveRentalRow(
            Long rentalId,
            String rentalNo,
            Long machineId,
            String vehicleNumber,
            Long warehouseId,
            String machineStatus,
            Integer inventoryCount,
            int availableQuantity,
            int lockedQuantity
    ) {
    }

    public record UnmatchedRentalLockRow(
            Long machineId,
            Long warehouseId,
            int availableQuantity,
            int lockedQuantity,
            String vehicleNumber,
            String machineStatus
    ) {
    }
}
