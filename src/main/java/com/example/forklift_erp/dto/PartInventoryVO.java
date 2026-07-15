// src/main/java/com/example/forklift_erp/dto/PartInventoryVO.java
package com.example.forklift_erp.dto;

import com.example.forklift_erp.entity.PartInventory;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class PartInventoryVO {
    private Long id;
    private Long version;
    private String partCode;
    private String partBrand;
    private String partName;
    private String specification;
    private String partCategory;
    private String applicableModels;
    private String source;
    private Long sourceMachineId;
    private Long warehouseId;
    private Integer quantity;
    private Integer reorderPoint;
    private List<PartWarehouseBalanceVO> warehouseBalances = new ArrayList<>();
    private String unit;
    private BigDecimal purchasePrice;
    private BigDecimal landedUnitCost;
    private BigDecimal salePrice;
    private BigDecimal settlementPrice;
    private Boolean isLocked;
    private String remarks;
    private LocalDate manufacturingDate;
    private LocalDateTime inboundDate;
    // 排除审计字段

    public static PartInventoryVO fromEntity(PartInventory entity) {
        PartInventoryVO vo = new PartInventoryVO();
        vo.setId(entity.getId());
        vo.setVersion(entity.getVersion());
        vo.setPartCode(entity.getPartCode());
        vo.setPartBrand(entity.getPartBrand());
        vo.setPartName(entity.getPartName());
        vo.setSpecification(entity.getSpecification());
        vo.setPartCategory(entity.getPartCategory());
        vo.setApplicableModels(entity.getApplicableModels());
        vo.setSource(entity.getSource());
        vo.setSourceMachineId(entity.getSourceMachineId());
        vo.setWarehouseId(entity.getWarehouseId());
        vo.setQuantity(entity.getQuantity());
        vo.setReorderPoint(entity.getReorderPoint());
        vo.setUnit(entity.getUnit());
        vo.setPurchasePrice(entity.getPurchasePrice());
        vo.setLandedUnitCost(entity.getLandedUnitCost());
        vo.setSalePrice(entity.getSalePrice());
        vo.setSettlementPrice(entity.getSettlementPrice());
        vo.setIsLocked(Boolean.TRUE.equals(entity.getIsLocked()));
        vo.setRemarks(entity.getRemarks());
        vo.setManufacturingDate(entity.getManufacturingDate());
        vo.setInboundDate(entity.getInboundDate());
        return vo;
    }
}
