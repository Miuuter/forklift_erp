package com.example.forklift_erp.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class VehiclePartInstallRequestDTO {
    private Long machineVersion;
    private Long newPartVersion;

    @NotNull(message = "车辆ID不能为空")
    private Long machineId;

    @NotNull(message = "配件分类不能为空")
    private Long configItemId;

    @NotNull(message = "仓库配件不能为空")
    private Long newPartId;

    @Min(value = 1, message = "数量必须大于0")
    private Integer quantity = 1;

    private Long warehouseId;
    private LocalDate businessDate;
    private String operator;
    private String remark;
}
