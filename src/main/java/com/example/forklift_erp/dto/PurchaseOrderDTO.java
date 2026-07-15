package com.example.forklift_erp.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class PurchaseOrderDTO {
    private Long version;

    private Long supplierId;
    private String supplierName;
    private String supplier;

    private Long configItemId;
    private Long configValueId;
    private String resourceType;

    private Long warehouseId;
    private Long resourceId;

    private String resourceCode;
    private String resourceName;
    private String specificationModel;

    @NotNull(message = "采购数量不能为空")
    @Min(value = 1, message = "采购数量必须大于0")
    private Integer quantity;

    private String unit;
    @DecimalMin(value = "0.00", message = "\u91c7\u8d2d\u5355\u4ef7\u4e0d\u80fd\u4e3a\u8d1f\u6570")
    private BigDecimal unitPrice;
    @DecimalMin(value = "0.00", message = "\u91c7\u8d2d\u603b\u91d1\u989d\u4e0d\u80fd\u4e3a\u8d1f\u6570")
    private BigDecimal totalAmount;
    @DecimalMin(value = "0.00", message = "\u8fd0\u8d39\u4e0d\u80fd\u4e3a\u8d1f\u6570")
    private BigDecimal freightAmount;
    private LocalDate orderDate;
    private LocalDate expectedArrivalDate;
    private LocalDate receivedDate;
    private String status;
    private String operator;
    private String remark;
}
