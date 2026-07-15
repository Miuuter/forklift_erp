package com.example.forklift_erp.dto;

import com.example.forklift_erp.entity.ModificationWorkOrder;
import com.example.forklift_erp.entity.ModificationWorkOrderLine;
import com.example.forklift_erp.entity.MachineInventory;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;

@Data
public class ModificationWorkOrderVO {
    private Long id;
    private Long version;
    private String workOrderNo;
    private Long machineId;
    private String machineProductNumber;
    private String machineName;
    private String specificationModel;
    private String customerName;
    private String salesOrderNo;
    private String workOrderType;
    private Long warehouseId;
    private LocalDate businessDate;
    private Boolean financialPosted;
    private String status;
    private String operator;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
    private LocalDateTime canceledAt;
    private List<ModificationWorkOrderLineVO> lines;

    public static ModificationWorkOrderVO fromEntity(ModificationWorkOrder entity, List<ModificationWorkOrderLine> lines) {
        ModificationWorkOrderVO vo = new ModificationWorkOrderVO();
        vo.setId(entity.getId());
        vo.setVersion(entity.getVersion());
        vo.setWorkOrderNo(entity.getWorkOrderNo());
        vo.setMachineId(entity.getMachineId());
        vo.setCustomerName(entity.getCustomerName());
        vo.setSalesOrderNo(entity.getSalesOrderNo());
        vo.setWorkOrderType(entity.getWorkOrderType());
        vo.setWarehouseId(entity.getWarehouseId());
        vo.setBusinessDate(entity.getBusinessDate());
        vo.setFinancialPosted(entity.getFinancialPosted());
        vo.setStatus(entity.getStatus());
        vo.setOperator(entity.getOperator());
        vo.setRemark(entity.getRemark());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        vo.setCompletedAt(entity.getCompletedAt());
        vo.setCanceledAt(entity.getCanceledAt());
        vo.setLines(lines.stream().map(ModificationWorkOrderLineVO::fromEntity).toList());
        return vo;
    }

    public static ModificationWorkOrderVO fromEntity(
            ModificationWorkOrder entity,
            List<ModificationWorkOrderLine> lines,
            MachineInventory machine
    ) {
        ModificationWorkOrderVO vo = fromEntity(entity, lines);
        if (machine != null) {
            vo.setMachineProductNumber(machine.getVehicleProductNumber());
            vo.setMachineName(machine.getName());
            vo.setSpecificationModel(machine.getSpecificationModel());
        }
        return vo;
    }
}
