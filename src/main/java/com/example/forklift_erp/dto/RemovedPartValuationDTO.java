package com.example.forklift_erp.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class RemovedPartValuationDTO {
    private Long version;

    @NotNull(message = "旧件估值不能为空")
    @DecimalMin(value = "0.01", message = "旧件估值必须大于 0")
    private BigDecimal unitCost;

    @NotBlank(message = "估值依据不能为空")
    private String valuationSource;

    private String condition;
    private LocalDate businessDate;
    private String operator;
    private String remark;
}
