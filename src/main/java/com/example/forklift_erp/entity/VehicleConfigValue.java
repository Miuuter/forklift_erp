package com.example.forklift_erp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "vehicle_config_value")
public class VehicleConfigValue implements CollaborativeResource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @NotNull(message = "整车配置项不能为空")
    @Column(name = "vehicle_config_item_id", nullable = false)
    private Long vehicleConfigItemId;

    @NotNull(message = "配置项不能为空")
    @Column(name = "config_item_id", nullable = false)
    private Long configItemId;

    @NotNull(message = "配置值不能为空")
    @Column(name = "config_value_id", nullable = false)
    private Long configValueId;

    @Column(name = "config_item_name", length = 120)
    private String configItemName;

    @Column(name = "config_value_label", length = 200)
    private String configValueLabel;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(length = 255)
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
