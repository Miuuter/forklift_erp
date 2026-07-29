package com.example.forklift_erp.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "purchase_order")
public class PurchaseOrder implements CollaborativeResource {
    public static final String RESOURCE_PART = "PART";
    public static final String RESOURCE_MACHINE = "MACHINE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "purchase_no", nullable = false, unique = true, length = 80)
    private String purchaseNo;

    @Column(name = "supplier_id")
    private Long supplierId;

    @Column(name = "supplier_name", length = 120)
    private String supplierName;

    @Column(name = "config_item_id")
    private Long configItemId;

    @Column(name = "config_value_id")
    private Long configValueId;

    @Column(name = "resource_type", nullable = false, length = 30)
    private String resourceType;

    @Column(name = "warehouse_id")
    private Long warehouseId;

    @Column(name = "resource_id")
    private Long resourceId;

    @Column(name = "resource_code", length = 100)
    private String resourceCode;

    @Column(name = "resource_name", length = 120)
    private String resourceName;

    @Column(name = "specification_model", length = 120)
    private String specificationModel;

    @Column(nullable = false)
    private Integer quantity = 1;

    @Column(length = 20)
    private String unit;

    @Column(name = "unit_price", precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "total_amount", precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "freight_amount", precision = 12, scale = 2)
    private BigDecimal freightAmount = BigDecimal.ZERO;

    @Column(name = "order_date")
    private LocalDate orderDate;

    @Column(name = "expected_arrival_date")
    private LocalDate expectedArrivalDate;

    @Column(name = "received_date")
    private LocalDate receivedDate;

    @Column(name = "received_stock_movement_id")
    private Long receivedStockMovementId;

    @Column(name = "stock_lot_id")
    private Long stockLotId;

    @Column(name = "landed_unit_cost", precision = 18, scale = 6)
    private BigDecimal landedUnitCost;

    @Column(name = "previous_resource_purchase_price", precision = 12, scale = 2)
    private BigDecimal previousResourcePurchasePrice;

    @Column(name = "previous_resource_landed_unit_cost", precision = 18, scale = 6)
    private BigDecimal previousResourceLandedUnitCost;

    @Column(name = "resource_cost_snapshot_captured", nullable = false)
    private Boolean resourceCostSnapshotCaptured = false;

    @Column(name = "financial_posted")
    private Boolean financialPosted = false;

    @Column(nullable = false, length = 30)
    private String status = "ORDERED";

    @Column(name = "status_before_received", length = 30)
    private String statusBeforeReceived;

    @Column(length = 50)
    private String operator;

    @Column(length = 500)
    private String remark;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "last_modified_by", length = 50)
    private String lastModifiedBy;

    @Column(name = "last_modified_role", length = 30)
    private String lastModifiedRole;

    @Column(name = "last_modified_priority")
    private Integer lastModifiedPriority = 0;

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
