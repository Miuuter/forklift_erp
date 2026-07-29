package com.example.forklift_erp.service;

import com.example.forklift_erp.entity.StockMovement;
import com.example.forklift_erp.entity.StockMovementLine;
import com.example.forklift_erp.repository.StockBalanceRepository;
import com.example.forklift_erp.repository.StockMovementLineRepository;
import com.example.forklift_erp.repository.StockMovementRepository;
import com.example.forklift_erp.repository.WarehouseRepository;
import com.example.forklift_erp.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockLedgerServiceTests {

    @Test
    void movementIdempotencyReturnsTheOriginalMovementForAnIdenticalPayload() {
        Fixtures fixtures = fixtures();
        StockMovement existing = movement();
        StockMovementLine line = line();
        when(fixtures.movementRepository.findByIdempotencyKeyForUpdate("MOVEMENT-KEY"))
                .thenReturn(Optional.of(existing));
        when(fixtures.lineRepository.findByMovementIdForUpdate(41L))
                .thenReturn(List.of(line));

        StockMovement result = fixtures.service.recordMovement(
                "OUTBOUND", "PART", 7L, "P-7", "Part 7", 9L,
                5, 3, new BigDecimal("12.500000"), "operator", "remark",
                "OUTBOUND_ORDER", 88L, 12L, LocalDate.of(2026, 7, 19),
                "SALE_OUTBOUND", BigDecimal.ZERO, "MOVEMENT-KEY", 101L, null);

        assertThat(result).isSameAs(existing);
        verify(fixtures.balanceRepository, never()).findForUpdate("PART", 7L, 9L);
    }

    @Test
    void movementIdempotencyRejectsAChangedQuantityOrSource() {
        Fixtures fixtures = fixtures();
        when(fixtures.movementRepository.findByIdempotencyKeyForUpdate("MOVEMENT-KEY"))
                .thenReturn(Optional.of(movement()));
        when(fixtures.lineRepository.findByMovementIdForUpdate(41L))
                .thenReturn(List.of(line()));

        assertThatThrownBy(() -> fixtures.service.recordMovement(
                "OUTBOUND", "PART", 7L, "P-7", "Part 7", 9L,
                5, 4, new BigDecimal("12.500000"), "operator", "remark",
                "OUTBOUND_ORDER", 88L, 12L, LocalDate.of(2026, 7, 19),
                "SALE_OUTBOUND", BigDecimal.ZERO, "MOVEMENT-KEY", 101L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Stock movement idempotency key is already bound to a different payload");
        verify(fixtures.balanceRepository, never()).findForUpdate("PART", 7L, 9L);
    }

    private Fixtures fixtures() {
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        StockBalanceRepository balanceRepository = mock(StockBalanceRepository.class);
        StockMovementRepository movementRepository = mock(StockMovementRepository.class);
        StockMovementLineRepository lineRepository = mock(StockMovementLineRepository.class);
        when(warehouseRepository.existsById(9L)).thenReturn(true);
        StockLedgerService service = new StockLedgerService();
        ReflectionTestUtils.setField(service, "warehouseRepository", warehouseRepository);
        ReflectionTestUtils.setField(service, "stockBalanceRepository", balanceRepository);
        ReflectionTestUtils.setField(service, "stockMovementRepository", movementRepository);
        ReflectionTestUtils.setField(service, "stockMovementLineRepository", lineRepository);
        return new Fixtures(service, balanceRepository, movementRepository, lineRepository);
    }

    private StockMovement movement() {
        StockMovement movement = new StockMovement();
        movement.setId(41L);
        movement.setMovementType("OUTBOUND");
        movement.setResourceType("PART");
        movement.setSourceType("OUTBOUND_ORDER");
        movement.setSourceId(88L);
        movement.setSourceLineId(12L);
        movement.setBusinessDate(LocalDate.of(2026, 7, 19));
        movement.setBusinessType("SALE_OUTBOUND");
        movement.setOperator("operator");
        movement.setRemark("remark");
        return movement;
    }

    private StockMovementLine line() {
        StockMovementLine line = new StockMovementLine();
        line.setMovementId(41L);
        line.setResourceType("PART");
        line.setResourceId(7L);
        line.setResourceCode("P-7");
        line.setResourceName("Part 7");
        line.setWarehouseId(9L);
        line.setBeforeQuantity(5);
        line.setAfterQuantity(3);
        line.setQuantityDelta(-2);
        line.setUnitCost(new BigDecimal("12.500000"));
        line.setUnitRevenue(BigDecimal.ZERO);
        line.setStockLotId(101L);
        line.setSourceLineId(12L);
        return line;
    }

    private record Fixtures(
            StockLedgerService service,
            StockBalanceRepository balanceRepository,
            StockMovementRepository movementRepository,
            StockMovementLineRepository lineRepository
    ) {
    }
}
