package com.example.forklift_erp.service;

import com.example.forklift_erp.entity.FinancialEvent;
import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.repository.FinancialEventRepository;
import com.example.forklift_erp.repository.PaymentRecordRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinancialEventServiceTests {

    @Test
    void replaceSalesPostingDoesNotReverseTheSameRevisionTwice() {
        FinancialEventRepository financialEventRepository = mock(FinancialEventRepository.class);
        PaymentRecordRepository paymentRecordRepository = mock(PaymentRecordRepository.class);
        FinancialEventService service = new FinancialEventService(financialEventRepository, paymentRecordRepository);
        OutboundOrder order = new OutboundOrder();
        order.setId(8L);
        order.setVersion(4L);
        order.setOrderNo("OO-008");
        order.setSalesDate(LocalDate.of(2026, 7, 1));
        FinancialEvent existingRevision = new FinancialEvent();
        when(financialEventRepository.findByIdempotencyKey("SALE:8:REVISION:4:AR"))
                .thenReturn(Optional.of(existingRevision));

        service.replaceSalesPosting(order, new BigDecimal("90.00"), false);

        verify(financialEventRepository).findByIdempotencyKey("SALE:8:REVISION:4:AR");
        verify(financialEventRepository, never()).findBySourceTypeAndSourceIdAndEventTypeInOrderByIdAsc(any(), any(), any());
        verify(financialEventRepository, never()).save(any());
    }
}
