package com.example.forklift_erp.service;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.entity.StockLot;
import com.example.forklift_erp.entity.StockLotConsumption;
import com.example.forklift_erp.entity.StockLotCostAdjustment;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.StockLotCostAdjustmentRepository;
import com.example.forklift_erp.repository.StockLotConsumptionRepository;
import com.example.forklift_erp.repository.StockLotRepository;
import com.example.forklift_erp.util.MoneyValues;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * FIFO cost layer. Stock profiles are never used as the source of cost;
 * every consumption records the precise lot(s) that supplied it.
 */
@Service
public class StockLotService {
    @Autowired
    private RequestIdempotencyGuard requestIdempotencyGuard;

    private final StockLotRepository stockLotRepository;
    private final StockLotConsumptionRepository consumptionRepository;
    private final StockLotCostAdjustmentRepository costAdjustmentRepository;

    public StockLotService(
            StockLotRepository stockLotRepository,
            StockLotConsumptionRepository consumptionRepository,
            StockLotCostAdjustmentRepository costAdjustmentRepository
    ) {
        this.stockLotRepository = stockLotRepository;
        this.consumptionRepository = consumptionRepository;
        this.costAdjustmentRepository = costAdjustmentRepository;
    }

    @Transactional
    public StockLot createReceiptLot(
            String resourceType,
            Long resourceId,
            Long warehouseId,
            int quantity,
            BigDecimal unitCost,
            BigDecimal freightAllocated,
            String sourceType,
            Long sourceId,
            Long sourceLineId,
            LocalDate businessDate,
            String idempotencyKey
    ) {
        return createReceiptLotInternal(
                resourceType, resourceId, warehouseId, quantity, unitCost, null,
                freightAllocated, sourceType, sourceId, sourceLineId, businessDate, idempotencyKey);
    }

    @Transactional
    public StockLot createReceiptLotWithTotalCost(
            String resourceType,
            Long resourceId,
            Long warehouseId,
            int quantity,
            BigDecimal unitCost,
            BigDecimal totalCost,
            BigDecimal freightAllocated,
            String sourceType,
            Long sourceId,
            Long sourceLineId,
            LocalDate businessDate,
            String idempotencyKey
    ) {
        return createReceiptLotInternal(
                resourceType, resourceId, warehouseId, quantity, unitCost, totalCost,
                freightAllocated, sourceType, sourceId, sourceLineId, businessDate, idempotencyKey);
    }

    private StockLot createReceiptLotInternal(
            String resourceType,
            Long resourceId,
            Long warehouseId,
            int quantity,
            BigDecimal unitCost,
            BigDecimal totalCost,
            BigDecimal freightAllocated,
            String sourceType,
            Long sourceId,
            Long sourceLineId,
            LocalDate businessDate,
            String idempotencyKey
    ) {
        if (quantity <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Stock lot quantity must be greater than 0");
        }
        BigDecimal normalizedUnitCost = MoneyValues.zeroIfNullOrNegative(unitCost);
        BigDecimal normalizedTotalCost = totalCost == null
                ? normalizedUnitCost.multiply(BigDecimal.valueOf(quantity))
                        .setScale(2, java.math.RoundingMode.HALF_UP)
                : MoneyValues.zeroIfNullOrNegative(totalCost).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal normalizedFreight = MoneyValues.zeroIfNullOrNegative(freightAllocated);
        boolean claimWon = claimTechnicalKey("STOCK_LOT_RECEIPT", idempotencyKey);
        if (idempotencyKey != null) {
            StockLot existing = findReceiptIdempotentLot(idempotencyKey, claimWon).orElse(null);
            if (existing != null) {
                StockLotIdempotencyValidator.validateReceiptLot(
                        existing,
                        resourceType,
                        resourceId,
                        warehouseId,
                        quantity,
                        normalizedUnitCost,
                        normalizedTotalCost,
                        normalizedFreight,
                        sourceType,
                        sourceId,
                        sourceLineId,
                        businessDate
                );
                return existing;
            }
            if (!claimWon) {
                throw StockLotIdempotencyValidator.inProgress("Stock lot");
            }
        }
        StockLot lot = new StockLot();
        lot.setResourceType(resourceType);
        lot.setResourceId(resourceId);
        lot.setWarehouseId(warehouseId);
        lot.setSourceType(sourceType);
        lot.setSourceId(sourceId);
        lot.setSourceLineId(sourceLineId);
        lot.setReceivedBusinessDate(businessDate == null ? LocalDate.now() : businessDate);
        lot.setOriginalQuantity(quantity);
        lot.setRemainingQuantity(quantity);
        lot.setUnitCost(normalizedUnitCost);
        lot.setOriginalCostAmount(normalizedTotalCost);
        lot.setRemainingCostAmount(normalizedTotalCost);
        lot.setFreightAllocated(normalizedFreight);
        lot.setIdempotencyKey(idempotencyKey);
        return stockLotRepository.save(lot);
    }

    @Transactional
    public ConsumptionResult consumeFifo(
            String resourceType,
            Long resourceId,
            Long warehouseId,
            int quantity,
            BigDecimal fallbackUnitCost,
            int availableBeforeConsumption,
            String sourceType,
            Long sourceId,
            Long sourceLineId,
            LocalDate businessDate,
            String idempotencyKey
    ) {
        if (quantity <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "FIFO consumption quantity must be greater than 0");
        }
        boolean claimWon = claimTechnicalKey("STOCK_LOT_FIFO", idempotencyKey);
        if (idempotencyKey != null) {
            List<StockLotConsumption> existing = findConsumptionIdempotencyFacts(
                    idempotencyKey + ":", claimWon);
            if (!existing.isEmpty()) {
                StockLotIdempotencyValidator.validateConsumption(
                        existing,
                        resourceType,
                        resourceId,
                        warehouseId,
                        quantity,
                        sourceType,
                        sourceId,
                        sourceLineId,
                        businessDate,
                        idempotencyKey
                );
                return result(existing);
            }
            if (!claimWon) {
                throw StockLotIdempotencyValidator.inProgress("FIFO consumption");
            }
        }

        List<StockLot> lots = new ArrayList<>(stockLotRepository.findOpenFifoForUpdate(resourceType, resourceId, warehouseId));
        int lotAvailable = lots.stream().mapToInt(lot -> value(lot.getRemainingQuantity())).sum();
        if (lotAvailable < quantity) {
            int currentAvailable = Math.max(0, availableBeforeConsumption);
            if (currentAvailable < quantity) {
                throw new BusinessException(ResultCode.INSUFFICIENT_STOCK, "Insufficient stock for FIFO consumption");
            }
            int openingQuantity = currentAvailable - lotAvailable;
            if (openingQuantity > 0) {
                StockLot openingLot = createReceiptLot(
                        resourceType,
                        resourceId,
                        warehouseId,
                        openingQuantity,
                        fallbackUnitCost,
                        BigDecimal.ZERO,
                        "MIGRATION_INITIAL",
                        resourceId,
                        null,
                        businessDate,
                        "OPENING-LOT:" + resourceType + ":" + resourceId + ":" + warehouseId
                );
                lots.add(openingLot);
            }
        }

        int remaining = quantity;
        List<StockLotConsumption> consumed = new ArrayList<>();
        for (StockLot lot : lots) {
            if (remaining == 0) {
                break;
            }
            int lotQuantity = value(lot.getRemainingQuantity());
            if (lotQuantity <= 0 || StockLot.STATUS_REVERSED.equals(lot.getStatus())) {
                continue;
            }
            int used = Math.min(lotQuantity, remaining);
            BigDecimal allocatedCost = StockLotCostAllocator.allocate(lot, lotQuantity, used);
            lot.setRemainingQuantity(lotQuantity - used);
            lot.setRemainingCostAmount(StockLotCostAllocator.remainingCost(lot).subtract(allocatedCost));
            lot.refreshStatus();
            stockLotRepository.save(lot);

            StockLotConsumption consumption = new StockLotConsumption();
            consumption.setStockLotId(lot.getId());
            consumption.setResourceType(resourceType);
            consumption.setResourceId(resourceId);
            consumption.setWarehouseId(warehouseId);
            consumption.setSourceType(sourceType);
            consumption.setSourceId(sourceId);
            consumption.setSourceLineId(sourceLineId);
            consumption.setQuantity(used);
            consumption.setTotalCost(allocatedCost);
            consumption.setUnitCost(allocatedCost.divide(
                    BigDecimal.valueOf(used), 6, java.math.RoundingMode.HALF_UP));
            consumption.setBusinessDate(businessDate == null ? LocalDate.now() : businessDate);
            consumption.setIdempotencyKey(idempotencyKey == null ? null : idempotencyKey + ":lot:" + lot.getId());
            consumed.add(consumptionRepository.save(consumption));
            remaining -= used;
        }
        if (remaining != 0) {
            throw new BusinessException(ResultCode.INSUFFICIENT_STOCK, "FIFO lots do not cover the requested quantity");
        }
        return result(consumed);
    }

    @Transactional
    public ConsumptionResult restoreSourceConsumption(
            String sourceType,
            Long sourceId,
            LocalDate businessDate,
            String idempotencyPrefix
    ) {
        List<StockLotConsumption> originals = consumptionRepository.findBySourceTypeAndSourceIdOrderByIdAsc(sourceType, sourceId);
        if (originals.isEmpty()) {
            return ConsumptionResult.empty();
        }
        Set<Long> alreadyReversed = new HashSet<>(consumptionRepository
                .findByReversalOfConsumptionIdIn(originals.stream().map(StockLotConsumption::getId).toList())
                .stream()
                .map(StockLotConsumption::getReversalOfConsumptionId)
                .toList());
        List<StockLotConsumption> restored = new ArrayList<>();
        for (StockLotConsumption original : originals) {
            if (original.getReversalOfConsumptionId() != null || alreadyReversed.contains(original.getId())) {
                continue;
            }
            StockLot lot = stockLotRepository.findByIdForUpdate(original.getStockLotId())
                    .orElseThrow(() -> new BusinessException(ResultCode.CONFLICT, "Stock lot no longer exists for restoration"));
            lot.setRemainingQuantity(value(lot.getRemainingQuantity()) + value(original.getQuantity()));
            StockLotCostAllocator.restore(lot, original);
            lot.setStatus(StockLot.STATUS_OPEN);
            stockLotRepository.save(lot);

            StockLotConsumption reversal = new StockLotConsumption();
            reversal.setStockLotId(original.getStockLotId());
            reversal.setResourceType(original.getResourceType());
            reversal.setResourceId(original.getResourceId());
            reversal.setWarehouseId(original.getWarehouseId());
            reversal.setSourceType(original.getSourceType());
            reversal.setSourceId(original.getSourceId());
            reversal.setSourceLineId(original.getSourceLineId());
            reversal.setQuantity(-value(original.getQuantity()));
            reversal.setUnitCost(original.getUnitCost());
            reversal.setTotalCost(MoneyValues.zeroIfNullOrNegative(original.getTotalCost()).negate());
            reversal.setBusinessDate(businessDate == null ? LocalDate.now() : businessDate);
            reversal.setReversalOfConsumptionId(original.getId());
            reversal.setIdempotencyKey(idempotencyPrefix == null ? null : idempotencyPrefix + ":" + original.getId());
            restored.add(consumptionRepository.save(reversal));
        }
        return result(restored);
    }

    /**
     * Restores only one business-line's FIFO consumption. Repair and
     * modification edits use this instead of reversing every line on the
     * document, so an edit never creates a temporary return for unrelated
     * material usage.
     */
    @Transactional
    public ConsumptionResult restoreSourceLineConsumption(
            String sourceType,
            Long sourceId,
            Long sourceLineId,
            LocalDate businessDate,
            String idempotencyPrefix
    ) {
        List<StockLotConsumption> originals = consumptionRepository
                .findBySourceTypeAndSourceIdAndSourceLineIdOrderByIdAsc(sourceType, sourceId, sourceLineId);
        if (originals.isEmpty()) {
            return ConsumptionResult.empty();
        }
        Set<Long> alreadyReversed = new HashSet<>(consumptionRepository
                .findByReversalOfConsumptionIdIn(originals.stream().map(StockLotConsumption::getId).toList())
                .stream()
                .map(StockLotConsumption::getReversalOfConsumptionId)
                .toList());
        List<StockLotConsumption> restored = new ArrayList<>();
        for (StockLotConsumption original : originals) {
            if (original.getReversalOfConsumptionId() != null || alreadyReversed.contains(original.getId())) {
                continue;
            }
            StockLot lot = stockLotRepository.findByIdForUpdate(original.getStockLotId())
                    .orElseThrow(() -> new BusinessException(ResultCode.CONFLICT, "Stock lot no longer exists for restoration"));
            lot.setRemainingQuantity(value(lot.getRemainingQuantity()) + value(original.getQuantity()));
            StockLotCostAllocator.restore(lot, original);
            lot.setStatus(StockLot.STATUS_OPEN);
            stockLotRepository.save(lot);

            StockLotConsumption reversal = new StockLotConsumption();
            reversal.setStockLotId(original.getStockLotId());
            reversal.setResourceType(original.getResourceType());
            reversal.setResourceId(original.getResourceId());
            reversal.setWarehouseId(original.getWarehouseId());
            reversal.setSourceType(original.getSourceType());
            reversal.setSourceId(original.getSourceId());
            reversal.setSourceLineId(original.getSourceLineId());
            reversal.setQuantity(-value(original.getQuantity()));
            reversal.setUnitCost(original.getUnitCost());
            reversal.setTotalCost(MoneyValues.zeroIfNullOrNegative(original.getTotalCost()).negate());
            reversal.setBusinessDate(businessDate == null ? LocalDate.now() : businessDate);
            reversal.setReversalOfConsumptionId(original.getId());
            reversal.setIdempotencyKey(idempotencyPrefix == null ? null : idempotencyPrefix + ":" + original.getId());
            restored.add(consumptionRepository.save(reversal));
        }
        return result(restored);
    }

    @Transactional(readOnly = true)
    public boolean canReverseReceipt(Long lotId) {
        StockLot lot = stockLotRepository.findById(lotId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Stock lot not found"));
        return value(lot.getRemainingQuantity()) == value(lot.getOriginalQuantity())
                && StockLotCostAllocator.remainingCost(lot)
                        .compareTo(StockLotCostAllocator.originalCost(lot)) == 0
                && !StockLot.STATUS_REVERSED.equals(lot.getStatus());
    }

    @Transactional
    public void reverseReceiptLot(Long lotId) {
        StockLot lot = stockLotRepository.findByIdForUpdate(lotId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Stock lot not found"));
        if (value(lot.getRemainingQuantity()) != value(lot.getOriginalQuantity())
                || StockLotCostAllocator.remainingCost(lot)
                        .compareTo(StockLotCostAllocator.originalCost(lot)) != 0
                || StockLot.STATUS_REVERSED.equals(lot.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "A consumed FIFO lot cannot have its receipt directly reversed");
        }
        lot.setRemainingQuantity(0);
        lot.setRemainingCostAmount(BigDecimal.ZERO.setScale(2));
        lot.setStatus(StockLot.STATUS_REVERSED);
        stockLotRepository.save(lot);
    }

    /**
     * Adds a traceable cost layer to one physical serialized asset that is
     * still in stock. This is used for pre-sale vehicle modifications, so its
     * eventual FIFO sale cost includes the installed part cost instead of
     * recognizing it prematurely as service cost.
     */
    @Transactional
    public void capitalizeSerializedAssetCost(
            String resourceType,
            Long resourceId,
            Long warehouseId,
            BigDecimal amount,
            String sourceType,
            Long sourceId,
            Long sourceLineId,
            LocalDate businessDate,
            String idempotencyKey
    ) {
        BigDecimal adjustment = amount == null ? BigDecimal.ZERO : amount;
        boolean claimWon = adjustment.signum() == 0
                || claimTechnicalKey("STOCK_LOT_COST_ADJUSTMENT", idempotencyKey);
        if (idempotencyKey != null) {
            StockLotCostAdjustment existing = findCostAdjustmentIdempotentFact(
                    idempotencyKey, claimWon).orElse(null);
            if (existing != null) {
                StockLot existingLot = stockLotRepository.findById(existing.getStockLotId())
                        .orElseThrow(() -> new BusinessException(ResultCode.CONFLICT,
                                "Cost adjustment idempotency key points to a missing stock lot"));
                StockLotIdempotencyValidator.validateCostAdjustment(
                        existing,
                        existingLot,
                        resourceType,
                        resourceId,
                        warehouseId,
                        adjustment,
                        sourceType,
                        sourceId,
                        sourceLineId,
                        businessDate
                );
                return;
            }
            if (!claimWon) {
                throw StockLotIdempotencyValidator.inProgress("Cost adjustment");
            }
        }
        if (adjustment.signum() == 0) {
            return;
        }
        List<StockLot> openLots = stockLotRepository.findOpenFifoForUpdate(resourceType, resourceId, warehouseId);
        StockLot targetLot = openLots.stream()
                .filter(lot -> value(lot.getRemainingQuantity()) == 1)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.CONFLICT,
                        "Serialized asset has no single-unit open FIFO lot available for cost capitalization"));
        BigDecimal updatedCost = MoneyValues.zeroIfNullOrNegative(targetLot.getUnitCost()).add(adjustment);
        if (updatedCost.signum() < 0) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Serialized asset cost adjustment would make the FIFO unit cost negative");
        }
        targetLot.setUnitCost(updatedCost);
        BigDecimal updatedTotalCost = updatedCost.setScale(2, java.math.RoundingMode.HALF_UP);
        targetLot.setOriginalCostAmount(updatedTotalCost);
        targetLot.setRemainingCostAmount(updatedTotalCost);
        stockLotRepository.save(targetLot);

        StockLotCostAdjustment row = new StockLotCostAdjustment();
        row.setStockLotId(targetLot.getId());
        row.setAmount(adjustment);
        row.setSourceType(sourceType);
        row.setSourceId(sourceId);
        row.setSourceLineId(sourceLineId);
        row.setBusinessDate(businessDate == null ? LocalDate.now() : businessDate);
        row.setIdempotencyKey(idempotencyKey);
        costAdjustmentRepository.save(row);
    }

    /**
     * Finalizes the valuation of an unconsumed receipt lot, typically a
     * quarantined part removed from a vehicle. Quantity is unchanged; the
     * immutable adjustment row explains the increase in inventory value.
     */
    @Transactional
    public BigDecimal revalueUnconsumedReceiptLots(
            String resourceType,
            Long resourceId,
            Long warehouseId,
            BigDecimal newUnitCost,
            String sourceType,
            Long sourceId,
            LocalDate businessDate,
            String idempotencyPrefix
    ) {
        BigDecimal normalizedCost = MoneyValues.zeroIfNullOrNegative(newUnitCost);
        if (normalizedCost.signum() <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Final unit cost must be greater than zero");
        }
        List<StockLot> openLots = stockLotRepository.findOpenFifoForUpdate(resourceType, resourceId, warehouseId);
        if (openLots.isEmpty()) {
            throw new BusinessException(ResultCode.CONFLICT, "No open FIFO lot is available for valuation");
        }
        BigDecimal totalAdjustment = BigDecimal.ZERO;
        for (StockLot lot : openLots) {
            int remaining = value(lot.getRemainingQuantity());
            if (remaining <= 0 || remaining != value(lot.getOriginalQuantity())) {
                throw new BusinessException(ResultCode.CONFLICT,
                        "A consumed FIFO lot cannot be revalued as a pending receipt");
            }
            BigDecimal previousCost = MoneyValues.zeroIfNullOrNegative(lot.getUnitCost());
            BigDecimal unitAdjustment = normalizedCost.subtract(previousCost);
            if (unitAdjustment.signum() < 0) {
                throw new BusinessException(ResultCode.CONFLICT,
                        "Pending receipt valuation cannot reduce the existing FIFO cost");
            }
            lot.setUnitCost(normalizedCost);
            BigDecimal revaluedTotal = normalizedCost.multiply(BigDecimal.valueOf(remaining))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            lot.setOriginalCostAmount(revaluedTotal);
            lot.setRemainingCostAmount(revaluedTotal);
            stockLotRepository.save(lot);
            BigDecimal lotAdjustment = unitAdjustment.multiply(BigDecimal.valueOf(remaining));
            totalAdjustment = totalAdjustment.add(lotAdjustment);
            if (lotAdjustment.signum() == 0) {
                continue;
            }
            StockLotCostAdjustment row = new StockLotCostAdjustment();
            row.setStockLotId(lot.getId());
            row.setAmount(lotAdjustment);
            row.setSourceType(sourceType);
            row.setSourceId(sourceId);
            row.setBusinessDate(businessDate == null ? LocalDate.now() : businessDate);
            row.setIdempotencyKey(idempotencyPrefix == null ? null : idempotencyPrefix + ":" + lot.getId());
            costAdjustmentRepository.save(row);
        }
        return totalAdjustment;
    }

    /**
     * Moves FIFO layers together with a physical warehouse transfer. A
     * warehouse balance transfer without this step would leave cost in the
     * source warehouse and force later outbound operations to fabricate an
     * opening cost layer in the target warehouse.
     */
    @Transactional
    public void transferFifo(
            String resourceType,
            Long resourceId,
            Long fromWarehouseId,
            Long toWarehouseId,
            int quantity,
            BigDecimal fallbackUnitCost,
            int availableBeforeTransfer,
            Long stockMovementId,
            LocalDate businessDate
    ) {
        if (quantity <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "FIFO transfer quantity must be greater than 0");
        }
        if (fromWarehouseId.equals(toWarehouseId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "FIFO transfer warehouses must be different");
        }
        String sourceType = "STOCK_TRANSFER";
        if (stockMovementId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Stock transfer movement is required for FIFO transfer");
        }
        int alreadyTransferred = stockLotRepository.findBySourceTypeAndSourceIdOrderByIdAsc(sourceType, stockMovementId)
                .stream()
                .filter(lot -> toWarehouseId.equals(lot.getWarehouseId()))
                .mapToInt(lot -> value(lot.getOriginalQuantity()))
                .sum();
        if (alreadyTransferred >= quantity) {
            return;
        }

        List<StockLot> sourceLots = new ArrayList<>(
                stockLotRepository.findOpenFifoForUpdate(resourceType, resourceId, fromWarehouseId));
        int lotAvailable = sourceLots.stream().mapToInt(lot -> value(lot.getRemainingQuantity())).sum();
        if (lotAvailable < quantity) {
            int physicalAvailable = Math.max(0, availableBeforeTransfer);
            if (physicalAvailable < quantity) {
                throw new BusinessException(ResultCode.INSUFFICIENT_STOCK, "Insufficient stock for FIFO transfer");
            }
            int openingQuantity = physicalAvailable - lotAvailable;
            if (openingQuantity > 0) {
                StockLot openingLot = createReceiptLot(
                        resourceType,
                        resourceId,
                        fromWarehouseId,
                        openingQuantity,
                        fallbackUnitCost,
                        BigDecimal.ZERO,
                        "MIGRATION_INITIAL",
                        resourceId,
                        null,
                        businessDate,
                        "OPENING-LOT:" + resourceType + ":" + resourceId + ":" + fromWarehouseId
                );
                sourceLots.add(openingLot);
            }
        }

        int remaining = quantity;
        for (StockLot sourceLot : sourceLots) {
            if (remaining == 0) {
                break;
            }
            int sourceQuantity = value(sourceLot.getRemainingQuantity());
            if (sourceQuantity <= 0 || StockLot.STATUS_REVERSED.equals(sourceLot.getStatus())) {
                continue;
            }
            int movedQuantity = Math.min(sourceQuantity, remaining);
            BigDecimal movedCost = StockLotCostAllocator.allocate(sourceLot, sourceQuantity, movedQuantity);
            String key = "TRANSFER-LOT:" + stockMovementId + ":" + sourceLot.getId();
            StockLot existingTarget = stockLotRepository.findByIdempotencyKey(key).orElse(null);
            if (existingTarget == null) {
                createReceiptLotWithTotalCost(
                        resourceType,
                        resourceId,
                        toWarehouseId,
                        movedQuantity,
                        sourceLot.getUnitCost(),
                        movedCost,
                        BigDecimal.ZERO,
                        sourceType,
                        stockMovementId,
                        sourceLot.getId(),
                        sourceLot.getReceivedBusinessDate(),
                        key
                );
            }
            sourceLot.setRemainingQuantity(sourceQuantity - movedQuantity);
            sourceLot.setRemainingCostAmount(
                    StockLotCostAllocator.remainingCost(sourceLot).subtract(movedCost));
            sourceLot.refreshStatus();
            stockLotRepository.save(sourceLot);
            remaining -= movedQuantity;
        }
        if (remaining != 0) {
            throw new BusinessException(ResultCode.INSUFFICIENT_STOCK, "FIFO lots do not cover the transfer quantity");
        }
    }

    private ConsumptionResult result(List<StockLotConsumption> lines) {
        BigDecimal total = lines.stream()
                .map(StockLotConsumption::getTotalCost)
                .map(value -> value == null ? BigDecimal.ZERO : value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int quantity = lines.stream().mapToInt(line -> Math.abs(value(line.getQuantity()))).sum();
        BigDecimal unit = quantity == 0
                ? BigDecimal.ZERO
                : total.abs().divide(BigDecimal.valueOf(quantity), 6, java.math.RoundingMode.HALF_UP);
        return new ConsumptionResult(total, unit, List.copyOf(lines));
    }

    private int value(Integer input) {
        return input == null ? 0 : input;
    }

    private boolean claimTechnicalKey(String scope, String key) {
        return requestIdempotencyGuard == null
                || requestIdempotencyGuard.claimTechnicalKey(scope, key);
    }

    private Optional<StockLot> findReceiptIdempotentLot(String key, boolean claimWon) {
        if (requestIdempotencyGuard != null && claimWon) {
            return stockLotRepository.findByIdempotencyKey(key);
        }
        return stockLotRepository.findByIdempotencyKeyForUpdate(key);
    }

    private List<StockLotConsumption> findConsumptionIdempotencyFacts(
            String prefix,
            boolean claimWon
    ) {
        if (requestIdempotencyGuard != null && claimWon) {
            return consumptionRepository.findByIdempotencyKeyStartingWithOrderByIdAsc(prefix);
        }
        return consumptionRepository.findByIdempotencyKeyPrefixForUpdate(prefix);
    }

    private Optional<StockLotCostAdjustment> findCostAdjustmentIdempotentFact(
            String key,
            boolean claimWon
    ) {
        if (requestIdempotencyGuard != null && claimWon) {
            return costAdjustmentRepository.findByIdempotencyKey(key);
        }
        return costAdjustmentRepository.findByIdempotencyKeyForUpdate(key);
    }

    public record ConsumptionResult(BigDecimal totalCost, BigDecimal unitCost, List<StockLotConsumption> consumptions) {
        public static ConsumptionResult empty() {
            return new ConsumptionResult(BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        }
    }
}
