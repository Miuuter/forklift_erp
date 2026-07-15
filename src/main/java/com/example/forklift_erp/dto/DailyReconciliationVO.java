package com.example.forklift_erp.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class DailyReconciliationVO {
    private LocalDate activityDate;
    private LocalDateTime stateCapturedAt;
    private String stateScopeWarning;
    private Summary summary = new Summary();
    private List<StockIssue> stockIssues = new ArrayList<>();
    private List<SalesRow> sales = new ArrayList<>();
    private List<RentalIssue> rentalIssues = new ArrayList<>();

    @Data
    public static class Summary {
        private int errorCount;
        private int warningCount;
        private int stockIssueCount;
        private int rentalIssueCount;
        private int overpaidSalesCount;
    }

    @Data
    public static class StockIssue {
        private String severity;
        private String code;
        private String resourceType;
        private Long resourceId;
        private String resourceCode;
        private String resourceName;
        private Long warehouseId;
        private Integer profileQuantity;
        private Integer availableQuantity;
        private Integer reservedQuantity;
        private Integer lockedQuantity;
        private Integer fifoQuantity;
        private Integer latestMovementAfterQuantity;
        private Integer dailyMovementDelta;
        private String message;
    }

    @Data
    public static class SalesRow {
        private Long outboundOrderId;
        private String orderNo;
        private String customerName;
        private BigDecimal receivable = BigDecimal.ZERO;
        private BigDecimal receipts = BigDecimal.ZERO;
        private BigDecimal unpaid = BigDecimal.ZERO;
        private BigDecimal activityReceivable = BigDecimal.ZERO;
        private BigDecimal activityReceipts = BigDecimal.ZERO;
        private String status;
    }

    @Data
    public static class RentalIssue {
        private String severity;
        private String code;
        private Long rentalId;
        private Long machineId;
        private String rentalNo;
        private String vehicleNumber;
        private Long warehouseId;
        private Integer availableQuantity;
        private Integer lockedQuantity;
        private String machineStatus;
        private String message;
    }
}
