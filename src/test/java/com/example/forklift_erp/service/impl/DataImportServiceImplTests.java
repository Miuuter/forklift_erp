package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.entity.DataImportJob;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.DataImportJobRepository;
import com.example.forklift_erp.service.OperationAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DataImportServiceImplTests {

    @Test
    void confirmRejectsAStagedFileThatChangedAfterValidation() {
        DataImportJobRepository jobRepository = mock(DataImportJobRepository.class);
        DataImportJobStatusService jobStatusService = mock(DataImportJobStatusService.class);
        DataImportVehicleImporter vehicleImporter = mock(DataImportVehicleImporter.class);
        DataImportPartsImporter partsImporter = mock(DataImportPartsImporter.class);
        DataImportFileStorage fileStorage = mock(DataImportFileStorage.class);
        Path stagedFile = Path.of("pom.xml").toAbsolutePath();

        DataImportJob job = new DataImportJob();
        job.setId(7L);
        job.setStatus("READY");
        job.setImportType("vehicle-workbook");
        job.setImportMode(ImportContext.MODE_BUSINESS_DOCUMENT);
        job.setStagedFileName("staged.xlsx");
        job.setFileFingerprint("validated-fingerprint");

        when(jobRepository.findById(7L)).thenReturn(Optional.of(job));
        when(jobStatusService.markImporting(7L)).thenReturn(job);
        when(fileStorage.resolve("staged.xlsx")).thenReturn(stagedFile);
        when(fileStorage.fingerprint(stagedFile)).thenReturn("changed-fingerprint");

        DataImportServiceImpl service = new DataImportServiceImpl(
                mock(PlatformTransactionManager.class),
                jobRepository,
                jobStatusService,
                vehicleImporter,
                partsImporter,
                mock(ObjectMapper.class),
                fileStorage,
                mock(DataImportTemplateBuilder.class),
                mock(DataImportWorkbookReader.class),
                mock(DataImportWorkbookValidator.class),
                mock(OperationAuditService.class)
        );

        assertThatThrownBy(() -> service.confirm(7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("changed after validation");

        verify(jobStatusService).markImporting(7L);
        verify(jobStatusService).markFailed(eq(7L), contains("changed after validation"), any());
        verifyNoInteractions(vehicleImporter, partsImporter);
    }
}
