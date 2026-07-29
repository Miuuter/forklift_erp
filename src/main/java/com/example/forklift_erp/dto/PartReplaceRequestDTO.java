package com.example.forklift_erp.dto;

import com.example.forklift_erp.constant.PartChangeAction;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Digits;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class PartReplaceRequestDTO {
    private Long machineVersion;
    private Long machineConfigVersion;
    private Long newPartVersion;

    @NotNull(message = "车辆ID不能为空")
    private Long machineId;

    @NotNull(message = "车辆配置ID不能为空")
    private Long machineConfigId;

    @NotNull(message = "新配件ID不能为空")
    private Long newPartId;

    @Min(value = 1, message = "数量必须大于0")
    private Integer quantity = 1;

    private String oldPartAction = PartChangeAction.STOCK_IN.code();
    private String oldPartDisposition;
    private Long oldPartWarehouseId;
    private String oldPartCondition;
    private String oldPartValuationSource;
    @Digits(integer = 10, fraction = 2, message = "Old-part unit cost must fit DECIMAL(12,2)")
    private BigDecimal oldPartUnitCost;
    private Long warehouseId;
    private LocalDate businessDate;
    private String workOrderType;
    private String stockMovementSourceType;
    private Long stockMovementSourceId;
    private Long stockMovementSourceLineId;
    private String operator;
    private String remark;
}
