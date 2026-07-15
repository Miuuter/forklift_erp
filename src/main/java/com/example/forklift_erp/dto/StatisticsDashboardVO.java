package com.example.forklift_erp.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class StatisticsDashboardVO {
    private Integer selectedYear;
    private LocalDateTime generatedAt;
    private Boolean dataComplete = true;
    private List<String> dataWarnings = new ArrayList<>();
    private FinancialRow annualSummary;
    private List<FinancialRow> monthlyFinance = new ArrayList<>();
    private List<FinancialRow> yearlyFinance = new ArrayList<>();
    private List<ResourceFlowRow> resourceFlows = new ArrayList<>();
    private List<TopOutboundRow> topOutbounds = new ArrayList<>();
    private List<TopRentalRow> topRentals = new ArrayList<>();
    private List<StockValueRow> stockValues = new ArrayList<>();
    private List<LowStockRow> lowStocks = new ArrayList<>();

    @Data
    public static class FinancialRow {
        private String period;
        private BigDecimal inboundCost = BigDecimal.ZERO;
        private BigDecimal outboundRevenue = BigDecimal.ZERO;
        private BigDecimal outboundCost = BigDecimal.ZERO;
        private BigDecimal grossProfit = BigDecimal.ZERO;
        private BigDecimal netProfit = BigDecimal.ZERO;
        private BigDecimal netCashflow = BigDecimal.ZERO;
        private BigDecimal repairIncome = BigDecimal.ZERO;
        private BigDecimal repairReceivable = BigDecimal.ZERO;
        private BigDecimal repairExpense = BigDecimal.ZERO;
        private BigDecimal repairPartsCost = BigDecimal.ZERO;
        private BigDecimal rentalIncome = BigDecimal.ZERO;
        private BigDecimal modificationIncome = BigDecimal.ZERO;
        private BigDecimal modificationExpense = BigDecimal.ZERO;
        private BigDecimal inventoryGain = BigDecimal.ZERO;
        private BigDecimal inventoryLoss = BigDecimal.ZERO;
        private BigDecimal totalIncome = BigDecimal.ZERO;
        private BigDecimal totalExpense = BigDecimal.ZERO;
        private Integer inboundQuantity = 0;
        private Integer outboundQuantity = 0;
        private Integer repairOrders = 0;
        private Integer rentalOrders = 0;
        private Integer modificationOrders = 0;
    }

    @Data
    public static class ResourceFlowRow {
        private String resourceType;
        private String label;
        private Integer inboundQuantity = 0;
        private Integer outboundQuantity = 0;
        private BigDecimal inboundCost = BigDecimal.ZERO;
        private BigDecimal outboundRevenue = BigDecimal.ZERO;
        private BigDecimal grossProfit = BigDecimal.ZERO;
    }

    @Data
    public static class TopOutboundRow {
        private String resourceType;
        private String resourceCode;
        private String resourceName;
        private Integer quantity = 0;
        private BigDecimal revenue = BigDecimal.ZERO;
        private BigDecimal cost = BigDecimal.ZERO;
        private BigDecimal grossProfit = BigDecimal.ZERO;
    }

    @Data
    public static class TopRentalRow {
        private String rentalNo;
        private String vehicleNumber;
        private String machineName;
        private String specificationModel;
        private String destination;
        private String status;
        private BigDecimal rentalPrice = BigDecimal.ZERO;
    }

    @Data
    public static class StockValueRow {
        private String resourceType;
        private String label;
        private Integer itemCount = 0;
        private Integer stockQuantity = 0;
        private BigDecimal costValue = BigDecimal.ZERO;
        private BigDecimal settlementValue = BigDecimal.ZERO;
        @Deprecated
        private BigDecimal retailValue = BigDecimal.ZERO;

        @SuppressWarnings("deprecation")
        public void setRetailValueForCompatibility(BigDecimal value) {
            this.retailValue = value;
        }
    }

    @Data
    public static class LowStockRow {
        private String resourceType;
        private String resourceCode;
        private String resourceName;
        private Integer quantity = 0;
        private String unit;
        private Integer threshold = 0;
    }
}
