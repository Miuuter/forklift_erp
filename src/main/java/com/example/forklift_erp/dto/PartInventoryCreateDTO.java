// src/main/java/com/example/forklift_erp/dto/PartInventoryCreateDTO.java
package com.example.forklift_erp.dto;

import com.example.forklift_erp.entity.PartInventory;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class PartInventoryCreateDTO {
    private Long version;

    @NotBlank(message = "配件编码不能为空")
    @Size(max = 100)
    private String partCode;

    @Size(max = 100)
    private String partBrand;

    @NotBlank(message = "配件名称不能为空")
    @Size(max = 100)
    private String partName;

    @Size(max = 100)
    private String specification;

    @Size(max = 50)
    private String partCategory;

    @Size(max = 255)
    private String applicableModels;

    private String source;

    private Long sourceMachineId;

    private Long warehouseId;

    @NotNull(message = "数量不能为空")
    @Min(value = 0, message = "数量不能为负数")
    private Integer quantity = 0;

    private String unit;

    @DecimalMin(value = "0.00", message = "\u91c7\u8d2d\u4ef7\u4e0d\u80fd\u4e3a\u8d1f\u6570")
    private BigDecimal purchasePrice;
    @DecimalMin(value = "0.00", message = "Landed unit cost cannot be negative")
    private BigDecimal landedUnitCost;
    @DecimalMin(value = "0.00", message = "\u9500\u552e\u4ef7\u4e0d\u80fd\u4e3a\u8d1f\u6570")
    private BigDecimal salePrice;
    @DecimalMin(value = "0.00", message = "\u7ed3\u7b97\u4ef7\u4e0d\u80fd\u4e3a\u8d1f\u6570")
    private BigDecimal settlementPrice;

    @Size(max = 255)
    private String remarks;

    private LocalDate manufacturingDate;
    private LocalDateTime inboundDate;

    public PartInventory toEntity() {
        PartInventory entity = new PartInventory();
        entity.setPartCode(this.partCode);
        entity.setPartBrand(this.partBrand);
        entity.setPartName(this.partName);
        entity.setSpecification(this.specification);
        entity.setPartCategory(this.partCategory);
        entity.setApplicableModels(this.applicableModels);
        entity.setSource(this.source);
        entity.setSourceMachineId(this.sourceMachineId);
        if (this.warehouseId != null) {
            entity.setWarehouseId(this.warehouseId);
        }
        entity.setQuantity(this.quantity);
        entity.setUnit(this.unit);
        entity.setPurchasePrice(this.purchasePrice);
        entity.setLandedUnitCost(this.landedUnitCost);
        entity.setSalePrice(this.salePrice);
        entity.setSettlementPrice(this.settlementPrice);
        entity.setRemarks(this.remarks);
        entity.setManufacturingDate(this.manufacturingDate);
        entity.setInboundDate(this.inboundDate);
        return entity;
    }

    // 用于更新时，将 DTO 的值赋给已有的实体
    public void updateEntity(PartInventory entity) {
        entity.setPartCode(this.partCode);
        entity.setPartBrand(this.partBrand);
        entity.setPartName(this.partName);
        entity.setSpecification(this.specification);
        entity.setPartCategory(this.partCategory);
        entity.setApplicableModels(this.applicableModels);
        entity.setSource(this.source);
        entity.setSourceMachineId(this.sourceMachineId);
        if (this.warehouseId != null) {
            entity.setWarehouseId(this.warehouseId);
        }
        entity.setQuantity(this.quantity);
        entity.setUnit(this.unit);
        entity.setPurchasePrice(this.purchasePrice);
        entity.setLandedUnitCost(this.landedUnitCost);
        entity.setSalePrice(this.salePrice);
        entity.setSettlementPrice(this.settlementPrice);
        entity.setRemarks(this.remarks);
        entity.setManufacturingDate(this.manufacturingDate);
        entity.setInboundDate(this.inboundDate);
    }
}
