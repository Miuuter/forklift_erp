package com.example.forklift_erp.service;

import com.example.forklift_erp.constant.MachineStockStatus;
import com.example.forklift_erp.dto.DailyReconciliationVO;
import com.example.forklift_erp.repository.DailyReconciliationProjectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DailyReconciliationService {
    private static final String SEVERITY_ERROR = "ERROR";
    private static final String SEVERITY_WARNING = "WARNING";

    private final DailyReconciliationProjectionRepository projectionRepository;

    public DailyReconciliationService(DailyReconciliationProjectionRepository projectionRepository) {
        this.projectionRepository = projectionRepository;
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

        List<DailyReconciliationProjectionRepository.ResourceProfileRow> profiles =
                projectionRepository.resourceProfiles();
        Map<ResourceKey, Integer> profileQuantities = profiles.stream()
                .filter(profile -> profile.profileQuantity() != null)
                .collect(Collectors.toMap(
                        profile -> new ResourceKey(profile.resourceType(), profile.resourceId()),
                        DailyReconciliationProjectionRepository.ResourceProfileRow::profileQuantity,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Map<ResourceKey, ResourceLabel> labels = profiles.stream()
                .collect(Collectors.toMap(
                        profile -> new ResourceKey(profile.resourceType(), profile.resourceId()),
                        profile -> new ResourceLabel(profile.resourceCode(), profile.resourceName()),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        List<DailyReconciliationProjectionRepository.BalanceRow> balances = projectionRepository.balances();
        Map<ResourceWarehouseKey, DailyReconciliationProjectionRepository.BalanceRow> balanceByKey =
                balances.stream().collect(Collectors.toMap(
                        row -> key(row.resourceType(), row.resourceId(), row.warehouseId()),
                        row -> row,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Map<ResourceKey, Integer> availableTotals = balances.stream()
                .collect(Collectors.groupingBy(
                        row -> new ResourceKey(row.resourceType(), row.resourceId()),
                        LinkedHashMap::new,
                        Collectors.summingInt(
                                DailyReconciliationProjectionRepository.BalanceRow::availableQuantity)
                ));

        Map<ResourceWarehouseKey, Integer> fifoByKey = projectionRepository.fifoTotals().stream()
                .collect(Collectors.toMap(
                        row -> key(row.resourceType(), row.resourceId(), row.warehouseId()),
                        DailyReconciliationProjectionRepository.FifoRow::fifoQuantity,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Map<ResourceWarehouseKey, DailyReconciliationProjectionRepository.MovementStateRow> movementByKey =
                projectionRepository.movementStates(activityDate).stream()
                        .collect(Collectors.toMap(
                                row -> key(row.resourceType(), row.resourceId(), row.warehouseId()),
                                row -> row,
                                (left, right) -> left,
                                LinkedHashMap::new
                        ));

        reconcileProfileTotals(result, profileQuantities, availableTotals, fifoByKey, labels);
        reconcileWarehouseStates(
                result,
                profileQuantities,
                labels,
                balanceByKey,
                fifoByKey,
                movementByKey
        );
        result.setSales(reconcileSales(activityDate));
        reconcileRentals(result);
        finalizeSummary(result);
        return result;
    }

    private void reconcileProfileTotals(
            DailyReconciliationVO result,
            Map<ResourceKey, Integer> profileQuantities,
            Map<ResourceKey, Integer> availableTotals,
            Map<ResourceWarehouseKey, Integer> fifoByKey,
            Map<ResourceKey, ResourceLabel> labels
    ) {
        Set<ResourceKey> resources = new LinkedHashSet<>();
        resources.addAll(profileQuantities.keySet());
        resources.addAll(availableTotals.keySet());
        fifoByKey.keySet().stream().map(ResourceWarehouseKey::resourceKey).forEach(resources::add);
        for (ResourceKey resource : resources) {
            Integer profileQuantity = profileQuantities.get(resource);
            int availableTotal = availableTotals.getOrDefault(resource, 0);
            if (profileQuantity == null) {
                addStockIssue(result, issue(
                        SEVERITY_ERROR,
                        "ORPHAN_LEDGER_RESOURCE",
                        resource,
                        null,
                        labels.get(resource),
                        null,
                        availableTotal,
                        0,
                        0,
                        fifoByKey.entrySet().stream()
                                .filter(entry -> entry.getKey().resourceKey().equals(resource))
                                .mapToInt(Map.Entry::getValue)
                                .sum(),
                        null,
                        "Inventory ledger references a missing vehicle or part master record"
                ));
                continue;
            }
            if (profileQuantity == availableTotal) {
                continue;
            }
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

    private void reconcileWarehouseStates(
            DailyReconciliationVO result,
            Map<ResourceKey, Integer> profileQuantities,
            Map<ResourceKey, ResourceLabel> labels,
            Map<ResourceWarehouseKey, DailyReconciliationProjectionRepository.BalanceRow> balanceByKey,
            Map<ResourceWarehouseKey, Integer> fifoByKey,
            Map<ResourceWarehouseKey, DailyReconciliationProjectionRepository.MovementStateRow> movementByKey
    ) {
        Set<ResourceWarehouseKey> warehouseKeys = new LinkedHashSet<>();
        warehouseKeys.addAll(balanceByKey.keySet());
        warehouseKeys.addAll(fifoByKey.keySet());
        warehouseKeys.addAll(movementByKey.keySet());
        for (ResourceWarehouseKey key : warehouseKeys) {
            DailyReconciliationProjectionRepository.BalanceRow balance = balanceByKey.get(key);
            int available = balance == null ? 0 : balance.availableQuantity();
            int reserved = balance == null ? 0 : balance.reservedQuantity();
            int locked = balance == null ? 0 : balance.lockedQuantity();
            int fifo = fifoByKey.getOrDefault(key, 0);
            DailyReconciliationProjectionRepository.MovementStateRow movement = movementByKey.get(key);
            Integer latestAfter = movement == null ? null : movement.latestAfterQuantity();
            ResourceLabel label = labels.get(key.resourceKey());

            if (balance != null && latestAfter != null && available != latestAfter) {
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
                issue.setDailyMovementDelta(movement.dailyDelta());
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
                issue.setDailyMovementDelta(movement == null ? 0 : movement.dailyDelta());
                addStockIssue(result, issue);
            }
        }
    }

    private List<DailyReconciliationVO.SalesRow> reconcileSales(LocalDate activityDate) {
        return projectionRepository.sales(activityDate).stream().map(row -> {
            DailyReconciliationVO.SalesRow result = new DailyReconciliationVO.SalesRow();
            result.setOutboundOrderId(row.outboundOrderId());
            result.setOrderNo(row.orderNo());
            result.setCustomerName(row.customerName());
            result.setReceivable(row.receivable());
            result.setReceipts(row.receipts());
            result.setActivityReceivable(row.activityReceivable());
            result.setActivityReceipts(row.activityReceipts());
            BigDecimal unpaid = row.receivable().subtract(row.receipts());
            result.setUnpaid(unpaid);
            result.setStatus(unpaid.signum() < 0 ? "OVERPAID"
                    : unpaid.signum() == 0 ? "SETTLED" : "OUTSTANDING");
            return result;
        }).toList();
    }

    private void reconcileRentals(DailyReconciliationVO result) {
        for (DailyReconciliationProjectionRepository.ActiveRentalRow rental :
                projectionRepository.activeRentals()) {
            if (rental.availableQuantity() == 0
                    && rental.lockedQuantity() == 1
                    && MachineStockStatus.RENTED.code().equals(rental.machineStatus())
                    && value(rental.inventoryCount()) == 0) {
                continue;
            }
            DailyReconciliationVO.RentalIssue issue = new DailyReconciliationVO.RentalIssue();
            issue.setSeverity(SEVERITY_ERROR);
            issue.setCode("ACTIVE_RENTAL_STOCK_MISMATCH");
            issue.setRentalId(rental.rentalId());
            issue.setRentalNo(rental.rentalNo());
            issue.setMachineId(rental.machineId());
            issue.setVehicleNumber(rental.vehicleNumber());
            issue.setWarehouseId(rental.warehouseId());
            issue.setAvailableQuantity(rental.availableQuantity());
            issue.setLockedQuantity(rental.lockedQuantity());
            issue.setMachineStatus(rental.machineStatus());
            issue.setMessage("进行中租赁必须对应锁定 1、可用 0、车辆状态 RENTED 且主档可用库存为 0");
            result.getRentalIssues().add(issue);
        }

        for (DailyReconciliationProjectionRepository.UnmatchedRentalLockRow lock :
                projectionRepository.unmatchedRentalLocks()) {
            DailyReconciliationVO.RentalIssue issue = new DailyReconciliationVO.RentalIssue();
            issue.setSeverity(SEVERITY_ERROR);
            issue.setCode("UNMATCHED_RENTAL_LOCK");
            issue.setMachineId(lock.machineId());
            issue.setVehicleNumber(lock.vehicleNumber());
            issue.setWarehouseId(lock.warehouseId());
            issue.setAvailableQuantity(lock.availableQuantity());
            issue.setLockedQuantity(lock.lockedQuantity());
            issue.setMachineStatus(lock.machineStatus());
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

    private ResourceWarehouseKey key(String resourceType, Long resourceId, Long warehouseId) {
        return new ResourceWarehouseKey(resourceType, resourceId, warehouseId);
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
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
