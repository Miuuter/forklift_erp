package com.example.forklift_erp.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class VehicleOutboundOrderCreateDTO {
    @NotNull(message = "出库车辆不能为空")
    private Long machineId;

    private Long machineVersion;

    private Long warehouseId;

    @NotNull(message = "客户不能为空")
    private Long customerId;

    @NotNull(message = "结算价不能为空")
    @DecimalMin(value = "0.00", message = "\u7ed3\u7b97\u4ef7\u4e0d\u80fd\u4e3a\u8d1f\u6570")
    private BigDecimal settlementPrice;
    @DecimalMin(value = "0.00", message = "Unit sale price cannot be negative")
    private BigDecimal unitSalePrice;
    @DecimalMin(value = "0.00", message = "Line amount cannot be negative")
    private BigDecimal lineAmount;

    private LocalDate salesDate;
    @DecimalMin(value = "0.00", message = "\u9500\u552e\u4ef7\u4e0d\u80fd\u4e3a\u8d1f\u6570")
    private BigDecimal salePrice;
    @DecimalMin(value = "0.00", message = "\u5e94\u6536\u91d1\u989d\u4e0d\u80fd\u4e3a\u8d1f\u6570")
    private BigDecimal receivableAmount;
    @DecimalMin(value = "0.00", message = "\u5df2\u6536\u91d1\u989d\u4e0d\u80fd\u4e3a\u8d1f\u6570")
    private BigDecimal receivedAmount;
    private LocalDate paymentDueDate;
    private LocalDate lastPaymentDate;
    private Boolean paymentSettled;
    private String paymentRemark;
    private Boolean salesReported;
    private LocalDate salesReportDate;
    private Boolean invoiceApplied;
    private LocalDate invoiceApplicationDate;
    private String invoiceStatus;
    private LocalDate invoiceIssuedDate;
    private String registrationStatus;
    private String contractType;

    private String operator;
    private String orderRemark;
}
