package com.example.forklift_erp.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class OutboundOrderUpdateDTO {
    private Long version;
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
    @Size(max = 500)
    private String paymentRemark;
    private Boolean salesReported;
    private Boolean invoiceApplied;
    private LocalDate salesReportDate;
    private LocalDate invoiceApplicationDate;
    @Size(max = 120)
    private String invoiceStatus;
    private LocalDate invoiceIssuedDate;
    @Size(max = 120)
    private String registrationStatus;
    @Size(max = 80)
    private String contractType;
    @Size(max = 500)
    private String orderRemark;
    @Size(max = 50)
    private String operator;
}
