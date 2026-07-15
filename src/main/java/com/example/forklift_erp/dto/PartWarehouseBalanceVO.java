package com.example.forklift_erp.dto;

import lombok.Data;

@Data
public class PartWarehouseBalanceVO {
    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private Integer availableQuantity = 0;
    private Integer reservedQuantity = 0;
    private Integer lockedQuantity = 0;
}
