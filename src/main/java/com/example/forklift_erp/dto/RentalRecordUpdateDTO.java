package com.example.forklift_erp.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class RentalRecordUpdateDTO {
    private Long version;
    private Long warehouseId;

    @NotNull(message = "租赁去向不能为空")
    private Long customerId;

    @Size(max = 255)
    private String destination;

    @DecimalMin(value = "0.00", inclusive = false, message = "月租价格必须大于0")
    @Digits(integer = 10, fraction = 2, message = "Monthly rental price must fit DECIMAL(12,2)")
    private BigDecimal monthlyRentalPrice;

    @DecimalMin(value = "0.00", message = "\u79df\u8d41\u4ef7\u683c\u4e0d\u80fd\u4e3a\u8d1f\u6570")
    @Digits(integer = 10, fraction = 2, message = "Rental price must fit DECIMAL(12,2)")
    private BigDecimal rentalPrice;

    private LocalDate startDate;

    private LocalDate endDate;
    private LocalDate returnDate;

    private String status;

    private String operator;

    private String remark;
}
