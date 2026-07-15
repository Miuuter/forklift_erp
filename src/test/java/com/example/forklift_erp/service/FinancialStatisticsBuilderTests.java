package com.example.forklift_erp.service;

import com.example.forklift_erp.constant.ModificationWorkOrderStatus;
import com.example.forklift_erp.constant.PartChangeAction;
import com.example.forklift_erp.constant.RentalStatus;
import com.example.forklift_erp.constant.RepairStatus;
import com.example.forklift_erp.dto.StatisticsDashboardVO;
import com.example.forklift_erp.entity.FinancialEvent;
import com.example.forklift_erp.entity.ModificationWorkOrder;
import com.example.forklift_erp.entity.ModificationWorkOrderLine;
import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.entity.PurchaseOrder;
import com.example.forklift_erp.entity.RentalRecord;
import com.example.forklift_erp.entity.RepairRecord;
import com.example.forklift_erp.entity.StockOperationLog;
import com.example.forklift_erp.constant.FinancialEventType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialStatisticsBuilderTests {

    private static final int SELECTED_YEAR = 2026;

    private final FinancialStatisticsBuilder builder = new FinancialStatisticsBuilder(
            new RentalRevenueCalculator(Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneId.of("Asia/Shanghai")))
    );

    @Test
    void buildMonthlyRowsCombinesStockRepairRentalAndModification() {
        StockOperationLog inbound = stockLog("INBOUND", 2, null, null, "100.00", "0.00", 5);
        StockOperationLog outbound = stockLog("OUTBOUND", 1, null, null, "100.00", "180.00", 6);
        StockOperationLog adjustOut = stockLog("ADJUST", 3, 10, 7, "40.00", "60.00", 7);
        RepairRecord repair = completedRepair();
        RentalRecord rental = returnedRental();
        ModificationWorkOrder order = completedModificationOrder(42L);

        List<StatisticsDashboardVO.FinancialRow> rows = builder.buildMonthlyRows(
                SELECTED_YEAR,
                List.of(inbound, outbound, adjustOut),
                List.of(repair),
                List.of(rental),
                List.of(order),
                Map.of(42L, List.of(modificationLine("-25.00"), modificationLine("10.00"), ignoredModificationLine()))
        );

        StatisticsDashboardVO.FinancialRow january = findPeriod(rows, "2026-01");

        assertThat(january.getInboundQuantity()).isEqualTo(2);
        assertThat(january.getOutboundQuantity()).isEqualTo(4);
        assertThat(january.getRepairOrders()).isEqualTo(1);
        assertThat(january.getRentalOrders()).isEqualTo(1);
        assertThat(january.getModificationOrders()).isEqualTo(1);
        assertMoney(january.getInboundCost(), "200.00");
        assertMoney(january.getOutboundRevenue(), "360.00");
        assertMoney(january.getOutboundCost(), "220.00");
        assertMoney(january.getRepairIncome(), "150.00");
        assertMoney(january.getRepairReceivable(), "220.00");
        assertMoney(january.getRepairExpense(), "50.00");
        assertMoney(january.getRepairPartsCost(), "30.00");
        assertMoney(january.getRentalIncome(), "3100.00");
        assertMoney(january.getModificationIncome(), "25.00");
        assertMoney(january.getModificationExpense(), "10.00");
        assertMoney(january.getTotalIncome(), "3635.00");
        assertMoney(january.getTotalExpense(), "510.00");
        assertMoney(january.getNetProfit(), "3325.00");
        assertMoney(january.getNetCashflow(), "3125.00");
    }

    @Test
    void annualRowReturnsEmptyRowWhenSelectedYearHasNoData() {
        StatisticsDashboardVO.FinancialRow row = builder.annualRow(SELECTED_YEAR, List.of());

        assertThat(row.getPeriod()).isEqualTo("2026");
        assertThat(row.getTotalIncome()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void hybridRowsKeepUnpostedHistoryWithoutDoubleCountingPostedSales() {
        OutboundOrder sale = new OutboundOrder();
        sale.setId(1L);
        sale.setSalesDate(LocalDate.of(SELECTED_YEAR, 1, 10));
        sale.setQuantity(1);
        sale.setLineAmount(new BigDecimal("100.00"));
        sale.setReceivedAmount(new BigDecimal("40.00"));
        sale.setLastPaymentDate(LocalDate.of(SELECTED_YEAR, 1, 10));
        sale.setStockOperationLogId(10L);
        StockOperationLog saleLog = stockLog("OUTBOUND", 1, 1, 0, "60.00", "100.00", 10);
        saleLog.setId(10L);

        RepairRecord repair = completedRepair();
        repair.setId(2L);
        repair.setRepairFee(new BigDecimal("50.00"));
        repair.setPartsFee(new BigDecimal("20.00"));
        repair.setRepairExpense(new BigDecimal("10.00"));
        repair.setPartsCost(new BigDecimal("5.00"));
        repair.setReceivableAmount(new BigDecimal("70.00"));
        repair.setTotalFee(new BigDecimal("70.00"));

        RentalRecord rental = new RentalRecord();
        rental.setId(3L);
        rental.setMonthlyRentalPrice(new BigDecimal("3100.00"));
        rental.setStartDate(LocalDate.of(SELECTED_YEAR, 1, 1));
        rental.setStatus(RentalStatus.ACTIVE.code());

        PurchaseOrder purchase = new PurchaseOrder();
        purchase.setId(4L);
        purchase.setStatus("RECEIVED");
        purchase.setReceivedDate(LocalDate.of(SELECTED_YEAR, 1, 5));
        purchase.setQuantity(2);
        purchase.setTotalAmount(new BigDecimal("200.00"));
        purchase.setFreightAmount(new BigDecimal("20.00"));

        List<FinancialEvent> events = List.of(
                event(FinancialEventType.REVENUE, "100.00", "OUTBOUND_ORDER", 1L),
                event(FinancialEventType.COST_OF_GOODS_SOLD, "60.00", "OUTBOUND_ORDER", 1L),
                event(FinancialEventType.CASH_RECEIPT, "40.00", "OUTBOUND_ORDER", 1L),
                event(FinancialEventType.INVENTORY_GAIN, "15.00", "STOCKTAKING", 9L)
        );

        StatisticsDashboardVO.FinancialRow january = findPeriod(
                builder.buildMonthlyRowsHybrid(
                        SELECTED_YEAR,
                        events,
                        List.of(saleLog),
                        List.of(purchase),
                        List.of(sale),
                        Map.of(10L, saleLog),
                        List.of(repair),
                        List.of(rental),
                        List.of(),
                        List.of(),
                        Map.of()
                ),
                "2026-01"
        );

        assertThat(january.getInboundQuantity()).isEqualTo(2);
        assertThat(january.getOutboundQuantity()).isEqualTo(1);
        assertThat(january.getRepairOrders()).isEqualTo(1);
        assertThat(january.getRentalOrders()).isEqualTo(1);
        assertMoney(january.getInboundCost(), "220.00");
        assertMoney(january.getOutboundRevenue(), "100.00");
        assertMoney(january.getOutboundCost(), "60.00");
        assertMoney(january.getRepairIncome(), "70.00");
        assertMoney(january.getRepairExpense(), "10.00");
        assertMoney(january.getRepairPartsCost(), "5.00");
        assertMoney(january.getRentalIncome(), "3100.00");
        assertMoney(january.getInventoryGain(), "15.00");
        assertMoney(january.getTotalIncome(), "3285.00");
        assertMoney(january.getTotalExpense(), "75.00");
        assertMoney(january.getNetCashflow(), "40.00");
    }

    private FinancialEvent event(String type, String amount, String sourceType, Long sourceId) {
        FinancialEvent event = new FinancialEvent();
        event.setEventType(type);
        event.setAmount(new BigDecimal(amount));
        event.setBusinessDate(LocalDate.of(SELECTED_YEAR, 1, 10));
        event.setSourceType(sourceType);
        event.setSourceId(sourceId);
        return event;
    }

    private StockOperationLog stockLog(
            String operationType,
            int quantity,
            Integer beforeQuantity,
            Integer afterQuantity,
            String unitCost,
            String unitRevenue,
            int day
    ) {
        StockOperationLog log = new StockOperationLog();
        log.setResourceType("PART");
        log.setOperationType(operationType);
        log.setQuantity(quantity);
        log.setBeforeQuantity(beforeQuantity);
        log.setAfterQuantity(afterQuantity);
        log.setUnitCost(new BigDecimal(unitCost));
        log.setUnitRevenue(new BigDecimal(unitRevenue));
        log.setCreatedAt(LocalDateTime.of(SELECTED_YEAR, 1, day, 9, 0));
        return log;
    }

    private RepairRecord completedRepair() {
        RepairRecord repair = new RepairRecord();
        repair.setRepairDate(LocalDateTime.of(SELECTED_YEAR, 1, 8, 10, 0));
        repair.setRepairExternal(true);
        repair.setRepairFee(new BigDecimal("100.00"));
        repair.setPartsFee(new BigDecimal("50.00"));
        repair.setRepairExpense(new BigDecimal("50.00"));
        repair.setPartsCost(new BigDecimal("30.00"));
        repair.setTotalFee(new BigDecimal("220.00"));
        repair.setStatus(RepairStatus.COMPLETED.code());
        return repair;
    }

    private RentalRecord returnedRental() {
        RentalRecord rental = new RentalRecord();
        rental.setMonthlyRentalPrice(new BigDecimal("3100.00"));
        rental.setStartDate(LocalDate.of(SELECTED_YEAR, 1, 1));
        rental.setEndDate(LocalDate.of(SELECTED_YEAR, 1, 31));
        rental.setStatus(RentalStatus.RETURNED.code());
        return rental;
    }

    private ModificationWorkOrder completedModificationOrder(Long id) {
        ModificationWorkOrder order = new ModificationWorkOrder();
        order.setId(id);
        order.setStatus(ModificationWorkOrderStatus.COMPLETED.code());
        order.setCompletedAt(LocalDateTime.of(SELECTED_YEAR, 1, 9, 10, 0));
        return order;
    }

    private ModificationWorkOrderLine modificationLine(String priceDifference) {
        ModificationWorkOrderLine line = new ModificationWorkOrderLine();
        line.setOldPartAction(PartChangeAction.DISCOUNT.code());
        line.setPriceDifference(new BigDecimal(priceDifference));
        return line;
    }

    private ModificationWorkOrderLine ignoredModificationLine() {
        ModificationWorkOrderLine line = modificationLine("-999.00");
        line.setOldPartAction(PartChangeAction.STOCK_IN.code());
        return line;
    }

    private StatisticsDashboardVO.FinancialRow findPeriod(List<StatisticsDashboardVO.FinancialRow> rows, String period) {
        return rows.stream()
                .filter(row -> period.equals(row.getPeriod()))
                .findFirst()
                .orElseThrow();
    }

    private void assertMoney(BigDecimal actual, String expected) {
        assertThat(actual).isEqualByComparingTo(new BigDecimal(expected));
    }
}
