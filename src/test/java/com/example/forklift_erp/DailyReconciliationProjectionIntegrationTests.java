package com.example.forklift_erp;

import com.example.forklift_erp.constant.FinancialEventType;
import com.example.forklift_erp.entity.FinancialEvent;
import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.repository.FinancialEventRepository;
import com.example.forklift_erp.repository.OutboundOrderRepository;
import com.example.forklift_erp.service.DailyReconciliationService;
import com.example.forklift_erp.service.FinancialEventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DailyReconciliationProjectionIntegrationTests extends TestcontainersDatabaseSupport {

    @Autowired
    private DailyReconciliationService reconciliationService;

    @Autowired
    private OutboundOrderRepository outboundOrderRepository;

    @Autowired
    private FinancialEventRepository financialEventRepository;

    private final List<Long> financialEventIds = new ArrayList<>();
    private Long orderId;

    @AfterEach
    void cleanFacts() {
        financialEventRepository.deleteAllByIdInBatch(financialEventIds);
        financialEventIds.clear();
        if (orderId != null) {
            outboundOrderRepository.deleteById(orderId);
            orderId = null;
        }
    }

    @Test
    void aggregatesSalesFactsWithoutLoadingAllEntities() {
        LocalDate activityDate = LocalDate.of(2092, 7, 15);
        OutboundOrder order = new OutboundOrder();
        order.setOrderNo("RECON-" + UUID.randomUUID());
        order.setResourceType(OutboundOrder.RESOURCE_PART);
        order.setResourceCode("RECON-PART");
        order.setResourceName("Reconciliation part");
        order.setQuantity(1);
        order.setCustomerName("Reconciliation customer");
        orderId = outboundOrderRepository.saveAndFlush(order).getId();

        saveEvent(FinancialEventType.ACCOUNTS_RECEIVABLE, "100.00", activityDate, "AR");
        saveEvent(FinancialEventType.CASH_RECEIPT, "40.00", activityDate, "RECEIPT");

        var result = reconciliationService.reconcile(activityDate);

        assertThat(result.getSales()).filteredOn(row -> orderId.equals(row.getOutboundOrderId()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getReceivable()).isEqualByComparingTo("100.00");
                    assertThat(row.getReceipts()).isEqualByComparingTo("40.00");
                    assertThat(row.getUnpaid()).isEqualByComparingTo("60.00");
                    assertThat(row.getStatus()).isEqualTo("OUTSTANDING");
                });
    }

    private void saveEvent(String eventType, String amount, LocalDate date, String suffix) {
        FinancialEvent event = new FinancialEvent();
        event.setEventNo("RECON-FE-" + UUID.randomUUID());
        event.setEventType(eventType);
        event.setAmount(new BigDecimal(amount));
        event.setBusinessDate(date);
        event.setSourceType(FinancialEventService.SOURCE_OUTBOUND_ORDER);
        event.setSourceId(orderId);
        event.setIdempotencyKey("RECON-" + orderId + "-" + suffix);
        financialEventIds.add(financialEventRepository.saveAndFlush(event).getId());
    }
}
