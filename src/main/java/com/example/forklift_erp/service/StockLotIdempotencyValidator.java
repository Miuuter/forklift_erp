package com.example.forklift_erp.service;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.entity.StockLot;
import com.example.forklift_erp.entity.StockLotConsumption;
import com.example.forklift_erp.entity.StockLotCostAdjustment;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.util.MoneyValues;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

final class StockLotIdempotencyValidator {
    private StockLotIdempotencyValidator() {
    }

    static void validateReceiptLot(
            StockLot existing,
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
            LocalDate businessDate
    ) {
        boolean matches = Objects.equals(resourceType, existing.getResourceType())
                && Objects.equals(resourceId, existing.getResourceId())
                && Objects.equals(warehouseId, existing.getWarehouseId())
                && Objects.equals(sourceType, existing.getSourceType())
                && Objects.equals(sourceId, existing.getSourceId())
                && Objects.equals(sourceLineId, existing.getSourceLineId())
                && quantity == value(existing.getOriginalQuantity())
                && decimalEquals(unitCost, existing.getUnitCost())
                && decimalEquals(totalCost, originalCost(existing))
                && decimalEquals(freightAllocated, existing.getFreightAllocated())
                && (businessDate == null
                        || Objects.equals(businessDate, existing.getReceivedBusinessDate()));
        if (!matches) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Stock lot idempotency key is already bound to a different payload");
        }
    }

    static void validateConsumption(
            List<StockLotConsumption> existing,
            String resourceType,
            Long resourceId,
            Long warehouseId,
            int quantity,
            String sourceType,
            Long sourceId,
            Long sourceLineId,
            LocalDate businessDate,
            String idempotencyKey
    ) {
        int existingQuantity = 0;
        for (StockLotConsumption consumption : existing) {
            boolean matches = consumption.getReversalOfConsumptionId() == null
                    && value(consumption.getQuantity()) > 0
                    && Objects.equals(resourceType, consumption.getResourceType())
                    && Objects.equals(resourceId, consumption.getResourceId())
                    && Objects.equals(warehouseId, consumption.getWarehouseId())
                    && Objects.equals(sourceType, consumption.getSourceType())
                    && Objects.equals(sourceId, consumption.getSourceId())
                    && Objects.equals(sourceLineId, consumption.getSourceLineId())
                    && (businessDate == null
                            || Objects.equals(businessDate, consumption.getBusinessDate()))
                    && consumption.getIdempotencyKey() != null
                    && consumption.getIdempotencyKey().startsWith(idempotencyKey + ":lot:");
            if (!matches) {
                throw consumptionConflict();
            }
            existingQuantity += value(consumption.getQuantity());
        }
        if (existingQuantity != quantity) {
            throw consumptionConflict();
        }
    }

    static void validateCostAdjustment(
            StockLotCostAdjustment existing,
            StockLot existingLot,
            String resourceType,
            Long resourceId,
            Long warehouseId,
            BigDecimal amount,
            String sourceType,
            Long sourceId,
            Long sourceLineId,
            LocalDate businessDate
    ) {
        boolean matches = Objects.equals(resourceType, existingLot.getResourceType())
                && Objects.equals(resourceId, existingLot.getResourceId())
                && Objects.equals(warehouseId, existingLot.getWarehouseId())
                && decimalEquals(amount, existing.getAmount())
                && Objects.equals(sourceType, existing.getSourceType())
                && Objects.equals(sourceId, existing.getSourceId())
                && Objects.equals(sourceLineId, existing.getSourceLineId())
                && (businessDate == null || Objects.equals(businessDate, existing.getBusinessDate()));
        if (!matches) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Cost adjustment idempotency key is already bound to a different payload");
        }
    }

    private static BigDecimal originalCost(StockLot lot) {
        if (lot.getOriginalCostAmount() != null) {
            return lot.getOriginalCostAmount();
        }
        return MoneyValues.zeroIfNullOrNegative(lot.getUnitCost())
                .multiply(BigDecimal.valueOf(value(lot.getOriginalQuantity())))
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static boolean decimalEquals(BigDecimal left, BigDecimal right) {
        BigDecimal normalizedLeft = left == null ? BigDecimal.ZERO : left;
        BigDecimal normalizedRight = right == null ? BigDecimal.ZERO : right;
        return normalizedLeft.compareTo(normalizedRight) == 0;
    }

    private static int value(Integer input) {
        return input == null ? 0 : input;
    }

    private static BusinessException consumptionConflict() {
        return new BusinessException(ResultCode.CONFLICT,
                "FIFO consumption idempotency key is already bound to a different payload");
    }

    static BusinessException inProgress(String fact) {
        return new BusinessException(ResultCode.CONFLICT,
                fact + " idempotency key is already being processed; retry with the same key");
    }
}
