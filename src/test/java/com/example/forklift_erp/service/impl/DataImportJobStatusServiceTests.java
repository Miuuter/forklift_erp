package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.entity.DataImportJob;
import com.example.forklift_erp.repository.DataImportJobRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataImportJobStatusServiceTests {

    @Test
    void markFailedPersistsFailureSnapshot() {
        DataImportJobRepository repository = mock(DataImportJobRepository.class);
        DataImportJob job = new DataImportJob();
        job.setId(7L);
        job.setStatus("IMPORTING");
        when(repository.findById(7L)).thenReturn(Optional.of(job));
        when(repository.failImportIfInProgress(
                eq(7L), eq("Workbook row failed"), eq("tester"), any(LocalDateTime.class)
        )).thenAnswer(invocation -> {
            job.setStatus("FAILED");
            job.setSummary(invocation.getArgument(1));
            job.setImportedBy(invocation.getArgument(2));
            job.setFinishedAt(invocation.getArgument(3));
            return 1;
        });

        DataImportJob saved = new DataImportJobStatusService(repository)
                .markFailed(7L, "Workbook row failed", "tester");

        assertThat(saved.getStatus()).isEqualTo("FAILED");
        assertThat(saved.getSummary()).isEqualTo("Workbook row failed");
        assertThat(saved.getImportedBy()).isEqualTo("tester");
        assertThat(saved.getFinishedAt()).isNotNull();
    }
}
