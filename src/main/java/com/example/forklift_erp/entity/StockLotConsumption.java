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
@Table(name = "stock_lot_consumption")
public class StockLotConsumption {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_lot_id", nullable = false)
    private Long stockLotId;

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

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "total_cost", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalCost;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "reversal_of_consumption_id")
    private Long reversalOfConsumptionId;

    @Column(name = "idempotency_key", length = 160, unique = true)
    private String idempotencyKey;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (businessDate == null) {
            businessDate = LocalDate.now();
        }
        if (unitCost == null) {
            unitCost = BigDecimal.ZERO;
        }
        if (totalCost == null) {
            totalCost = unitCost.multiply(BigDecimal.valueOf(quantity == null ? 0 : quantity));
        }
        createdAt = LocalDateTime.now();
    }
}
