package com.example.forklift_erp.service;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.dto.PaymentRecordCreateDTO;
import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.entity.PaymentRecord;
import com.example.forklift_erp.entity.RepairRecord;
import com.example.forklift_erp.entity.ModificationWorkOrder;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.ModificationWorkOrderRepository;
import com.example.forklift_erp.repository.OutboundOrderRepository;
import com.example.forklift_erp.repository.PaymentRecordRepository;
import com.example.forklift_erp.repository.PurchaseOrderRepository;
import com.example.forklift_erp.repository.RentalBillRepository;
import com.example.forklift_erp.repository.RepairRecordRepository;
import com.example.forklift_erp.service.impl.OutboundReceivablePolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class PaymentRecordServiceTests {

    @Test
    void rejectsUnknownPaymentSourceBeforePostingCash() {
        Fixture fixture = fixture();
        PaymentRecordCreateDTO request = request("RECEIPT", "UNKNOWN", 99L);

        assertThatThrownBy(() -> fixture.service.create(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResultCode.PARAM_ERROR.getCode()))
                .hasMessage("Unsupported payment source type: UNKNOWN");

        verifyNoInteractions(fixture.financialEventService);
    }

    @Test
    void purchaseOrderRejectsReceiptDirection() {
        Fixture fixture = fixture();
        when(fixture.purchaseOrderRepository.existsById(7L)).thenReturn(true);
        PaymentRecordCreateDTO request = request(
                PaymentRecord.DIRECTION_RECEIPT,
                FinancialEventService.SOURCE_PURCHASE_ORDER,
                7L
        );

        assertThatThrownBy(() -> fixture.service.create(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResultCode.PARAM_ERROR.getCode()))
                .hasMessage("Purchase orders only accept PAYMENT records");

        verifyNoInteractions(fixture.financialEventService);
    }

    @Test
    void pendingRepairRejectsPaymentBeforeCashPosting() {
        Fixture fixture = fixture();
        RepairRecord repair = new RepairRecord();
        repair.setId(8L);
        repair.setStatus("PENDING");
        when(fixture.repairRecordRepository.existsById(8L)).thenReturn(true);
        when(fixture.repairRecordRepository.findById(8L)).thenReturn(java.util.Optional.of(repair));

        assertThatThrownBy(() -> fixture.service.create(request(
                PaymentRecord.DIRECTION_RECEIPT,
                FinancialEventService.SOURCE_REPAIR,
                8L
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Repair payments can only be recorded after the repair is completed");

        verifyNoInteractions(fixture.financialEventService);
    }

    @Test
    void preSaleModificationRejectsCustomerReceipt() {
        Fixture fixture = fixture();
        ModificationWorkOrder order = new ModificationWorkOrder();
        order.setId(9L);
        order.setStatus("COMPLETED");
        order.setWorkOrderType("PRE_SALE");
        when(fixture.modificationWorkOrderRepository.existsById(9L)).thenReturn(true);
        when(fixture.modificationWorkOrderRepository.findById(9L))
                .thenReturn(java.util.Optional.of(order));

        assertThatThrownBy(() -> fixture.service.create(request(
                PaymentRecord.DIRECTION_RECEIPT,
                "MODIFICATION_WORK_ORDER",
                9L
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Modification receipts require a completed after-sale work order");

        verifyNoInteractions(fixture.financialEventService);
    }

    @Test
    void reversalRecordCannotBeReversedAgain() {
        Fixture fixture = fixture();
        PaymentRecord reversal = new PaymentRecord();
        reversal.setId(21L);
        reversal.setAmount(new BigDecimal("-10.00"));
        reversal.setReversalOfPaymentId(20L);
        when(fixture.paymentRecordRepository.findById(21L))
                .thenReturn(java.util.Optional.of(reversal));

        assertThatThrownBy(() -> fixture.service.reverse(21L, "reverse-21", null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Only an original positive payment can be reversed");

        verifyNoInteractions(fixture.financialEventService);
    }

    @Test
    void reversingLatestOutboundReceiptKeepsOnlyNetReceiptAndClearsSettlement() {
        Fixture fixture = fixture();
        OutboundOrder order = new OutboundOrder();
        order.setId(31L);
        order.setReceivableAmount(new BigDecimal("540.00"));
        order.setReceivedAmount(new BigDecimal("540.00"));
        order.setPaymentSettled(true);
        order.setLastPaymentDate(LocalDate.of(2026, 7, 2));

        PaymentRecord deposit = payment(41L, "200.00", LocalDate.of(2026, 7, 1));
        PaymentRecord balance = payment(42L, "340.00", LocalDate.of(2026, 7, 2));
        PaymentRecord reversal = payment(43L, "-340.00", LocalDate.of(2026, 7, 2));

        when(fixture.paymentRecordRepository.findById(42L)).thenReturn(Optional.of(balance));
        when(fixture.paymentRecordRepository.findByReversalOfPaymentId(42L)).thenReturn(Optional.empty());
        when(fixture.paymentRecordRepository.totalForSource(
                FinancialEventService.SOURCE_OUTBOUND_ORDER,
                31L,
                PaymentRecord.DIRECTION_RECEIPT
        )).thenReturn(new BigDecimal("540.00"));
        when(fixture.financialEventService.recordPayment(
                PaymentRecord.DIRECTION_RECEIPT,
                new BigDecimal("-340.00"),
                LocalDate.of(2026, 7, 2),
                null,
                null,
                FinancialEventService.SOURCE_OUTBOUND_ORDER,
                31L,
                "reverse balance",
                "PAYMENT-REVERSAL-REQUEST:reverse-42"
        )).thenReturn(reversal);
        when(fixture.outboundOrderRepository.findByIdForUpdate(31L)).thenReturn(Optional.of(order));
        when(fixture.financialEventService.receiptTotal(
                FinancialEventService.SOURCE_OUTBOUND_ORDER, 31L
        )).thenReturn(new BigDecimal("200.00"));
        when(fixture.paymentRecordRepository.findBySourceTypeAndSourceIdOrderByPaymentDateAscIdAsc(
                FinancialEventService.SOURCE_OUTBOUND_ORDER, 31L
        )).thenReturn(List.of(deposit, balance, reversal));

        fixture.service.reverse(42L, "reverse-42", "reverse balance");

        assertThat(order.getReceivedAmount()).isEqualByComparingTo("200.00");
        assertThat(order.getPaymentSettled()).isFalse();
        assertThat(order.getLastPaymentDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(reversal.getReversalOfPaymentId()).isEqualTo(42L);
    }

    @Test
    void duplicateClaimReturnsCommittedPayment() {
        Fixture fixture = fixture();
        PaymentRecord existing = payment(51L, "10.00", LocalDate.of(2026, 7, 15));
        existing.setRequestId("PAYMENT-REQUEST:request-OUTBOUND_ORDER-31");
        when(fixture.outboundOrderRepository.existsById(31L)).thenReturn(true);
        when(fixture.requestIdempotencyGuard.claim(anyString(), anyString())).thenReturn(false);
        when(fixture.paymentRecordRepository.findByRequestIdForUpdate(existing.getRequestId()))
                .thenReturn(Optional.of(existing));

        var result = fixture.service.create(request(
                PaymentRecord.DIRECTION_RECEIPT,
                FinancialEventService.SOURCE_OUTBOUND_ORDER,
                31L
        ));

        assertThat(result.getId()).isEqualTo(51L);
        verifyNoInteractions(fixture.financialEventService);
    }

    private PaymentRecord payment(Long id, String amount, LocalDate date) {
        PaymentRecord record = new PaymentRecord();
        record.setId(id);
        record.setDirection(PaymentRecord.DIRECTION_RECEIPT);
        record.setAmount(new BigDecimal(amount));
        record.setPaymentDate(date);
        record.setSourceType(FinancialEventService.SOURCE_OUTBOUND_ORDER);
        record.setSourceId(31L);
        return record;
    }

    private PaymentRecordCreateDTO request(String direction, String sourceType, Long sourceId) {
        PaymentRecordCreateDTO request = new PaymentRecordCreateDTO();
        request.setDirection(direction);
        request.setSourceType(sourceType);
        request.setSourceId(sourceId);
        request.setAmount(new BigDecimal("10.00"));
        request.setRequestId("request-" + sourceType + "-" + sourceId);
        return request;
    }

    private Fixture fixture() {
        PaymentRecordRepository paymentRecordRepository = mock(PaymentRecordRepository.class);
        FinancialEventService financialEventService = mock(FinancialEventService.class);
        OutboundOrderRepository outboundOrderRepository = mock(OutboundOrderRepository.class);
        PurchaseOrderRepository purchaseOrderRepository = mock(PurchaseOrderRepository.class);
        RepairRecordRepository repairRecordRepository = mock(RepairRecordRepository.class);
        RentalBillRepository rentalBillRepository = mock(RentalBillRepository.class);
        ModificationWorkOrderRepository modificationWorkOrderRepository =
                mock(ModificationWorkOrderRepository.class);
        RequestIdempotencyGuard requestIdempotencyGuard = mock(RequestIdempotencyGuard.class);
        when(requestIdempotencyGuard.claim(anyString(), anyString())).thenReturn(true);
        PaymentRecordService service = new PaymentRecordService(
                paymentRecordRepository,
                financialEventService,
                outboundOrderRepository,
                new OutboundReceivablePolicy(),
                purchaseOrderRepository,
                repairRecordRepository,
                rentalBillRepository,
                modificationWorkOrderRepository,
                mock(OperationAuditService.class),
                requestIdempotencyGuard
        );
        return new Fixture(
                service,
                financialEventService,
                paymentRecordRepository,
                outboundOrderRepository,
                purchaseOrderRepository,
                repairRecordRepository,
                modificationWorkOrderRepository,
                requestIdempotencyGuard
        );
    }

    private record Fixture(
            PaymentRecordService service,
            FinancialEventService financialEventService,
            PaymentRecordRepository paymentRecordRepository,
            OutboundOrderRepository outboundOrderRepository,
            PurchaseOrderRepository purchaseOrderRepository,
            RepairRecordRepository repairRecordRepository,
            ModificationWorkOrderRepository modificationWorkOrderRepository,
            RequestIdempotencyGuard requestIdempotencyGuard
    ) {
    }
}
