package com.example.forklift_erp.dto;

import com.example.forklift_erp.entity.MachineInventory;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class MachineInventoryCreateDTO {
    private Long version;

    @Size(max = 100)
    private String vehicleProductNumber;

    @NotBlank(message = "名称不能为空")
    @Size(max = 100)
    private String name;

    @NotBlank(message = "规格型号不能为空")
    @Size(max = 100)
    private String specificationModel;

    @Size(max = 30)
    private String machineType;
    @Size(max = 500)
    private String configuration;
    @Size(max = 50)
    private String supplier;
    private Long supplierId;
    @Size(max = 120)
    private String supplierNameSnapshot;
    @Size(max = 100)
    private String warehouseName;
    private Long warehouseId;
    @Size(max = 30)
    private String stockStatus;
    @Size(max = 100)
    private String applicationNumber;
    @Size(max = 100)
    private String materialNumber;
    @DecimalMin(value = "0.00", message = "\u91c7\u8d2d\u4ef7\u4e0d\u80fd\u4e3a\u8d1f\u6570")
    @Digits(integer = 10, fraction = 2, message = "Purchase price must fit DECIMAL(12,2)")
    private BigDecimal purchasePrice;
    @DecimalMin(value = "0.00", message = "Landed unit cost cannot be negative")
    @Digits(integer = 10, fraction = 2, message = "Landed unit cost must fit DECIMAL(12,2)")
    private BigDecimal landedUnitCost;
    @DecimalMin(value = "0.00", message = "\u9500\u552e\u4ef7\u4e0d\u80fd\u4e3a\u8d1f\u6570")
    @Digits(integer = 10, fraction = 2, message = "Sale price must fit DECIMAL(12,2)")
    private BigDecimal salePrice;
    @DecimalMin(value = "0.00", message = "\u7ed3\u7b97\u4ef7\u4e0d\u80fd\u4e3a\u8d1f\u6570")
    @Digits(integer = 10, fraction = 2, message = "Settlement price must fit DECIMAL(12,2)")
    private BigDecimal settlementPrice;
    @Size(max = 100)
    private String engineNumber;
    @Size(max = 100)
    private String frameNumber;
    @Size(max = 100)
    private String warrantyCardNumber;
    private LocalDate manufacturingDate;
    private LocalDateTime inboundDate;
    @Size(max = 10)
    private String salesDate;
    @Size(max = 10)
    private String isSalesReported;
    private LocalDate salesReportDate;
    @Min(value = 0, message = "\u5e93\u5b58\u6570\u91cf\u4e0d\u80fd\u4e3a\u8d1f\u6570")
    private Integer inventoryCount;
    @Size(max = 255)
    private String destination1;
    @Size(max = 255)
    private String destination2;
    @Size(max = 255)
    private String destination3;
    @Size(max = 255)
    private String destination4;
    @Size(max = 255)
    private String destination5;
    @Size(max = 50)
    private String isInvoiceApplied;
    @Size(max = 500)
    private String remarks;
    private Boolean modelOnly;

    public MachineInventory toEntity() {
        MachineInventory entity = new MachineInventory();
        entity.setVehicleProductNumber(this.vehicleProductNumber);
        entity.setName(this.name);
        entity.setSpecificationModel(this.specificationModel);
        entity.setMachineType(this.machineType);
        entity.setConfiguration(this.configuration);
        entity.setSupplier(this.supplier);
        entity.setSupplierId(this.supplierId);
        entity.setSupplierNameSnapshot(this.supplierNameSnapshot);
        entity.setWarehouseName(this.warehouseName);
        if (this.warehouseId != null) {
            entity.setWarehouseId(this.warehouseId);
        }
        if (this.stockStatus != null) {
            entity.setStockStatus(this.stockStatus);
        }
        entity.setApplicationNumber(this.applicationNumber);
        entity.setMaterialNumber(this.materialNumber);
        entity.setPurchasePrice(this.purchasePrice);
        entity.setLandedUnitCost(this.landedUnitCost);
        entity.setSalePrice(this.salePrice);
        entity.setSettlementPrice(this.settlementPrice);
        entity.setEngineNumber(this.engineNumber);
        entity.setFrameNumber(this.frameNumber);
        entity.setWarrantyCardNumber(this.warrantyCardNumber);
        entity.setManufacturingDate(this.manufacturingDate);
        entity.setInboundDate(this.inboundDate);
        entity.setSalesDate(this.salesDate);
        entity.setIsSalesReported(this.isSalesReported);
        entity.setSalesReportDate(this.salesReportDate);
        entity.setInventoryCount(this.inventoryCount);
        entity.setDestination1(this.destination1);
        entity.setDestination2(this.destination2);
        entity.setDestination3(this.destination3);
        entity.setDestination4(this.destination4);
        entity.setDestination5(this.destination5);
        entity.setIsInvoiceApplied(this.isInvoiceApplied);
        entity.setRemarks(this.remarks);
        if (this.modelOnly != null) {
            entity.setModelOnly(this.modelOnly);
        }
        return entity;
    }

    // 用于更新时，将 DTO 的值赋给已有的实体（避免创建新实体时丢失 id）
    public void updateEntity(MachineInventory entity) {
        entity.setVehicleProductNumber(this.vehicleProductNumber);
        entity.setName(this.name);
        entity.setSpecificationModel(this.specificationModel);
        entity.setMachineType(this.machineType);
        entity.setConfiguration(this.configuration);
        entity.setSupplier(this.supplier);
        entity.setSupplierId(this.supplierId);
        entity.setSupplierNameSnapshot(this.supplierNameSnapshot);
        entity.setWarehouseName(this.warehouseName);
        if (this.warehouseId != null) {
            entity.setWarehouseId(this.warehouseId);
        }
        if (this.stockStatus != null) {
            entity.setStockStatus(this.stockStatus);
        }
        entity.setApplicationNumber(this.applicationNumber);
        entity.setMaterialNumber(this.materialNumber);
        entity.setPurchasePrice(this.purchasePrice);
        entity.setLandedUnitCost(this.landedUnitCost);
        entity.setSalePrice(this.salePrice);
        entity.setSettlementPrice(this.settlementPrice);
        entity.setEngineNumber(this.engineNumber);
        entity.setFrameNumber(this.frameNumber);
        entity.setWarrantyCardNumber(this.warrantyCardNumber);
        entity.setManufacturingDate(this.manufacturingDate);
        entity.setInboundDate(this.inboundDate);
        entity.setSalesDate(this.salesDate);
        entity.setIsSalesReported(this.isSalesReported);
        entity.setSalesReportDate(this.salesReportDate);
        entity.setInventoryCount(this.inventoryCount);
        entity.setDestination1(this.destination1);
        entity.setDestination2(this.destination2);
        entity.setDestination3(this.destination3);
        entity.setDestination4(this.destination4);
        entity.setDestination5(this.destination5);
        entity.setIsInvoiceApplied(this.isInvoiceApplied);
        entity.setRemarks(this.remarks);
        if (this.modelOnly != null) {
            entity.setModelOnly(this.modelOnly);
        }
    }
}
