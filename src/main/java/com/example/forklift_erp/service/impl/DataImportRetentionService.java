package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.entity.DataImportJob;
import com.example.forklift_erp.repository.DataImportJobRepository;
import com.example.forklift_erp.service.OperationAuditService;
import com.example.forklift_erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class DataImportRetentionService {
    private final DataImportJobRepository jobRepository;
    private final DataImportFileStorage fileStorage;
    private final OperationAuditService operationAuditService;
    private final int retentionDays;
    private final boolean enabled;

    public DataImportRetentionService(
            DataImportJobRepository jobRepository,
            DataImportFileStorage fileStorage,
            OperationAuditService operationAuditService,
            @Value("${forklift-erp.import-retention.days:30}") int retentionDays,
            @Value("${forklift-erp.import-retention.enabled:true}") boolean enabled
    ) {
        this.jobRepository = jobRepository;
        this.fileStorage = fileStorage;
        this.operationAuditService = operationAuditService;
        this.retentionDays = Math.max(1, retentionDays);
        this.enabled = enabled;
    }

    @Scheduled(
            cron = "${forklift-erp.import-retention.cron:0 15 2 * * *}",
            zone = "Asia/Shanghai"
    )
    @Transactional
    public int purgeExpiredFiles() {
        if (!enabled) {
            return 0;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        List<DataImportJob> expired =
                jobRepository.findByStagedFileNameIsNotNullAndCreatedAtBeforeOrderByIdAsc(cutoff);
        int deleted = 0;
        for (DataImportJob job : expired) {
            try {
                fileStorage.delete(job.getStagedFileName());
            } catch (RuntimeException ex) {
                log.error("Failed to purge import source file for jobId={}", job.getId(), ex);
                continue;
            }
            job.setStagedFileName(null);
            jobRepository.save(job);
            deleted++;
        }
        if (deleted > 0) {
            operationAuditService.record(
                    "Data import",
                    "SOURCE_FILE_RETENTION",
                    "DATA_IMPORT_JOB",
                    null,
                    null,
                    "Import source files",
                    "Deleted " + deleted + " source files older than " + retentionDays + " days",
                    SecurityUtils.currentUsername(),
                    "Job metadata retained"
            );
        }
        return deleted;
    }
}
