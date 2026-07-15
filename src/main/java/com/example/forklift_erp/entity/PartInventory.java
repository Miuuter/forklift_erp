package com.example.forklift_erp.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 配件库存表
 * 存储仓库中所有独立配件（包括从整车上拆下的旧件）
 */
@Data
@Entity
@Table(name = "part_inventory")
public class PartInventory implements CollaborativeResource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // 主键ID

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "is_locked")
    private Boolean isLocked = false;    // 是否锁定（true 时仅管理员可查看/修改）

    @Column(name = "manufacturing_date") // 指定列名
    private LocalDate manufacturingDate; // 出厂日期

    @Column(name = "inbound_date")
    private LocalDateTime inboundDate; // 入库日期

    @Column(name = "sales_date", length = 10)
    private String salesDate; // 销售日期 (格式如 20221101)

    @Column(name = "sales_report_date")
    private LocalDate salesReportDate; // 报销售日期

    @NotBlank(message = "配件编码不能为空")
    @Size(max = 100, message = "配件编码长度不能超过100")
    @Column(name = "part_code", length = 100, unique = true, nullable = false)
    private String partCode; //配件编码(唯一标识)

    @Column(name= "part_brand", length = 100)
    private String partBrand; // 配件品牌

    @NotBlank(message = "配件名称不能为空")
    @Size(max = 100, message = "配件名称长度不能超过100")
    @Column(name = "part_name", length = 100, nullable = false)
    private String partName; // 配件名称

    @Column(name = "specification", length = 100)
    private String specification; // 规格型号

    @Column(name = "part_category", length = 50)
    private String partCategory; // 配件分类

    @Column(name = "applicable_models", length = 255)
    private String applicableModels; // 适用车型(空白为通用)

    @Column(name = "source", length = 30)
    private String source; // 来源 (PURCHASE = 采购;REMOVED = 从整车上拆下;RETURN = 退货返回)

    @Column(name = "source_machine_id")
    private Long sourceMachineId; // 来源车辆编号

    @Column(name = "warehouse_id")
    private Long warehouseId;

    @NotNull(message = "数量不能为空")
    @Min(value = 0, message = "数量不能为负数")
    @Column(name = "quantity", nullable = false)
    private Integer quantity = 0; // 当前库存数量

    @Column(name = "unit", length = 20)
    private String unit = "个"; // 单位

    @Column(name = "purchase_price", precision = 12, scale = 2)
    private BigDecimal purchasePrice; // 采购单价

    @Column(name = "landed_unit_cost", precision = 12, scale = 2)
    private BigDecimal landedUnitCost;

    @Column(name = "sale_price", precision = 12, scale = 2)
    private BigDecimal salePrice; // 销售单价

    @Column(name = "settlement_price", precision = 12, scale = 2)
    private BigDecimal settlementPrice; // 结算单价

    @Column(name = "is_sales_reported", length = 10)
    private String isSalesReported; // 是否报销售

    @Column(length = 255)
    private String remarks; // 备注

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt; // 创建时间

    @Column(name = "updated_at")
    private LocalDateTime updatedAt; // 更新时间

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
