package com.example.forklift_erp.entity;

import com.example.forklift_erp.constant.PartChangeAction;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "modification_work_order_line")
public class ModificationWorkOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "work_order_id", nullable = false)
    private Long workOrderId;

    @Column(name = "machine_config_id", nullable = false)
    private Long machineConfigId;

    @Column(name = "config_item_id")
    private Long configItemId;

    @Column(name = "item_name", length = 100)
    private String itemName;

    @Column(name = "old_value", length = 200)
    private String oldValue;

    @Column(name = "new_part_id")
    private Long newPartId;

    @Column(name = "new_part_code", length = 100)
    private String newPartCode;

    @Column(name = "new_part_name", length = 120)
    private String newPartName;

    @Column(name = "new_value", length = 200)
    private String newValue;

    @Column(name = "new_config_value_id")
    private Long newConfigValueId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity = 1;

    @Column(name = "warehouse_id")
    private Long warehouseId;

    @Column(name = "old_part_action", nullable = false, length = 30)
    private String oldPartAction = PartChangeAction.STOCK_IN.code();

    @Column(name = "price_difference", nullable = false, precision = 12, scale = 2)
    private BigDecimal priceDifference = BigDecimal.ZERO;

    @Column(name = "charge_unit_price", precision = 12, scale = 2)
    private BigDecimal chargeUnitPrice;

    @Column(name = "discount_amount", precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "charge_amount", precision = 14, scale = 2)
    private BigDecimal chargeAmount;

    @Column(name = "cost_amount", precision = 14, scale = 2)
    private BigDecimal costAmount;

    @Column(name = "old_part_disposition", length = 30)
    private String oldPartDisposition;

    @Column(name = "old_part_warehouse_id")
    private Long oldPartWarehouseId;

    @Column(name = "old_part_condition", length = 50)
    private String oldPartCondition;

    @Column(name = "old_part_valuation_source", length = 100)
    private String oldPartValuationSource;

    @Column(name = "old_part_unit_cost", precision = 12, scale = 2)
    private BigDecimal oldPartUnitCost;

    @Column(name = "replace_log_id")
    private Long replaceLogId;

    @Column(length = 500)
    private String remark;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
