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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class DataImportRetentionService {
    private static final List<String> TERMINAL_STATUSES = List.of(
            "COMPLETED", "FAILED", "VALIDATION_FAILED"
    );
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
        List<Long> expiredJobIds = jobRepository.findTerminalExpiredFileJobIds(cutoff, TERMINAL_STATUSES);
        int deleted = 0;
        for (Long jobId : expiredJobIds) {
            DataImportJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
            if (!eligible(job, cutoff)) {
                continue;
            }
            String stagedFileName = job.getStagedFileName();
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                try {
                    fileStorage.delete(stagedFileName);
                } catch (RuntimeException ex) {
                    log.error("Failed to purge import source file for jobId={}", job.getId(), ex);
                    continue;
                }
            } else {
                deleteAfterCommit(job.getId(), stagedFileName);
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

    private boolean eligible(DataImportJob job, LocalDateTime cutoff) {
        return job != null
                && job.getStagedFileName() != null
                && !job.getStagedFileName().isBlank()
                && job.getCreatedAt() != null
                && job.getCreatedAt().isBefore(cutoff)
                && TERMINAL_STATUSES.contains(job.getStatus());
    }

    private void deleteAfterCommit(Long jobId, String stagedFileName) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    fileStorage.delete(stagedFileName);
                } catch (RuntimeException ex) {
                    // The metadata no longer references the file, so a retrying
                    // orphan-file sweeper may remove it without risking data loss.
                    log.error("Failed to purge committed import source file for jobId={}", jobId, ex);
                }
            }
        });
    }
}
