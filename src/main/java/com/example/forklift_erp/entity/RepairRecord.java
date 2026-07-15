package com.example.forklift_erp.entity;

import com.example.forklift_erp.constant.RepairStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 外出维修记录表
 */
@Data
@Entity
@Table(name = "repair_record")
public class RepairRecord implements CollaborativeResource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "version")
    private Long version;

    /**
     * 是否锁定（默认为 false，即非锁定状态,true 时仅管理员可查看/修改）
     */
    @Column(name = "is_locked")
    private Boolean isLocked = false;

    /**
     * 维修日期
     */
    @NotNull(message = "维修日期不能为空")
    @Column(name = "repair_date", nullable = false)
    private LocalDateTime repairDate;

    /**
     * 关联的车辆ID（可能为空，维修配件不涉及整车）
     */
    @Column(name = "machine_id")
    private Long machineId;

    /**
     * 车辆编号
     */
    @Column(name = "vehicle_number", length = 100)
    private String vehicleNumber;

    @Column(name = "customer_id")
    private Long customerId;

    /**
     * 客户名称
     */
    @NotBlank(message = "客户名称不能为空")
    @Size(max = 100, message = "客户名称长度不能超过100")
    @Column(name = "customer_name", length = 100)
    private String customerName;

    /**
     * 客户地址
     */
    @Column(name = "customer_address", length = 255)
    private String customerAddress;

    /**
     * 故障描述
     */
    @NotBlank(message = "故障描述不能为空")
    @Size(max = 500, message = "故障描述长度不能超过500")
    @Column(name = "fault_description", length = 500)
    private String faultDescription;

    /**
     * 维修内容
     */
    @Column(name = "repair_content", length = 1000)
    private String repairContent;

    /**
     * 维修人员
     */
    @Column(name = "repair_person", length = 50)
    private String repairPerson;

    @Column(name = "repair_person_user_id")
    private Long repairPersonUserId;

    @Column(name = "repair_external")
    private Boolean repairExternal = false;

    /**
     * 使用的配件（可用于关联配件出库）
     */
    @Column(name = "used_parts", length = 500)
    private String usedParts;

    @Column(name = "used_part_ids", length = 500)
    private String usedPartIds;

    /**
     * 工时（小时）
     */
    @Column(name = "work_hours", precision = 5, scale = 1)
    private BigDecimal workHours;

    /**
     * 维修收入
     */
    @Column(name = "repair_fee", precision = 10, scale = 2)
    private BigDecimal repairFee;

    @Column(name = "repair_expense", precision = 10, scale = 2)
    private BigDecimal repairExpense;

    @Column(name = "pass_through_amount", precision = 12, scale = 2)
    private BigDecimal passThroughAmount = BigDecimal.ZERO;

    /**
     * 配件费用
     */
    @Column(name = "parts_fee", precision = 10, scale = 2)
    private BigDecimal partsFee;

    @Column(name = "parts_cost", precision = 12, scale = 2)
    private BigDecimal partsCost;

    /**
     * 总费用
     */
    @Column(name = "total_fee", precision = 10, scale = 2)
    private BigDecimal totalFee;

    @Column(name = "receivable_amount", precision = 12, scale = 2)
    private BigDecimal receivableAmount;

    @Column(name = "financial_posted")
    private Boolean financialPosted = false;

    /**
     * 状态
     * PENDING = 待维修
     * COMPLETED = 已完成
     */
    @Pattern(regexp = RepairStatus.VALIDATION_PATTERN, message = "状态值非法")
    @Column(name = "status", length = 20)
    private String status = RepairStatus.PENDING.code();

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
