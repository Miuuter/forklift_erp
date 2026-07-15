package com.example.forklift_erp.dto;

import com.example.forklift_erp.entity.StocktakingRecord;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class StocktakingRecordVO {
    private Long id;
    private Long version;
    private String stocktakingNo;
    private String resourceType;
    private Long resourceId;
    private Long warehouseId;
    private String resourceCode;
    private String resourceName;
    private String specificationModel;
    private Integer bookQuantity;
    private Long bookBalanceVersion;
    private Long snapshotMovementId;
    private Integer actualQuantity;
    private Integer differenceQuantity;
    private LocalDate stocktakingDate;
    private String status;
    private String operator;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static StocktakingRecordVO fromEntity(StocktakingRecord entity) {
        StocktakingRecordVO vo = new StocktakingRecordVO();
        vo.setId(entity.getId());
        vo.setVersion(entity.getVersion());
        vo.setStocktakingNo(entity.getStocktakingNo());
        vo.setResourceType(entity.getResourceType());
        vo.setResourceId(entity.getResourceId());
        vo.setWarehouseId(entity.getWarehouseId());
        vo.setResourceCode(entity.getResourceCode());
        vo.setResourceName(entity.getResourceName());
        vo.setSpecificationModel(entity.getSpecificationModel());
        vo.setBookQuantity(entity.getBookQuantity());
        vo.setBookBalanceVersion(entity.getBookBalanceVersion());
        vo.setSnapshotMovementId(entity.getSnapshotMovementId());
        vo.setActualQuantity(entity.getActualQuantity());
        vo.setDifferenceQuantity(entity.getDifferenceQuantity());
        vo.setStocktakingDate(entity.getStocktakingDate());
        vo.setStatus(entity.getStatus());
        vo.setOperator(entity.getOperator());
        vo.setRemark(entity.getRemark());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }
}
