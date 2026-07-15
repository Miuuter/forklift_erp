package com.example.forklift_erp.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class PartOutboundOrderCreateDTO {
    @NotBlank(message = "配件编码不能为空")
    private String partCode;

    private Long partVersion;

    private Long warehouseId;

    @NotNull(message = "出库数量不能为空")
    @Min(value = 1, message = "出库数量必须大于0")
    private Integer quantity;

    @NotNull(message = "客户不能为空")
    private Long customerId;

    @DecimalMin(value = "0.00", message = "\u7ed3\u7b97\u4ef7\u4e0d\u80fd\u4e3a\u8d1f\u6570")
    private BigDecimal settlementPrice;
    @DecimalMin(value = "0.00", message = "Unit sale price cannot be negative")
    private BigDecimal unitSalePrice;
    @DecimalMin(value = "0.00", message = "Line amount cannot be negative")
    private BigDecimal lineAmount;
    @DecimalMin(value = "0.00", message = "\u5e94\u6536\u91d1\u989d\u4e0d\u80fd\u4e3a\u8d1f\u6570")
    private BigDecimal receivableAmount;
    @DecimalMin(value = "0.00", message = "\u5df2\u6536\u91d1\u989d\u4e0d\u80fd\u4e3a\u8d1f\u6570")
    private BigDecimal receivedAmount;
    private LocalDate paymentDueDate;
    private LocalDate lastPaymentDate;
    private LocalDate salesDate;
    private Boolean paymentSettled;
    private String paymentRemark;
    private String operator;
    private String orderRemark;
}
