package com.example.forklift_erp.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class StocktakingRecordDTO {
    private Long version;

    @NotBlank(message = "盘点类型不能为空")
    private String resourceType;

    @NotNull(message = "盘点资源不能为空")
    private Long resourceId;

    private Long warehouseId;

    @NotNull(message = "实盘数量不能为空")
    @Min(value = 0, message = "实盘数量不能小于0")
    private Integer actualQuantity;

    private LocalDate stocktakingDate;
    private String status;
    private String operator;
    private String remark;
}
