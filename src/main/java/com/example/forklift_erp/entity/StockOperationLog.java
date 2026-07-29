package com.example.forklift_erp.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "stock_operation_log")
public class StockOperationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "resource_type", length = 30, nullable = false)
    private String resourceType;

    @Column(name = "operation_type", length = 30, nullable = false)
    private String operationType;

    @Column(name = "resource_id")
    private Long resourceId;

    @Column(name = "resource_code", length = 100)
    private String resourceCode;

    @Column(name = "resource_name", length = 100)
    private String resourceName;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "before_quantity")
    private Integer beforeQuantity;

    @Column(name = "after_quantity")
    private Integer afterQuantity;

    @Column(name = "unit_cost", precision = 18, scale = 6)
    private BigDecimal unitCost;

    @Column(name = "unit_revenue", precision = 12, scale = 2)
    private BigDecimal unitRevenue;

    @Column(length = 50)
    private String operator;

    @Column(length = 255)
    private String remark;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
