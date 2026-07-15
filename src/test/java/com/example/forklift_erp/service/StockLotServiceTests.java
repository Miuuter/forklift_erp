package com.example.forklift_erp.service;

import com.example.forklift_erp.entity.StockLot;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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

    private StockLot lot(Long id, int original, int remaining, String unitCost) {
        StockLot lot = new StockLot();
        lot.setId(id);
        lot.setOriginalQuantity(original);
        lot.setRemainingQuantity(remaining);
        lot.setUnitCost(new BigDecimal(unitCost));
        lot.setStatus(StockLot.STATUS_OPEN);
        return lot;
    }
}
