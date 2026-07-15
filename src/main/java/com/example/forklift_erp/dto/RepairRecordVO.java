// src/main/java/com/example/forklift_erp/dto/RepairRecordVO.java
package com.example.forklift_erp.dto;

import com.example.forklift_erp.entity.RepairRecord;
import com.example.forklift_erp.entity.RepairPartUsage;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class RepairRecordVO {
    private Long id;
    private Long version;
    private LocalDateTime repairDate;
    private Long machineId;
    private String vehicleNumber;
    private Long customerId;
    private String customerName;
    private String customerAddress;
    private String faultDescription;
    private String repairContent;
    private String repairPerson;
    private Long repairPersonUserId;
    private Boolean repairExternal;
    private String usedParts;
    private String usedPartIds;
    private List<RepairPartUsageDTO> partUsages = new ArrayList<>();
    private String partUsageTrackingStatus;
    private BigDecimal workHours;
    private BigDecimal repairFee;
    private BigDecimal repairExpense;
    private BigDecimal passThroughAmount;
    private BigDecimal partsFee;
    private BigDecimal partsCost;
    private BigDecimal totalFee;
    private BigDecimal receivableAmount;
    private Boolean financialPosted;
    private String status;
    private String remarks;
    // 排除审计字段

    public static RepairRecordVO fromEntity(RepairRecord entity) {
        RepairRecordVO vo = new RepairRecordVO();
        vo.setId(entity.getId());
        vo.setVersion(entity.getVersion());
        vo.setRepairDate(entity.getRepairDate());
        vo.setMachineId(entity.getMachineId());
        vo.setVehicleNumber(entity.getVehicleNumber());
        vo.setCustomerId(entity.getCustomerId());
        vo.setCustomerName(entity.getCustomerName());
        vo.setCustomerAddress(entity.getCustomerAddress());
        vo.setFaultDescription(entity.getFaultDescription());
        vo.setRepairContent(entity.getRepairContent());
        vo.setRepairPerson(entity.getRepairPerson());
        vo.setRepairPersonUserId(entity.getRepairPersonUserId());
        vo.setRepairExternal(Boolean.TRUE.equals(entity.getRepairExternal()));
        vo.setUsedParts(entity.getUsedParts());
        vo.setUsedPartIds(entity.getUsedPartIds());
        vo.setWorkHours(entity.getWorkHours());
        vo.setRepairFee(entity.getRepairFee());
        vo.setRepairExpense(entity.getRepairExpense());
        vo.setPassThroughAmount(entity.getPassThroughAmount());
        vo.setPartsFee(entity.getPartsFee());
        vo.setPartsCost(entity.getPartsCost());
        vo.setTotalFee(entity.getTotalFee());
        vo.setReceivableAmount(entity.getReceivableAmount());
        vo.setFinancialPosted(Boolean.TRUE.equals(entity.getFinancialPosted()));
        vo.setStatus(entity.getStatus());
        vo.setRemarks(entity.getRemarks());
        return vo;
    }

    public static RepairRecordVO fromEntity(RepairRecord entity, List<RepairPartUsage> usages) {
        RepairRecordVO vo = fromEntity(entity);
        if (usages != null) {
            vo.setPartUsages(usages.stream().map(RepairRecordVO::usageToDto).toList());
            boolean hasLegacyUntrackedUsage = entity.getUsedPartIds() != null && !entity.getUsedPartIds().isBlank()
                    && (usages.isEmpty() || usages.stream().anyMatch(usage ->
                    usage.getStockMovementId() == null && usage.getStockLotConsumptionId() == null));
            vo.setPartUsageTrackingStatus(hasLegacyUntrackedUsage ? "LEGACY_UNTRACKED" : "TRACKED");
        }
        return vo;
    }

    private static RepairPartUsageDTO usageToDto(RepairPartUsage usage) {
        RepairPartUsageDTO dto = new RepairPartUsageDTO();
        dto.setId(usage.getId());
        dto.setPartId(usage.getPartId());
        dto.setPartCode(usage.getPartCode());
        dto.setPartName(usage.getPartName());
        dto.setWarehouseId(usage.getWarehouseId());
        dto.setQuantity(usage.getQuantity());
        dto.setChargeUnitPrice(usage.getChargeUnitPrice());
        dto.setDiscountAmount(usage.getDiscountAmount());
        dto.setChargeAmount(usage.getChargeAmount());
        dto.setUnitCost(usage.getUnitCost());
        dto.setCostAmount(usage.getCostAmount());
        dto.setStockMovementId(usage.getStockMovementId());
        dto.setRemark(usage.getRemark());
        return dto;
    }
}
