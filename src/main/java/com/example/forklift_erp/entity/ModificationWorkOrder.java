package com.example.forklift_erp.entity;

import com.example.forklift_erp.constant.ModificationWorkOrderStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "modification_work_order")
public class ModificationWorkOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "work_order_no", nullable = false, unique = true, length = 80)
    private String workOrderNo;

    @Column(name = "machine_id", nullable = false)
    private Long machineId;

    @Column(name = "customer_name", length = 100)
    private String customerName;

    @Column(name = "sales_order_no", length = 100)
    private String salesOrderNo;

    @Column(name = "work_order_type", length = 30)
    private String workOrderType = "PRE_SALE";

    @Column(name = "warehouse_id")
    private Long warehouseId;

    @Column(name = "business_date")
    private LocalDate businessDate;

    @Column(name = "status", nullable = false, length = 30)
    private String status = ModificationWorkOrderStatus.WAITING_PARTS.code();

    @Column(name = "financial_posted")
    private Boolean financialPosted = false;

    @Column(length = 50)
    private String operator;

    @Column(length = 500)
    private String remark;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

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
