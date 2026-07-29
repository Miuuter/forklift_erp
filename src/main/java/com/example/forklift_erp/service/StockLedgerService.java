package com.example.forklift_erp.service;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.constant.StockBusinessType;
import com.example.forklift_erp.entity.StockBalance;
import com.example.forklift_erp.entity.StockMovement;
import com.example.forklift_erp.entity.StockMovementLine;
import com.example.forklift_erp.entity.Warehouse;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.StockBalanceRepository;
import com.example.forklift_erp.repository.StockMovementLineRepository;
import com.example.forklift_erp.repository.StockMovementRepository;
import com.example.forklift_erp.repository.WarehouseRepository;
import com.example.forklift_erp.util.BusinessNumberGenerator;
import com.example.forklift_erp.util.MoneyValues;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class StockLedgerService {

    public static final String DEFAULT_WAREHOUSE_CODE = "DEFAULT";
    public static final String RESOURCE_MACHINE = "MACHINE";
    public static final String RESOURCE_PART = "PART";

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private StockBalanceRepository stockBalanceRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private StockMovementLineRepository stockMovementLineRepository;

    @Autowired
    private RequestIdempotencyGuard requestIdempotencyGuard;

    @Transactional
    public Long resolveWarehouseId(Long warehouseId) {
        if (warehouseId != null) {
            if (!warehouseRepository.existsById(warehouseId)) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "Warehouse does not exist: " + warehouseId);
            }
            return warehouseId;
        }
        if (warehouseRepository.count() > 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Warehouse is required when more than one warehouse exists");
        }
        return resolveDefaultWarehouse().getId();
    }

    @Transactional
    public Warehouse resolveDefaultWarehouse() {
        return warehouseRepository.findFirstByDefaultWarehouseTrueOrderByIdAsc()
                .or(() -> warehouseRepository.findByWarehouseCode(DEFAULT_WAREHOUSE_CODE))
                .orElseGet(() -> {
                    Warehouse warehouse = new Warehouse();
                    warehouse.setWarehouseCode(DEFAULT_WAREHOUSE_CODE);
                    warehouse.setWarehouseName("Default Warehouse");
                    warehouse.setWarehouseType("MAIN");
                    warehouse.setDefaultWarehouse(true);
                    return warehouseRepository.save(warehouse);
                });
    }

    @Transactional
    public StockBalance syncBalance(String resourceType, Long resourceId, Long warehouseId, Integer availableQuantity) {
        Long resolvedWarehouseId = resolveWarehouseId(warehouseId);
        int quantity = availableQuantity == null ? 0 : availableQuantity;
        if (quantity < 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Inventory quantity cannot be negative");
        }
        StockBalance balance = stockBalanceRepository
                .findForUpdate(resourceType, resourceId, resolvedWarehouseId)
                .orElseGet(() -> {
                    StockBalance created = new StockBalance();
                    created.setResourceType(resourceType);
                    created.setResourceId(resourceId);
                    created.setWarehouseId(resolvedWarehouseId);
                    created.setReservedQuantity(0);
                    created.setLockedQuantity(0);
                    return created;
                });
        balance.setAvailableQuantity(quantity);
        return stockBalanceRepository.save(balance);
    }

    @Transactional
    public void reconcileAvailableQuantity(
            String resourceType,
            Long resourceId,
            Long preferredWarehouseId,
            Integer expectedTotalQuantity
    ) {
        Long resolvedWarehouseId = resolveWarehouseId(preferredWarehouseId);
        int expectedTotal = expectedTotalQuantity == null ? 0 : expectedTotalQuantity;
        if (expectedTotal < 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Inventory quantity cannot be negative");
        }

        List<StockBalance> balances = stockBalanceRepository.findAllForUpdate(resourceType, resourceId);
        validateBalances(balances);
        if (balances.isEmpty()) {
            StockBalance balance = newBalance(resourceType, resourceId, resolvedWarehouseId);
            balance.setAvailableQuantity(expectedTotal);
            stockBalanceRepository.save(balance);
            return;
        }
        int currentTotal = balances.stream()
                .mapToInt(balance -> quantity(balance.getAvailableQuantity()))
                .sum();
        if (currentTotal == expectedTotal) {
            return;
        }
        throw new BusinessException(ResultCode.CONFLICT,
                "Inventory profile quantity differs from warehouse balances; create an explicit adjustment instead");
    }

    @Transactional(readOnly = true)
    public int availableQuantity(String resourceType, Long resourceId, Long warehouseId) {
        Long resolvedWarehouseId = resolveWarehouseId(warehouseId);
        return stockBalanceRepository.findByResourceTypeAndResourceIdAndWarehouseId(resourceType, resourceId, resolvedWarehouseId)
                .map(balance -> quantity(balance.getAvailableQuantity()))
                .orElse(0);
    }

    @Transactional(readOnly = true)
    public int totalAvailableQuantity(String resourceType, Long resourceId) {
        return stockBalanceRepository.findByResourceTypeAndResourceId(resourceType, resourceId).stream()
                .mapToInt(balance -> quantity(balance.getAvailableQuantity()))
                .sum();
    }

    @Transactional
    public boolean hasAnyPhysicalQuantityForUpdate(String resourceType, Long resourceId) {
        List<StockBalance> balances = stockBalanceRepository.findAllForUpdate(resourceType, resourceId);
        validateBalances(balances);
        return balances.stream().anyMatch(balance ->
                quantity(balance.getAvailableQuantity()) > 0
                        || quantity(balance.getReservedQuantity()) > 0
                        || quantity(balance.getLockedQuantity()) > 0
        );
    }

    @Transactional
    public void deleteEmptyBalances(String resourceType, Long resourceId) {
        List<StockBalance> balances = stockBalanceRepository.findAllForUpdate(resourceType, resourceId);
        boolean hasQuantity = balances.stream().anyMatch(balance ->
                quantity(balance.getAvailableQuantity()) != 0
                        || quantity(balance.getReservedQuantity()) != 0
                        || quantity(balance.getLockedQuantity()) != 0
        );
        if (hasQuantity) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Inventory with a non-zero warehouse balance cannot be deleted");
        }
        stockBalanceRepository.deleteAll(balances);
    }

    @Transactional
    public StockMovement transferBalance(
            String resourceType,
            Long resourceId,
            String resourceCode,
            String resourceName,
            Long fromWarehouseId,
            Long toWarehouseId,
            Integer quantity,
            String operator,
            String remark,
            String sourceType,
            Long sourceId
    ) {
        Long resolvedFromWarehouseId = resolveWarehouseId(fromWarehouseId);
        Long resolvedToWarehouseId = resolveWarehouseId(toWarehouseId);
        if (resolvedFromWarehouseId.equals(resolvedToWarehouseId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Source and target warehouse cannot be the same");
        }
        int transferQuantity = quantity == null ? 0 : quantity;
        if (transferQuantity <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Transfer quantity must be greater than 0");
        }

        StockBalance sourceBalance = findOrCreateBalanceForUpdate(resourceType, resourceId, resolvedFromWarehouseId);
        int sourceBefore = sourceBalance.getAvailableQuantity() == null ? 0 : sourceBalance.getAvailableQuantity();
        if (sourceBefore < transferQuantity) {
            throw new BusinessException(ResultCode.INSUFFICIENT_STOCK, "Insufficient source warehouse stock: " + sourceBefore);
        }

        StockBalance targetBalance = findOrCreateBalanceForUpdate(resourceType, resourceId, resolvedToWarehouseId);
        int targetBefore = targetBalance.getAvailableQuantity() == null ? 0 : targetBalance.getAvailableQuantity();

        sourceBalance.setAvailableQuantity(sourceBefore - transferQuantity);
        targetBalance.setAvailableQuantity(targetBefore + transferQuantity);
        stockBalanceRepository.save(sourceBalance);
        stockBalanceRepository.save(targetBalance);

        StockMovement movement = new StockMovement();
        movement.setMovementNo(nextMovementNo());
        movement.setMovementType("TRANSFER");
        movement.setBusinessType(StockBusinessType.TRANSFER);
        movement.setBusinessDate(LocalDate.now());
        movement.setResourceType(resourceType);
        movement.setSourceType(sourceType);
        movement.setSourceId(sourceId);
        movement.setOperator(operator);
        movement.setRemark(remark);
        StockMovement savedMovement = stockMovementRepository.save(movement);

        StockMovementLine sourceLine = new StockMovementLine();
        sourceLine.setMovementId(savedMovement.getId());
        sourceLine.setResourceType(resourceType);
        sourceLine.setResourceId(resourceId);
        sourceLine.setResourceCode(resourceCode);
        sourceLine.setResourceName(resourceName);
        sourceLine.setWarehouseId(resolvedFromWarehouseId);
        sourceLine.setQuantityDelta(-transferQuantity);
        sourceLine.setBeforeQuantity(sourceBefore);
        sourceLine.setAfterQuantity(sourceBefore - transferQuantity);
        stockMovementLineRepository.save(sourceLine);

        StockMovementLine targetLine = new StockMovementLine();
        targetLine.setMovementId(savedMovement.getId());
        targetLine.setResourceType(resourceType);
        targetLine.setResourceId(resourceId);
        targetLine.setResourceCode(resourceCode);
        targetLine.setResourceName(resourceName);
        targetLine.setWarehouseId(resolvedToWarehouseId);
        targetLine.setQuantityDelta(transferQuantity);
        targetLine.setBeforeQuantity(targetBefore);
        targetLine.setAfterQuantity(targetBefore + transferQuantity);
        stockMovementLineRepository.save(targetLine);

        return savedMovement;
    }

    @Transactional
    public StockMovement freezeForRental(
            String resourceType,
            Long resourceId,
            String resourceCode,
            String resourceName,
            Long warehouseId,
            int quantity,
            String operator,
            String remark,
            String sourceType,
            Long sourceId,
            LocalDate businessDate
    ) {
        Long resolvedWarehouseId = resolveWarehouseId(warehouseId);
        if (quantity <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Rental quantity must be greater than 0");
        }
        StockBalance balance = findOrCreateBalanceForUpdate(resourceType, resourceId, resolvedWarehouseId);
        int beforeAvailable = quantity(balance.getAvailableQuantity());
        int beforeLocked = quantity(balance.getLockedQuantity());
        if (beforeAvailable < quantity) {
            throw new BusinessException(ResultCode.INSUFFICIENT_STOCK, "Insufficient available stock for rental");
        }
        balance.setAvailableQuantity(beforeAvailable - quantity);
        balance.setLockedQuantity(beforeLocked + quantity);
        stockBalanceRepository.save(balance);

        StockMovement movement = new StockMovement();
        movement.setMovementNo(nextMovementNo());
        movement.setMovementType("RENT_OUT");
        movement.setBusinessType(StockBusinessType.RENT_OUT);
        movement.setBusinessDate(businessDate == null ? LocalDate.now() : businessDate);
        movement.setResourceType(resourceType);
        movement.setSourceType(sourceType);
        movement.setSourceId(sourceId);
        movement.setOperator(operator);
        movement.setRemark(remark);
        StockMovement saved = stockMovementRepository.save(movement);
        StockMovementLine line = new StockMovementLine();
        line.setMovementId(saved.getId());
        line.setResourceType(resourceType);
        line.setResourceId(resourceId);
        line.setResourceCode(resourceCode);
        line.setResourceName(resourceName);
        line.setWarehouseId(resolvedWarehouseId);
        line.setQuantityDelta(-quantity);
        line.setBeforeQuantity(beforeAvailable);
        line.setAfterQuantity(beforeAvailable - quantity);
        stockMovementLineRepository.save(line);
        return saved;
    }

    @Transactional
    public StockMovement releaseRental(
            String resourceType,
            Long resourceId,
            String resourceCode,
            String resourceName,
            Long warehouseId,
            int quantity,
            String operator,
            String remark,
            String sourceType,
            Long sourceId,
            LocalDate businessDate
    ) {
        Long resolvedWarehouseId = resolveWarehouseId(warehouseId);
        if (quantity <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Rental quantity must be greater than 0");
        }
        StockBalance balance = findOrCreateBalanceForUpdate(resourceType, resourceId, resolvedWarehouseId);
        int beforeAvailable = quantity(balance.getAvailableQuantity());
        int beforeLocked = quantity(balance.getLockedQuantity());
        if (beforeLocked < quantity) {
            throw new BusinessException(ResultCode.CONFLICT, "Rental lock is missing or already released");
        }
        balance.setAvailableQuantity(beforeAvailable + quantity);
        balance.setLockedQuantity(beforeLocked - quantity);
        stockBalanceRepository.save(balance);

        StockMovement movement = new StockMovement();
        movement.setMovementNo(nextMovementNo());
        movement.setMovementType("RENT_RETURN");
        movement.setBusinessType(StockBusinessType.RENT_RETURN);
        movement.setBusinessDate(businessDate == null ? LocalDate.now() : businessDate);
        movement.setResourceType(resourceType);
        movement.setSourceType(sourceType);
        movement.setSourceId(sourceId);
        movement.setOperator(operator);
        movement.setRemark(remark);
        StockMovement saved = stockMovementRepository.save(movement);
        StockMovementLine line = new StockMovementLine();
        line.setMovementId(saved.getId());
        line.setResourceType(resourceType);
        line.setResourceId(resourceId);
        line.setResourceCode(resourceCode);
        line.setResourceName(resourceName);
        line.setWarehouseId(resolvedWarehouseId);
        line.setQuantityDelta(quantity);
        line.setBeforeQuantity(beforeAvailable);
        line.setAfterQuantity(beforeAvailable + quantity);
        stockMovementLineRepository.save(line);
        return saved;
    }

    /** Backward-compatible entry point for ordinary (non-reversal) movements. */
    @Transactional
    public StockMovement recordMovement(
            String movementType,
            String resourceType,
            Long resourceId,
            String resourceCode,
            String resourceName,
            Long warehouseId,
            Integer beforeQuantity,
            Integer afterQuantity,
            BigDecimal unitCost,
            String operator,
            String remark,
            String sourceType,
            Long sourceId,
            Long sourceLineId,
            LocalDate businessDate,
            String businessType,
            BigDecimal unitRevenue,
            String idempotencyKey,
            Long stockLotId
    ) {
        return recordMovement(
                movementType,
                resourceType,
                resourceId,
                resourceCode,
                resourceName,
                warehouseId,
                beforeQuantity,
                afterQuantity,
                unitCost,
                operator,
                remark,
                sourceType,
                sourceId,
                sourceLineId,
                businessDate,
                businessType,
                unitRevenue,
                idempotencyKey,
                stockLotId,
                null
        );
    }

    @Transactional
    public StockMovement recordMovement(
            String movementType,
            String resourceType,
            Long resourceId,
            String resourceCode,
            String resourceName,
            Long warehouseId,
            Integer beforeQuantity,
            Integer afterQuantity,
            String operator,
            String remark,
            String sourceType,
            Long sourceId
    ) {
        return recordMovement(
                movementType,
                resourceType,
                resourceId,
                resourceCode,
                resourceName,
                warehouseId,
                beforeQuantity,
                afterQuantity,
                null,
                operator,
                remark,
                sourceType,
                sourceId,
                null,
                LocalDate.now(),
                movementType,
                null,
                null,
                null,
                null
        );
    }

    @Transactional
    public StockMovement recordMovement(
            String movementType,
            String resourceType,
            Long resourceId,
            String resourceCode,
            String resourceName,
            Long warehouseId,
            Integer beforeQuantity,
            Integer afterQuantity,
            BigDecimal unitCost,
            String operator,
            String remark,
            String sourceType,
            Long sourceId
    ) {
        return recordMovement(
                movementType,
                resourceType,
                resourceId,
                resourceCode,
                resourceName,
                warehouseId,
                beforeQuantity,
                afterQuantity,
                unitCost,
                operator,
                remark,
                sourceType,
                sourceId,
                null,
                LocalDate.now(),
                movementType,
                null,
                null,
                null,
                null
        );
    }

    @Transactional
    public StockMovement recordMovement(
            String movementType,
            String resourceType,
            Long resourceId,
            String resourceCode,
            String resourceName,
            Long warehouseId,
            Integer beforeQuantity,
            Integer afterQuantity,
            BigDecimal unitCost,
            String operator,
            String remark,
            String sourceType,
            Long sourceId,
            Long sourceLineId,
            LocalDate businessDate,
            String businessType,
            BigDecimal unitRevenue,
            String idempotencyKey,
            Long stockLotId,
            Long reversalOfMovementId
    ) {
        boolean claimWon = idempotencyKey == null
                || requestIdempotencyGuard == null
                || requestIdempotencyGuard.claimTechnicalKey("STOCK_MOVEMENT", idempotencyKey);
        int before = beforeQuantity == null ? 0 : beforeQuantity;
        int after = afterQuantity == null ? 0 : afterQuantity;
        int delta = after - before;
        LocalDate effectiveBusinessDate = businessDate == null ? LocalDate.now() : businessDate;
        String effectiveBusinessType = businessType == null || businessType.isBlank()
                ? movementType
                : businessType;
        Long resolvedWarehouseId = resolveWarehouseId(warehouseId);
        if (idempotencyKey != null) {
            StockMovement existing = findIdempotentMovement(idempotencyKey, claimWon).orElse(null);
            if (existing != null) {
                StockMovementIdempotencyValidator.validate(
                        existing,
                        stockMovementLineRepository.findByMovementIdForUpdate(existing.getId()),
                        movementType,
                        resourceType,
                        resourceId,
                        resourceCode,
                        resourceName,
                        resolvedWarehouseId,
                        before,
                        after,
                        unitCost,
                        unitRevenue,
                        operator,
                        remark,
                        sourceType,
                        sourceId,
                        sourceLineId,
                        businessDate,
                        effectiveBusinessDate,
                        effectiveBusinessType,
                        stockLotId,
                        reversalOfMovementId
                );
                return existing;
            }
            if (!claimWon) {
                throw new BusinessException(ResultCode.CONFLICT,
                        "Stock movement idempotency key is already being processed; retry with the same key");
            }
        }
        StockMovementLine originalReversalLine = null;
        if (reversalOfMovementId != null) {
            StockMovement originalMovement = stockMovementRepository.findById(reversalOfMovementId)
                    .orElseThrow(() -> new BusinessException(ResultCode.CONFLICT,
                            "Original stock movement does not exist"));
            originalReversalLine = StockMovementReversalValidator.requireExactSingleLineReversal(
                    originalMovement,
                    stockMovementLineRepository.findByMovementIdOrderByIdAsc(reversalOfMovementId),
                    resourceType,
                    resourceId,
                    resolvedWarehouseId,
                    before,
                    after,
                    unitCost,
                    unitRevenue,
                    stockLotId,
                    sourceType,
                    sourceId,
                    sourceLineId
            );
        }
        StockBalance balance = findOrCreateBalanceForUpdate(resourceType, resourceId, resolvedWarehouseId);
        int actualBefore = quantity(balance.getAvailableQuantity());
        if (actualBefore == before) {
            if (after < 0) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "Inventory quantity cannot be negative");
            }
            balance.setAvailableQuantity(after);
            stockBalanceRepository.save(balance);
        } else if (actualBefore != after) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Warehouse balance changed since the operation was prepared; refresh and retry");
        }

        StockMovement movement = new StockMovement();
        movement.setMovementNo(nextMovementNo());
        movement.setMovementType(movementType);
        movement.setResourceType(resourceType);
        movement.setSourceType(sourceType);
        movement.setSourceId(sourceId);
        movement.setSourceLineId(sourceLineId);
        movement.setBusinessDate(effectiveBusinessDate);
        movement.setBusinessType(effectiveBusinessType);
        movement.setOperator(operator);
        movement.setRemark(remark);
        movement.setIdempotencyKey(idempotencyKey);
        movement.setReversalOfMovementId(reversalOfMovementId);
        StockMovement savedMovement = stockMovementRepository.save(movement);

        StockMovementLine line = new StockMovementLine();
        line.setMovementId(savedMovement.getId());
        line.setResourceType(resourceType);
        line.setResourceId(resourceId);
        line.setResourceCode(resourceCode);
        line.setResourceName(resourceName);
        line.setWarehouseId(resolvedWarehouseId);
        line.setQuantityDelta(delta);
        line.setBeforeQuantity(before);
        line.setAfterQuantity(after);
        line.setUnitCost(unitCost);
        line.setUnitRevenue(unitRevenue);
        line.setStockLotId(stockLotId);
        line.setSourceLineId(sourceLineId);
        line.setReversalOfMovementId(reversalOfMovementId);
        line.setReversalOfMovementLineId(
                originalReversalLine == null ? null : originalReversalLine.getId());
        line.setCostAmount(MoneyValues.zeroIfNullOrNegative(unitCost).multiply(BigDecimal.valueOf(Math.abs(delta))));
        line.setLineAmount(MoneyValues.zeroIfNullOrNegative(unitRevenue).multiply(BigDecimal.valueOf(Math.abs(delta))));
        stockMovementLineRepository.save(line);

        return savedMovement;
    }

    /**
     * A successful technical claim proves that no other transaction can be
     * publishing the same fact. A locking lookup for that missing key would
     * acquire a next-key gap lock on the unique idempotency index and can
     * deadlock unrelated concurrent inserts. Only claim losers need a current
     * locking read, because their claim insert has already waited for the
     * winner to commit the authoritative fact.
     */
    private Optional<StockMovement> findIdempotentMovement(String key, boolean claimWon) {
        if (requestIdempotencyGuard != null && claimWon) {
            return stockMovementRepository.findByIdempotencyKey(key);
        }
        return stockMovementRepository.findByIdempotencyKeyForUpdate(key);
    }

    private StockBalance findOrCreateBalanceForUpdate(String resourceType, Long resourceId, Long warehouseId) {
        return stockBalanceRepository.findForUpdate(resourceType, resourceId, warehouseId)
                .orElseGet(() -> newBalance(resourceType, resourceId, warehouseId));
    }

    private StockBalance newBalance(String resourceType, Long resourceId, Long warehouseId) {
        StockBalance created = new StockBalance();
        created.setResourceType(resourceType);
        created.setResourceId(resourceId);
        created.setWarehouseId(warehouseId);
        created.setAvailableQuantity(0);
        created.setReservedQuantity(0);
        created.setLockedQuantity(0);
        return created;
    }

    private int quantity(Integer value) {
        return value == null ? 0 : value;
    }

    private void validateBalances(List<StockBalance> balances) {
        boolean hasNegativeQuantity = balances.stream().anyMatch(balance ->
                quantity(balance.getAvailableQuantity()) < 0
                        || quantity(balance.getReservedQuantity()) < 0
                        || quantity(balance.getLockedQuantity()) < 0
        );
        if (hasNegativeQuantity) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Warehouse balance contains a negative quantity");
        }
    }

    private String nextMovementNo() {
        return BusinessNumberGenerator.next("SM", 8);
    }
}
