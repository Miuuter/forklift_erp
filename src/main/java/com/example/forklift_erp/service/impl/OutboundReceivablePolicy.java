package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.util.MoneyValues;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

@Component
public class OutboundReceivablePolicy {

    public void apply(OutboundOrder order) {
        Boolean explicitSettlement = Boolean.TRUE.equals(order.getPaymentSettled()) ? Boolean.TRUE : null;
        apply(order, explicitSettlement);
    }

    public void apply(OutboundOrder order, Boolean explicitPaymentSettled) {
        BigDecimal receivable = MoneyValues.firstNonNegativeOrNull(
                order.getReceivableAmount(),
                order.getSettlementPrice(),
                BigDecimal.ZERO
        );
        BigDecimal received = MoneyValues.zeroIfNullOrNegative(order.getReceivedAmount());
        order.setReceivableAmount(MoneyValues.zeroIfNullOrNegative(receivable));
        order.setReceivedAmount(received);

        if (Boolean.TRUE.equals(explicitPaymentSettled)
                && received.compareTo(order.getReceivableAmount()) < 0) {
            order.setReceivedAmount(order.getReceivableAmount());
        }
        if (explicitPaymentSettled != null) {
            order.setPaymentSettled(explicitPaymentSettled);
        } else if (order.getReceivableAmount().signum() > 0
                && order.getReceivedAmount().compareTo(order.getReceivableAmount()) >= 0) {
            order.setPaymentSettled(true);
        } else if (order.getReceivableAmount().subtract(order.getReceivedAmount()).signum() > 0) {
            order.setPaymentSettled(false);
        }
        if (Boolean.TRUE.equals(order.getPaymentSettled()) && order.getLastPaymentDate() == null) {
            order.setLastPaymentDate(LocalDate.now());
        }
    }
}
