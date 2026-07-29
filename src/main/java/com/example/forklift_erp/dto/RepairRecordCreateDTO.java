// src/main/java/com/example/forklift_erp/dto/RepairRecordCreateDTO.java
package com.example.forklift_erp.dto;

import com.example.forklift_erp.constant.RepairStatus;
import com.example.forklift_erp.entity.RepairRecord;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class RepairRecordCreateDTO {
    private Long version;

    @NotNull(message = "维修日期不能为空")
    private LocalDateTime repairDate;

    private Long machineId;

    @Size(max = 100)
    private String vehicleNumber;

    private Long customerId;

    @Size(max = 100)
    private String customerName;

    @Size(max = 255)
    private String customerAddress;

    @NotBlank(message = "故障描述不能为空")
    @Size(max = 500)
    private String faultDescription;

    @Size(max = 1000)
    private String repairContent;

    @Size(max = 50)
    private String repairPerson;

    private String repairPersonChoice;

    private Long repairPersonUserId;

    private Boolean repairExternal;

    @Size(max = 500)
    private String usedParts;

    private List<Long> usedPartIds = new ArrayList<>();

    @Valid
    private List<RepairPartUsageDTO> partUsages = new ArrayList<>();

    @DecimalMin(value = "0.00", message = "\u5de5\u65f6\u4e0d\u80fd\u4e3a\u8d1f\u6570")
    @Digits(integer = 4, fraction = 1, message = "Work hours must fit DECIMAL(5,1)")
    private BigDecimal workHours;
    @DecimalMin(value = "0.00", message = "\u7ef4\u4fee\u6536\u5165\u4e0d\u80fd\u4e3a\u8d1f\u6570")
    @Digits(integer = 8, fraction = 2, message = "Repair fee must fit DECIMAL(10,2)")
    private BigDecimal repairFee;
    @DecimalMin(value = "0.00", message = "\u7ef4\u4fee\u652f\u51fa\u4e0d\u80fd\u4e3a\u8d1f\u6570")
    @Digits(integer = 8, fraction = 2, message = "Repair expense must fit DECIMAL(10,2)")
    private BigDecimal repairExpense;
    @DecimalMin(value = "0.00", message = "可转嫁金额不能为负数")
    @Digits(integer = 10, fraction = 2, message = "Pass-through amount must fit DECIMAL(12,2)")
    private BigDecimal passThroughAmount;
    @DecimalMin(value = "0.00", message = "\u914d\u4ef6\u8d39\u4e0d\u80fd\u4e3a\u8d1f\u6570")
    @Digits(integer = 8, fraction = 2, message = "Parts fee must fit DECIMAL(10,2)")
    private BigDecimal partsFee;
    @DecimalMin(value = "0.00", message = "\u603b\u8d39\u7528\u4e0d\u80fd\u4e3a\u8d1f\u6570")
    @Digits(integer = 8, fraction = 2, message = "Total fee must fit DECIMAL(10,2)")
    private BigDecimal totalFee;
    @DecimalMin(value = "0.00", message = "客户应收不能为负数")
    @Digits(integer = 10, fraction = 2, message = "Receivable amount must fit DECIMAL(12,2)")
    private BigDecimal receivableAmount;

    @Pattern(regexp = RepairStatus.VALIDATION_PATTERN, message = "状态值非法")
    private String status;

    @Size(max = 500)
    private String remarks;

    public RepairRecord toEntity() {
        RepairRecord entity = new RepairRecord();
        applyToEntity(entity);
        return entity;
    }

    public void applyToEntity(RepairRecord entity) {
        entity.setRepairDate(this.repairDate);
        entity.setMachineId(this.machineId);
        entity.setVehicleNumber(this.vehicleNumber);
        entity.setCustomerId(this.customerId);
        entity.setCustomerName(this.customerName);
        entity.setCustomerAddress(this.customerAddress);
        entity.setFaultDescription(this.faultDescription);
        entity.setRepairContent(this.repairContent);
        entity.setRepairPerson(this.repairPerson);
        applyRepairPerson(entity);
        entity.setUsedParts(this.usedParts);
        entity.setUsedPartIds(joinIds(this.usedPartIds));
        entity.setWorkHours(null);
        entity.setRepairFee(this.repairFee);
        entity.setRepairExpense(this.repairExpense);
        entity.setPassThroughAmount(this.passThroughAmount);
        entity.setPartsFee(this.partsFee);
        entity.setTotalFee(this.totalFee);
        entity.setReceivableAmount(this.receivableAmount);
        entity.setStatus(this.status);
        entity.setRemarks(this.remarks);
    }

    private void applyRepairPerson(RepairRecord entity) {
        String choice = repairPersonChoice == null ? "" : repairPersonChoice.trim();
        if ("OTHER".equalsIgnoreCase(choice)) {
            entity.setRepairPersonUserId(null);
            entity.setRepairExternal(true);
            entity.setRepairPerson("其他");
            return;
        }
        if (!choice.isBlank()) {
            try {
                entity.setRepairPersonUserId(Long.parseLong(choice));
            } catch (NumberFormatException ignored) {
                entity.setRepairPersonUserId(this.repairPersonUserId);
            }
        } else {
            entity.setRepairPersonUserId(this.repairPersonUserId);
        }
        entity.setRepairExternal(Boolean.TRUE.equals(this.repairExternal));
    }

    private String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        return ids.stream()
                .filter(id -> id != null && id > 0)
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
    }
}
