package com.example.forklift_erp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "stock_lot")
public class StockLot {
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CONSUMED = "CONSUMED";
    public static final String STATUS_REVERSED = "REVERSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "resource_type", nullable = false, length = 30)
    private String resourceType;

    @Column(name = "resource_id", nullable = false)
    private Long resourceId;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Column(name = "source_type", nullable = false, length = 40)
    private String sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "source_line_id")
    private Long sourceLineId;

    @Column(name = "received_business_date", nullable = false)
    private LocalDate receivedBusinessDate;

    @Column(name = "original_quantity", nullable = false)
    private Integer originalQuantity;

    @Column(name = "remaining_quantity", nullable = false)
    private Integer remainingQuantity;

    @Column(name = "unit_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "freight_allocated", nullable = false, precision = 14, scale = 2)
    private BigDecimal freightAllocated = BigDecimal.ZERO;

    @Column(nullable = false, length = 30)
    private String status = STATUS_OPEN;

    @Column(name = "idempotency_key", length = 160, unique = true)
    private String idempotencyKey;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (receivedBusinessDate == null) {
            receivedBusinessDate = LocalDate.now();
        }
        if (originalQuantity == null) {
            originalQuantity = 0;
        }
        if (remainingQuantity == null) {
            remainingQuantity = originalQuantity;
        }
        if (unitCost == null) {
            unitCost = BigDecimal.ZERO;
        }
        if (freightAllocated == null) {
            freightAllocated = BigDecimal.ZERO;
        }
        refreshStatus();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
        refreshStatus();
    }

    public void refreshStatus() {
        if (STATUS_REVERSED.equals(status)) {
            return;
        }
        status = remainingQuantity != null && remainingQuantity > 0 ? STATUS_OPEN : STATUS_CONSUMED;
    }
}
