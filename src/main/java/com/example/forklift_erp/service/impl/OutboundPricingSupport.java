package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.entity.StockMovement;
import com.example.forklift_erp.entity.StockMovementLine;
import com.example.forklift_erp.repository.StockMovementLineRepository;
import com.example.forklift_erp.repository.StockMovementRepository;
import com.example.forklift_erp.repository.StockOperationLogRepository;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.util.MoneyValues;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

final class OutboundPricingSupport {
    private OutboundPricingSupport() {
    }

    static BigDecimal resolveLineAmount(
            BigDecimal requestedLineAmount,
            BigDecimal requestedReceivableAmount,
            BigDecimal unitSalePrice,
            Integer quantity
    ) {
        BigDecimal explicitLine = requestedLineAmount == null
                ? null : MoneyValues.zeroIfNullOrNegative(requestedLineAmount);
        BigDecimal explicitReceivable = requestedReceivableAmount == null
                ? null : MoneyValues.zeroIfNullOrNegative(requestedReceivableAmount);
        if (explicitLine != null && explicitReceivable != null
                && explicitLine.compareTo(explicitReceivable) != 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Line amount and receivable amount must be equal");
        }
        BigDecimal explicit = MoneyValues.firstNonNegativeOrNull(explicitLine, explicitReceivable);
        int safeQuantity = quantity == null || quantity < 1 ? 1 : quantity;
        BigDecimal calculated = MoneyValues.zeroIfNullOrNegative(unitSalePrice)
                .multiply(BigDecimal.valueOf(safeQuantity))
                .setScale(2, RoundingMode.HALF_UP);
        if (explicit != null) {
            if (explicit.setScale(2, RoundingMode.HALF_UP).compareTo(calculated) != 0) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "Line amount must equal quantity multiplied by unit sale price");
            }
            return explicit.setScale(2, RoundingMode.HALF_UP);
        }
        return calculated;
    }

    static BigDecimal outboundCost(
            OutboundOrder order,
            String sourceType,
            StockMovementRepository stockMovementRepository,
            StockMovementLineRepository stockMovementLineRepository,
            StockOperationLogRepository stockOperationLogRepository
    ) {
        List<StockMovement> movements = stockMovementRepository
                .findBySourceTypeAndSourceId(sourceType, order.getId());
        List<StockMovementLine> lines = movements.stream()
                .filter(movement -> movement.getReversalOfMovementId() == null)
                .flatMap(movement -> stockMovementLineRepository
                        .findByMovementIdOrderByIdAsc(movement.getId()).stream())
                .toList();
        if (!lines.isEmpty() && lines.stream().allMatch(line -> line.getCostAmount() != null)) {
            return lines.stream()
                    .map(StockMovementLine::getCostAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);
        }
        if (order.getStockOperationLogId() == null) {
            return BigDecimal.ZERO;
        }
        return stockOperationLogRepository.findById(order.getStockOperationLogId())
                .map(log -> MoneyValues.zeroIfNullOrNegative(log.getUnitCost())
                        .multiply(BigDecimal.valueOf(order.getQuantity() == null ? 1 : order.getQuantity()))
                        .setScale(2, RoundingMode.HALF_UP))
                .orElse(BigDecimal.ZERO);
    }

    static BigDecimal unitCostForOrder(OutboundOrder order, BigDecimal totalCost) {
        int quantity = order.getQuantity() == null || order.getQuantity() < 1 ? 1 : order.getQuantity();
        return totalCost.divide(BigDecimal.valueOf(quantity), 6, RoundingMode.HALF_UP);
    }
}
