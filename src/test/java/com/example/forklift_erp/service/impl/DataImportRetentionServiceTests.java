package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.entity.DataImportJob;
import com.example.forklift_erp.repository.DataImportJobRepository;
import com.example.forklift_erp.service.OperationAuditService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DataImportRetentionServiceTests {

    @Test
    void purgeDeletesExpiredFileButRetainsJobMetadata() {
        DataImportJobRepository repository = mock(DataImportJobRepository.class);
        DataImportFileStorage storage = mock(DataImportFileStorage.class);
        OperationAuditService auditService = mock(OperationAuditService.class);
        DataImportJob job = new DataImportJob();
        job.setId(12L);
        job.setOriginalFileName("parts.xlsx");
        job.setStagedFileName("import-parts-12.xlsx");
        job.setStatus("COMPLETED");
        job.setCreatedAt(LocalDateTime.now().minusDays(31));
        when(repository.findTerminalExpiredFileJobIds(any(LocalDateTime.class), anyList()))
                .thenReturn(List.of(12L));
        when(repository.findByIdForUpdate(12L)).thenReturn(java.util.Optional.of(job));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        DataImportRetentionService service =
                new DataImportRetentionService(repository, storage, auditService, 30, true);

        int deleted = service.purgeExpiredFiles();

        assertThat(deleted).isEqualTo(1);
        assertThat(job.getStagedFileName()).isNull();
        assertThat(job.getOriginalFileName()).isEqualTo("parts.xlsx");
        verify(storage).delete("import-parts-12.xlsx");
        verify(repository).save(job);
        verify(auditService).record(
                any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void disabledRetentionDoesNothing() {
        DataImportJobRepository repository = mock(DataImportJobRepository.class);
        DataImportFileStorage storage = mock(DataImportFileStorage.class);
        OperationAuditService auditService = mock(OperationAuditService.class);
        DataImportRetentionService service =
                new DataImportRetentionService(repository, storage, auditService, 30, false);

        assertThat(service.purgeExpiredFiles()).isZero();

        verify(repository, never())
                .findTerminalExpiredFileJobIds(any(), anyList());
        verifyNoInteractions(storage, auditService);
    }

    @Test
    void purgeRechecksStatusUnderLockAndNeverDeletesReadyFiles() {
        DataImportJobRepository repository = mock(DataImportJobRepository.class);
        DataImportFileStorage storage = mock(DataImportFileStorage.class);
        OperationAuditService auditService = mock(OperationAuditService.class);
        DataImportJob job = new DataImportJob();
        job.setId(13L);
        job.setStatus("READY");
        job.setCreatedAt(LocalDateTime.now().minusDays(31));
        job.setStagedFileName("ready.xlsx");
        when(repository.findTerminalExpiredFileJobIds(any(LocalDateTime.class), anyList()))
                .thenReturn(List.of(13L));
        when(repository.findByIdForUpdate(13L)).thenReturn(java.util.Optional.of(job));
        DataImportRetentionService service =
                new DataImportRetentionService(repository, storage, auditService, 30, true);

        assertThat(service.purgeExpiredFiles()).isZero();

        assertThat(job.getStagedFileName()).isEqualTo("ready.xlsx");
        verifyNoInteractions(storage, auditService);
        verify(repository, never()).save(any());
    }
}
