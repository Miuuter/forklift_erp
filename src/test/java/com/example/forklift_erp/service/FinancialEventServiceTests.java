package com.example.forklift_erp.service;

import com.example.forklift_erp.entity.FinancialEvent;
import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.entity.PaymentRecord;
import com.example.forklift_erp.repository.FinancialEventRepository;
import com.example.forklift_erp.repository.PaymentRecordRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    void sourceReversalCopiesIdentityFromTheOriginalEvent() {
        FinancialEventRepository financialEventRepository = mock(FinancialEventRepository.class);
        PaymentRecordRepository paymentRecordRepository = mock(PaymentRecordRepository.class);
        FinancialEvent original = new FinancialEvent();
        original.setId(41L);
        original.setEventType("REVENUE");
        original.setAmount(new BigDecimal("125.50"));
        original.setSourceType("OUTBOUND_ORDER");
        original.setSourceId(17L);
        original.setSourceLineId(23L);
        when(financialEventRepository.findBySourceTypeAndSourceIdAndEventTypeInOrderByIdAsc(
                "CALLER_VALUE", 999L, List.of("REVENUE")))
                .thenReturn(List.of(original));
        when(financialEventRepository.findByIdempotencyKey("REV:41"))
                .thenReturn(Optional.empty());
        when(financialEventRepository.save(any(FinancialEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        FinancialEventService service = new FinancialEventService(
                financialEventRepository, paymentRecordRepository);

        service.reverseSourceEvents(
                "CALLER_VALUE",
                999L,
                List.of("REVENUE"),
                LocalDate.of(2026, 7, 19),
                "Correction",
                "REV"
        );

        ArgumentCaptor<FinancialEvent> captor = ArgumentCaptor.forClass(FinancialEvent.class);
        verify(financialEventRepository).save(captor.capture());
        FinancialEvent reversal = captor.getValue();
        assertThat(reversal.getEventType()).isEqualTo("REVENUE");
        assertThat(reversal.getAmount()).isEqualByComparingTo("-125.50");
        assertThat(reversal.getSourceType()).isEqualTo("OUTBOUND_ORDER");
        assertThat(reversal.getSourceId()).isEqualTo(17L);
        assertThat(reversal.getSourceLineId()).isEqualTo(23L);
        assertThat(reversal.getReversalOfEventId()).isEqualTo(41L);
    }

    @Test
    void paymentReversalBindsBothFactsToTheOriginalFinancialEvent() {
        FinancialEventRepository financialEventRepository = mock(FinancialEventRepository.class);
        PaymentRecordRepository paymentRecordRepository = mock(PaymentRecordRepository.class);
        when(paymentRecordRepository.findByIdempotencyKey("PAYMENT-REVERSAL-REQUEST:7"))
                .thenReturn(Optional.empty());
        when(financialEventRepository.findByIdempotencyKey(
                "PAYMENT-REVERSAL-REQUEST:7:EVENT"))
                .thenReturn(Optional.empty());
        when(financialEventRepository.save(any(FinancialEvent.class)))
                .thenAnswer(invocation -> {
                    FinancialEvent event = invocation.getArgument(0);
                    event.setId(201L);
                    return event;
                });
        when(paymentRecordRepository.save(any(com.example.forklift_erp.entity.PaymentRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        FinancialEventService service = new FinancialEventService(
                financialEventRepository, paymentRecordRepository);

        var payment = service.recordPayment(
                "RECEIPT",
                new BigDecimal("-10.00"),
                LocalDate.of(2026, 7, 19),
                null,
                null,
                "OUTBOUND_ORDER",
                17L,
                "Reverse payment",
                "PAYMENT-REVERSAL-REQUEST:7",
                7L,
                101L
        );

        ArgumentCaptor<FinancialEvent> eventCaptor = ArgumentCaptor.forClass(FinancialEvent.class);
        verify(financialEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getReversalOfEventId()).isEqualTo(101L);
        assertThat(payment.getFinancialEventId()).isEqualTo(201L);
        assertThat(payment.getReversalOfPaymentId()).isEqualTo(7L);
        assertThat(payment.getReversalOfFinancialEventId()).isEqualTo(101L);
    }

    @Test
    void financialEventIdempotencyKeyCannotBeReusedForDifferentPayload() {
        FinancialEventRepository financialEventRepository = mock(FinancialEventRepository.class);
        PaymentRecordRepository paymentRecordRepository = mock(PaymentRecordRepository.class);
        FinancialEvent existing = new FinancialEvent();
        existing.setId(301L);
        existing.setEventType("CASH_RECEIPT");
        existing.setAmount(new BigDecimal("10.00"));
        existing.setBusinessDate(LocalDate.of(2026, 7, 19));
        existing.setSourceType("OUTBOUND_ORDER");
        existing.setSourceId(17L);
        existing.setRemark("original");
        when(financialEventRepository.findByIdempotencyKey("EVENT-KEY"))
                .thenReturn(Optional.of(existing));
        FinancialEventService service = new FinancialEventService(
                financialEventRepository, paymentRecordRepository);

        assertThatThrownBy(() -> service.post(
                "CASH_RECEIPT",
                new BigDecimal("11.00"),
                LocalDate.of(2026, 7, 19),
                "OUTBOUND_ORDER",
                17L,
                null,
                null,
                null,
                null,
                "original",
                "EVENT-KEY"
        ))
                .isInstanceOf(com.example.forklift_erp.exception.BusinessException.class)
                .hasMessage("Financial event idempotency key is already bound to a different payload");
    }

    @Test
    void nonReversalFinancialEventCannotCarryANegativeAmount() {
        FinancialEventRepository financialEventRepository = mock(FinancialEventRepository.class);
        PaymentRecordRepository paymentRecordRepository = mock(PaymentRecordRepository.class);
        FinancialEventService service = new FinancialEventService(
                financialEventRepository, paymentRecordRepository);

        assertThatThrownBy(() -> service.post(
                "REVENUE",
                new BigDecimal("-0.01"),
                LocalDate.of(2026, 7, 19),
                "OUTBOUND_ORDER",
                17L,
                null,
                null,
                null,
                null,
                null,
                "NEGATIVE-ORIGINAL"
        ))
                .isInstanceOf(com.example.forklift_erp.exception.BusinessException.class)
                .hasMessage("A non-reversal financial event cannot have a negative amount");
        verify(financialEventRepository, never()).save(any());
    }

    @Test
    void cashFinancialEventCannotCarryASourceLineIdentity() {
        FinancialEventRepository financialEventRepository = mock(FinancialEventRepository.class);
        PaymentRecordRepository paymentRecordRepository = mock(PaymentRecordRepository.class);
        FinancialEventService service = new FinancialEventService(
                financialEventRepository, paymentRecordRepository);

        assertThatThrownBy(() -> service.post(
                "CASH_RECEIPT",
                new BigDecimal("10.00"),
                LocalDate.of(2026, 7, 19),
                "OUTBOUND_ORDER",
                17L,
                99L,
                null,
                null,
                null,
                null,
                "CASH-LINE"
        ))
                .isInstanceOf(com.example.forklift_erp.exception.BusinessException.class)
                .hasMessage("Cash financial events cannot be bound to a source detail line");
        verify(financialEventRepository, never()).save(any());
    }

    @Test
    void paymentIdempotencyWithDefaultDateReturnsTheOriginalFact() {
        FinancialEventRepository financialEventRepository = mock(FinancialEventRepository.class);
        PaymentRecordRepository paymentRecordRepository = mock(PaymentRecordRepository.class);
        PaymentRecord existing = payment(301L, "10.00", 401L, LocalDate.of(2026, 7, 18));
        existing.setAccountName("cash");
        existing.setPaymentMethod("bank");
        existing.setRemark("original");
        when(paymentRecordRepository.findByIdempotencyKey("PAYMENT-DATE-DEFAULT"))
                .thenReturn(Optional.of(existing));
        FinancialEventService service = new FinancialEventService(
                financialEventRepository, paymentRecordRepository);

        PaymentRecord result = service.recordPayment(
                PaymentRecord.DIRECTION_RECEIPT,
                new BigDecimal("10.00"),
                null,
                "cash",
                "bank",
                "OUTBOUND_ORDER",
                17L,
                "original",
                "PAYMENT-DATE-DEFAULT"
        );

        assertThat(result).isSameAs(existing);
        verify(financialEventRepository, never()).save(any());
        verify(paymentRecordRepository, never()).save(any());
    }

    @Test
    void receiptTargetReductionUsesWholeReversalsAndAPositiveTail() {
        FinancialEventRepository financialEventRepository = mock(FinancialEventRepository.class);
        PaymentRecordRepository paymentRecordRepository = mock(PaymentRecordRepository.class);
        PaymentRecord older = payment(11L, "60.00", 111L, LocalDate.of(2026, 7, 1));
        PaymentRecord latest = payment(12L, "40.00", 112L, LocalDate.of(2026, 7, 2));
        when(paymentRecordRepository.findBySourceTypeAndSourceIdAndDirectionForUpdate(
                "OUTBOUND_ORDER", 17L, PaymentRecord.DIRECTION_RECEIPT))
                .thenReturn(List.of(older, latest));
        when(financialEventRepository.findByIdempotencyKey(any()))
                .thenReturn(Optional.empty());
        when(paymentRecordRepository.findByIdempotencyKey(any()))
                .thenReturn(Optional.empty());
        when(financialEventRepository.save(any(FinancialEvent.class)))
                .thenAnswer(invocation -> {
                    FinancialEvent event = invocation.getArgument(0);
                    event.setId(event.getReversalOfEventId() == null ? 201L : 202L);
                    return event;
                });
        when(paymentRecordRepository.save(any(PaymentRecord.class)))
                .thenAnswer(invocation -> {
                    PaymentRecord payment = invocation.getArgument(0);
                    payment.setId(payment.getAmount().signum() < 0 ? 31L : 32L);
                    return payment;
                });
        FinancialEventService service = new FinancialEventService(
                financialEventRepository, paymentRecordRepository);

        PaymentRecord result = service.syncReceiptToTarget(
                "OUTBOUND_ORDER",
                17L,
                new BigDecimal("70.00"),
                LocalDate.of(2026, 7, 3),
                null,
                null,
                "Lower target",
                "SYNC-17"
        );

        ArgumentCaptor<PaymentRecord> captor = ArgumentCaptor.forClass(PaymentRecord.class);
        verify(paymentRecordRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        PaymentRecord reversal = captor.getAllValues().get(0);
        PaymentRecord tail = captor.getAllValues().get(1);
        assertThat(reversal.getAmount()).isEqualByComparingTo("-40.00");
        assertThat(reversal.getReversalOfPaymentId()).isEqualTo(12L);
        assertThat(reversal.getReversalOfFinancialEventId()).isEqualTo(112L);
        assertThat(tail.getAmount()).isEqualByComparingTo("10.00");
        assertThat(tail.getReversalOfPaymentId()).isNull();
        assertThat(result).isSameAs(tail);
    }

    @Test
    void receiptTargetReductionRejectsWhenNoCompleteOriginalCoversDelta() {
        FinancialEventRepository financialEventRepository = mock(FinancialEventRepository.class);
        PaymentRecordRepository paymentRecordRepository = mock(PaymentRecordRepository.class);
        PaymentRecord original = payment(13L, "10.00", 113L, LocalDate.of(2026, 7, 1));
        PaymentRecord inconsistentReversal = payment(14L, "-1.00", 114L, LocalDate.of(2026, 7, 2));
        inconsistentReversal.setReversalOfPaymentId(13L);
        when(paymentRecordRepository.findBySourceTypeAndSourceIdAndDirectionForUpdate(
                "OUTBOUND_ORDER", 17L, PaymentRecord.DIRECTION_RECEIPT))
                .thenReturn(List.of(original, inconsistentReversal));
        FinancialEventService service = new FinancialEventService(
                financialEventRepository, paymentRecordRepository);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.syncReceiptToTarget(
                        "OUTBOUND_ORDER", 17L, new BigDecimal("0.00"),
                        LocalDate.of(2026, 7, 3), null, null, null, "SYNC-13"))
                .isInstanceOf(com.example.forklift_erp.exception.BusinessException.class)
                .hasMessage("Receipt target cannot be reduced because no complete payment reversal covers the delta");
    }

    private PaymentRecord payment(Long id, String amount, Long eventId, LocalDate date) {
        PaymentRecord payment = new PaymentRecord();
        payment.setId(id);
        payment.setAmount(new BigDecimal(amount));
        payment.setFinancialEventId(eventId);
        payment.setSourceType("OUTBOUND_ORDER");
        payment.setSourceId(17L);
        payment.setDirection(PaymentRecord.DIRECTION_RECEIPT);
        payment.setPaymentDate(date);
        return payment;
    }
}
