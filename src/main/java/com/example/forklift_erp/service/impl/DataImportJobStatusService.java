package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.entity.DataImportJob;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.DataImportJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class DataImportJobStatusService {
    private final DataImportJobRepository jobRepository;

    public DataImportJobStatusService(DataImportJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DataImportJob markImporting(Long jobId) {
        if (jobRepository.claimReadyForImport(jobId) != 1) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Import job has already been confirmed or is no longer ready");
        }
        return findJob(jobId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DataImportJob markCompleted(Long jobId, int importedRows, int skippedRows, String summary, String importedBy) {
        DataImportJob job = findJob(jobId);
        job.setStatus("COMPLETED");
        job.setImportedRows(importedRows);
        job.setSkippedRows(skippedRows);
        job.setSummary(summary);
        job.setImportedBy(importedBy);
        job.setFinishedAt(LocalDateTime.now());
        return jobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DataImportJob markFailed(Long jobId, String summary, String importedBy) {
        DataImportJob job = findJob(jobId);
        job.setStatus("FAILED");
        job.setSummary(summary);
        job.setImportedBy(importedBy);
        job.setFinishedAt(LocalDateTime.now());
        return jobRepository.save(job);
    }

    private DataImportJob findJob(Long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Import job not found"));
    }
}
