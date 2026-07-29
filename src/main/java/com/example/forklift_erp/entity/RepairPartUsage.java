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
@Table(name = "repair_part_usage")
public class RepairPartUsage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repair_id", nullable = false)
    private Long repairId;

    @Column(name = "part_id", nullable = false)
    private Long partId;

    @Column(name = "part_code", length = 100)
    private String partCode;

    @Column(name = "part_name", length = 120)
    private String partName;

    @Column(nullable = false)
    private Integer quantity;

    /**
     * Legacy field retained as the charged unit price.
     */
    @Column(name = "unit_price", precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "warehouse_id")
    private Long warehouseId;

    @Column(name = "unit_cost", precision = 18, scale = 6)
    private BigDecimal unitCost;

    @Column(name = "charge_unit_price", precision = 12, scale = 2)
    private BigDecimal chargeUnitPrice;

    @Column(name = "discount_amount", precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "charge_amount", precision = 14, scale = 2)
    private BigDecimal chargeAmount;

    @Column(name = "cost_amount", precision = 14, scale = 2)
    private BigDecimal costAmount;

    @Column(name = "stock_movement_id")
    private Long stockMovementId;

    @Column(name = "stock_lot_consumption_id")
    private Long stockLotConsumptionId;

    @Column(length = 500)
    private String remark;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (quantity == null) {
            quantity = 0;
        }
        if (chargeUnitPrice == null) {
            chargeUnitPrice = unitPrice == null ? BigDecimal.ZERO : unitPrice;
        }
        if (unitPrice == null) {
            unitPrice = chargeUnitPrice;
        }
        if (discountAmount == null) {
            discountAmount = BigDecimal.ZERO;
        }
        if (chargeAmount == null) {
            chargeAmount = chargeUnitPrice.multiply(BigDecimal.valueOf(quantity)).subtract(discountAmount);
        }
        if (costAmount == null) {
            costAmount = unitCost == null ? BigDecimal.ZERO : unitCost.multiply(BigDecimal.valueOf(quantity));
        }
        createdAt = LocalDateTime.now();
    }
}
