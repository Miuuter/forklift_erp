package com.example.forklift_erp.service;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.entity.StockLot;
import com.example.forklift_erp.entity.StockLotConsumption;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.util.MoneyValues;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class StockLotCostAllocator {
    private StockLotCostAllocator() {
    }

    static BigDecimal allocate(StockLot lot, int remainingBefore, int used) {
        if (remainingBefore <= 0 || used <= 0 || used > remainingBefore) {
            throw new BusinessException(ResultCode.CONFLICT, "FIFO lot quantity is inconsistent");
        }
        BigDecimal remainingCost = remainingCost(lot);
        if (remainingCost.signum() < 0) {
            throw new BusinessException(ResultCode.CONFLICT, "FIFO lot has a negative remaining cost");
        }
        if (used == remainingBefore) {
            return remainingCost.setScale(2, RoundingMode.HALF_UP);
        }
        return remainingCost.multiply(BigDecimal.valueOf(used))
                .divide(BigDecimal.valueOf(remainingBefore), 2, RoundingMode.HALF_UP);
    }

    static void restore(StockLot lot, StockLotConsumption original) {
        BigDecimal restoredCost = original.getTotalCost() == null
                ? BigDecimal.ZERO
                : original.getTotalCost().abs();
        BigDecimal restoredRemainingCost = remainingCost(lot).add(restoredCost);
        if (restoredRemainingCost.compareTo(originalCost(lot)) > 0) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "FIFO restoration would exceed the lot's original value");
        }
        lot.setRemainingCostAmount(restoredRemainingCost.setScale(2, RoundingMode.HALF_UP));
    }

    static BigDecimal originalCost(StockLot lot) {
        if (lot.getOriginalCostAmount() != null) {
            return lot.getOriginalCostAmount();
        }
        return MoneyValues.zeroIfNullOrNegative(lot.getUnitCost())
                .multiply(BigDecimal.valueOf(value(lot.getOriginalQuantity())))
                .setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal remainingCost(StockLot lot) {
        if (lot.getRemainingCostAmount() != null) {
            return lot.getRemainingCostAmount();
        }
        return MoneyValues.zeroIfNullOrNegative(lot.getUnitCost())
                .multiply(BigDecimal.valueOf(value(lot.getRemainingQuantity())))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static int value(Integer input) {
        return input == null ? 0 : input;
    }
}
