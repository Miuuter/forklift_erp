package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.entity.DataImportJob;
import com.example.forklift_erp.repository.DataImportJobRepository;
import com.example.forklift_erp.service.OperationAuditService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        when(repository.findByStagedFileNameIsNotNullAndCreatedAtBeforeOrderByIdAsc(
                any(LocalDateTime.class)
        )).thenReturn(List.of(job));
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
                .findByStagedFileNameIsNotNullAndCreatedAtBeforeOrderByIdAsc(any());
        verifyNoInteractions(storage, auditService);
    }
}
