package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.repository.DataImportJobRepository;
import com.example.forklift_erp.service.OperationAuditService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DataImportRecoveryServiceTests {

    @Test
    void recoversOnlyJobsStillStaleAtTheConditionalUpdate() {
        DataImportJobRepository repository = mock(DataImportJobRepository.class);
        OperationAuditService auditService = mock(OperationAuditService.class);
        when(repository.findStaleImportingJobIds(any(LocalDateTime.class)))
                .thenReturn(List.of(11L, 12L));
        when(repository.failStaleImportIfStillInProgress(
                eq(11L), any(LocalDateTime.class), eq(DataImportRecoveryService.RECOVERY_SUMMARY),
                eq("system-recovery"), any(LocalDateTime.class)
        )).thenReturn(1);
        when(repository.failStaleImportIfStillInProgress(
                eq(12L), any(LocalDateTime.class), eq(DataImportRecoveryService.RECOVERY_SUMMARY),
                eq("system-recovery"), any(LocalDateTime.class)
        )).thenReturn(0);

        DataImportRecoveryService service =
                new DataImportRecoveryService(repository, auditService, 60, true);

        assertThat(service.recoverStaleJobs()).isEqualTo(1);

        verify(auditService).record(
                eq("Data import"), eq("STALE_JOB_RECOVERY"), eq("DATA_IMPORT_JOB"),
                eq(null), eq(null), eq("Stale import jobs"),
                eq("Marked 1 interrupted import job(s) as failed"),
                eq("system-recovery"), eq("Timeout=60 minutes")
        );
    }

    @Test
    void disabledRecoveryDoesNotReadOrMutateJobs() {
        DataImportJobRepository repository = mock(DataImportJobRepository.class);
        OperationAuditService auditService = mock(OperationAuditService.class);
        DataImportRecoveryService service =
                new DataImportRecoveryService(repository, auditService, 60, false);

        assertThat(service.recoverStaleJobs()).isZero();

        verify(repository, never()).findStaleImportingJobIds(any());
        verifyNoInteractions(auditService);
    }
}
