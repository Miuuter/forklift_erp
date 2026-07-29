package com.example.forklift_erp.service;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.entity.StockMovement;
import com.example.forklift_erp.entity.StockMovementLine;
import com.example.forklift_erp.exception.BusinessException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

final class StockMovementIdempotencyValidator {
    private StockMovementIdempotencyValidator() {
    }

    static void validate(
            StockMovement existing,
            List<StockMovementLine> lines,
            String movementType,
            String resourceType,
            Long resourceId,
            String resourceCode,
            String resourceName,
            Long warehouseId,
            int before,
            int after,
            BigDecimal unitCost,
            BigDecimal unitRevenue,
            String operator,
            String remark,
            String sourceType,
            Long sourceId,
            Long sourceLineId,
            LocalDate requestedBusinessDate,
            LocalDate effectiveBusinessDate,
            String effectiveBusinessType,
            Long stockLotId,
            Long reversalOfMovementId
    ) {
        if (lines.size() != 1) {
            throw conflict();
        }
        StockMovementLine line = lines.get(0);
        boolean headerMatches = Objects.equals(movementType, existing.getMovementType())
                && Objects.equals(sourceType, existing.getSourceType())
                && Objects.equals(sourceId, existing.getSourceId())
                && Objects.equals(sourceLineId, existing.getSourceLineId())
                && Objects.equals(effectiveBusinessType, normalizeBusinessType(existing))
                && Objects.equals(reversalOfMovementId, existing.getReversalOfMovementId())
                && (requestedBusinessDate == null
                        || Objects.equals(effectiveBusinessDate, existing.getBusinessDate()))
                && Objects.equals(operator, existing.getOperator())
                && Objects.equals(remark, existing.getRemark());
        boolean lineMatches = Objects.equals(resourceType, line.getResourceType())
                && Objects.equals(resourceId, line.getResourceId())
                && Objects.equals(resourceCode, line.getResourceCode())
                && Objects.equals(resourceName, line.getResourceName())
                && Objects.equals(warehouseId, line.getWarehouseId())
                && before == value(line.getBeforeQuantity())
                && after == value(line.getAfterQuantity())
                && decimalEquals(unitCost, line.getUnitCost())
                && decimalEquals(unitRevenue, line.getUnitRevenue())
                && Objects.equals(stockLotId, line.getStockLotId())
                && Objects.equals(sourceLineId, line.getSourceLineId())
                && Objects.equals(reversalOfMovementId, line.getReversalOfMovementId())
                && (reversalOfMovementId == null
                        ? line.getReversalOfMovementLineId() == null
                        : line.getReversalOfMovementLineId() != null);
        if (!headerMatches || !lineMatches) {
            throw conflict();
        }
    }

    private static String normalizeBusinessType(StockMovement movement) {
        return movement.getBusinessType() == null || movement.getBusinessType().isBlank()
                ? movement.getMovementType()
                : movement.getBusinessType();
    }

    private static boolean decimalEquals(BigDecimal left, BigDecimal right) {
        BigDecimal normalizedLeft = left == null ? BigDecimal.ZERO : left;
        BigDecimal normalizedRight = right == null ? BigDecimal.ZERO : right;
        return normalizedLeft.compareTo(normalizedRight) == 0;
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static BusinessException conflict() {
        return new BusinessException(ResultCode.CONFLICT,
                "Stock movement idempotency key is already bound to a different payload");
    }
}
