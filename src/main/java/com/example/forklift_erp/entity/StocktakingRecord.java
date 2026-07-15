package com.example.forklift_erp.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "stocktaking_record")
public class StocktakingRecord implements CollaborativeResource {
    public static final String RESOURCE_MACHINE = "MACHINE";
    public static final String RESOURCE_PART = "PART";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "stocktaking_no", nullable = false, unique = true, length = 80)
    private String stocktakingNo;

    @Column(name = "resource_type", nullable = false, length = 30)
    private String resourceType;

    @Column(name = "resource_id")
    private Long resourceId;

    @Column(name = "warehouse_id")
    private Long warehouseId;

    @Column(name = "resource_code", length = 100)
    private String resourceCode;

    @Column(name = "resource_name", length = 120)
    private String resourceName;

    @Column(name = "specification_model", length = 120)
    private String specificationModel;

    @Column(name = "book_quantity", nullable = false)
    private Integer bookQuantity = 0;

    @Column(name = "book_balance_version")
    private Long bookBalanceVersion;

    @Column(name = "snapshot_movement_id")
    private Long snapshotMovementId;

    @Column(name = "actual_quantity", nullable = false)
    private Integer actualQuantity = 0;

    @Column(name = "difference_quantity", nullable = false)
    private Integer differenceQuantity = 0;

    @Column(name = "stocktaking_date")
    private LocalDate stocktakingDate;

    @Column(nullable = false, length = 30)
    private String status = "DRAFT";

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
