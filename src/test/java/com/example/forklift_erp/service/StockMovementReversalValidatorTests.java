package com.example.forklift_erp.service;

import com.example.forklift_erp.entity.StockMovement;
import com.example.forklift_erp.entity.StockMovementLine;
import com.example.forklift_erp.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockMovementReversalValidatorTests {
    @Test
    void acceptsOneCompleteEconomicallyOppositeLine() {
        StockMovement original = originalMovement();
        StockMovementLine originalLine = originalLine();

        StockMovementLine result = StockMovementReversalValidator.requireExactSingleLineReversal(
                original,
                List.of(originalLine),
                "PART",
                7L,
                9L,
                7,
                10,
                new BigDecimal("5.000000"),
                new BigDecimal("12.00"),
                31L,
                "PURCHASE_ORDER",
                88L,
                null
        );

        assertThat(result).isSameAs(originalLine);
    }

    @Test
    void rejectsAReversalAfterTheBalanceSnapshotHasMoved() {
        assertThatThrownBy(() -> StockMovementReversalValidator.requireExactSingleLineReversal(
                originalMovement(),
                List.of(originalLine()),
                "PART",
                7L,
                9L,
                8,
                11,
                new BigDecimal("5.000000"),
                new BigDecimal("12.00"),
                31L,
                "PURCHASE_ORDER",
                88L,
                null
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Stock movement reversal must exactly negate one complete original movement");
    }

    private StockMovement originalMovement() {
        StockMovement movement = new StockMovement();
        movement.setId(41L);
        movement.setResourceType("PART");
        movement.setSourceType("PURCHASE_ORDER");
        movement.setSourceId(88L);
        return movement;
    }

    private StockMovementLine originalLine() {
        StockMovementLine line = new StockMovementLine();
        line.setId(51L);
        line.setMovementId(41L);
        line.setResourceType("PART");
        line.setResourceId(7L);
        line.setWarehouseId(9L);
        line.setQuantityDelta(-3);
        line.setBeforeQuantity(10);
        line.setAfterQuantity(7);
        line.setUnitCost(new BigDecimal("5.000000"));
        line.setUnitRevenue(new BigDecimal("12.00"));
        line.setCostAmount(new BigDecimal("15.00"));
        line.setLineAmount(new BigDecimal("36.00"));
        line.setStockLotId(31L);
        return line;
    }
}
