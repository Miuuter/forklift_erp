package com.example.forklift_erp.service;

import com.example.forklift_erp.entity.MigrationException;
import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.dto.MigrationExceptionVO;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.MigrationExceptionRepository;
import com.example.forklift_erp.util.SecurityUtils;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Records facts that cannot be reconstructed safely from historic data.
 * The independent transaction is intentional: a business request can be
 * rejected while its data-quality exception remains visible for follow-up.
 */
@Service
public class MigrationExceptionService {
    private final MigrationExceptionRepository migrationExceptionRepository;
    private final OperationAuditService operationAuditService;

    public MigrationExceptionService(
            MigrationExceptionRepository migrationExceptionRepository,
            OperationAuditService operationAuditService
    ) {
        this.migrationExceptionRepository = migrationExceptionRepository;
        this.operationAuditService = operationAuditService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void openOnce(String exceptionType, String sourceType, Long sourceId, String detail) {
        if (migrationExceptionRepository.existsByExceptionTypeAndSourceTypeAndSourceIdAndStatus(
                exceptionType, sourceType, sourceId, "OPEN")) {
            return;
        }
        MigrationException exception = new MigrationException();
        exception.setExceptionType(exceptionType);
        exception.setSourceType(sourceType);
        exception.setSourceId(sourceId);
        exception.setDetail(detail);
        exception.setStatus("OPEN");
        migrationExceptionRepository.save(exception);
    }

    @Transactional(readOnly = true)
    public List<MigrationExceptionVO> findAll(String status) {
        List<MigrationException> rows = status == null || status.isBlank()
                ? migrationExceptionRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt", "id"))
                : migrationExceptionRepository.findByStatusOrderByCreatedAtDescIdDesc(status.trim().toUpperCase());
        return rows.stream().map(MigrationExceptionVO::fromEntity).toList();
    }

    @Transactional
    public MigrationExceptionVO resolve(Long id) {
        MigrationException exception = migrationExceptionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Migration exception not found"));
        if (!"RESOLVED".equals(exception.getStatus())) {
            exception.setStatus("RESOLVED");
            exception.setResolvedAt(LocalDateTime.now());
            exception.setResolvedBy(SecurityUtils.currentUsername());
            migrationExceptionRepository.save(exception);
            operationAuditService.record(
                    "Data quality",
                    "RESOLVE",
                    "MIGRATION_EXCEPTION",
                    exception.getId(),
                    exception.getExceptionType(),
                    exception.getSourceType() + ":" + exception.getSourceId(),
                    "Resolve migration exception",
                    SecurityUtils.currentUsername(),
                    exception.getDetail(),
                    exception.getSourceType(),
                    exception.getSourceId()
            );
        }
        return MigrationExceptionVO.fromEntity(exception);
    }
}
