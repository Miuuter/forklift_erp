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
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "stock_movement_line")
public class StockMovementLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "movement_id", nullable = false)
    private Long movementId;

    /**
     * The reversal header and line this detail belongs to. Both identifiers
     * are populated together for a movement reversal; ordinary lines leave
     * them null and V51 enforces that relationship at the database boundary.
     */
    @Column(name = "reversal_of_movement_id")
    private Long reversalOfMovementId;

    @Column(name = "reversal_of_movement_line_id")
    private Long reversalOfMovementLineId;

    @Column(name = "resource_type", length = 30, nullable = false)
    private String resourceType;

    @Column(name = "resource_id", nullable = false)
    private Long resourceId;

    @Column(name = "resource_code", length = 100)
    private String resourceCode;

    @Column(name = "resource_name", length = 120)
    private String resourceName;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Column(name = "quantity_delta", nullable = false)
    private Integer quantityDelta;

    @Column(name = "before_quantity", nullable = false)
    private Integer beforeQuantity;

    @Column(name = "after_quantity", nullable = false)
    private Integer afterQuantity;

    @Column(name = "unit_cost", precision = 18, scale = 6)
    private BigDecimal unitCost;

    @Column(name = "unit_revenue", precision = 12, scale = 2)
    private BigDecimal unitRevenue;

    @Column(name = "line_amount", precision = 14, scale = 2)
    private BigDecimal lineAmount;

    @Column(name = "cost_amount", precision = 14, scale = 2)
    private BigDecimal costAmount;

    @Column(name = "stock_lot_id")
    private Long stockLotId;

    @Column(name = "source_line_id")
    private Long sourceLineId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        if (this.unitCost == null) {
            this.unitCost = BigDecimal.ZERO;
        }
        if (this.unitRevenue == null) {
            this.unitRevenue = BigDecimal.ZERO;
        }
        if (this.costAmount == null) {
            this.costAmount = this.unitCost.multiply(BigDecimal.valueOf(Math.abs(this.quantityDelta == null ? 0 : this.quantityDelta)));
        }
        if (this.lineAmount == null) {
            this.lineAmount = this.unitRevenue.multiply(BigDecimal.valueOf(Math.abs(this.quantityDelta == null ? 0 : this.quantityDelta)));
        }
    }
}
