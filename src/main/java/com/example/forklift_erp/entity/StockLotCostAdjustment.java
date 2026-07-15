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
 * Immutable audit trail for cost capitalized into an open serialized-asset lot.
 */
@Data
@Entity
@Table(name = "stock_lot_cost_adjustment")
public class StockLotCostAdjustment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_lot_id", nullable = false)
    private Long stockLotId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "source_type", nullable = false, length = 40)
    private String sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "source_line_id")
    private Long sourceLineId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "idempotency_key", length = 160, unique = true)
    private String idempotencyKey;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }
        if (businessDate == null) {
            businessDate = LocalDate.now();
        }
        createdAt = LocalDateTime.now();
    }
}
