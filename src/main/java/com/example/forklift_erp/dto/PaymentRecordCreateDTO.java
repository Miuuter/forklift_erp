package com.example.forklift_erp.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class PaymentRecordCreateDTO {
    @NotBlank(message = "Request ID is required")
    private String requestId;

    @NotBlank(message = "Payment direction is required")
    private String direction;

    @NotNull(message = "Payment amount is required")
    @DecimalMin(value = "0.01", message = "Payment amount must be greater than zero")
    @Digits(integer = 12, fraction = 2, message = "Payment amount must fit DECIMAL(14,2)")
    private BigDecimal amount;

    private LocalDate paymentDate;
    private String accountName;
    private String paymentMethod;

    @NotBlank(message = "Payment source type is required")
    private String sourceType;

    @NotNull(message = "Payment source ID is required")
    private Long sourceId;

    private String remark;
}
