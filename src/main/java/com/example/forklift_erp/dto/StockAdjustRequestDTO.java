package com.example.forklift_erp.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class StockAdjustRequestDTO {
    private Long version;

    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量必须大于0")
    private Integer quantity;

    private Long warehouseId;
    private java.time.LocalDate businessDate;
    /**
     * Internal import flag: opening balances create inventory/FIFO history but
     * must not be recognized as current-period inventory gains.
     */
    private Boolean openingBalance = false;
    private String reason;
    private String operator;
    private String remark;
}
