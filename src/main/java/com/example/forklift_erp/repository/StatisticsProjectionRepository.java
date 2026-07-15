package com.example.forklift_erp.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public class StatisticsProjectionRepository {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public StatisticsProjectionRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<FinancialSummary> monthlyFinancial(LocalDate start, LocalDate end) {
        return financial(
                start,
                end,
                "DATE_FORMAT(business_date, '%Y-%m')",
                "DATE_FORMAT(m.business_date, '%Y-%m')"
        );
    }

    public List<FinancialSummary> yearlyFinancial(LocalDate start, LocalDate end) {
        return financial(
                start,
                end,
                "CAST(YEAR(business_date) AS CHAR)",
                "CAST(YEAR(m.business_date) AS CHAR)"
        );
    }

    public List<ResourceFlowSummary> resourceFlows(LocalDate start, LocalDate end) {
        String sql = """
                SELECT
                    l.resource_type,
                    SUM(CASE WHEN l.quantity_delta > 0 THEN l.quantity_delta ELSE 0 END) AS inbound_quantity,
                    SUM(CASE WHEN l.quantity_delta < 0 THEN -l.quantity_delta ELSE 0 END) AS outbound_quantity,
                    SUM(CASE WHEN l.quantity_delta > 0 THEN COALESCE(l.cost_amount, 0) ELSE 0 END) AS inbound_cost,
                    SUM(CASE WHEN l.quantity_delta < 0 THEN COALESCE(l.line_amount, 0) ELSE 0 END) AS outbound_revenue,
                    SUM(CASE WHEN l.quantity_delta < 0 AND COALESCE(l.line_amount, 0) <> 0
                             THEN COALESCE(l.line_amount, 0) - COALESCE(l.cost_amount, 0)
                             ELSE 0 END) AS gross_profit
                FROM stock_movement_line l
                JOIN stock_movement m ON m.id = l.movement_id
                WHERE m.business_date >= :startDate
                  AND m.business_date <= :endDate
                GROUP BY l.resource_type
                ORDER BY l.resource_type
                """;
        return jdbcTemplate.query(sql, dates(start, end), (rs, rowNum) -> new ResourceFlowSummary(
                rs.getString("resource_type"),
                rs.getLong("inbound_quantity"),
                rs.getLong("outbound_quantity"),
                money(rs.getBigDecimal("inbound_cost")),
                money(rs.getBigDecimal("outbound_revenue")),
                money(rs.getBigDecimal("gross_profit"))
        ));
    }

    public List<TopOutboundSummary> topOutbounds(LocalDate start, LocalDate end, int limit) {
        String sql = """
                SELECT
                    l.resource_type,
                    l.resource_code,
                    l.resource_name,
                    SUM(-l.quantity_delta) AS quantity,
                    SUM(COALESCE(l.line_amount, 0)) AS revenue,
                    SUM(COALESCE(l.cost_amount, 0)) AS cost,
                    SUM(COALESCE(l.line_amount, 0) - COALESCE(l.cost_amount, 0)) AS gross_profit
                FROM stock_movement_line l
                JOIN stock_movement m ON m.id = l.movement_id
                WHERE m.business_date >= :startDate
                  AND m.business_date <= :endDate
                  AND l.quantity_delta < 0
                  AND COALESCE(l.line_amount, 0) > 0
                GROUP BY l.resource_type, l.resource_id, l.resource_code, l.resource_name
                ORDER BY revenue DESC
                LIMIT :rowLimit
                """;
        MapSqlParameterSource parameters = dates(start, end).addValue("rowLimit", limit);
        return jdbcTemplate.query(sql, parameters, (rs, rowNum) -> new TopOutboundSummary(
                rs.getString("resource_type"),
                rs.getString("resource_code"),
                rs.getString("resource_name"),
                rs.getLong("quantity"),
                money(rs.getBigDecimal("revenue")),
                money(rs.getBigDecimal("cost")),
                money(rs.getBigDecimal("gross_profit"))
        ));
    }

    public List<TopRentalSummary> topRentals(LocalDate start, LocalDate end, int limit) {
        String sql = """
                SELECT
                    r.rental_no,
                    r.vehicle_number,
                    r.machine_name,
                    r.specification_model,
                    r.destination,
                    r.status,
                    SUM(b.amount) AS rental_amount
                FROM rental_bill b
                JOIN rental_record r ON r.id = b.rental_id
                WHERE b.business_date >= :startDate
                  AND b.business_date <= :endDate
                  AND b.status = 'POSTED'
                GROUP BY r.id, r.rental_no, r.vehicle_number, r.machine_name,
                         r.specification_model, r.destination, r.status
                ORDER BY rental_amount DESC
                LIMIT :rowLimit
                """;
        MapSqlParameterSource parameters = dates(start, end).addValue("rowLimit", limit);
        return jdbcTemplate.query(sql, parameters, (rs, rowNum) -> new TopRentalSummary(
                rs.getString("rental_no"),
                rs.getString("vehicle_number"),
                rs.getString("machine_name"),
                rs.getString("specification_model"),
                rs.getString("destination"),
                rs.getString("status"),
                money(rs.getBigDecimal("rental_amount"))
        ));
    }

    public List<StockValueSummary> stockValues() {
        return List.of(machineStockValue(), partStockValue());
    }

    public List<LowStockSummary> lowStocks(int limit) {
        String sql = """
                SELECT
                    p.part_code,
                    p.part_name,
                    COALESCE(balance.available_quantity, 0) AS quantity,
                    p.unit,
                    COALESCE(p.reorder_point, 5) AS reorder_point
                FROM part_inventory p
                LEFT JOIN (
                    SELECT resource_id, SUM(available_quantity) AS available_quantity
                    FROM stock_balance
                    WHERE resource_type = 'PART'
                    GROUP BY resource_id
                ) balance ON balance.resource_id = p.id
                WHERE COALESCE(balance.available_quantity, 0) <= COALESCE(p.reorder_point, 5)
                ORDER BY quantity ASC, p.id ASC
                LIMIT :rowLimit
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource("rowLimit", limit), (rs, rowNum) ->
                new LowStockSummary(
                        rs.getString("part_code"),
                        rs.getString("part_name"),
                        rs.getLong("quantity"),
                        rs.getString("unit"),
                        rs.getInt("reorder_point")
                ));
    }

    private List<FinancialSummary> financial(
            LocalDate start,
            LocalDate end,
            String financialPeriodExpression,
            String movementPeriodExpression
    ) {
        String sql = """
                SELECT
                    period,
                    SUM(inbound_quantity) AS inbound_quantity,
                    SUM(outbound_quantity) AS outbound_quantity,
                    SUM(inbound_cost) AS inbound_cost,
                    SUM(outbound_revenue) AS outbound_revenue,
                    SUM(outbound_cost) AS outbound_cost,
                    SUM(repair_income) AS repair_income,
                    SUM(repair_receivable) AS repair_receivable,
                    SUM(repair_expense) AS repair_expense,
                    SUM(repair_parts_cost) AS repair_parts_cost,
                    SUM(rental_income) AS rental_income,
                    SUM(modification_income) AS modification_income,
                    SUM(modification_expense) AS modification_expense,
                    SUM(inventory_gain) AS inventory_gain,
                    SUM(inventory_loss) AS inventory_loss,
                    SUM(net_cashflow) AS net_cashflow,
                    SUM(repair_orders) AS repair_orders,
                    SUM(rental_orders) AS rental_orders,
                    SUM(modification_orders) AS modification_orders
                FROM (
                    SELECT
                        %s AS period,
                        0 AS inbound_quantity,
                        0 AS outbound_quantity,
                        0 AS inbound_cost,
                        SUM(CASE WHEN event_type = 'REVENUE' AND source_type = 'OUTBOUND_ORDER'
                                 THEN amount ELSE 0 END) AS outbound_revenue,
                        SUM(CASE WHEN event_type = 'COST_OF_GOODS_SOLD' AND source_type = 'OUTBOUND_ORDER'
                                 THEN amount ELSE 0 END) AS outbound_cost,
                        SUM(CASE WHEN event_type = 'REVENUE' AND source_type = 'REPAIR'
                                 THEN amount ELSE 0 END) AS repair_income,
                        SUM(CASE WHEN event_type = 'ACCOUNTS_RECEIVABLE' AND source_type = 'REPAIR'
                                 THEN amount ELSE 0 END) AS repair_receivable,
                        SUM(CASE WHEN event_type = 'OPERATING_COST' AND source_type = 'REPAIR'
                                 THEN amount ELSE 0 END) AS repair_expense,
                        SUM(CASE WHEN event_type = 'COST_OF_GOODS_SOLD' AND source_type = 'REPAIR'
                                 THEN amount ELSE 0 END) AS repair_parts_cost,
                        SUM(CASE WHEN event_type = 'REVENUE' AND source_type = 'RENTAL_BILL'
                                 THEN amount ELSE 0 END) AS rental_income,
                        SUM(CASE WHEN event_type = 'REVENUE'
                                      AND source_type NOT IN ('OUTBOUND_ORDER', 'REPAIR', 'RENTAL_BILL')
                                 THEN amount ELSE 0 END) AS modification_income,
                        SUM(CASE WHEN event_type IN ('OPERATING_COST', 'COST_OF_GOODS_SOLD')
                                      AND source_type NOT IN ('OUTBOUND_ORDER', 'REPAIR')
                                 THEN amount ELSE 0 END) AS modification_expense,
                        SUM(CASE WHEN event_type = 'INVENTORY_GAIN' THEN amount ELSE 0 END) AS inventory_gain,
                        SUM(CASE WHEN event_type = 'INVENTORY_LOSS' THEN amount ELSE 0 END) AS inventory_loss,
                        SUM(CASE WHEN event_type = 'CASH_RECEIPT' THEN amount
                                 WHEN event_type = 'CASH_PAYMENT' THEN -amount
                                 ELSE 0 END) AS net_cashflow,
                        SUM(CASE WHEN event_type = 'ACCOUNTS_RECEIVABLE' AND source_type = 'REPAIR'
                                 THEN SIGN(amount) ELSE 0 END) AS repair_orders,
                        SUM(CASE WHEN event_type = 'REVENUE' AND source_type = 'RENTAL_BILL'
                                 THEN SIGN(amount) ELSE 0 END) AS rental_orders,
                        SUM(CASE WHEN event_type = 'REVENUE'
                                      AND source_type NOT IN ('OUTBOUND_ORDER', 'REPAIR', 'RENTAL_BILL')
                                 THEN SIGN(amount) ELSE 0 END) AS modification_orders
                    FROM financial_event
                    WHERE business_date >= :startDate
                      AND business_date <= :endDate
                    GROUP BY %s

                    UNION ALL

                    SELECT
                        %s AS period,
                        SUM(CASE WHEN l.quantity_delta > 0 THEN l.quantity_delta ELSE 0 END) AS inbound_quantity,
                        SUM(CASE WHEN l.quantity_delta < 0 THEN -l.quantity_delta ELSE 0 END) AS outbound_quantity,
                        SUM(CASE WHEN l.quantity_delta > 0 THEN COALESCE(l.cost_amount, 0) ELSE 0 END) AS inbound_cost,
                        0 AS outbound_revenue,
                        0 AS outbound_cost,
                        0 AS repair_income,
                        0 AS repair_receivable,
                        0 AS repair_expense,
                        0 AS repair_parts_cost,
                        0 AS rental_income,
                        0 AS modification_income,
                        0 AS modification_expense,
                        0 AS inventory_gain,
                        0 AS inventory_loss,
                        0 AS net_cashflow,
                        0 AS repair_orders,
                        0 AS rental_orders,
                        0 AS modification_orders
                    FROM stock_movement_line l
                    JOIN stock_movement m ON m.id = l.movement_id
                    WHERE m.business_date >= :startDate
                      AND m.business_date <= :endDate
                    GROUP BY %s
                ) facts
                GROUP BY period
                ORDER BY period
                """.formatted(
                financialPeriodExpression,
                financialPeriodExpression,
                movementPeriodExpression,
                movementPeriodExpression
        );
        return jdbcTemplate.query(sql, dates(start, end), (rs, rowNum) -> new FinancialSummary(
                rs.getString("period"),
                rs.getLong("inbound_quantity"),
                rs.getLong("outbound_quantity"),
                money(rs.getBigDecimal("inbound_cost")),
                money(rs.getBigDecimal("outbound_revenue")),
                money(rs.getBigDecimal("outbound_cost")),
                money(rs.getBigDecimal("repair_income")),
                money(rs.getBigDecimal("repair_receivable")),
                money(rs.getBigDecimal("repair_expense")),
                money(rs.getBigDecimal("repair_parts_cost")),
                money(rs.getBigDecimal("rental_income")),
                money(rs.getBigDecimal("modification_income")),
                money(rs.getBigDecimal("modification_expense")),
                money(rs.getBigDecimal("inventory_gain")),
                money(rs.getBigDecimal("inventory_loss")),
                money(rs.getBigDecimal("net_cashflow")),
                rs.getLong("repair_orders"),
                rs.getLong("rental_orders"),
                rs.getLong("modification_orders")
        ));
    }

    private StockValueSummary machineStockValue() {
        String sql = """
                SELECT
                    COUNT(m.id) AS item_count,
                    COALESCE(SUM(COALESCE(balance.physical_quantity, 0)), 0) AS stock_quantity,
                    COALESCE(SUM(COALESCE(lots.cost_value, 0)), 0) AS cost_value,
                    COALESCE(SUM(
                        COALESCE(balance.physical_quantity, 0)
                        * COALESCE(m.sale_price, m.settlement_price, 0)
                    ), 0) AS settlement_value
                FROM machine_inventory m
                LEFT JOIN (
                    SELECT resource_id,
                           SUM(available_quantity + reserved_quantity + locked_quantity) AS physical_quantity
                    FROM stock_balance
                    WHERE resource_type = 'MACHINE'
                    GROUP BY resource_id
                ) balance ON balance.resource_id = m.id
                LEFT JOIN (
                    SELECT resource_id, SUM(remaining_quantity * unit_cost) AS cost_value
                    FROM stock_lot
                    WHERE resource_type = 'MACHINE'
                      AND status <> 'REVERSED'
                      AND remaining_quantity > 0
                    GROUP BY resource_id
                ) lots ON lots.resource_id = m.id
                WHERE COALESCE(m.model_only, 0) = 0
                """;
        return jdbcTemplate.queryForObject(sql, new MapSqlParameterSource(), (rs, rowNum) ->
                new StockValueSummary(
                        "MACHINE",
                        "Vehicle inventory",
                        rs.getLong("item_count"),
                        rs.getLong("stock_quantity"),
                        money(rs.getBigDecimal("cost_value")),
                        money(rs.getBigDecimal("settlement_value"))
                ));
    }

    private StockValueSummary partStockValue() {
        String sql = """
                SELECT
                    COUNT(p.id) AS item_count,
                    COALESCE(SUM(COALESCE(balance.physical_quantity, 0)), 0) AS stock_quantity,
                    COALESCE(SUM(COALESCE(lots.cost_value, 0)), 0) AS cost_value,
                    COALESCE(SUM(
                        COALESCE(balance.physical_quantity, 0)
                        * COALESCE(p.sale_price, p.settlement_price, 0)
                    ), 0) AS settlement_value
                FROM part_inventory p
                LEFT JOIN (
                    SELECT resource_id,
                           SUM(available_quantity + reserved_quantity + locked_quantity) AS physical_quantity
                    FROM stock_balance
                    WHERE resource_type = 'PART'
                    GROUP BY resource_id
                ) balance ON balance.resource_id = p.id
                LEFT JOIN (
                    SELECT resource_id, SUM(remaining_quantity * unit_cost) AS cost_value
                    FROM stock_lot
                    WHERE resource_type = 'PART'
                      AND status <> 'REVERSED'
                      AND remaining_quantity > 0
                    GROUP BY resource_id
                ) lots ON lots.resource_id = p.id
                """;
        return jdbcTemplate.queryForObject(sql, new MapSqlParameterSource(), (rs, rowNum) ->
                new StockValueSummary(
                        "PART",
                        "Part inventory",
                        rs.getLong("item_count"),
                        rs.getLong("stock_quantity"),
                        money(rs.getBigDecimal("cost_value")),
                        money(rs.getBigDecimal("settlement_value"))
                ));
    }

    private MapSqlParameterSource dates(LocalDate start, LocalDate end) {
        return new MapSqlParameterSource()
                .addValue("startDate", start)
                .addValue("endDate", end);
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record FinancialSummary(
            String period,
            long inboundQuantity,
            long outboundQuantity,
            BigDecimal inboundCost,
            BigDecimal outboundRevenue,
            BigDecimal outboundCost,
            BigDecimal repairIncome,
            BigDecimal repairReceivable,
            BigDecimal repairExpense,
            BigDecimal repairPartsCost,
            BigDecimal rentalIncome,
            BigDecimal modificationIncome,
            BigDecimal modificationExpense,
            BigDecimal inventoryGain,
            BigDecimal inventoryLoss,
            BigDecimal netCashflow,
            long repairOrders,
            long rentalOrders,
            long modificationOrders
    ) {
    }

    public record ResourceFlowSummary(
            String resourceType,
            long inboundQuantity,
            long outboundQuantity,
            BigDecimal inboundCost,
            BigDecimal outboundRevenue,
            BigDecimal grossProfit
    ) {
    }

    public record TopOutboundSummary(
            String resourceType,
            String resourceCode,
            String resourceName,
            long quantity,
            BigDecimal revenue,
            BigDecimal cost,
            BigDecimal grossProfit
    ) {
    }

    public record TopRentalSummary(
            String rentalNo,
            String vehicleNumber,
            String machineName,
            String specificationModel,
            String destination,
            String status,
            BigDecimal rentalAmount
    ) {
    }

    public record StockValueSummary(
            String resourceType,
            String label,
            long itemCount,
            long stockQuantity,
            BigDecimal costValue,
            BigDecimal settlementValue
    ) {
    }

    public record LowStockSummary(
            String resourceCode,
            String resourceName,
            long quantity,
            String unit,
            int reorderPoint
    ) {
    }
}
