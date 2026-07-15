package com.example.forklift_erp.dto;

import com.example.forklift_erp.entity.MigrationException;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MigrationExceptionVO {
    private Long id;
    private String exceptionType;
    private String sourceType;
    private Long sourceId;
    private String detail;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;

    public static MigrationExceptionVO fromEntity(MigrationException entity) {
        MigrationExceptionVO vo = new MigrationExceptionVO();
        vo.setId(entity.getId());
        vo.setExceptionType(entity.getExceptionType());
        vo.setSourceType(entity.getSourceType());
        vo.setSourceId(entity.getSourceId());
        vo.setDetail(entity.getDetail());
        vo.setStatus(entity.getStatus());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setResolvedAt(entity.getResolvedAt());
        return vo;
    }
}
