package com.example.forklift_erp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "stock_movement")
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "movement_no", length = 60, nullable = false, unique = true)
    private String movementNo;

    @Column(name = "movement_type", length = 40, nullable = false)
    private String movementType;

    @Column(name = "resource_type", length = 30, nullable = false)
    private String resourceType;

    @Column(name = "source_type", length = 40)
    private String sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "source_line_id")
    private Long sourceLineId;

    @Column(name = "business_date")
    private LocalDate businessDate;

    @Column(name = "business_type", length = 50)
    private String businessType;

    @Column(length = 50)
    private String operator;

    @Column(length = 255)
    private String remark;

    @Column(name = "idempotency_key", length = 160, unique = true)
    private String idempotencyKey;

    @Column(name = "reversal_of_movement_id")
    private Long reversalOfMovementId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        if (this.businessDate == null) {
            this.businessDate = this.createdAt.toLocalDate();
        }
        if (this.businessType == null || this.businessType.isBlank()) {
            this.businessType = this.movementType;
        }
    }
}
