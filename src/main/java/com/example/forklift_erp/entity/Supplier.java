package com.example.forklift_erp.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "supplier_profile")
public class Supplier implements CollaborativeResource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "supplier_name", nullable = false, unique = true, length = 120)
    private String supplierName;

    @Column(name = "supplier_type", length = 50)
    private String supplierType;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "contact_name", length = 80)
    private String contactName;

    @Column(name = "contact_phone", length = 50)
    private String contactPhone;

    @Column(length = 255)
    private String address;

    @Column(name = "tax_number", length = 100)
    private String taxNumber;

    @Column(name = "bank_account", length = 120)
    private String bankAccount;

    @Column(length = 500)
    private String remarks;

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
