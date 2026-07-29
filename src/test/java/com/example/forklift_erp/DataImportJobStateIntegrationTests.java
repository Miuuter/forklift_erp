package com.example.forklift_erp;

import com.example.forklift_erp.entity.DataImportJob;
import com.example.forklift_erp.repository.DataImportJobRepository;
import com.example.forklift_erp.service.impl.DataImportJobStatusService;
import com.example.forklift_erp.service.impl.DataImportRecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class DataImportJobStateIntegrationTests extends TestcontainersDatabaseSupport {

    @Autowired
    private DataImportJobRepository jobRepository;

    @Autowired
    private DataImportJobStatusService jobStatusService;

    @Autowired
    private DataImportRecoveryService importRecoveryService;

    @Test
    void claimingReadyJobAdvancesVersionAndRejectsStaleWriter() {
        DataImportJob stale = new DataImportJob();
        stale.setImportType("PARTS");
        stale.setImportMode("BUSINESS_DOCUMENT");
        stale.setOriginalFileName("version-claim.xlsx");
        stale.setStatus("READY");
        stale = jobRepository.saveAndFlush(stale);
        Long originalVersion = stale.getVersion();

        DataImportJob claimed = jobStatusService.markImporting(stale.getId());

        assertThat(claimed.getStatus()).isEqualTo("IMPORTING");
        assertThat(claimed.getVersion()).isEqualTo(originalVersion + 1);
        assertThat(claimed.getStartedAt()).isNotNull();

        stale.setSummary("stale writer must not overwrite claimed state");
        DataImportJob detachedStale = stale;
        assertThatThrownBy(() -> jobRepository.saveAndFlush(detachedStale))
                .isInstanceOf(OptimisticLockingFailureException.class);

        DataImportJob persisted = jobRepository.findById(claimed.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo("IMPORTING");
        assertThat(persisted.getSummary()).isNull();
        assertThat(persisted.getVersion()).isEqualTo(originalVersion + 1);

        jobRepository.deleteById(claimed.getId());
    }

    @Test
    void recoveryFailsOnlyExpiredImportingJobsAndAdvancesTheirVersion() {
        DataImportJob expired = importingJob("expired-import.xlsx", LocalDateTime.now().minusDays(2));
        DataImportJob active = importingJob("active-import.xlsx", LocalDateTime.now());
        expired = jobRepository.saveAndFlush(expired);
        active = jobRepository.saveAndFlush(active);
        Long expiredVersion = expired.getVersion();
        Long activeVersion = active.getVersion();

        int recovered = importRecoveryService.recoverStaleJobs();

        DataImportJob recoveredJob = jobRepository.findById(expired.getId()).orElseThrow();
        DataImportJob activeJob = jobRepository.findById(active.getId()).orElseThrow();
        assertThat(recovered).isEqualTo(1);
        assertThat(recoveredJob.getStatus()).isEqualTo("FAILED");
        assertThat(recoveredJob.getVersion()).isEqualTo(expiredVersion + 1);
        assertThat(recoveredJob.getFinishedAt()).isNotNull();
        assertThat(recoveredJob.getImportedBy()).isEqualTo("system-recovery");
        assertThat(recoveredJob.getSummary()).contains("interrupted");
        assertThat(activeJob.getStatus()).isEqualTo("IMPORTING");
        assertThat(activeJob.getVersion()).isEqualTo(activeVersion);
        assertThat(activeJob.getFinishedAt()).isNull();

        jobRepository.deleteAllByIdInBatch(List.of(expired.getId(), active.getId()));
    }

    private DataImportJob importingJob(String fileName, LocalDateTime startedAt) {
        DataImportJob job = new DataImportJob();
        job.setImportType("PARTS");
        job.setImportMode("BUSINESS_DOCUMENT");
        job.setOriginalFileName(fileName);
        job.setStatus("IMPORTING");
        job.setStartedAt(startedAt);
        return job;
    }
}
