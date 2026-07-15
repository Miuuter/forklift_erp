package com.example.forklift_erp.dto;

import com.example.forklift_erp.constant.PartChangeAction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class ModificationWorkOrderCreateDTO {
    private Long machineVersion;

    @NotNull(message = "车辆ID不能为空")
    private Long machineId;

    private String customerName;
    private String salesOrderNo;
    private String workOrderType = "PRE_SALE";
    private Long warehouseId;
    private LocalDate businessDate;
    private String operator;
    private String remark;

    @Valid
    @NotEmpty(message = "工单替换明细不能为空")
    private List<Line> lines;

    @Data
    public static class Line {
        private Long machineConfigVersion;
        private Long newPartVersion;
        private Long newConfigValueVersion;

        @NotNull(message = "车辆配置ID不能为空")
        private Long machineConfigId;

        private Long newPartId;
        private Long newConfigValueId;

        @Min(value = 1, message = "数量必须大于0")
        private Integer quantity = 1;

        private String oldPartAction = PartChangeAction.STOCK_IN.code();
        private BigDecimal priceDifference = BigDecimal.ZERO;
        private Long warehouseId;
        private BigDecimal chargeUnitPrice;
        private BigDecimal discountAmount = BigDecimal.ZERO;
        private String oldPartDisposition;
        private Long oldPartWarehouseId;
        private String oldPartCondition;
        private String oldPartValuationSource;
        private BigDecimal oldPartUnitCost;
        private String remark;
    }
}
