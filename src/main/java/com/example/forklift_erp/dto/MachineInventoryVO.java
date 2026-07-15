// src/main/java/com/example/forklift_erp/dto/MachineInventoryVO.java
package com.example.forklift_erp.dto;

import com.example.forklift_erp.entity.MachineInventory;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class MachineInventoryVO {
    private Long id;
    private Long version;
    private String vehicleProductNumber;
    private String name;
    private String specificationModel;
    private String machineType;
    private String configuration;
    private String supplier;
    private Long supplierId;
    private String supplierNameSnapshot;
    private String warehouseName;
    private Long warehouseId;
    private String stockStatus;
    private String applicationNumber;
    private String materialNumber;
    private BigDecimal purchasePrice;
    private BigDecimal landedUnitCost;
    private BigDecimal salePrice;
    private BigDecimal settlementPrice;
    private String engineNumber;
    private String frameNumber;
    private String warrantyCardNumber;
    private LocalDate manufacturingDate;
    private LocalDateTime inboundDate;
    private String salesDate;
    private String isSalesReported;
    private LocalDate salesReportDate;
    private Integer inventoryCount;
    private String destination1;
    private String destination2;
    private String destination3;
    private String destination4;
    private String destination5;
    private String isInvoiceApplied;
    private String remarks;
    private Boolean modelOnly;
    private Boolean isLocked;
    // 排除审计字段

    public static MachineInventoryVO fromEntity(MachineInventory entity) {
        MachineInventoryVO vo = new MachineInventoryVO();
        vo.setId(entity.getId());
        vo.setVersion(entity.getVersion());
        vo.setVehicleProductNumber(entity.getVehicleProductNumber());
        vo.setName(entity.getName());
        vo.setSpecificationModel(entity.getSpecificationModel());
        vo.setMachineType(entity.getMachineType());
        vo.setConfiguration(entity.getConfiguration());
        vo.setSupplier(entity.getSupplier());
        vo.setSupplierId(entity.getSupplierId());
        vo.setSupplierNameSnapshot(entity.getSupplierNameSnapshot());
        vo.setWarehouseName(entity.getWarehouseName());
        vo.setWarehouseId(entity.getWarehouseId());
        vo.setStockStatus(entity.getStockStatus());
        vo.setApplicationNumber(entity.getApplicationNumber());
        vo.setMaterialNumber(entity.getMaterialNumber());
        vo.setPurchasePrice(entity.getPurchasePrice());
        vo.setLandedUnitCost(entity.getLandedUnitCost());
        vo.setSalePrice(entity.getSalePrice());
        vo.setSettlementPrice(entity.getSettlementPrice());
        vo.setEngineNumber(entity.getEngineNumber());
        vo.setFrameNumber(entity.getFrameNumber());
        vo.setWarrantyCardNumber(entity.getWarrantyCardNumber());
        vo.setManufacturingDate(entity.getManufacturingDate());
        vo.setInboundDate(entity.getInboundDate());
        vo.setSalesDate(entity.getSalesDate());
        vo.setIsSalesReported(entity.getIsSalesReported());
        vo.setSalesReportDate(entity.getSalesReportDate());
        vo.setInventoryCount(entity.getInventoryCount());
        vo.setDestination1(entity.getDestination1());
        vo.setDestination2(entity.getDestination2());
        vo.setDestination3(entity.getDestination3());
        vo.setDestination4(entity.getDestination4());
        vo.setDestination5(entity.getDestination5());
        vo.setIsInvoiceApplied(entity.getIsInvoiceApplied());
        vo.setRemarks(entity.getRemarks());
        vo.setModelOnly(Boolean.TRUE.equals(entity.getModelOnly()));
        vo.setIsLocked(Boolean.TRUE.equals(entity.getIsLocked()));
        return vo;
    }
}
