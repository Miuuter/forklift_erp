package com.example.forklift_erp.service;

import com.example.forklift_erp.entity.StockLot;
import com.example.forklift_erp.entity.StockLotConsumption;
import com.example.forklift_erp.entity.StockLotCostAdjustment;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.StockLotConsumptionRepository;
import com.example.forklift_erp.repository.StockLotCostAdjustmentRepository;
import com.example.forklift_erp.repository.StockLotRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockLotServiceTests {

    @Test
    void revalueUnconsumedReceiptUpdatesFifoAndRecordsValueLayer() {
        StockLotRepository lotRepository = mock(StockLotRepository.class);
        StockLotCostAdjustmentRepository adjustmentRepository = mock(StockLotCostAdjustmentRepository.class);
        StockLot lot = lot(31L, 2, 2, "0.00");
        when(lotRepository.findOpenFifoForUpdate("PART", 7L, 3L)).thenReturn(List.of(lot));
        StockLotService service = new StockLotService(
                lotRepository,
                mock(StockLotConsumptionRepository.class),
                adjustmentRepository
        );

        BigDecimal adjustment = service.revalueUnconsumedReceiptLots(
                "PART",
                7L,
                3L,
                new BigDecimal("25.00"),
                "REMOVED_PART_VALUATION",
                7L,
                LocalDate.of(2026, 7, 15),
                "VALUE:7"
        );

        assertThat(adjustment).isEqualByComparingTo("50.00");
        assertThat(lot.getUnitCost()).isEqualByComparingTo("25.00");
        verify(lotRepository).save(lot);
        ArgumentCaptor<StockLotCostAdjustment> captor = ArgumentCaptor.forClass(StockLotCostAdjustment.class);
        verify(adjustmentRepository).save(captor.capture());
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("50.00");
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo("VALUE:7:31");
    }

    @Test
    void revalueRejectsPartiallyConsumedReceiptLot() {
        StockLotRepository lotRepository = mock(StockLotRepository.class);
        StockLot lot = lot(32L, 2, 1, "0.00");
        when(lotRepository.findOpenFifoForUpdate("PART", 8L, 3L)).thenReturn(List.of(lot));
        StockLotService service = new StockLotService(
                lotRepository,
                mock(StockLotConsumptionRepository.class),
                mock(StockLotCostAdjustmentRepository.class)
        );

        assertThatThrownBy(() -> service.revalueUnconsumedReceiptLots(
                "PART", 8L, 3L, new BigDecimal("25.00"),
                "REMOVED_PART_VALUATION", 8L, LocalDate.now(), "VALUE:8"
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessage("A consumed FIFO lot cannot be revalued as a pending receipt");
    }

    @Test
    void receiptLotIdempotencyReturnsAnAlreadyConsumedLotForTheSameImmutablePayload() {
        StockLotRepository lotRepository = mock(StockLotRepository.class);
        StockLot existing = lot(38L, 4, 1, "12.500000");
        existing.setResourceType("PART");
        existing.setResourceId(8L);
        existing.setWarehouseId(3L);
        existing.setSourceType("PURCHASE_ORDER");
        existing.setSourceId(91L);
        existing.setSourceLineId(2L);
        existing.setReceivedBusinessDate(LocalDate.of(2026, 7, 18));
        existing.setFreightAllocated(new BigDecimal("2.00"));
        existing.setOriginalCostAmount(new BigDecimal("50.00"));
        existing.setRemainingCostAmount(new BigDecimal("12.50"));
        when(lotRepository.findByIdempotencyKeyForUpdate("LOT-KEY")).thenReturn(Optional.of(existing));
        StockLotService service = new StockLotService(
                lotRepository,
                mock(StockLotConsumptionRepository.class),
                mock(StockLotCostAdjustmentRepository.class)
        );

        assertThat(service.createReceiptLotWithTotalCost(
                "PART", 8L, 3L, 4, new BigDecimal("12.500000"),
                new BigDecimal("50.00"), new BigDecimal("2.00"),
                "PURCHASE_ORDER", 91L, 2L,
                LocalDate.of(2026, 7, 18), "LOT-KEY"))
                .isSameAs(existing);
    }

    @Test
    void receiptLotIdempotencyRejectsChangedCostOrQuantity() {
        StockLotRepository lotRepository = mock(StockLotRepository.class);
        StockLot existing = lot(39L, 4, 4, "12.500000");
        existing.setResourceType("PART");
        existing.setResourceId(8L);
        existing.setWarehouseId(3L);
        existing.setSourceType("PURCHASE_ORDER");
        existing.setSourceId(91L);
        existing.setOriginalCostAmount(new BigDecimal("50.00"));
        when(lotRepository.findByIdempotencyKeyForUpdate("LOT-KEY")).thenReturn(Optional.of(existing));
        StockLotService service = new StockLotService(
                lotRepository,
                mock(StockLotConsumptionRepository.class),
                mock(StockLotCostAdjustmentRepository.class)
        );

        assertThatThrownBy(() -> service.createReceiptLotWithTotalCost(
                "PART", 8L, 3L, 5, new BigDecimal("12.500000"),
                new BigDecimal("51.00"), BigDecimal.ZERO,
                "PURCHASE_ORDER", 91L, null,
                LocalDate.of(2026, 7, 18), "LOT-KEY"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Stock lot idempotency key is already bound to a different payload");
    }

    @Test
    void serializedCostAdjustmentIdempotencyRejectsAChangedAmountOrSource() {
        StockLotRepository lotRepository = mock(StockLotRepository.class);
        StockLotCostAdjustmentRepository adjustmentRepository = mock(StockLotCostAdjustmentRepository.class);
        StockLot existingLot = lot(41L, 1, 1, "100.000000");
        existingLot.setResourceType("MACHINE");
        existingLot.setResourceId(22L);
        existingLot.setWarehouseId(3L);
        StockLotCostAdjustment existing = new StockLotCostAdjustment();
        existing.setStockLotId(41L);
        existing.setAmount(new BigDecimal("12.00"));
        existing.setSourceType("MODIFICATION");
        existing.setSourceId(55L);
        existing.setSourceLineId(6L);
        existing.setBusinessDate(LocalDate.of(2026, 7, 18));
        when(adjustmentRepository.findByIdempotencyKeyForUpdate("COST-KEY"))
                .thenReturn(Optional.of(existing));
        when(lotRepository.findById(41L)).thenReturn(Optional.of(existingLot));
        StockLotService service = new StockLotService(
                lotRepository,
                mock(StockLotConsumptionRepository.class),
                adjustmentRepository
        );

        service.capitalizeSerializedAssetCost(
                "MACHINE", 22L, 3L, new BigDecimal("12.00"),
                "MODIFICATION", 55L, 6L, LocalDate.of(2026, 7, 18), "COST-KEY");

        assertThatThrownBy(() -> service.capitalizeSerializedAssetCost(
                "MACHINE", 22L, 3L, new BigDecimal("13.00"),
                "MODIFICATION", 55L, 6L, LocalDate.of(2026, 7, 18), "COST-KEY"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Cost adjustment idempotency key is already bound to a different payload");
    }

    @Test
    void fifoConsumptionAllocatesTheFullReceiptCostWithoutRoundingLoss() {
        StockLotRepository lotRepository = mock(StockLotRepository.class);
        StockLotConsumptionRepository consumptionRepository = mock(StockLotConsumptionRepository.class);
        StockLot lot = lot(33L, 3, 3, "33.333333");
        when(lotRepository.findOpenFifoForUpdate("PART", 9L, 4L)).thenReturn(List.of(lot));
        when(consumptionRepository.save(any(StockLotConsumption.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        StockLotService service = new StockLotService(
                lotRepository,
                consumptionRepository,
                mock(StockLotCostAdjustmentRepository.class)
        );

        StockLotService.ConsumptionResult first = consumeOne(service, 3, 1L);
        StockLotService.ConsumptionResult second = consumeOne(service, 2, 2L);
        StockLotService.ConsumptionResult third = consumeOne(service, 1, 3L);

        assertThat(first.totalCost()).isEqualByComparingTo("33.33");
        assertThat(second.totalCost()).isEqualByComparingTo("33.34");
        assertThat(third.totalCost()).isEqualByComparingTo("33.33");
        assertThat(first.totalCost().add(second.totalCost()).add(third.totalCost()))
                .isEqualByComparingTo("100.00");
        assertThat(third.consumptions()).singleElement()
                .satisfies(consumption -> assertThat(consumption.getUnitCost())
                        .isEqualByComparingTo("33.330000"));
        assertThat(lot.getRemainingQuantity()).isZero();
        assertThat(lot.getRemainingCostAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void batchConsumptionReturnsSixDecimalAverageConsistentWithExactTotal() {
        StockLotRepository lotRepository = mock(StockLotRepository.class);
        StockLotConsumptionRepository consumptionRepository = mock(StockLotConsumptionRepository.class);
        StockLot lot = lot(34L, 3, 3, "33.333333");
        when(lotRepository.findOpenFifoForUpdate("PART", 9L, 4L)).thenReturn(List.of(lot));
        when(consumptionRepository.save(any(StockLotConsumption.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        StockLotService service = new StockLotService(
                lotRepository,
                consumptionRepository,
                mock(StockLotCostAdjustmentRepository.class)
        );

        StockLotService.ConsumptionResult result = service.consumeFifo(
                "PART", 9L, 4L, 2, new BigDecimal("33.333333"), 3,
                "TEST", 9L, 1L, LocalDate.of(2026, 7, 18), null
        );

        assertThat(result.totalCost()).isEqualByComparingTo("66.67");
        assertThat(result.unitCost()).isEqualByComparingTo("33.335000");
        assertThat(result.unitCost().multiply(BigDecimal.valueOf(2)).setScale(
                2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo(result.totalCost());
        assertThat(lot.getRemainingQuantity()).isEqualTo(1);
        assertThat(lot.getRemainingCostAmount()).isEqualByComparingTo("33.33");
    }

    @Test
    void fifoConsumptionIdempotencyRequiresTheSameSourceAndTotalQuantity() {
        StockLotRepository lotRepository = mock(StockLotRepository.class);
        StockLotConsumptionRepository consumptionRepository = mock(StockLotConsumptionRepository.class);
        StockLotConsumption existing = consumption(93L, 40L, 2, "5.000000", "10.00");
        existing.setResourceType("PART");
        existing.setResourceId(9L);
        existing.setWarehouseId(4L);
        existing.setSourceType("REPAIR");
        existing.setSourceId(8L);
        existing.setSourceLineId(7L);
        existing.setBusinessDate(LocalDate.of(2026, 7, 18));
        existing.setIdempotencyKey("FIFO-KEY:lot:40");
        when(consumptionRepository.findByIdempotencyKeyPrefixForUpdate("FIFO-KEY:"))
                .thenReturn(List.of(existing));
        StockLotService service = new StockLotService(
                lotRepository,
                consumptionRepository,
                mock(StockLotCostAdjustmentRepository.class)
        );

        StockLotService.ConsumptionResult same = service.consumeFifo(
                "PART", 9L, 4L, 2, new BigDecimal("5.000000"), 2,
                "REPAIR", 8L, 7L, LocalDate.of(2026, 7, 18), "FIFO-KEY");
        assertThat(same.consumptions()).containsExactly(existing);

        assertThatThrownBy(() -> service.consumeFifo(
                "PART", 9L, 4L, 3, new BigDecimal("5.000000"), 3,
                "REPAIR", 8L, 7L, LocalDate.of(2026, 7, 18), "FIFO-KEY"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("FIFO consumption idempotency key is already bound to a different payload");
    }

    @Test
    void receiptReversalRejectsLotWhoseValueWasPartiallyConsumed() {
        StockLotRepository lotRepository = mock(StockLotRepository.class);
        StockLot lot = lot(35L, 3, 3, "33.333333");
        lot.setRemainingCostAmount(new BigDecimal("99.99"));
        when(lotRepository.findByIdForUpdate(35L)).thenReturn(java.util.Optional.of(lot));
        StockLotService service = new StockLotService(
                lotRepository,
                mock(StockLotConsumptionRepository.class),
                mock(StockLotCostAdjustmentRepository.class)
        );

        assertThatThrownBy(() -> service.reverseReceiptLot(35L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("A consumed FIFO lot cannot have its receipt directly reversed");
    }

    @Test
    void sourceRestorationLocksLotAndRestoresExactRemainingCost() {
        StockLotRepository lotRepository = mock(StockLotRepository.class);
        StockLotConsumptionRepository consumptionRepository = mock(StockLotConsumptionRepository.class);
        StockLot lot = lot(36L, 3, 1, "33.333333");
        StockLotConsumption original = consumption(91L, 36L, 2, "33.335000", "66.67");
        when(consumptionRepository.findBySourceTypeAndSourceIdOrderByIdAsc("TEST", 8L))
                .thenReturn(List.of(original));
        when(consumptionRepository.findByReversalOfConsumptionIdIn(List.of(91L))).thenReturn(List.of());
        when(lotRepository.findByIdForUpdate(36L)).thenReturn(Optional.of(lot));
        when(consumptionRepository.save(any(StockLotConsumption.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        StockLotService service = new StockLotService(
                lotRepository,
                consumptionRepository,
                mock(StockLotCostAdjustmentRepository.class)
        );

        StockLotService.ConsumptionResult result = service.restoreSourceConsumption(
                "TEST", 8L, LocalDate.of(2026, 7, 18), "RESTORE:8");

        assertThat(result.totalCost()).isEqualByComparingTo("-66.67");
        assertThat(result.unitCost()).isEqualByComparingTo("33.335000");
        assertThat(result.consumptions()).singleElement()
                .satisfies(reversal -> {
                    assertThat(reversal.getSourceType()).isEqualTo("TEST");
                    assertThat(reversal.getSourceId()).isEqualTo(8L);
                    assertThat(reversal.getSourceLineId()).isEqualTo(7L);
                });
        assertThat(lot.getRemainingQuantity()).isEqualTo(3);
        assertThat(lot.getRemainingCostAmount()).isEqualByComparingTo("100.00");
        verify(lotRepository).findByIdForUpdate(36L);
    }

    @Test
    void sourceLineRestorationAlsoLocksLotAndRestoresExactRemainingCost() {
        StockLotRepository lotRepository = mock(StockLotRepository.class);
        StockLotConsumptionRepository consumptionRepository = mock(StockLotConsumptionRepository.class);
        StockLot lot = lot(37L, 3, 1, "33.333333");
        StockLotConsumption original = consumption(92L, 37L, 2, "33.335000", "66.67");
        when(consumptionRepository.findBySourceTypeAndSourceIdAndSourceLineIdOrderByIdAsc("TEST", 8L, 7L))
                .thenReturn(List.of(original));
        when(consumptionRepository.findByReversalOfConsumptionIdIn(List.of(92L))).thenReturn(List.of());
        when(lotRepository.findByIdForUpdate(37L)).thenReturn(Optional.of(lot));
        when(consumptionRepository.save(any(StockLotConsumption.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        StockLotService service = new StockLotService(
                lotRepository,
                consumptionRepository,
                mock(StockLotCostAdjustmentRepository.class)
        );

        StockLotService.ConsumptionResult result = service.restoreSourceLineConsumption(
                "TEST", 8L, 7L, LocalDate.of(2026, 7, 18), "RESTORE:8:7");

        assertThat(result.totalCost()).isEqualByComparingTo("-66.67");
        assertThat(result.unitCost()).isEqualByComparingTo("33.335000");
        assertThat(result.consumptions()).singleElement()
                .satisfies(reversal -> {
                    assertThat(reversal.getSourceType()).isEqualTo("TEST");
                    assertThat(reversal.getSourceId()).isEqualTo(8L);
                    assertThat(reversal.getSourceLineId()).isEqualTo(7L);
                });
        assertThat(lot.getRemainingQuantity()).isEqualTo(3);
        assertThat(lot.getRemainingCostAmount()).isEqualByComparingTo("100.00");
        verify(lotRepository).findByIdForUpdate(37L);
    }

    private StockLotService.ConsumptionResult consumeOne(
            StockLotService service,
            int availableBefore,
            long sourceLineId
    ) {
        return service.consumeFifo(
                "PART", 9L, 4L, 1, new BigDecimal("33.333333"), availableBefore,
                "TEST", 8L, sourceLineId, LocalDate.of(2026, 7, 18), null
        );
    }

    private StockLot lot(Long id, int original, int remaining, String unitCost) {
        StockLot lot = new StockLot();
        lot.setId(id);
        lot.setOriginalQuantity(original);
        lot.setRemainingQuantity(remaining);
        lot.setUnitCost(new BigDecimal(unitCost));
        BigDecimal totalCost = lot.getUnitCost().multiply(BigDecimal.valueOf(original))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        lot.setOriginalCostAmount(totalCost);
        lot.setRemainingCostAmount(totalCost.multiply(BigDecimal.valueOf(remaining))
                .divide(BigDecimal.valueOf(original), 2, java.math.RoundingMode.HALF_UP));
        lot.setStatus(StockLot.STATUS_OPEN);
        return lot;
    }

    private StockLotConsumption consumption(
            Long id,
            Long stockLotId,
            int quantity,
            String unitCost,
            String totalCost
    ) {
        StockLotConsumption consumption = new StockLotConsumption();
        consumption.setId(id);
        consumption.setStockLotId(stockLotId);
        consumption.setResourceType("PART");
        consumption.setResourceId(9L);
        consumption.setWarehouseId(4L);
        consumption.setSourceType("TEST");
        consumption.setSourceId(8L);
        consumption.setSourceLineId(7L);
        consumption.setQuantity(quantity);
        consumption.setUnitCost(new BigDecimal(unitCost));
        consumption.setTotalCost(new BigDecimal(totalCost));
        return consumption;
    }
}
