package com.example.forklift_erp.service;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.entity.StockMovement;
import com.example.forklift_erp.entity.StockMovementLine;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.util.MoneyValues;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

final class StockMovementReversalValidator {
    private StockMovementReversalValidator() {
    }

    static StockMovementLine requireExactSingleLineReversal(
            StockMovement original,
            List<StockMovementLine> originalLines,
            String resourceType,
            Long resourceId,
            Long warehouseId,
            int before,
            int after,
            BigDecimal unitCost,
            BigDecimal unitRevenue,
            Long stockLotId,
            String sourceType,
            Long sourceId,
            Long sourceLineId
    ) {
        if (original.getReversalOfMovementId() != null
                || !Objects.equals(resourceType, original.getResourceType())
                || !Objects.equals(sourceType, original.getSourceType())
                || !Objects.equals(sourceId, original.getSourceId())
                || !Objects.equals(sourceLineId, original.getSourceLineId())
                || originalLines.size() != 1) {
            throw conflict();
        }

        StockMovementLine originalLine = originalLines.getFirst();
        int delta = after - before;
        BigDecimal costAmount = MoneyValues.zeroIfNullOrNegative(unitCost)
                .multiply(BigDecimal.valueOf(Math.abs(delta)));
        BigDecimal lineAmount = MoneyValues.zeroIfNullOrNegative(unitRevenue)
                .multiply(BigDecimal.valueOf(Math.abs(delta)));
        boolean exactOpposite = originalLine.getReversalOfMovementLineId() == null
                && Objects.equals(resourceType, originalLine.getResourceType())
                && Objects.equals(resourceId, originalLine.getResourceId())
                && Objects.equals(warehouseId, originalLine.getWarehouseId())
                && Objects.equals(stockLotId, originalLine.getStockLotId())
                && Objects.equals(sourceLineId, originalLine.getSourceLineId())
                && delta == -value(originalLine.getQuantityDelta())
                && before == value(originalLine.getAfterQuantity())
                && after == value(originalLine.getBeforeQuantity())
                && decimalEquals(unitCost, originalLine.getUnitCost())
                && decimalEquals(unitRevenue, originalLine.getUnitRevenue())
                && decimalEquals(costAmount, originalLine.getCostAmount())
                && decimalEquals(lineAmount, originalLine.getLineAmount());
        if (!exactOpposite) {
            throw conflict();
        }
        return originalLine;
    }

    private static boolean decimalEquals(BigDecimal left, BigDecimal right) {
        BigDecimal normalizedLeft = left == null ? BigDecimal.ZERO : left;
        BigDecimal normalizedRight = right == null ? BigDecimal.ZERO : right;
        return normalizedLeft.compareTo(normalizedRight) == 0;
    }

    private static int value(Integer input) {
        return input == null ? 0 : input;
    }

    private static BusinessException conflict() {
        return new BusinessException(ResultCode.CONFLICT,
                "Stock movement reversal must exactly negate one complete original movement");
    }
}
