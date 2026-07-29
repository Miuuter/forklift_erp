package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.repository.DataImportJobRepository;
import com.example.forklift_erp.service.OperationAuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class DataImportRecoveryService {
    static final String RECOVERY_SUMMARY =
            "Import was interrupted or exceeded its execution lease; its business transaction was rolled back. Validate the source file again before retrying.";
    private static final String RECOVERY_ACTOR = "system-recovery";

    private final DataImportJobRepository jobRepository;
    private final OperationAuditService operationAuditService;
    private final int timeoutMinutes;
    private final boolean enabled;

    public DataImportRecoveryService(
            DataImportJobRepository jobRepository,
            OperationAuditService operationAuditService,
            @Value("${forklift-erp.import-recovery.timeout-minutes:1440}") int timeoutMinutes,
            @Value("${forklift-erp.import-recovery.enabled:true}") boolean enabled
    ) {
        this.jobRepository = jobRepository;
        this.operationAuditService = operationAuditService;
        this.timeoutMinutes = Math.max(5, timeoutMinutes);
        this.enabled = enabled;
    }

    @Scheduled(
            fixedDelayString = "${forklift-erp.import-recovery.interval-milliseconds:900000}",
            initialDelayString = "${forklift-erp.import-recovery.initial-delay-milliseconds:60000}"
    )
    @Transactional
    public int recoverStaleJobs() {
        if (!enabled) {
            return 0;
        }
        LocalDateTime recoveredAt = LocalDateTime.now();
        LocalDateTime cutoff = recoveredAt.minusMinutes(timeoutMinutes);
        List<Long> staleJobIds = jobRepository.findStaleImportingJobIds(cutoff);
        int recovered = 0;
        for (Long jobId : staleJobIds) {
            recovered += jobRepository.failStaleImportIfStillInProgress(
                    jobId,
                    cutoff,
                    RECOVERY_SUMMARY,
                    RECOVERY_ACTOR,
                    recoveredAt
            );
        }
        if (recovered > 0) {
            log.warn("Recovered {} stale data-import job(s) after a {} minute timeout", recovered, timeoutMinutes);
            operationAuditService.record(
                    "Data import",
                    "STALE_JOB_RECOVERY",
                    "DATA_IMPORT_JOB",
                    null,
                    null,
                    "Stale import jobs",
                    "Marked " + recovered + " interrupted import job(s) as failed",
                    RECOVERY_ACTOR,
                    "Timeout=" + timeoutMinutes + " minutes"
            );
        }
        return recovered;
    }
}
