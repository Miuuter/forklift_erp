package com.example.forklift_erp.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class RepairPartUsageDTO {
    private Long id;

    @NotNull(message = "Repair part is required")
    private Long partId;

    private Long warehouseId;

    @NotNull(message = "Repair part quantity is required")
    @Min(value = 1, message = "Repair part quantity must be greater than 0")
    private Integer quantity = 1;

    @DecimalMin(value = "0.00", message = "Repair part charge unit price cannot be negative")
    private BigDecimal chargeUnitPrice;

    @DecimalMin(value = "0.00", message = "Repair part discount cannot be negative")
    private BigDecimal discountAmount = BigDecimal.ZERO;

    private String remark;

    // Response-only fields. They are intentionally accepted on update so an
    // unchanged line can round-trip through the form without losing identity.
    private String partCode;
    private String partName;
    private BigDecimal unitCost;
    private BigDecimal chargeAmount;
    private BigDecimal costAmount;
    private Long stockMovementId;
}
