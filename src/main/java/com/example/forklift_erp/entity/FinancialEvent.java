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

/**
 * Append-only financial fact. Corrections are represented by a separate,
 * signed reversal event instead of updating an already posted event.
 */
@Data
@Entity
@Table(name = "financial_event")
public class FinancialEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_no", nullable = false, unique = true, length = 80)
    private String eventNo;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "source_type", nullable = false, length = 40)
    private String sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "source_line_id")
    private Long sourceLineId;

    @Column(name = "counterparty_type", length = 30)
    private String counterpartyType;

    @Column(name = "counterparty_id")
    private Long counterpartyId;

    @Column(name = "counterparty_name", length = 120)
    private String counterpartyName;

    @Column(length = 500)
    private String remark;

    @Column(name = "idempotency_key", length = 160, unique = true)
    private String idempotencyKey;

    @Column(name = "reversal_of_event_id")
    private Long reversalOfEventId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (businessDate == null) {
            businessDate = LocalDate.now();
        }
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }
        createdAt = LocalDateTime.now();
    }
}
