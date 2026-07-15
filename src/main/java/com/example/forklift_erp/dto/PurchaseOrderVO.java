package com.example.forklift_erp.dto;

import com.example.forklift_erp.entity.PurchaseOrder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class PurchaseOrderVO {
    private Long id;
    private Long version;
    private String purchaseNo;
    private Long supplierId;
    private String supplierName;
    private Long configItemId;
    private Long configValueId;
    private String resourceType;
    private Long warehouseId;
    private Long resourceId;
    private String resourceCode;
    private String resourceName;
    private String specificationModel;
    private Integer quantity;
    private String unit;
    private BigDecimal unitPrice;
    private BigDecimal totalAmount;
    private BigDecimal freightAmount;
    private LocalDate orderDate;
    private LocalDate expectedArrivalDate;
    private LocalDate receivedDate;
    private Long receivedStockMovementId;
    private Long stockLotId;
    private BigDecimal landedUnitCost;
    private Boolean financialPosted;
    private String status;
    private String statusBeforeReceived;
    private String operator;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static PurchaseOrderVO fromEntity(PurchaseOrder entity) {
        PurchaseOrderVO vo = new PurchaseOrderVO();
        vo.setId(entity.getId());
        vo.setVersion(entity.getVersion());
        vo.setPurchaseNo(entity.getPurchaseNo());
        vo.setSupplierId(entity.getSupplierId());
        vo.setSupplierName(entity.getSupplierName());
        vo.setConfigItemId(entity.getConfigItemId());
        vo.setConfigValueId(entity.getConfigValueId());
        vo.setResourceType(entity.getResourceType());
        vo.setWarehouseId(entity.getWarehouseId());
        vo.setResourceId(entity.getResourceId());
        vo.setResourceCode(entity.getResourceCode());
        vo.setResourceName(entity.getResourceName());
        vo.setSpecificationModel(entity.getSpecificationModel());
        vo.setQuantity(entity.getQuantity());
        vo.setUnit(entity.getUnit());
        vo.setUnitPrice(entity.getUnitPrice());
        vo.setTotalAmount(entity.getTotalAmount());
        vo.setFreightAmount(entity.getFreightAmount());
        vo.setOrderDate(entity.getOrderDate());
        vo.setExpectedArrivalDate(entity.getExpectedArrivalDate());
        vo.setReceivedDate(entity.getReceivedDate());
        vo.setReceivedStockMovementId(entity.getReceivedStockMovementId());
        vo.setStockLotId(entity.getStockLotId());
        vo.setLandedUnitCost(entity.getLandedUnitCost());
        vo.setFinancialPosted(Boolean.TRUE.equals(entity.getFinancialPosted()));
        vo.setStatus(entity.getStatus());
        vo.setStatusBeforeReceived(entity.getStatusBeforeReceived());
        vo.setOperator(entity.getOperator());
        vo.setRemark(entity.getRemark());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }
}
