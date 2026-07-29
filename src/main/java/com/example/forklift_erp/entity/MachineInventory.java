// MachineInventory.java
package com.example.forklift_erp.entity;

import com.example.forklift_erp.constant.MachineStockStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 整车库存表
 * 存储仓库中所有整车
 */
@Data // Lombok注解，自动生成getter/setter/toString等方法
@Entity // JPA注解，声明这是一个数据库实体
@Table(name = "machine_inventory") // 指定数据库中对应的表名
public class MachineInventory implements CollaborativeResource {

    @Id // 主键
    @GeneratedValue(strategy = GenerationType.IDENTITY) // 主键生成策略：自增
    private Long id; // 对应Excel的“序列”列，我们作为主键

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "is_locked")
    private Boolean isLocked = false;    // 是否锁定（true 时仅管理员可查看/修改）

    @Column(name = "manufacturing_date") // 指定列名
    private LocalDate manufacturingDate; // 出厂日期

    @Column(name = "inbound_date")
    private LocalDateTime inboundDate; // 入库日期

    @Column(name = "annual_inspection_date")
    private LocalDate annualInspectionDate; // 年审日期

    @Column(name = "sales_date", length = 10)
    private String salesDate; // 销售日期 (格式如 20221101)

    @Column(name = "sales_report_date")
    private LocalDate salesReportDate; // 报销售日期

    @Column(name = "application_number", length = 100)
    private String applicationNumber; // 样机申请单号

    @Column(name = "material_number", length = 100)
    private String materialNumber; // 物料号

    @NotBlank(message = "车号/产品编号不能为空")
    @Size(max = 100, message = "车号长度不能超过100")
    @Column(name = "vehicle_number", unique = true, length = 100) // 假设车号/产品编号是唯一的
    private String vehicleProductNumber; // 车号/产品编号

    @Column(name = "engine_number", length = 100)
    private String engineNumber; // 发动机号

    @Column(name = "frame_number", length = 100)
    private String frameNumber; // 车架号

    @Column(name = "warranty_card_number", length = 100)
    private String warrantyCardNumber; // 保修卡号

    @NotBlank(message = "名称不能为空")
    @Size(max = 100, message = "名称长度不能超过100")
    @Column(length = 100)
    private String name; // 名称

    @NotBlank(message = "规格型号不能为空")
    @Size(max = 100, message = "规格型号长度不能超过100")
    @Column(name = "specification_model", length = 100)
    private String specificationModel; // 规格型号

    @Column(name = "machine_type", length = 30)
    private String machineType; // 所属车辆类型(可选值：内燃叉车、电动叉车、托盘搬运车、堆高车、手动托盘车...未来可随时扩展)

    @Column(name = "configuration", length = 500) // 配置可能很长
    private String configuration; // 配置

    @Column(length = 50)
    private String supplier; // 供应商

    @Column(name = "supplier_id")
    private Long supplierId;

    @Column(name = "supplier_name_snapshot", length = 120)
    private String supplierNameSnapshot;

    @Column(name = "warehouse_name", length = 100)
    private String warehouseName; // 所在仓库 (龙工或二级经销商等)

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Column(name = "stock_status", length = 30)
    private String stockStatus = MachineStockStatus.IN_STOCK.code();

    @Column(name = "purchase_price", precision = 12, scale = 2) // 价格类型使用BigDecimal，精度更高
    private BigDecimal purchasePrice;// 采购单价

    @Column(name = "landed_unit_cost", precision = 18, scale = 6)
    private BigDecimal landedUnitCost;

    @Column(name = "sale_price", precision = 12, scale = 2)
    private BigDecimal salePrice; // 销售单价

    @Column(name = "settlement_price", precision = 12, scale = 2)
    private BigDecimal settlementPrice; // 结算单价

    @Column(name = "is_sales_reported", length = 10)
    private String isSalesReported; // 是否报销售

    @Column(name = "inventory_count")
    private Integer inventoryCount; // 库存数

    @Column(name = "model_only")
    private Boolean modelOnly = false; // 是否仅为车型模板，不代表一台实际库存车

    // 以下几个“去向”字段，可以用多个字段，也可以未来单独建表，我们先简单处理
    @Column(name = "destination1", length = 255)
    private String destination1;

    @Column(name = "destination2", length = 255)
    private String destination2;

    @Column(name = "destination3", length = 255)
    private String destination3;

    @Column(name = "destination4", length = 255)
    private String destination4;

    @Column(name = "destination5", length = 255)
    private String destination5;

    @Column(name = "is_invoice_applied", length = 50)
    private String isInvoiceApplied; // 是否申请发票

    @Column(length = 500)
    private String remarks; // 备注

    // 以下两个字段通常用于审计和记录
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

    @PrePersist // 在实体第一次保存之前自动执行
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate // 在实体更新之前自动执行
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
