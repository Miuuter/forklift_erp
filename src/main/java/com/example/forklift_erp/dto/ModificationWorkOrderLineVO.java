package com.example.forklift_erp.dto;

import com.example.forklift_erp.entity.ModificationWorkOrderLine;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ModificationWorkOrderLineVO {
    private Long id;
    private Long workOrderId;
    private Long machineConfigId;
    private Long configItemId;
    private String itemName;
    private String oldValue;
    private Long newPartId;
    private String newPartCode;
    private String newPartName;
    private String newValue;
    private Long newConfigValueId;
    private Integer quantity;
    private Long warehouseId;
    private String oldPartAction;
    private BigDecimal priceDifference;
    private BigDecimal chargeUnitPrice;
    private BigDecimal discountAmount;
    private BigDecimal chargeAmount;
    private BigDecimal costAmount;
    private String oldPartDisposition;
    private Long oldPartWarehouseId;
    private String oldPartCondition;
    private String oldPartValuationSource;
    private BigDecimal oldPartUnitCost;
    private Long replaceLogId;
    private String remark;
    private LocalDateTime createdAt;

    public static ModificationWorkOrderLineVO fromEntity(ModificationWorkOrderLine entity) {
        ModificationWorkOrderLineVO vo = new ModificationWorkOrderLineVO();
        vo.setId(entity.getId());
        vo.setWorkOrderId(entity.getWorkOrderId());
        vo.setMachineConfigId(entity.getMachineConfigId());
        vo.setConfigItemId(entity.getConfigItemId());
        vo.setItemName(entity.getItemName());
        vo.setOldValue(entity.getOldValue());
        vo.setNewPartId(entity.getNewPartId());
        vo.setNewPartCode(entity.getNewPartCode());
        vo.setNewPartName(entity.getNewPartName());
        vo.setNewValue(entity.getNewValue());
        vo.setNewConfigValueId(entity.getNewConfigValueId());
        vo.setQuantity(entity.getQuantity());
        vo.setWarehouseId(entity.getWarehouseId());
        vo.setOldPartAction(entity.getOldPartAction());
        vo.setPriceDifference(entity.getPriceDifference());
        vo.setChargeUnitPrice(entity.getChargeUnitPrice());
        vo.setDiscountAmount(entity.getDiscountAmount());
        vo.setChargeAmount(entity.getChargeAmount());
        vo.setCostAmount(entity.getCostAmount());
        vo.setOldPartDisposition(entity.getOldPartDisposition());
        vo.setOldPartWarehouseId(entity.getOldPartWarehouseId());
        vo.setOldPartCondition(entity.getOldPartCondition());
        vo.setOldPartValuationSource(entity.getOldPartValuationSource());
        vo.setOldPartUnitCost(entity.getOldPartUnitCost());
        vo.setReplaceLogId(entity.getReplaceLogId());
        vo.setRemark(entity.getRemark());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
