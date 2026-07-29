package com.example.forklift_erp;

import com.example.forklift_erp.dto.PaymentRecordCreateDTO;
import com.example.forklift_erp.dto.PaymentRecordVO;
import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.entity.PaymentRecord;
import com.example.forklift_erp.repository.OutboundOrderRepository;
import com.example.forklift_erp.service.FinancialEventService;
import com.example.forklift_erp.service.PaymentRecordService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PaymentIdempotencyIntegrationTests extends TestcontainersDatabaseSupport {

    private static final int CONCURRENCY = 20;

    @Autowired
    private PaymentRecordService paymentRecordService;

    @Autowired
    private OutboundOrderRepository outboundOrderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long orderId;
    private String paymentRequestId;
    private String reversalRequestId;
    private final List<String> additionalRequestIds = new ArrayList<>();

    @BeforeEach
    void createPaymentSource() {
        OutboundOrder order = new OutboundOrder();
        order.setOrderNo("IDEMPOTENCY-" + UUID.randomUUID());
        order.setResourceType(OutboundOrder.RESOURCE_PART);
        order.setResourceCode("IDEMPOTENCY-PART");
        order.setResourceName("Payment idempotency test part");
        order.setQuantity(1);
        order.setCustomerName("Payment idempotency test customer");
        order.setReceivableAmount(new BigDecimal("200.00"));
        orderId = outboundOrderRepository.saveAndFlush(order).getId();
        paymentRequestId = "payment-" + UUID.randomUUID();
        reversalRequestId = "reversal-" + UUID.randomUUID();
    }

    @AfterEach
    void cleanPaymentFacts() {
        if (orderId == null) {
            return;
        }
        jdbcTemplate.update(
                "DELETE FROM operation_audit_log WHERE source_type = 'OUTBOUND_ORDER' AND source_id = ?",
                orderId
        );
        jdbcTemplate.update(
                "DELETE FROM payment_record WHERE source_type = 'OUTBOUND_ORDER' AND source_id = ? AND reversal_of_payment_id IS NOT NULL",
                orderId
        );
        jdbcTemplate.update(
                "DELETE FROM payment_record WHERE source_type = 'OUTBOUND_ORDER' AND source_id = ?",
                orderId
        );
        jdbcTemplate.update(
                """
                DELETE FROM financial_event
                WHERE source_type = 'OUTBOUND_ORDER' AND source_id = ?
                  AND reversal_of_event_id IS NOT NULL
                """,
                orderId
        );
        jdbcTemplate.update(
                """
                DELETE FROM financial_event
                WHERE source_type = 'OUTBOUND_ORDER' AND source_id = ?
                """,
                orderId
        );
        jdbcTemplate.update(
                "DELETE FROM request_idempotency WHERE request_id IN (?, ?)",
                paymentRequestId,
                reversalRequestId
        );
        for (String requestId : additionalRequestIds) {
            jdbcTemplate.update(
                    "DELETE FROM request_idempotency WHERE scope = 'PAYMENT_CREATE' AND request_id = ?",
                    requestId
            );
        }
        additionalRequestIds.clear();
        outboundOrderRepository.deleteById(orderId);
    }

    @Test
    @Timeout(30)
    void concurrentPaymentAndReversalRequestsConvergeToSingleFacts() throws Exception {
        List<PaymentRecordVO> payments = invokeConcurrently(() -> paymentRecordService.create(paymentRequest()));

        Set<Long> paymentIds = payments.stream().map(PaymentRecordVO::getId).collect(java.util.stream.Collectors.toSet());
        assertThat(paymentIds).hasSize(1);
        Long paymentId = paymentIds.iterator().next();
        Long originalFinancialEventId = jdbcTemplate.queryForObject(
                "SELECT financial_event_id FROM payment_record WHERE id = ?",
                Long.class,
                paymentId
        );
        assertThat(originalFinancialEventId).isNotNull();
        assertThat(countPaymentRows("PAYMENT-REQUEST:" + paymentRequestId)).isEqualTo(1);
        assertThat(countFinancialRows("PAYMENT-REQUEST:" + paymentRequestId + ":EVENT")).isEqualTo(1);
        assertThat(countClaims("PAYMENT_CREATE", paymentRequestId)).isEqualTo(1);

        List<PaymentRecordVO> reversals = invokeConcurrently(() ->
                paymentRecordService.reverse(paymentId, reversalRequestId, "Concurrent reversal"));

        assertThat(reversals.stream().map(PaymentRecordVO::getId).collect(java.util.stream.Collectors.toSet()))
                .hasSize(1);
        assertThat(countPaymentRows("PAYMENT-REVERSAL-REQUEST:" + reversalRequestId)).isEqualTo(1);
        assertThat(countFinancialRows("PAYMENT-REVERSAL-REQUEST:" + reversalRequestId + ":EVENT")).isEqualTo(1);
        assertThat(countClaims("PAYMENT_REVERSE", reversalRequestId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reversal_of_financial_event_id FROM payment_record WHERE id = ?",
                Long.class,
                reversals.getFirst().getId()
        )).isEqualTo(originalFinancialEventId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reversal_of_event_id FROM financial_event WHERE id = "
                        + "(SELECT financial_event_id FROM payment_record WHERE id = ?)",
                Long.class,
                reversals.getFirst().getId()
        )).isEqualTo(originalFinancialEventId);
    }

    @Test
    @Timeout(30)
    void concurrentDistinctPaymentsKeepLedgerEventsAndOrderProjectionEqual() throws Exception {
        for (int index = 0; index < CONCURRENCY; index++) {
            additionalRequestIds.add("distinct-payment-" + UUID.randomUUID());
        }

        List<PaymentRecordVO> payments = invokeConcurrently(index ->
                paymentRecordService.create(paymentRequest(additionalRequestIds.get(index))));

        assertThat(payments).hasSize(CONCURRENCY);
        assertThat(payments.stream().map(PaymentRecordVO::getId).collect(java.util.stream.Collectors.toSet()))
                .hasSize(CONCURRENCY);
        BigDecimal paymentTotal = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(amount), 0)
                FROM payment_record
                WHERE source_type = 'OUTBOUND_ORDER' AND source_id = ? AND direction = 'RECEIPT'
                """,
                BigDecimal.class,
                orderId
        );
        BigDecimal eventTotal = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(amount), 0)
                FROM financial_event
                WHERE source_type = 'OUTBOUND_ORDER' AND source_id = ? AND event_type = 'CASH_RECEIPT'
                """,
                BigDecimal.class,
                orderId
        );
        OutboundOrder refreshed = outboundOrderRepository.findById(orderId).orElseThrow();

        assertThat(paymentTotal).isEqualByComparingTo("200.00");
        assertThat(eventTotal).isEqualByComparingTo("200.00");
        assertThat(refreshed.getReceivedAmount()).isEqualByComparingTo("200.00");
        assertThat(refreshed.getPaymentSettled()).isTrue();
    }

    private PaymentRecordCreateDTO paymentRequest() {
        return paymentRequest(paymentRequestId);
    }

    private PaymentRecordCreateDTO paymentRequest(String requestId) {
        PaymentRecordCreateDTO request = new PaymentRecordCreateDTO();
        request.setRequestId(requestId);
        request.setDirection(PaymentRecord.DIRECTION_RECEIPT);
        request.setAmount(new BigDecimal("10.00"));
        request.setSourceType(FinancialEventService.SOURCE_OUTBOUND_ORDER);
        request.setSourceId(orderId);
        request.setRemark("Concurrent payment");
        return request;
    }

    private int countPaymentRows(String requestId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_record WHERE request_id = ?",
                Integer.class,
                requestId
        );
    }

    private int countFinancialRows(String idempotencyKey) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM financial_event WHERE idempotency_key = ?",
                Integer.class,
                idempotencyKey
        );
    }

    private int countClaims(String scope, String requestId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_idempotency WHERE scope = ? AND request_id = ?",
                Integer.class,
                scope,
                requestId
        );
    }

    private List<PaymentRecordVO> invokeConcurrently(Supplier<PaymentRecordVO> operation) throws Exception {
        return invokeConcurrently(index -> operation.get());
    }

    private List<PaymentRecordVO> invokeConcurrently(IntFunction<PaymentRecordVO> operation) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch ready = new CountDownLatch(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<PaymentRecordVO>> tasks = java.util.stream.IntStream.range(0, CONCURRENCY)
                    .mapToObj(index -> (Callable<PaymentRecordVO>) () -> {
                        ready.countDown();
                        start.await();
                        return operation.apply(index);
                    })
                    .toList();
            List<Future<PaymentRecordVO>> futures = tasks.stream().map(executor::submit).toList();
            ready.await();
            start.countDown();
            return futures.stream().map(this::result).toList();
        } finally {
            executor.shutdownNow();
        }
    }

    private PaymentRecordVO result(Future<PaymentRecordVO> future) {
        try {
            return future.get();
        } catch (Exception error) {
            throw new AssertionError("Concurrent payment operation failed", error);
        }
    }
}
