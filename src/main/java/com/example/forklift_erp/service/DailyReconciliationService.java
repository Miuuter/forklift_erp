package com.example.forklift_erp.service;

import com.example.forklift_erp.constant.FinancialEventType;
import com.example.forklift_erp.constant.MachineStockStatus;
import com.example.forklift_erp.dto.DailyReconciliationVO;
import com.example.forklift_erp.entity.FinancialEvent;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.RentalRecord;
import com.example.forklift_erp.entity.StockBalance;
import com.example.forklift_erp.entity.StockLot;
import com.example.forklift_erp.entity.StockMovement;
import com.example.forklift_erp.entity.StockMovementLine;
import com.example.forklift_erp.repository.FinancialEventRepository;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.OutboundOrderRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.RentalRecordRepository;
import com.example.forklift_erp.repository.StockBalanceRepository;
import com.example.forklift_erp.repository.StockLotRepository;
import com.example.forklift_erp.repository.StockMovementLineRepository;
import com.example.forklift_erp.repository.StockMovementRepository;
import com.example.forklift_erp.util.MoneyValues;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only daily data quality view. Current stock/profile state is evaluated
 * as captured; the selected date is used for movement and financial activity.
 */
@Service
public class DailyReconciliationService {
    private static final String SEVERITY_ERROR = "ERROR";
    private static final String SEVERITY_WARNING = "WARNING";

    private final StockBalanceRepository stockBalanceRepository;
    private final StockLotRepository stockLotRepository;
    private final StockMovementRepository stockMovementRepository;
    private final StockMovementLineRepository stockMovementLineRepository;
    private final MachineInventoryRepository machineInventoryRepository;
    private final PartInventoryRepository partInventoryRepository;
    private final FinancialEventRepository financialEventRepository;
    private final OutboundOrderRepository outboundOrderRepository;
    private final RentalRecordRepository rentalRecordRepository;

    public DailyReconciliationService(
            StockBalanceRepository stockBalanceRepository,
            StockLotRepository stockLotRepository,
            StockMovementRepository stockMovementRepository,
            StockMovementLineRepository stockMovementLineRepository,
            MachineInventoryRepository machineInventoryRepository,
            PartInventoryRepository partInventoryRepository,
            FinancialEventRepository financialEventRepository,
            OutboundOrderRepository outboundOrderRepository,
            RentalRecordRepository rentalRecordRepository
    ) {
        this.stockBalanceRepository = stockBalanceRepository;
        this.stockLotRepository = stockLotRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.stockMovementLineRepository = stockMovementLineRepository;
        this.machineInventoryRepository = machineInventoryRepository;
        this.partInventoryRepository = partInventoryRepository;
        this.financialEventRepository = financialEventRepository;
        this.outboundOrderRepository = outboundOrderRepository;
        this.rentalRecordRepository = rentalRecordRepository;
    }

    @Transactional(readOnly = true)
    public DailyReconciliationVO reconcile(LocalDate requestedDate) {
        LocalDate activityDate = requestedDate == null ? LocalDate.now() : requestedDate;
        DailyReconciliationVO result = new DailyReconciliationVO();
        result.setActivityDate(activityDate);
        result.setStateCapturedAt(LocalDateTime.now());
        if (!LocalDate.now().equals(activityDate)) {
            result.setStateScopeWarning(
                    "库存、FIFO 与租赁检查均为当前状态；所选日期仅用于流水和财务活动，历史日结需后续快照支持。");
        }

        List<StockBalance> balances = stockBalanceRepository.findAll();
        List<StockLot> lots = stockLotRepository.findAll();
        Map<ResourceWarehouseKey, StockBalance> balanceByKey = balances.stream()
                .collect(Collectors.toMap(
                        balance -> new ResourceWarehouseKey(balance.getResourceType(), balance.getResourceId(), balance.getWarehouseId()),
                        balance -> balance,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Map<ResourceWarehouseKey, Integer> fifoByKey = lots.stream()
                .filter(lot -> !StockLot.STATUS_REVERSED.equals(lot.getStatus()))
                .collect(Collectors.groupingBy(
                        lot -> new ResourceWarehouseKey(lot.getResourceType(), lot.getResourceId(), lot.getWarehouseId()),
                        LinkedHashMap::new,
                        Collectors.summingInt(lot -> value(lot.getRemainingQuantity()))
                ));

        Map<Long, StockMovement> movements = stockMovementRepository.findAll().stream()
                .collect(Collectors.toMap(StockMovement::getId, movement -> movement, (left, right) -> left));
        Map<ResourceWarehouseKey, StockMovementLine> latestLines = new HashMap<>();
        Map<ResourceWarehouseKey, Integer> dailyDeltas = new HashMap<>();
        for (StockMovementLine line : stockMovementLineRepository.findAll()) {
            StockMovement movement = movements.get(line.getMovementId());
            ResourceWarehouseKey key = new ResourceWarehouseKey(line.getResourceType(), line.getResourceId(), line.getWarehouseId());
            if (movement != null && activityDate.equals(movement.getBusinessDate())) {
                dailyDeltas.merge(key, value(line.getQuantityDelta()), Integer::sum);
            }
            StockMovementLine previous = latestLines.get(key);
            if (previous == null || later(line, movement, previous, movements.get(previous.getMovementId()))) {
                latestLines.put(key, line);
            }
        }

        Map<ResourceKey, Integer> profileQuantities = profileQuantities();
        Map<ResourceKey, ResourceLabel> labels = profileLabels();
        Map<ResourceKey, Integer> availableTotals = balances.stream()
                .collect(Collectors.groupingBy(
                        balance -> new ResourceKey(balance.getResourceType(), balance.getResourceId()),
                        Collectors.summingInt(balance -> value(balance.getAvailableQuantity()))
                ));

        Set<ResourceKey> allResources = new java.util.LinkedHashSet<>();
        allResources.addAll(profileQuantities.keySet());
        allResources.addAll(availableTotals.keySet());
        allResources.addAll(fifoByKey.keySet().stream().map(ResourceWarehouseKey::resourceKey).toList());
        for (ResourceKey resource : allResources) {
            Integer profileQuantity = profileQuantities.get(resource);
            int availableTotal = availableTotals.getOrDefault(resource, 0);
            if (profileQuantity != null && profileQuantity != availableTotal) {
                addStockIssue(result, issue(
                        SEVERITY_ERROR,
                        "PROFILE_BALANCE_MISMATCH",
                        resource,
                        null,
                        labels.get(resource),
                        profileQuantity,
                        availableTotal,
                        0,
                        0,
                        0,
                        null,
                        "库存主档数量与各仓可用余额合计不一致"
                ));
            }
        }

        Set<ResourceWarehouseKey> warehouseKeys = new java.util.LinkedHashSet<>();
        warehouseKeys.addAll(balanceByKey.keySet());
        warehouseKeys.addAll(fifoByKey.keySet());
        warehouseKeys.addAll(latestLines.keySet());
        for (ResourceWarehouseKey key : warehouseKeys) {
            StockBalance balance = balanceByKey.get(key);
            int available = balance == null ? 0 : value(balance.getAvailableQuantity());
            int reserved = balance == null ? 0 : value(balance.getReservedQuantity());
            int locked = balance == null ? 0 : value(balance.getLockedQuantity());
            int fifo = fifoByKey.getOrDefault(key, 0);
            StockMovementLine latest = latestLines.get(key);
            Integer latestAfter = latest == null ? null : latest.getAfterQuantity();
            ResourceLabel label = labels.get(key.resourceKey());
            if (balance != null && latestAfter != null && available != value(latestAfter)) {
                DailyReconciliationVO.StockIssue issue = issue(
                        SEVERITY_ERROR,
                        "BALANCE_MOVEMENT_MISMATCH",
                        key.resourceKey(),
                        key.warehouseId(),
                        label,
                        profileQuantities.get(key.resourceKey()),
                        available,
                        reserved,
                        locked,
                        fifo,
                        latestAfter,
                        "仓库余额与最近库存流水的期末数不一致"
                );
                issue.setDailyMovementDelta(dailyDeltas.getOrDefault(key, 0));
                addStockIssue(result, issue);
            }
            int physicalQuantity = available + reserved + locked;
            if (fifo != physicalQuantity) {
                DailyReconciliationVO.StockIssue issue = issue(
                        SEVERITY_ERROR,
                        "FIFO_BALANCE_MISMATCH",
                        key.resourceKey(),
                        key.warehouseId(),
                        label,
                        profileQuantities.get(key.resourceKey()),
                        available,
                        reserved,
                        locked,
                        fifo,
                        latestAfter,
                        "FIFO 批次剩余量与仓库实物余额（可用+预留+锁定）不一致"
                );
                issue.setDailyMovementDelta(dailyDeltas.getOrDefault(key, 0));
                addStockIssue(result, issue);
            }
        }

        result.setSales(reconcileSales(activityDate));
        reconcileRentals(result, balanceByKey);
        finalizeSummary(result);
        return result;
    }

    private Map<ResourceKey, Integer> profileQuantities() {
        Map<ResourceKey, Integer> result = new LinkedHashMap<>();
        for (MachineInventory machine : machineInventoryRepository.findAll()) {
            if (!Boolean.TRUE.equals(machine.getModelOnly())) {
                result.put(new ResourceKey(StockLedgerService.RESOURCE_MACHINE, machine.getId()),
                        value(machine.getInventoryCount()));
            }
        }
        for (PartInventory part : partInventoryRepository.findAll()) {
            result.put(new ResourceKey(StockLedgerService.RESOURCE_PART, part.getId()), value(part.getQuantity()));
        }
        return result;
    }

    private Map<ResourceKey, ResourceLabel> profileLabels() {
        Map<ResourceKey, ResourceLabel> result = new LinkedHashMap<>();
        for (MachineInventory machine : machineInventoryRepository.findAll()) {
            result.put(new ResourceKey(StockLedgerService.RESOURCE_MACHINE, machine.getId()),
                    new ResourceLabel(machine.getVehicleProductNumber(), machine.getName()));
        }
        for (PartInventory part : partInventoryRepository.findAll()) {
            result.put(new ResourceKey(StockLedgerService.RESOURCE_PART, part.getId()),
                    new ResourceLabel(part.getPartCode(), part.getPartName()));
        }
        return result;
    }

    private List<DailyReconciliationVO.SalesRow> reconcileSales(LocalDate activityDate) {
        Map<Long, OutboundOrder> orders = outboundOrderRepository.findAll().stream()
                .collect(Collectors.toMap(OutboundOrder::getId, order -> order, (left, right) -> left));
        Map<Long, DailyReconciliationVO.SalesRow> rows = new LinkedHashMap<>();
        for (FinancialEvent event : financialEventRepository.findAll()) {
            if (!FinancialEventService.SOURCE_OUTBOUND_ORDER.equals(event.getSourceType())
                    || event.getSourceId() == null
                    || event.getBusinessDate() == null
                    || event.getBusinessDate().isAfter(activityDate)) {
                continue;
            }
            if (!FinancialEventType.ACCOUNTS_RECEIVABLE.equals(event.getEventType())
                    && !FinancialEventType.CASH_RECEIPT.equals(event.getEventType())) {
                continue;
            }
            DailyReconciliationVO.SalesRow row = rows.computeIfAbsent(event.getSourceId(), sourceId -> {
                DailyReconciliationVO.SalesRow created = new DailyReconciliationVO.SalesRow();
                created.setOutboundOrderId(sourceId);
                OutboundOrder order = orders.get(sourceId);
                if (order != null) {
                    created.setOrderNo(order.getOrderNo());
                    created.setCustomerName(order.getCustomerName());
                }
                return created;
            });
            BigDecimal amount = event.getAmount() == null ? BigDecimal.ZERO : event.getAmount();
            if (FinancialEventType.ACCOUNTS_RECEIVABLE.equals(event.getEventType())) {
                row.setReceivable(row.getReceivable().add(amount));
                if (activityDate.equals(event.getBusinessDate())) {
                    row.setActivityReceivable(row.getActivityReceivable().add(amount));
                }
            } else {
                row.setReceipts(row.getReceipts().add(amount));
                if (activityDate.equals(event.getBusinessDate())) {
                    row.setActivityReceipts(row.getActivityReceipts().add(amount));
                }
            }
        }
        rows.values().forEach(row -> {
            row.setUnpaid(row.getReceivable().subtract(row.getReceipts()));
            row.setStatus(row.getUnpaid().signum() < 0 ? "OVERPAID"
                    : row.getUnpaid().signum() == 0 ? "SETTLED" : "OUTSTANDING");
        });
        return rows.values().stream()
                .sorted(Comparator.comparing(DailyReconciliationVO.SalesRow::getOutboundOrderId))
                .toList();
    }

    private void reconcileRentals(
            DailyReconciliationVO result,
            Map<ResourceWarehouseKey, StockBalance> balanceByKey
    ) {
        Map<Long, MachineInventory> machines = machineInventoryRepository.findAll().stream()
                .collect(Collectors.toMap(MachineInventory::getId, machine -> machine, (left, right) -> left));
        Set<Long> activeRentalMachineIds = new java.util.HashSet<>();
        for (RentalRecord rental : rentalRecordRepository.findAll()) {
            if (!RentalRecord.STATUS_ACTIVE.equals(rental.getStatus())) {
                continue;
            }
            activeRentalMachineIds.add(rental.getMachineId());
            MachineInventory machine = machines.get(rental.getMachineId());
            StockBalance balance = balanceByKey.get(new ResourceWarehouseKey(
                    StockLedgerService.RESOURCE_MACHINE, rental.getMachineId(), rental.getWarehouseId()));
            int available = balance == null ? 0 : value(balance.getAvailableQuantity());
            int locked = balance == null ? 0 : value(balance.getLockedQuantity());
            String machineStatus = machine == null ? null : machine.getStockStatus();
            if (machine == null || available != 0 || locked != 1
                    || !MachineStockStatus.RENTED.code().equals(machineStatus)
                    || value(machine.getInventoryCount()) != 0) {
                DailyReconciliationVO.RentalIssue issue = rentalIssue(
                        SEVERITY_ERROR,
                        "ACTIVE_RENTAL_STOCK_MISMATCH",
                        rental,
                        available,
                        locked,
                        machineStatus,
                        "进行中租赁必须对应锁定 1、可用 0、车辆状态 RENTED 且主档可用库存为 0"
                );
                result.getRentalIssues().add(issue);
            }
        }
        for (Map.Entry<ResourceWarehouseKey, StockBalance> entry : balanceByKey.entrySet()) {
            ResourceWarehouseKey key = entry.getKey();
            StockBalance balance = entry.getValue();
            if (!StockLedgerService.RESOURCE_MACHINE.equals(key.resourceType())
                    || value(balance.getLockedQuantity()) <= 0
                    || activeRentalMachineIds.contains(key.resourceId())) {
                continue;
            }
            DailyReconciliationVO.RentalIssue issue = new DailyReconciliationVO.RentalIssue();
            issue.setSeverity(SEVERITY_ERROR);
            issue.setCode("UNMATCHED_RENTAL_LOCK");
            issue.setMachineId(key.resourceId());
            issue.setWarehouseId(key.warehouseId());
            issue.setAvailableQuantity(value(balance.getAvailableQuantity()));
            issue.setLockedQuantity(value(balance.getLockedQuantity()));
            MachineInventory machine = machines.get(key.resourceId());
            issue.setVehicleNumber(machine == null ? null : machine.getVehicleProductNumber());
            issue.setMachineStatus(machine == null ? null : machine.getStockStatus());
            issue.setMessage("车辆锁定库存没有对应进行中租赁记录");
            result.getRentalIssues().add(issue);
        }
    }

    private DailyReconciliationVO.StockIssue issue(
            String severity,
            String code,
            ResourceKey resource,
            Long warehouseId,
            ResourceLabel label,
            Integer profileQuantity,
            int available,
            int reserved,
            int locked,
            int fifo,
            Integer latestAfter,
            String message
    ) {
        DailyReconciliationVO.StockIssue issue = new DailyReconciliationVO.StockIssue();
        issue.setSeverity(severity);
        issue.setCode(code);
        issue.setResourceType(resource.resourceType());
        issue.setResourceId(resource.resourceId());
        issue.setResourceCode(label == null ? null : label.code());
        issue.setResourceName(label == null ? null : label.name());
        issue.setWarehouseId(warehouseId);
        issue.setProfileQuantity(profileQuantity);
        issue.setAvailableQuantity(available);
        issue.setReservedQuantity(reserved);
        issue.setLockedQuantity(locked);
        issue.setFifoQuantity(fifo);
        issue.setLatestMovementAfterQuantity(latestAfter);
        issue.setMessage(message);
        return issue;
    }

    private DailyReconciliationVO.RentalIssue rentalIssue(
            String severity,
            String code,
            RentalRecord rental,
            int available,
            int locked,
            String machineStatus,
            String message
    ) {
        DailyReconciliationVO.RentalIssue issue = new DailyReconciliationVO.RentalIssue();
        issue.setSeverity(severity);
        issue.setCode(code);
        issue.setRentalId(rental.getId());
        issue.setRentalNo(rental.getRentalNo());
        issue.setMachineId(rental.getMachineId());
        issue.setVehicleNumber(rental.getVehicleNumber());
        issue.setWarehouseId(rental.getWarehouseId());
        issue.setAvailableQuantity(available);
        issue.setLockedQuantity(locked);
        issue.setMachineStatus(machineStatus);
        issue.setMessage(message);
        return issue;
    }

    private void addStockIssue(DailyReconciliationVO result, DailyReconciliationVO.StockIssue issue) {
        result.getStockIssues().add(issue);
    }

    private void finalizeSummary(DailyReconciliationVO result) {
        int errors = (int) result.getStockIssues().stream()
                .filter(issue -> SEVERITY_ERROR.equals(issue.getSeverity()))
                .count()
                + (int) result.getRentalIssues().stream()
                .filter(issue -> SEVERITY_ERROR.equals(issue.getSeverity()))
                .count();
        int warnings = (int) result.getStockIssues().stream()
                .filter(issue -> SEVERITY_WARNING.equals(issue.getSeverity()))
                .count()
                + (int) result.getRentalIssues().stream()
                .filter(issue -> SEVERITY_WARNING.equals(issue.getSeverity()))
                .count();
        result.getSummary().setErrorCount(errors);
        result.getSummary().setWarningCount(warnings);
        result.getSummary().setStockIssueCount(result.getStockIssues().size());
        result.getSummary().setRentalIssueCount(result.getRentalIssues().size());
        result.getSummary().setOverpaidSalesCount((int) result.getSales().stream()
                .filter(row -> "OVERPAID".equals(row.getStatus()))
                .count());
    }

    private boolean later(
            StockMovementLine candidate,
            StockMovement candidateMovement,
            StockMovementLine existing,
            StockMovement existingMovement
    ) {
        LocalDateTime candidateAt = candidateMovement == null ? candidate.getCreatedAt() : candidateMovement.getCreatedAt();
        LocalDateTime existingAt = existingMovement == null ? existing.getCreatedAt() : existingMovement.getCreatedAt();
        if (candidateAt == null) {
            return existingAt == null && value(candidate.getId()) > value(existing.getId());
        }
        if (existingAt == null) {
            return true;
        }
        return candidateAt.isAfter(existingAt)
                || (candidateAt.equals(existingAt) && value(candidate.getId()) > value(existing.getId()));
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }

    private record ResourceKey(String resourceType, Long resourceId) {
    }

    private record ResourceWarehouseKey(String resourceType, Long resourceId, Long warehouseId) {
        ResourceKey resourceKey() {
            return new ResourceKey(resourceType, resourceId);
        }
    }

    private record ResourceLabel(String code, String name) {
    }
}
