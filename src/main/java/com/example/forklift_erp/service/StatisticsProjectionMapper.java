package com.example.forklift_erp.service;

import com.example.forklift_erp.dto.StatisticsDashboardVO;
import com.example.forklift_erp.repository.StatisticsProjectionRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class StatisticsProjectionMapper {

    List<StatisticsDashboardVO.FinancialRow> monthlyRows(
            int selectedYear,
            List<StatisticsProjectionRepository.FinancialSummary> summaries
    ) {
        Map<String, StatisticsDashboardVO.FinancialRow> rows = new LinkedHashMap<>();
        for (int month = 1; month <= 12; month++) {
            String period = "%d-%02d".formatted(selectedYear, month);
            rows.put(period, emptyFinancialRow(period));
        }
        summaries.forEach(summary -> rows.put(summary.period(), financialRow(summary)));
        return new ArrayList<>(rows.values());
    }

    List<StatisticsDashboardVO.FinancialRow> yearlyRows(
            List<StatisticsProjectionRepository.FinancialSummary> summaries
    ) {
        return summaries.stream()
                .map(this::financialRow)
                .sorted(java.util.Comparator.comparing(
                        StatisticsDashboardVO.FinancialRow::getPeriod).reversed())
                .toList();
    }

    List<StatisticsDashboardVO.ResourceFlowRow> resourceFlows(
            List<StatisticsProjectionRepository.ResourceFlowSummary> summaries
    ) {
        Map<String, StatisticsDashboardVO.ResourceFlowRow> rows = new LinkedHashMap<>();
        for (String type : List.of("MACHINE", "PART")) {
            StatisticsDashboardVO.ResourceFlowRow row = new StatisticsDashboardVO.ResourceFlowRow();
            row.setResourceType(type);
            row.setLabel(label(type));
            rows.put(type, row);
        }
        for (StatisticsProjectionRepository.ResourceFlowSummary summary : summaries) {
            StatisticsDashboardVO.ResourceFlowRow row = new StatisticsDashboardVO.ResourceFlowRow();
            row.setResourceType(summary.resourceType());
            row.setLabel(label(summary.resourceType()));
            row.setInboundQuantity(toInt(summary.inboundQuantity()));
            row.setOutboundQuantity(toInt(summary.outboundQuantity()));
            row.setInboundCost(summary.inboundCost());
            row.setOutboundRevenue(summary.outboundRevenue());
            row.setGrossProfit(summary.grossProfit());
            rows.put(summary.resourceType(), row);
        }
        return new ArrayList<>(rows.values());
    }

    List<StatisticsDashboardVO.TopOutboundRow> topOutbounds(
            List<StatisticsProjectionRepository.TopOutboundSummary> summaries
    ) {
        return summaries.stream().map(summary -> {
            StatisticsDashboardVO.TopOutboundRow row = new StatisticsDashboardVO.TopOutboundRow();
            row.setResourceType(summary.resourceType());
            row.setResourceCode(summary.resourceCode());
            row.setResourceName(summary.resourceName());
            row.setQuantity(toInt(summary.quantity()));
            row.setRevenue(summary.revenue());
            row.setCost(summary.cost());
            row.setGrossProfit(summary.grossProfit());
            return row;
        }).toList();
    }

    List<StatisticsDashboardVO.TopRentalRow> topRentals(
            List<StatisticsProjectionRepository.TopRentalSummary> summaries
    ) {
        return summaries.stream().map(summary -> {
            StatisticsDashboardVO.TopRentalRow row = new StatisticsDashboardVO.TopRentalRow();
            row.setRentalNo(summary.rentalNo());
            row.setVehicleNumber(summary.vehicleNumber());
            row.setMachineName(summary.machineName());
            row.setSpecificationModel(summary.specificationModel());
            row.setDestination(summary.destination());
            row.setStatus(summary.status());
            row.setRentalPrice(summary.rentalAmount());
            return row;
        }).toList();
    }

    List<StatisticsDashboardVO.StockValueRow> stockValues(
            List<StatisticsProjectionRepository.StockValueSummary> summaries
    ) {
        return summaries.stream().map(summary -> {
            StatisticsDashboardVO.StockValueRow row = new StatisticsDashboardVO.StockValueRow();
            row.setResourceType(summary.resourceType());
            row.setLabel(summary.label());
            row.setItemCount(toInt(summary.itemCount()));
            row.setStockQuantity(toInt(summary.stockQuantity()));
            row.setCostValue(summary.costValue());
            row.setSettlementValue(summary.settlementValue());
            row.setRetailValueForCompatibility(summary.settlementValue());
            return row;
        }).toList();
    }

    List<StatisticsDashboardVO.LowStockRow> lowStocks(
            List<StatisticsProjectionRepository.LowStockSummary> summaries
    ) {
        return summaries.stream().map(summary -> {
            StatisticsDashboardVO.LowStockRow row = new StatisticsDashboardVO.LowStockRow();
            row.setResourceType("PART");
            row.setResourceCode(summary.resourceCode());
            row.setResourceName(summary.resourceName());
            row.setQuantity(toInt(summary.quantity()));
            row.setUnit(summary.unit());
            row.setThreshold(summary.reorderPoint());
            return row;
        }).toList();
    }

    StatisticsDashboardVO.FinancialRow annualRow(
            int selectedYear,
            List<StatisticsDashboardVO.FinancialRow> yearlyRows
    ) {
        String period = String.valueOf(selectedYear);
        return yearlyRows.stream()
                .filter(row -> period.equals(row.getPeriod()))
                .findFirst()
                .orElseGet(() -> emptyFinancialRow(period));
    }

    private StatisticsDashboardVO.FinancialRow financialRow(
            StatisticsProjectionRepository.FinancialSummary summary
    ) {
        StatisticsDashboardVO.FinancialRow row = emptyFinancialRow(summary.period());
        row.setInboundQuantity(toInt(summary.inboundQuantity()));
        row.setOutboundQuantity(toInt(summary.outboundQuantity()));
        row.setInboundCost(summary.inboundCost());
        row.setOutboundRevenue(summary.outboundRevenue());
        row.setOutboundCost(summary.outboundCost());
        row.setRepairIncome(summary.repairIncome());
        row.setRepairReceivable(summary.repairReceivable());
        row.setRepairExpense(summary.repairExpense());
        row.setRepairPartsCost(summary.repairPartsCost());
        row.setRentalIncome(summary.rentalIncome());
        row.setModificationIncome(summary.modificationIncome());
        row.setModificationExpense(summary.modificationExpense());
        row.setInventoryGain(summary.inventoryGain());
        row.setInventoryLoss(summary.inventoryLoss());
        row.setNetCashflow(summary.netCashflow());
        row.setRepairOrders(toInt(summary.repairOrders()));
        row.setRentalOrders(toInt(summary.rentalOrders()));
        row.setModificationOrders(toInt(summary.modificationOrders()));
        BigDecimal income = summary.outboundRevenue()
                .add(summary.repairIncome())
                .add(summary.rentalIncome())
                .add(summary.modificationIncome())
                .add(summary.inventoryGain());
        BigDecimal expense = summary.outboundCost()
                .add(summary.repairExpense())
                .add(summary.repairPartsCost())
                .add(summary.modificationExpense())
                .add(summary.inventoryLoss());
        row.setTotalIncome(income);
        row.setTotalExpense(expense);
        row.setGrossProfit(income.subtract(expense));
        row.setNetProfit(income.subtract(expense));
        return row;
    }

    private StatisticsDashboardVO.FinancialRow emptyFinancialRow(String period) {
        StatisticsDashboardVO.FinancialRow row = new StatisticsDashboardVO.FinancialRow();
        row.setPeriod(period);
        return row;
    }

    private String label(String resourceType) {
        return "MACHINE".equals(resourceType)
                ? "整车"
                : "PART".equals(resourceType) ? "配件" : resourceType;
    }

    private int toInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }
}
