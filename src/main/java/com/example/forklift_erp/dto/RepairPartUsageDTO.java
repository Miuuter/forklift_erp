package com.example.forklift_erp.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
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
    @Digits(integer = 10, fraction = 2, message = "Charge unit price must fit DECIMAL(12,2)")
    private BigDecimal chargeUnitPrice;

    @DecimalMin(value = "0.00", message = "Repair part discount cannot be negative")
    @Digits(integer = 10, fraction = 2, message = "Discount amount must fit DECIMAL(12,2)")
    private BigDecimal discountAmount = BigDecimal.ZERO;

    private String remark;

    // Response-only fields. They are intentionally accepted on update so an
    // unchanged line can round-trip through the form without losing identity.
    private String partCode;
    private String partName;
    @Digits(integer = 10, fraction = 2, message = "Unit cost must fit DECIMAL(12,2)")
    private BigDecimal unitCost;
    @Digits(integer = 12, fraction = 2, message = "Charge amount must fit DECIMAL(14,2)")
    private BigDecimal chargeAmount;
    @Digits(integer = 12, fraction = 2, message = "Cost amount must fit DECIMAL(14,2)")
    private BigDecimal costAmount;
    private Long stockMovementId;
}
