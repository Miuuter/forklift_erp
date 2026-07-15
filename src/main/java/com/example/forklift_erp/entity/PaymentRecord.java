package com.example.forklift_erp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "payment_record")
public class PaymentRecord {
    public static final String DIRECTION_RECEIPT = "RECEIPT";
    public static final String DIRECTION_PAYMENT = "PAYMENT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_no", nullable = false, unique = true, length = 80)
    private String paymentNo;

    @Column(nullable = false, length = 20)
    private String direction;

    /**
     * A signed amount: negative receipts/payments are immutable reversals.
     */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @Column(name = "account_name", length = 100)
    private String accountName;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Column(name = "source_type", nullable = false, length = 40)
    private String sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "financial_event_id")
    private Long financialEventId;

    @Column(length = 500)
    private String remark;

    @Column(name = "idempotency_key", length = 160, unique = true)
    private String idempotencyKey;

    @Column(name = "reversal_of_payment_id")
    private Long reversalOfPaymentId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (paymentDate == null) {
            paymentDate = LocalDate.now();
        }
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }
        createdAt = LocalDateTime.now();
    }
}
