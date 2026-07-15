package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.entity.OutboundOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundReceivablePolicyTests {

    private final OutboundReceivablePolicy policy = new OutboundReceivablePolicy();

    @Test
    void applyMarksOrderSettledWhenReceivedAmountCoversReceivable() {
        OutboundOrder order = new OutboundOrder();
        order.setSettlementPrice(new BigDecimal("100.00"));
        order.setReceivedAmount(new BigDecimal("120.00"));

        policy.apply(order);

        assertThat(order.getReceivableAmount()).isEqualByComparingTo("100.00");
        assertThat(order.getReceivedAmount()).isEqualByComparingTo("120.00");
        assertThat(order.getPaymentSettled()).isTrue();
        assertThat(order.getLastPaymentDate()).isNotNull();
    }

    @Test
    void applyFillsReceivedAmountWhenClientExplicitlyMarksSettled() {
        OutboundOrder order = new OutboundOrder();
        order.setReceivableAmount(new BigDecimal("88.50"));
        order.setReceivedAmount(new BigDecimal("10.00"));
        order.setPaymentSettled(true);

        policy.apply(order);

        assertThat(order.getReceivedAmount()).isEqualByComparingTo("88.50");
        assertThat(order.getPaymentSettled()).isTrue();
    }

    @Test
    void applyKeepsUnsettledWhenOutstandingAmountRemains() {
        OutboundOrder order = new OutboundOrder();
        order.setReceivableAmount(new BigDecimal("88.50"));
        order.setReceivedAmount(new BigDecimal("10.00"));

        policy.apply(order);

        assertThat(order.getPaymentSettled()).isFalse();
    }

    @Test
    void applyHonorsExplicitUnsettledChoiceWhenAmountsAreFullyReceived() {
        OutboundOrder order = new OutboundOrder();
        order.setReceivableAmount(new BigDecimal("480.00"));
        order.setReceivedAmount(new BigDecimal("480.00"));
        order.setPaymentSettled(true);

        policy.apply(order, false);

        assertThat(order.getReceivedAmount()).isEqualByComparingTo("480.00");
        assertThat(order.getPaymentSettled()).isFalse();
    }
}
