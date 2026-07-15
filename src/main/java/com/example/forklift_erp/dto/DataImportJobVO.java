package com.example.forklift_erp.dto;

import com.example.forklift_erp.entity.DataImportJob;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DataImportJobVO {
    private Long id;
    private Long version;
    private String importType;
    private String importMode;
    private String templateName;
    private String originalFileName;
    private String status;
    private Integer totalRows;
    private Integer validRows;
    private Integer errorRows;
    private Integer importedRows;
    private Integer skippedRows;
    private String summary;
    private String createdBy;
    private String importedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public static DataImportJobVO fromEntity(DataImportJob entity) {
        DataImportJobVO vo = new DataImportJobVO();
        vo.setId(entity.getId());
        vo.setVersion(entity.getVersion());
        vo.setImportType(entity.getImportType());
        vo.setImportMode(entity.getImportMode());
        vo.setTemplateName(entity.getTemplateName());
        vo.setOriginalFileName(entity.getOriginalFileName());
        vo.setStatus(entity.getStatus());
        vo.setTotalRows(entity.getTotalRows());
        vo.setValidRows(entity.getValidRows());
        vo.setErrorRows(entity.getErrorRows());
        vo.setImportedRows(entity.getImportedRows());
        vo.setSkippedRows(entity.getSkippedRows());
        vo.setSummary(entity.getSummary());
        vo.setCreatedBy(entity.getCreatedBy());
        vo.setImportedBy(entity.getImportedBy());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        vo.setStartedAt(entity.getStartedAt());
        vo.setFinishedAt(entity.getFinishedAt());
        return vo;
    }
}
