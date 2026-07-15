package com.example.forklift_erp.service;

import com.example.forklift_erp.dto.StatisticsDashboardVO;
import com.example.forklift_erp.dto.ListSummaryVO;
import com.example.forklift_erp.constant.ModificationWorkOrderStatus;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.FinancialEvent;
import com.example.forklift_erp.entity.ModificationWorkOrder;
import com.example.forklift_erp.entity.ModificationWorkOrderLine;
import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.PurchaseOrder;
import com.example.forklift_erp.entity.RentalBill;
import com.example.forklift_erp.entity.RepairRecord;
import com.example.forklift_erp.entity.RentalRecord;
import com.example.forklift_erp.entity.StockOperationLog;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.FinancialEventRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderLineRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderRepository;
import com.example.forklift_erp.repository.OutboundOrderRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.PurchaseOrderRepository;
import com.example.forklift_erp.repository.RentalBillRepository;
import com.example.forklift_erp.repository.RepairRecordRepository;
import com.example.forklift_erp.repository.RentalRecordRepository;
import com.example.forklift_erp.repository.StockOperationLogRepository;
import com.example.forklift_erp.util.MoneyValues;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class StatisticsService {
    private final StockOperationLogRepository stockOperationLogRepository;
    private final FinancialEventRepository financialEventRepository;
    private final MachineInventoryRepository machineInventoryRepository;
    private final PartInventoryRepository partInventoryRepository;
    private final RepairRecordRepository repairRecordRepository;
    private final RentalRecordRepository rentalRecordRepository;
    private final ModificationWorkOrderRepository modificationWorkOrderRepository;
    private final ModificationWorkOrderLineRepository modificationWorkOrderLineRepository;
    private final OutboundOrderRepository outboundOrderRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final RentalBillRepository rentalBillRepository;
    private final RentalRevenueCalculator rentalRevenueCalculator;
    private final FinancialStatisticsBuilder financialStatisticsBuilder;
    private final InventoryStatisticsBuilder inventoryStatisticsBuilder;
    private final ListSummaryService listSummaryService;

    public StatisticsService(
            StockOperationLogRepository stockOperationLogRepository,
            FinancialEventRepository financialEventRepository,
            MachineInventoryRepository machineInventoryRepository,
            PartInventoryRepository partInventoryRepository,
            RepairRecordRepository repairRecordRepository,
            RentalRecordRepository rentalRecordRepository,
            ModificationWorkOrderRepository modificationWorkOrderRepository,
            ModificationWorkOrderLineRepository modificationWorkOrderLineRepository,
            OutboundOrderRepository outboundOrderRepository,
            PurchaseOrderRepository purchaseOrderRepository,
            RentalBillRepository rentalBillRepository,
            RentalRevenueCalculator rentalRevenueCalculator,
            FinancialStatisticsBuilder financialStatisticsBuilder,
            InventoryStatisticsBuilder inventoryStatisticsBuilder,
            ListSummaryService listSummaryService
    ) {
        this.stockOperationLogRepository = stockOperationLogRepository;
        this.financialEventRepository = financialEventRepository;
        this.machineInventoryRepository = machineInventoryRepository;
        this.partInventoryRepository = partInventoryRepository;
        this.repairRecordRepository = repairRecordRepository;
        this.rentalRecordRepository = rentalRecordRepository;
        this.modificationWorkOrderRepository = modificationWorkOrderRepository;
        this.modificationWorkOrderLineRepository = modificationWorkOrderLineRepository;
        this.outboundOrderRepository = outboundOrderRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.rentalBillRepository = rentalBillRepository;
        this.rentalRevenueCalculator = rentalRevenueCalculator;
        this.financialStatisticsBuilder = financialStatisticsBuilder;
        this.inventoryStatisticsBuilder = inventoryStatisticsBuilder;
        this.listSummaryService = listSummaryService;
    }

    public StatisticsDashboardVO financeDashboard(Integer year) {
        int selectedYear = year == null ? LocalDate.now().getYear() : year;
        LocalDateTime startAt = LocalDate.of(selectedYear - 4, 1, 1).atStartOfDay();
        LocalDateTime endAt = LocalDate.of(selectedYear + 1, 1, 1).atStartOfDay();
        List<StockOperationLog> stockLogs = stockOperationLogRepository
                .findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(startAt, endAt);
        List<FinancialEvent> financialEvents = financialEventRepository.findByBusinessDateBetweenOrderByBusinessDateAscIdAsc(
                startAt.toLocalDate(), endAt.toLocalDate().minusDays(1));
        List<PurchaseOrder> purchases = purchaseOrderRepository.findAll().stream()
                .filter(order -> order.getReceivedDate() != null)
                .filter(order -> !order.getReceivedDate().isBefore(startAt.toLocalDate())
                        && order.getReceivedDate().isBefore(endAt.toLocalDate()))
                .toList();
        List<OutboundOrder> outbounds = outboundOrderRepository.findAll().stream()
                .filter(order -> {
                    LocalDate date = order.getSalesDate() != null
                            ? order.getSalesDate()
                            : order.getCreatedAt() == null ? null : order.getCreatedAt().toLocalDate();
                    return date != null && !date.isBefore(startAt.toLocalDate()) && date.isBefore(endAt.toLocalDate());
                })
                .toList();
        Set<Long> outboundStockLogIds = outbounds.stream()
                .map(OutboundOrder::getStockOperationLogId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, StockOperationLog> outboundStockLogs = outboundStockLogIds.isEmpty()
                ? Map.of()
                : stockOperationLogRepository.findAllById(outboundStockLogIds).stream()
                .collect(Collectors.toMap(StockOperationLog::getId, log -> log, (left, right) -> left));
        List<RepairRecord> repairs = repairRecordRepository.findByRepairDateBetween(startAt, endAt.minusNanos(1));
        List<RentalRecord> rentals = rentalRecordRepository.findInDateRange(
                startAt.toLocalDate(),
                endAt.toLocalDate().minusDays(1),
                startAt,
                endAt
        );
        Set<Long> rentalIds = rentals.stream()
                .map(RentalRecord::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<RentalBill> rentalBills = rentalIds.isEmpty()
                ? List.of()
                : rentalBillRepository.findAll().stream()
                .filter(bill -> rentalIds.contains(bill.getRentalId()))
                .toList();
        List<ModificationWorkOrder> modificationOrders = modificationWorkOrderRepository.findCompletedInRange(
                ModificationWorkOrderStatus.COMPLETED.code(),
                startAt,
                endAt
        );
        List<Long> modificationOrderIds = modificationOrders.stream()
                .map(ModificationWorkOrder::getId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, List<ModificationWorkOrderLine>> modificationLinesByOrderId = modificationOrderIds.isEmpty()
                ? Map.of()
                : modificationWorkOrderLineRepository.findByWorkOrderIdIn(modificationOrderIds).stream()
                .collect(Collectors.groupingBy(ModificationWorkOrderLine::getWorkOrderId));
        Map<Long, MachineInventory> machines = loadStockMachines(stockLogs);
        Map<Long, PartInventory> parts = loadStockParts(stockLogs);

        StatisticsDashboardVO dashboard = new StatisticsDashboardVO();
        dashboard.setSelectedYear(selectedYear);
        dashboard.setGeneratedAt(LocalDateTime.now());
        dashboard.setMonthlyFinance(financialStatisticsBuilder.buildMonthlyRowsHybrid(
                selectedYear,
                financialEvents,
                stockLogs,
                purchases,
                outbounds,
                outboundStockLogs,
                repairs,
                rentals,
                rentalBills,
                modificationOrders,
                modificationLinesByOrderId
        ));
        dashboard.setYearlyFinance(financialStatisticsBuilder.buildYearlyRowsHybrid(
                financialEvents,
                stockLogs,
                purchases,
                outbounds,
                outboundStockLogs,
                repairs,
                rentals,
                rentalBills,
                modificationOrders,
                modificationLinesByOrderId
        ));
        dashboard.setAnnualSummary(financialStatisticsBuilder.annualRow(selectedYear, dashboard.getYearlyFinance()));
        dashboard.setResourceFlows(buildResourceFlows(selectedYear, stockLogs, machines, parts));
        dashboard.setTopOutbounds(buildTopOutbounds(selectedYear, stockLogs, machines, parts));
        dashboard.setTopRentals(buildTopRentals(selectedYear, rentals));
        dashboard.setStockValues(inventoryStatisticsBuilder.stockValues());
        dashboard.setLowStocks(inventoryStatisticsBuilder.lowStocks());
        return dashboard;
    }

    public ListSummaryVO listSummary(String type, String keyword) {
        return listSummaryService.summarize(type, keyword);
    }

    public ListSummaryVO listSummary(String type, String keyword, String resourceType) {
        return listSummaryService.summarize(type, keyword, resourceType);
    }

    public ListSummaryVO listSummary(String type, String keyword, String resourceType, String status) {
        return listSummaryService.summarize(type, keyword, resourceType, status);
    }

    private Map<Long, MachineInventory> loadStockMachines(List<StockOperationLog> stockLogs) {
        Set<Long> ids = stockLogs.stream()
                .filter(log -> "MACHINE".equals(log.getResourceType()))
                .map(StockOperationLog::getResourceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        return machineInventoryRepository.findAllById(ids).stream()
                .filter(machine -> !Boolean.TRUE.equals(machine.getModelOnly()))
                .collect(Collectors.toMap(MachineInventory::getId, machine -> machine, (left, right) -> left));
    }

    private Map<Long, PartInventory> loadStockParts(List<StockOperationLog> stockLogs) {
        Set<Long> ids = stockLogs.stream()
                .filter(log -> "PART".equals(log.getResourceType()))
                .map(StockOperationLog::getResourceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        return partInventoryRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(PartInventory::getId, part -> part, (left, right) -> left));
    }

    private List<StatisticsDashboardVO.ResourceFlowRow> buildResourceFlows(
            int selectedYear,
            List<StockOperationLog> stockLogs,
            Map<Long, MachineInventory> machines,
            Map<Long, PartInventory> parts
    ) {
        Map<String, StatisticsDashboardVO.ResourceFlowRow> rows = new LinkedHashMap<>();
        for (String type : List.of("MACHINE", "PART")) {
            StatisticsDashboardVO.ResourceFlowRow row = new StatisticsDashboardVO.ResourceFlowRow();
            row.setResourceType(type);
            row.setLabel(resourceLabel(type));
            rows.put(type, row);
        }
        for (StockOperationLog log : stockLogs) {
            if (log.getCreatedAt() == null || log.getCreatedAt().getYear() != selectedYear) {
                continue;
            }
            StatisticsDashboardVO.ResourceFlowRow row = rows.computeIfAbsent(log.getResourceType(), type -> {
                StatisticsDashboardVO.ResourceFlowRow created = new StatisticsDashboardVO.ResourceFlowRow();
                created.setResourceType(type);
                created.setLabel(resourceLabel(type));
                return created;
            });
            Price price = priceFor(log, machines, parts);
            int inboundQuantity = inboundQuantity(log);
            if (inboundQuantity > 0) {
                row.setInboundQuantity(row.getInboundQuantity() + inboundQuantity);
                row.setInboundCost(row.getInboundCost().add(price.cost().multiply(BigDecimal.valueOf(inboundQuantity))));
            }
            int outboundQuantity = outboundQuantity(log);
            if (outboundQuantity > 0) {
                BigDecimal revenue = price.revenue().multiply(BigDecimal.valueOf(outboundQuantity));
                BigDecimal cost = price.cost().multiply(BigDecimal.valueOf(outboundQuantity));
                row.setOutboundQuantity(row.getOutboundQuantity() + outboundQuantity);
                row.setOutboundRevenue(row.getOutboundRevenue().add(revenue));
                if (revenue.signum() > 0) {
                    row.setGrossProfit(row.getGrossProfit().add(revenue.subtract(cost)));
                }
            }
        }
        return rows.values().stream().toList();
    }

    private List<StatisticsDashboardVO.TopOutboundRow> buildTopOutbounds(
            int selectedYear,
            List<StockOperationLog> stockLogs,
            Map<Long, MachineInventory> machines,
            Map<Long, PartInventory> parts
    ) {
        Map<String, StatisticsDashboardVO.TopOutboundRow> rows = new LinkedHashMap<>();
        for (StockOperationLog log : stockLogs) {
            if (log.getCreatedAt() == null
                    || log.getCreatedAt().getYear() != selectedYear) {
                continue;
            }
            int quantity = outboundQuantity(log);
            if (quantity <= 0) {
                continue;
            }
            String key = log.getResourceType() + ":" + Objects.toString(log.getResourceId(), log.getResourceCode());
            StatisticsDashboardVO.TopOutboundRow row = rows.computeIfAbsent(key, ignored -> {
                StatisticsDashboardVO.TopOutboundRow created = new StatisticsDashboardVO.TopOutboundRow();
                created.setResourceType(log.getResourceType());
                created.setResourceCode(log.getResourceCode());
                created.setResourceName(log.getResourceName());
                return created;
            });
            Price price = priceFor(log, machines, parts);
            BigDecimal revenue = price.revenue().multiply(BigDecimal.valueOf(quantity));
            BigDecimal cost = price.cost().multiply(BigDecimal.valueOf(quantity));
            if (revenue.signum() <= 0) {
                continue;
            }
            row.setQuantity(row.getQuantity() + quantity);
            row.setRevenue(row.getRevenue().add(revenue));
            row.setCost(row.getCost().add(cost));
            row.setGrossProfit(row.getGrossProfit().add(revenue.subtract(cost)));
        }
        return rows.values().stream()
                .filter(row -> row.getRevenue().signum() > 0)
                .sorted(Comparator.comparing(StatisticsDashboardVO.TopOutboundRow::getRevenue).reversed())
                .limit(8)
                .toList();
    }

    private List<StatisticsDashboardVO.TopRentalRow> buildTopRentals(
            int selectedYear,
            List<RentalRecord> rentals
    ) {
        LocalDate yearStart = LocalDate.of(selectedYear, 1, 1);
        LocalDate yearEnd = LocalDate.of(selectedYear, 12, 31);
        return rentals.stream()
                .map(rental -> Map.entry(rental, rentalRevenueCalculator.amountForRange(rental, yearStart, yearEnd)))
                .filter(entry -> entry.getValue().signum() > 0)
                .sorted(Map.Entry.<RentalRecord, BigDecimal>comparingByValue().reversed())
                .limit(8)
                .map(entry -> {
                    RentalRecord rental = entry.getKey();
                    StatisticsDashboardVO.TopRentalRow row = new StatisticsDashboardVO.TopRentalRow();
                    row.setRentalNo(rental.getRentalNo());
                    row.setVehicleNumber(rental.getVehicleNumber());
                    row.setMachineName(rental.getMachineName());
                    row.setSpecificationModel(rental.getSpecificationModel());
                    row.setDestination(rental.getDestination());
                    row.setStatus(rental.getStatus());
                    row.setRentalPrice(entry.getValue());
                    return row;
                })
                .toList();
    }

    private Price priceFor(
            StockOperationLog log,
            Map<Long, MachineInventory> machines,
            Map<Long, PartInventory> parts
    ) {
        return new Price(amount(log.getUnitCost()), amount(log.getUnitRevenue()));
    }

    private int quantity(StockOperationLog log) {
        return log.getQuantity() == null ? 0 : log.getQuantity();
    }

    private int inboundQuantity(StockOperationLog log) {
        String operationType = log.getOperationType();
        if ("INBOUND".equals(operationType) || "INITIAL".equals(operationType)) {
            return quantity(log);
        }
        if ("ADJUST".equals(operationType)) {
            return Math.max(quantityDelta(log), 0);
        }
        return 0;
    }

    private int outboundQuantity(StockOperationLog log) {
        String operationType = log.getOperationType();
        if ("OUTBOUND".equals(operationType)) {
            return quantity(log);
        }
        if ("ADJUST".equals(operationType)) {
            return Math.max(-quantityDelta(log), 0);
        }
        return 0;
    }

    private int quantityDelta(StockOperationLog log) {
        if (log.getBeforeQuantity() != null && log.getAfterQuantity() != null) {
            return log.getAfterQuantity() - log.getBeforeQuantity();
        }
        return quantity(log);
    }

    private BigDecimal amount(BigDecimal value) {
        return MoneyValues.zeroIfNullOrNegative(value);
    }

    private String resourceLabel(String resourceType) {
        return "MACHINE".equals(resourceType) ? "整车" : "PART".equals(resourceType) ? "配件" : resourceType;
    }

    private record Price(BigDecimal cost, BigDecimal revenue) {
    }

}
