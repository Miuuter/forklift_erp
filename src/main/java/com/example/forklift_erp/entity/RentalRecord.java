package com.example.forklift_erp.entity;

import com.example.forklift_erp.constant.RentalStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "rental_record")
public class RentalRecord implements CollaborativeResource {
    public static final String STATUS_ACTIVE = RentalStatus.ACTIVE.code();
    public static final String STATUS_RETURNED = RentalStatus.RETURNED.code();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "rental_no", nullable = false, unique = true, length = 80)
    private String rentalNo;

    @Column(name = "machine_id", nullable = false)
    private Long machineId;

    @Column(name = "warehouse_id")
    private Long warehouseId;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "vehicle_number", length = 100)
    private String vehicleNumber;

    @Column(name = "machine_name", length = 120)
    private String machineName;

    @Column(name = "specification_model", length = 120)
    private String specificationModel;

    @Column(name = "customer_name", length = 120)
    private String customerName;

    @Column(name = "customer_address", length = 255)
    private String customerAddress;

    @Column(name = "destination", nullable = false, length = 255)
    private String destination;

    @Column(name = "rental_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal rentalPrice;

    @Column(name = "monthly_rental_price", precision = 12, scale = 2)
    private BigDecimal monthlyRentalPrice;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "return_date")
    private LocalDate returnDate;

    @Column(name = "status", length = 30)
    private String status = STATUS_ACTIVE;

    @Column(name = "financial_posted")
    private Boolean financialPosted = false;

    @Column(name = "operator", length = 50)
    private String operator;

    @Column(name = "remark", length = 500)
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
