package com.example.forklift_erp.service;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.dto.HistoricalRepairReportVO;
import com.example.forklift_erp.dto.HistoricalRepairRequestDTO;
import com.example.forklift_erp.entity.FinancialEvent;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.ModificationWorkOrderLine;
import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.RentalRecord;
import com.example.forklift_erp.entity.RepairPartUsage;
import com.example.forklift_erp.entity.RepairRecord;
import com.example.forklift_erp.entity.StockBalance;
import com.example.forklift_erp.entity.StockLot;
import com.example.forklift_erp.entity.StockMovement;
import com.example.forklift_erp.entity.StockMovementLine;
import com.example.forklift_erp.entity.StockOperationLog;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.FinancialEventRepository;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderLineRepository;
import com.example.forklift_erp.repository.OutboundOrderRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.RentalRecordRepository;
import com.example.forklift_erp.repository.RepairPartUsageRepository;
import com.example.forklift_erp.repository.RepairRecordRepository;
import com.example.forklift_erp.repository.StockBalanceRepository;
import com.example.forklift_erp.repository.StockLotRepository;
import com.example.forklift_erp.repository.StockMovementLineRepository;
import com.example.forklift_erp.repository.StockMovementRepository;
import com.example.forklift_erp.repository.StockOperationLogRepository;
import com.example.forklift_erp.util.MoneyValues;
import com.example.forklift_erp.util.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Safe, idempotent repairs for facts that can be reconstructed from existing
 * documents.  Any ambiguous historical fact is deliberately placed in the
 * migration-exception queue instead of being guessed.
 */
@Service
public class HistoricalDataRepairService {
    private static final String SOURCE_OUTBOUND_ORDER = FinancialEventService.SOURCE_OUTBOUND_ORDER;
    private static final String RESOURCE_MACHINE = StockLedgerService.RESOURCE_MACHINE;
    private static final String RESOURCE_PART = StockLedgerService.RESOURCE_PART;

    private final OutboundOrderRepository outboundOrderRepository;
    private final StockMovementRepository stockMovementRepository;
    private final StockMovementLineRepository stockMovementLineRepository;
    private final StockOperationLogRepository stockOperationLogRepository;
    private final FinancialEventRepository financialEventRepository;
    private final FinancialEventService financialEventService;
    private final RentalRecordRepository rentalRecordRepository;
    private final MachineInventoryRepository machineInventoryRepository;
    private final PartInventoryRepository partInventoryRepository;
    private final StockBalanceRepository stockBalanceRepository;
    private final StockLotRepository stockLotRepository;
    private final StockLotService stockLotService;
    private final RepairRecordRepository repairRecordRepository;
    private final RepairPartUsageRepository repairPartUsageRepository;
    private final ModificationWorkOrderLineRepository modificationWorkOrderLineRepository;
    private final MigrationExceptionService migrationExceptionService;
    private final HistoricalRepairBackupService historicalRepairBackupService;
    private final HistoricalReferenceRepairService historicalReferenceRepairService;
    private final OperationAuditService operationAuditService;

    public HistoricalDataRepairService(
            OutboundOrderRepository outboundOrderRepository,
            StockMovementRepository stockMovementRepository,
            StockMovementLineRepository stockMovementLineRepository,
            StockOperationLogRepository stockOperationLogRepository,
            FinancialEventRepository financialEventRepository,
            FinancialEventService financialEventService,
            RentalRecordRepository rentalRecordRepository,
            MachineInventoryRepository machineInventoryRepository,
            PartInventoryRepository partInventoryRepository,
            StockBalanceRepository stockBalanceRepository,
            StockLotRepository stockLotRepository,
            StockLotService stockLotService,
            RepairRecordRepository repairRecordRepository,
            RepairPartUsageRepository repairPartUsageRepository,
            ModificationWorkOrderLineRepository modificationWorkOrderLineRepository,
            MigrationExceptionService migrationExceptionService,
            HistoricalRepairBackupService historicalRepairBackupService,
            HistoricalReferenceRepairService historicalReferenceRepairService,
            OperationAuditService operationAuditService
    ) {
        this.outboundOrderRepository = outboundOrderRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.stockMovementLineRepository = stockMovementLineRepository;
        this.stockOperationLogRepository = stockOperationLogRepository;
        this.financialEventRepository = financialEventRepository;
        this.financialEventService = financialEventService;
        this.rentalRecordRepository = rentalRecordRepository;
        this.machineInventoryRepository = machineInventoryRepository;
        this.partInventoryRepository = partInventoryRepository;
        this.stockBalanceRepository = stockBalanceRepository;
        this.stockLotRepository = stockLotRepository;
        this.stockLotService = stockLotService;
        this.repairRecordRepository = repairRecordRepository;
        this.repairPartUsageRepository = repairPartUsageRepository;
        this.modificationWorkOrderLineRepository = modificationWorkOrderLineRepository;
        this.migrationExceptionService = migrationExceptionService;
        this.historicalRepairBackupService = historicalRepairBackupService;
        this.historicalReferenceRepairService = historicalReferenceRepairService;
        this.operationAuditService = operationAuditService;
    }

    @Transactional(readOnly = true)
    public HistoricalRepairReportVO dryRun(LocalDate fallbackBusinessDate) {
        return reconcile(true, false, fallbackDate(fallbackBusinessDate));
    }

    @Transactional
    public HistoricalRepairReportVO repair(HistoricalRepairRequestDTO request) {
        if (request == null || !Boolean.TRUE.equals(request.getBackupConfirmed())) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Confirm historical repair. The system will create and retain a full JSON backup before mutation");
        }
        HistoricalRepairBackupService.BackupReceipt backup = historicalRepairBackupService.createPreRepairBackup();
        HistoricalRepairReportVO report = reconcile(false, true, fallbackDate(request.getFallbackBusinessDate()));
        report.setBackupFileName(backup.fileName());
        report.setBackupByteSize(backup.byteSize());
        report.setBackupSha256(backup.sha256());
        report.setBackupCreatedAt(backup.createdAt());
        operationAuditService.record(
                "Data quality",
                "HISTORICAL_REPAIR",
                "SYSTEM",
                null,
                backup.fileName(),
                "Historical data repair",
                "Fixed " + report.getFixedCount()
                        + ", defaulted " + report.getDefaultedCount()
                        + ", exceptions " + report.getExceptionCount(),
                SecurityUtils.currentUsername(),
                "Backup SHA-256: " + backup.sha256()
        );
        return report;
    }

    private HistoricalRepairReportVO reconcile(boolean dryRun, boolean backupConfirmed, LocalDate fallbackBusinessDate) {
        HistoricalRepairReportVO report = new HistoricalRepairReportVO();
        report.setDryRun(dryRun);
        report.setBackupConfirmed(backupConfirmed);
        report.setFallbackBusinessDate(fallbackBusinessDate);
        report.setGeneratedAt(LocalDateTime.now());
        report.setBackupInstruction(
                dryRun
                        ? "Dry-run does not write data. Executing the repair will create and retain a complete JSON backup first."
                        : "A complete JSON backup is created and retained before mutation; ambiguous history is never fabricated."
        );

        Map<Long, MachineInventory> machines = machineInventoryRepository.findAll().stream()
                .collect(Collectors.toMap(MachineInventory::getId, Function.identity(), (left, right) -> left));
        Map<Long, PartInventory> parts = partInventoryRepository.findAll().stream()
                .collect(Collectors.toMap(PartInventory::getId, Function.identity(), (left, right) -> left));

        historicalReferenceRepairService.repair(report, dryRun, machines, parts);
        repairPartOutboundAmounts(report, dryRun);
        repairReturnedRentalDates(report, dryRun, fallbackBusinessDate);
        createMissingFifoLots(report, dryRun, fallbackBusinessDate, machines, parts);
        reportUnreconstructableRepairUsage(report, dryRun);
        reportUnreconstructableOldPartCost(report, dryRun);
        return report;
    }

    private void repairPartOutboundAmounts(HistoricalRepairReportVO report, boolean dryRun) {
        for (OutboundOrder order : outboundOrderRepository.findAll()) {
            if (!OutboundOrder.RESOURCE_PART.equals(order.getResourceType())) {
                continue;
            }
            int quantity = safeQuantity(order.getQuantity());
            if (quantity <= 0) {
                exception(report, dryRun, "INVALID_PART_OUTBOUND_QUANTITY", "OUTBOUND_ORDER", order.getId(),
                        "Part outbound order has a non-positive quantity and cannot be reconstructed");
                continue;
            }
            BigDecimal lineAmount = reconstructSalesLineAmount(order, quantity);
            if (lineAmount == null) {
                exception(report, dryRun, "UNRESOLVED_PART_OUTBOUND_AMOUNT", "OUTBOUND_ORDER", order.getId(),
                        "No receivable/line amount is available to reconstruct the part sale unit price");
                continue;
            }
            BigDecimal unitPrice = lineAmount.divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_UP);
            List<StockMovement> movements = stockMovementRepository.findBySourceTypeAndSourceId(SOURCE_OUTBOUND_ORDER, order.getId());
            BigDecimal outboundCost = outboundCost(movements);
            boolean financialMismatch = salesPostingMismatch(order, lineAmount, outboundCost);
            boolean orderMismatch = !sameMoney(order.getLineAmount(), lineAmount)
                    || !sameMoney(order.getReceivableAmount(), lineAmount)
                    || !sameMoney(order.getUnitSalePrice(), unitPrice)
                    || !sameMoney(order.getSettlementPrice(), unitPrice);
            boolean movementMismatch = movementPricingMismatch(movements, unitPrice, lineAmount, order.getSalesDate());
            boolean locked = Boolean.TRUE.equals(order.getIsLocked());

            if (!orderMismatch && !movementMismatch && !financialMismatch) {
                continue;
            }
            if (dryRun) {
                report.addFixed("PART_OUTBOUND_AMOUNT", "OUTBOUND_ORDER", order.getId(),
                        repairDescription(orderMismatch, movementMismatch, financialMismatch, locked, unitPrice, lineAmount));
                continue;
            }

            if (!locked) {
                order.setUnitSalePrice(unitPrice);
                order.setSettlementPrice(unitPrice);
                order.setLineAmount(lineAmount);
                order.setReceivableAmount(lineAmount);
                order = outboundOrderRepository.saveAndFlush(order);
                updateUnlockedOutboundMovements(order, movements, unitPrice, lineAmount);
            }
            if (financialMismatch || orderMismatch) {
                financialEventService.replaceSalesPosting(order, outboundCost, locked);
            }
            report.addFixed("PART_OUTBOUND_AMOUNT", "OUTBOUND_ORDER", order.getId(),
                    repairDescription(orderMismatch, movementMismatch, financialMismatch, locked, unitPrice, lineAmount));
        }
    }

    private BigDecimal reconstructSalesLineAmount(OutboundOrder order, int quantity) {
        BigDecimal explicit = firstNonNegative(order.getLineAmount(), order.getReceivableAmount());
        if (explicit != null) {
            return scaleMoney(explicit);
        }
        if (order.getUnitSalePrice() != null && order.getUnitSalePrice().signum() >= 0) {
            return scaleMoney(order.getUnitSalePrice().multiply(BigDecimal.valueOf(quantity)));
        }
        // Legacy part orders wrote total receivable to settlement_price.  It is
        // deliberately interpreted as a total only when no newer price field
        // exists; newer orders have line_amount and unit_sale_price.
        BigDecimal legacyTotal = firstNonNegative(order.getSettlementPrice(), order.getSalePrice());
        return legacyTotal == null ? null : scaleMoney(legacyTotal);
    }

    private boolean movementPricingMismatch(
            List<StockMovement> movements,
            BigDecimal unitPrice,
            BigDecimal lineAmount,
            LocalDate salesDate
    ) {
        List<StockMovementLine> lines = outboundLines(movements);
        if (lines.isEmpty()) {
            return false;
        }
        int quantity = lines.stream().mapToInt(line -> Math.abs(value(line.getQuantityDelta()))).sum();
        BigDecimal allocated = BigDecimal.ZERO;
        for (int index = 0; index < lines.size(); index++) {
            StockMovementLine line = lines.get(index);
            int lineQuantity = Math.abs(value(line.getQuantityDelta()));
            BigDecimal expectedLineAmount = index == lines.size() - 1
                    ? lineAmount.subtract(allocated)
                    : unitPrice.multiply(BigDecimal.valueOf(lineQuantity));
            allocated = allocated.add(expectedLineAmount);
            if (!sameMoney(line.getUnitRevenue(), unitPrice) || !sameMoney(line.getLineAmount(), expectedLineAmount)) {
                return true;
            }
        }
        if (quantity <= 0) {
            return false;
        }
        return salesDate != null && movements.stream()
                .anyMatch(movement -> !salesDate.equals(movement.getBusinessDate()));
    }

    private void updateUnlockedOutboundMovements(
            OutboundOrder order,
            List<StockMovement> movements,
            BigDecimal unitPrice,
            BigDecimal lineAmount
    ) {
        List<StockMovementLine> lines = outboundLines(movements);
        BigDecimal allocated = BigDecimal.ZERO;
        for (int index = 0; index < lines.size(); index++) {
            StockMovementLine line = lines.get(index);
            int lineQuantity = Math.abs(value(line.getQuantityDelta()));
            BigDecimal allocatedLineAmount = index == lines.size() - 1
                    ? lineAmount.subtract(allocated)
                    : unitPrice.multiply(BigDecimal.valueOf(lineQuantity));
            allocated = allocated.add(allocatedLineAmount);
            line.setUnitRevenue(unitPrice);
            line.setLineAmount(allocatedLineAmount);
            stockMovementLineRepository.save(line);
        }
        for (StockMovement movement : movements) {
            if (order.getSalesDate() != null) {
                movement.setBusinessDate(order.getSalesDate());
            }
            movement.setBusinessType(com.example.forklift_erp.constant.StockBusinessType.SALE_OUTBOUND);
            stockMovementRepository.save(movement);
        }
        if (order.getStockOperationLogId() != null) {
            stockOperationLogRepository.findById(order.getStockOperationLogId()).ifPresent(log -> {
                log.setUnitRevenue(unitPrice);
                stockOperationLogRepository.save(log);
            });
        }
    }

    private boolean salesPostingMismatch(OutboundOrder order, BigDecimal expectedRevenue, BigDecimal expectedCost) {
        List<FinancialEvent> events = financialEventRepository
                .findBySourceTypeAndSourceIdOrderByIdAsc(FinancialEventService.SOURCE_OUTBOUND_ORDER, order.getId());
        if (events.isEmpty()) {
            return true;
        }
        return !sameMoney(eventTotal(events, com.example.forklift_erp.constant.FinancialEventType.ACCOUNTS_RECEIVABLE), expectedRevenue)
                || !sameMoney(eventTotal(events, com.example.forklift_erp.constant.FinancialEventType.REVENUE), expectedRevenue)
                || !sameMoney(eventTotal(events, com.example.forklift_erp.constant.FinancialEventType.COST_OF_GOODS_SOLD), expectedCost);
    }

    private BigDecimal eventTotal(List<FinancialEvent> events, String eventType) {
        return events.stream()
                .filter(event -> eventType.equals(event.getEventType()))
                .map(event -> event.getAmount() == null ? BigDecimal.ZERO : event.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal outboundCost(List<StockMovement> movements) {
        return outboundLines(movements).stream()
                .map(line -> line.getCostAmount() == null
                        ? MoneyValues.zeroIfNullOrNegative(line.getUnitCost())
                        .multiply(BigDecimal.valueOf(Math.abs(value(line.getQuantityDelta()))))
                        : line.getCostAmount())
                .map(value -> value == null ? BigDecimal.ZERO : value.abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<StockMovementLine> outboundLines(List<StockMovement> movements) {
        return movements.stream()
                .flatMap(movement -> stockMovementLineRepository.findByMovementIdOrderByIdAsc(movement.getId()).stream())
                .filter(line -> value(line.getQuantityDelta()) < 0)
                .sorted(Comparator.comparing(StockMovementLine::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
    }

    private String repairDescription(
            boolean orderMismatch,
            boolean movementMismatch,
            boolean financialMismatch,
            boolean locked,
            BigDecimal unitPrice,
            BigDecimal lineAmount
    ) {
        List<String> actions = new ArrayList<>();
        if (locked) {
            actions.add("locked document preserved");
        } else {
            if (orderMismatch) actions.add("order unit/line amount normalized");
            if (movementMismatch) actions.add("stock movement revenue/date normalized");
        }
        if (financialMismatch || orderMismatch) actions.add("append-only financial correction posted");
        return String.join("; ", actions)
                + "; unit price=" + unitPrice.toPlainString()
                + ", line amount=" + lineAmount.toPlainString();
    }

    private void repairReturnedRentalDates(
            HistoricalRepairReportVO report,
            boolean dryRun,
            LocalDate fallbackBusinessDate
    ) {
        for (RentalRecord rental : rentalRecordRepository.findAll()) {
            if (!RentalRecord.STATUS_RETURNED.equals(rental.getStatus())) {
                continue;
            }
            LocalDate inferred = firstNonNull(
                    rental.getReturnDate(),
                    rental.getEndDate(),
                    dateOf(rental.getUpdatedAt()),
                    dateOf(rental.getCreatedAt()),
                    fallbackBusinessDate
            );
            if (inferred == null) {
                exception(report, dryRun, "UNRESOLVED_RENTAL_RETURN_DATE", "RENTAL", rental.getId(),
                        "Returned rental has no end/return/create/update date from which to infer the return date");
                continue;
            }
            boolean returnMissing = rental.getReturnDate() == null;
            boolean endMissing = rental.getEndDate() == null;
            if (!returnMissing && !endMissing) {
                continue;
            }
            String detail = "Set " + (returnMissing ? "return date" : "")
                    + (returnMissing && endMissing ? " and " : "")
                    + (endMissing ? "end date" : "")
                    + " to inferred date " + inferred;
            if (!dryRun) {
                if (returnMissing) rental.setReturnDate(inferred);
                if (endMissing) rental.setEndDate(inferred);
                rentalRecordRepository.save(rental);
            }
            report.addDefaulted("RENTAL_RETURN_DATE", "RENTAL", rental.getId(), detail);
        }
    }

    private void createMissingFifoLots(
            HistoricalRepairReportVO report,
            boolean dryRun,
            LocalDate fallbackBusinessDate,
            Map<Long, MachineInventory> machines,
            Map<Long, PartInventory> parts
    ) {
        Map<ResourceWarehouseKey, Integer> physical = stockBalanceRepository.findAll().stream()
                .filter(balance -> RESOURCE_MACHINE.equals(balance.getResourceType()) || RESOURCE_PART.equals(balance.getResourceType()))
                .collect(Collectors.toMap(
                        balance -> new ResourceWarehouseKey(balance.getResourceType(), balance.getResourceId(), balance.getWarehouseId()),
                        balance -> value(balance.getAvailableQuantity()) + value(balance.getReservedQuantity()) + value(balance.getLockedQuantity()),
                        Integer::sum,
                        LinkedHashMap::new
                ));
        Map<ResourceWarehouseKey, Integer> fifo = stockLotRepository.findAll().stream()
                .filter(lot -> !StockLot.STATUS_REVERSED.equals(lot.getStatus()))
                .collect(Collectors.groupingBy(
                        lot -> new ResourceWarehouseKey(lot.getResourceType(), lot.getResourceId(), lot.getWarehouseId()),
                        LinkedHashMap::new,
                        Collectors.summingInt(lot -> value(lot.getRemainingQuantity()))
                ));
        for (Map.Entry<ResourceWarehouseKey, Integer> entry : physical.entrySet()) {
            ResourceWarehouseKey key = entry.getKey();
            int physicalQuantity = entry.getValue();
            if (physicalQuantity <= 0) {
                continue;
            }
            int existingFifo = fifo.getOrDefault(key, 0);
            if (existingFifo > physicalQuantity) {
                exception(report, dryRun, "FIFO_EXCEEDS_PHYSICAL_BALANCE", key.resourceType(), key.resourceId(),
                        "Warehouse " + key.warehouseId() + " has FIFO quantity " + existingFifo
                                + " but physical balance " + physicalQuantity);
                continue;
            }
            int missing = physicalQuantity - existingFifo;
            if (missing == 0) {
                continue;
            }
            CostAndDate costAndDate = costAndDate(key, machines, parts, fallbackBusinessDate);
            if (costAndDate == null) {
                exception(report, dryRun, "UNRESOLVED_FIFO_OPENING_COST", key.resourceType(), key.resourceId(),
                        "Warehouse " + key.warehouseId() + " has " + missing
                                + " physical units without a verifiable purchase/landed cost");
                continue;
            }
            if (!dryRun) {
                stockLotService.createReceiptLot(
                        key.resourceType(),
                        key.resourceId(),
                        key.warehouseId(),
                        missing,
                        costAndDate.unitCost(),
                        BigDecimal.ZERO,
                        "MIGRATION_INITIAL",
                        key.resourceId(),
                        null,
                        costAndDate.businessDate(),
                        "HISTORICAL-FIFO:" + key.resourceType() + ":" + key.resourceId() + ":" + key.warehouseId()
                );
            }
            report.addFixed("FIFO_OPENING_LOT", key.resourceType(), key.resourceId(),
                    "Created " + missing + " opening FIFO units in warehouse " + key.warehouseId()
                            + " at unit cost " + costAndDate.unitCost().toPlainString());
        }
    }

    private CostAndDate costAndDate(
            ResourceWarehouseKey key,
            Map<Long, MachineInventory> machines,
            Map<Long, PartInventory> parts,
            LocalDate fallbackBusinessDate
    ) {
        if (RESOURCE_MACHINE.equals(key.resourceType())) {
            MachineInventory machine = machines.get(key.resourceId());
            if (machine == null || Boolean.TRUE.equals(machine.getModelOnly())) {
                return null;
            }
            BigDecimal cost = firstNonNegative(machine.getLandedUnitCost(), machine.getPurchasePrice());
            if (cost == null) {
                return null;
            }
            return new CostAndDate(cost, firstNonNull(dateOf(machine.getInboundDate()), dateOf(machine.getCreatedAt()), fallbackBusinessDate));
        }
        PartInventory part = parts.get(key.resourceId());
        if (part == null) {
            return null;
        }
        BigDecimal cost = firstNonNegative(part.getLandedUnitCost(), part.getPurchasePrice());
        if (cost == null) {
            return null;
        }
        return new CostAndDate(cost, firstNonNull(dateOf(part.getInboundDate()), dateOf(part.getCreatedAt()), fallbackBusinessDate));
    }

    private void reportUnreconstructableRepairUsage(HistoricalRepairReportVO report, boolean dryRun) {
        for (RepairRecord repair : repairRecordRepository.findAll()) {
            List<RepairPartUsage> usages = repairPartUsageRepository.findByRepairIdOrderByIdAsc(repair.getId());
            if (hasText(repair.getUsedPartIds()) && usages.isEmpty()) {
                exception(report, dryRun, "UNTRACKED_REPAIR_PART_USAGE", "REPAIR", repair.getId(),
                        "Historical used_part_ids exists but no repair_part_usage/FIFO trace exists; manual correction is required");
                continue;
            }
            for (RepairPartUsage usage : usages) {
                if (usage.getStockMovementId() == null || usage.getStockLotConsumptionId() == null) {
                    exception(report, dryRun, "UNTRACKED_REPAIR_PART_USAGE", "REPAIR_PART_USAGE", usage.getId(),
                            "Repair material line has no linked stock movement/FIFO consumption and cannot be auto-rebuilt");
                }
            }
        }
    }

    private void reportUnreconstructableOldPartCost(HistoricalRepairReportVO report, boolean dryRun) {
        for (ModificationWorkOrderLine line : modificationWorkOrderLineRepository.findAll()) {
            if (!"STOCK_IN".equals(line.getOldPartDisposition())) {
                continue;
            }
            if (line.getOldPartWarehouseId() == null || line.getOldPartUnitCost() == null) {
                exception(report, dryRun, "UNRESOLVED_OLD_PART_COST", "MODIFICATION_WORK_ORDER_LINE", line.getId(),
                        "Old part is designated for stock-in but its warehouse or independent valuation is missing");
            }
        }
    }

    private void exception(
            HistoricalRepairReportVO report,
            boolean dryRun,
            String exceptionType,
            String sourceType,
            Long sourceId,
            String detail
    ) {
        report.addException(exceptionType, sourceType, sourceId, detail);
        if (!dryRun) {
            migrationExceptionService.openOnce(exceptionType, sourceType, sourceId, detail);
        }
    }

    private int safeQuantity(Integer quantity) {
        return quantity == null ? 0 : quantity;
    }

    private int value(Integer input) {
        return input == null ? 0 : input;
    }

    private boolean sameMoney(BigDecimal left, BigDecimal right) {
        return MoneyValues.zeroIfNullOrNegative(left).compareTo(MoneyValues.zeroIfNullOrNegative(right)) == 0;
    }

    private BigDecimal firstNonNegative(BigDecimal... values) {
        return MoneyValues.firstNonNegativeOrNull(values);
    }

    private BigDecimal scaleMoney(BigDecimal amount) {
        return MoneyValues.zeroIfNullOrNegative(amount).setScale(2, RoundingMode.HALF_UP);
    }

    private LocalDate fallbackDate(LocalDate value) {
        return value == null ? LocalDate.now() : value;
    }

    private LocalDate dateOf(LocalDateTime value) {
        return value == null ? null : value.toLocalDate();
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ResourceWarehouseKey(String resourceType, Long resourceId, Long warehouseId) {
    }

    private record CostAndDate(BigDecimal unitCost, LocalDate businessDate) {
    }
}
