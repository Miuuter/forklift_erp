package com.example.forklift_erp.service;

import com.example.forklift_erp.constant.FinancialEventType;
import com.example.forklift_erp.constant.StockBusinessType;
import com.example.forklift_erp.entity.StockLot;
import com.example.forklift_erp.entity.StockLotConsumption;
import com.example.forklift_erp.entity.StockMovement;
import com.example.forklift_erp.entity.StockMovementLine;
import com.example.forklift_erp.entity.StockOperationLog;
import com.example.forklift_erp.repository.StockMovementLineRepository;
import com.example.forklift_erp.repository.StockMovementRepository;
import com.example.forklift_erp.service.impl.StockOperationRecorder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryAdjustmentAccountingServiceTests {

    @Test
    void gainCreatesFifoMovementAndFinancialEventWithTheSameValue() {
        Fixture fixture = fixture();
        StockLot lot = new StockLot();
        lot.setId(11L);
        when(fixture.stockLotService.createReceiptLot(
                any(), any(), any(), any(Integer.class), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(lot);

        InventoryAdjustmentAccountingService.AdjustmentResult result = fixture.service.post(
                command(2, 5, "10.00", "GAIN-1")
        );

        assertThat(result.totalCost()).isEqualByComparingTo("30.00");
        assertThat(result.stockLotId()).isEqualTo(11L);
        verify(fixture.financialEventService).post(
                eq(FinancialEventType.INVENTORY_GAIN),
                eq(new BigDecimal("30.00")),
                eq(LocalDate.of(2026, 7, 15)),
                eq("STOCKTAKING"),
                eq(99L),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq("Count difference"),
                eq("GAIN-1:FINANCIAL")
        );
        assertThat(fixture.line.getCostAmount()).isEqualByComparingTo("30.00");
        assertThat(fixture.line.getStockLotId()).isEqualTo(11L);
    }

    @Test
    void lossUsesExactFifoTotalInsteadOfRoundedAverageTimesQuantity() {
        Fixture fixture = fixture();
        StockLotConsumption consumption = new StockLotConsumption();
        consumption.setId(21L);
        consumption.setStockLotId(12L);
        consumption.setQuantity(3);
        consumption.setUnitCost(new BigDecimal("8.33"));
        consumption.setTotalCost(new BigDecimal("25.00"));
        when(fixture.stockLotService.consumeFifo(
                any(), any(), any(), any(Integer.class), any(), any(Integer.class),
                any(), any(), any(), any(), any()
        )).thenReturn(new StockLotService.ConsumptionResult(
                new BigDecimal("25.00"),
                new BigDecimal("8.33"),
                List.of(consumption)
        ));

        InventoryAdjustmentAccountingService.AdjustmentResult result = fixture.service.post(
                command(5, 2, "9.00", "LOSS-1")
        );

        assertThat(result.totalCost()).isEqualByComparingTo("25.00");
        assertThat(result.unitCost()).isEqualByComparingTo("8.33");
        verify(fixture.financialEventService).post(
                eq(FinancialEventType.INVENTORY_LOSS),
                eq(new BigDecimal("25.00")),
                eq(LocalDate.of(2026, 7, 15)),
                eq("STOCKTAKING"),
                eq(99L),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq("Count difference"),
                eq("LOSS-1:FINANCIAL")
        );
        assertThat(fixture.line.getCostAmount()).isEqualByComparingTo("25.00");
        assertThat(fixture.line.getStockLotId()).isEqualTo(12L);
    }

    private Fixture fixture() {
        StockLotService stockLotService = mock(StockLotService.class);
        StockOperationRecorder recorder = mock(StockOperationRecorder.class);
        StockMovementRepository movementRepository = mock(StockMovementRepository.class);
        StockMovementLineRepository lineRepository = mock(StockMovementLineRepository.class);
        FinancialEventService financialEventService = mock(FinancialEventService.class);
        StockOperationLog stockLog = new StockOperationLog();
        stockLog.setId(5L);
        StockMovement movement = new StockMovement();
        movement.setId(7L);
        StockMovementLine line = new StockMovementLine();
        line.setId(8L);
        when(recorder.record(any())).thenReturn(stockLog);
        when(movementRepository.findByIdempotencyKey(any())).thenReturn(Optional.of(movement));
        when(lineRepository.findByMovementIdOrderByIdAsc(7L)).thenReturn(List.of(line));
        when(lineRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new Fixture(
                new InventoryAdjustmentAccountingService(
                        stockLotService, recorder, movementRepository, lineRepository, financialEventService),
                stockLotService,
                recorder,
                financialEventService,
                line
        );
    }

    private InventoryAdjustmentAccountingService.Command command(
            int before,
            int after,
            String fallbackCost,
            String idempotencyBase
    ) {
        return new InventoryAdjustmentAccountingService.Command(
                "Stocktaking stock",
                StockLedgerService.RESOURCE_PART,
                44L,
                "P-44",
                "Part 44",
                3L,
                before,
                after,
                new BigDecimal(fallbackCost),
                "tester",
                "Count difference",
                "STOCKTAKING",
                99L,
                "Post count",
                LocalDate.of(2026, 7, 15),
                after > before ? StockBusinessType.STOCKTAKING_GAIN : StockBusinessType.STOCKTAKING_LOSS,
                idempotencyBase,
                true
        );
    }

    private record Fixture(
            InventoryAdjustmentAccountingService service,
            StockLotService stockLotService,
            StockOperationRecorder recorder,
            FinancialEventService financialEventService,
            StockMovementLine line
    ) {
    }
}
