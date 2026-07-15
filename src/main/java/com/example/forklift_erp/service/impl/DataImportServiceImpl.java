package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.PageResult;
import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.dto.DataImportErrorVO;
import com.example.forklift_erp.dto.DataImportJobVO;
import com.example.forklift_erp.dto.DataImportTemplateFile;
import com.example.forklift_erp.dto.DataImportValidationVO;
import com.example.forklift_erp.entity.DataImportJob;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.DataImportJobRepository;
import com.example.forklift_erp.service.DataImportService;
import com.example.forklift_erp.service.OperationAuditService;
import com.example.forklift_erp.util.ListPageSupport;
import com.example.forklift_erp.util.SecurityUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class DataImportServiceImpl implements DataImportService {
    private static final Set<String> VEHICLE_IMPORT_TYPES = Set.of("VEHICLE", "VEHICLE-WORKBOOK", "FULL", "FULL-WORKBOOK");
    private static final Set<String> PART_IMPORT_TYPES = Set.of("PART", "PARTS", "PARTS-PURCHASE", "PARTS-PURCHASE-WORKBOOK");

    private final DataImportJobRepository jobRepository;
    private final DataImportJobStatusService jobStatusService;
    private final DataImportVehicleImporter vehicleImporter;
    private final DataImportPartsImporter partsImporter;
    private final ObjectMapper objectMapper;
    private final DataImportFileStorage importFileStorage;
    private final DataImportTemplateBuilder templateBuilder;
    private final DataImportWorkbookReader workbookReader;
    private final DataImportWorkbookValidator workbookValidator;
    private final OperationAuditService operationAuditService;
    private final TransactionTemplate importTransactionTemplate;

    public DataImportServiceImpl(
            PlatformTransactionManager transactionManager,
            DataImportJobRepository jobRepository,
            DataImportJobStatusService jobStatusService,
            DataImportVehicleImporter vehicleImporter,
            DataImportPartsImporter partsImporter,
            ObjectMapper objectMapper,
            DataImportFileStorage importFileStorage,
            DataImportTemplateBuilder templateBuilder,
            DataImportWorkbookReader workbookReader,
            DataImportWorkbookValidator workbookValidator,
            OperationAuditService operationAuditService
    ) {
        this.importTransactionTemplate = new TransactionTemplate(transactionManager);
        this.jobRepository = jobRepository;
        this.jobStatusService = jobStatusService;
        this.vehicleImporter = vehicleImporter;
        this.partsImporter = partsImporter;
        this.objectMapper = objectMapper;
        this.importFileStorage = importFileStorage;
        this.templateBuilder = templateBuilder;
        this.workbookReader = workbookReader;
        this.workbookValidator = workbookValidator;
        this.operationAuditService = operationAuditService;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<DataImportJobVO> findPage(String importType, String keyword, Integer page, Integer size) {
        int pageNumber = ListPageSupport.page(page);
        int pageSize = ListPageSupport.size(size);
        Page<DataImportJob> result = jobRepository.search(trimToNull(importType), trimToNull(keyword),
                ListPageSupport.pageRequest(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return PageResult.of(result.getContent().stream().map(DataImportJobVO::fromEntity).toList(),
                pageNumber, pageSize, result.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public DataImportJobVO findById(Long id) {
        return DataImportJobVO.fromEntity(findJobOrThrow(id));
    }

    @Override
    public DataImportTemplateFile template(String importType) {
        String normalizedType = normalizeImportType(importType);
        if (VEHICLE_IMPORT_TYPES.contains(normalizedType)) {
            return templateBuilder.vehicleTemplate();
        }
        if (PART_IMPORT_TYPES.contains(normalizedType)) {
            return templateBuilder.partTemplate();
        }
        throw new BusinessException(ResultCode.PARAM_ERROR, "Unsupported import type");
    }

    @Override
    @Transactional
    public DataImportValidationVO validate(String importType, String importMode, MultipartFile file) {
        ImportProfile profile = profile(importType);
        String normalizedMode = normalizeImportMode(importMode, profile);
        DataImportJob job = createJob(profile, normalizedMode, file);
        Path stagedFile = importFileStorage.store(file, job.getId(), profile.code());
        importFileStorage.registerRollbackCleanup(stagedFile);
        job.setStagedFileName(stagedFile.getFileName().toString());
        job.setFileFingerprint(importFileStorage.fingerprint(stagedFile));

        ValidationResult validation = profile.validate(stagedFile);
        job.setTotalRows(validation.totalRows());
        job.setValidRows(validation.validRows());
        job.setErrorRows(validation.errorRows());
        job.setErrorDetails(validation.errors().size());
        job.setSummary(validation.summary());
        job.setErrorRowsJson(writeJson(validation.errors()));
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>(validation.snapshot());
        snapshot.put("importMode", normalizedMode);
        snapshot.put("fileFingerprint", job.getFileFingerprint());
        job.setValidationSnapshotJson(writeJson(snapshot));
        job.setStatus(validation.importable() ? "READY" : "VALIDATION_FAILED");
        jobRepository.save(job);
        return toValidationVO(job, validation.errors(), validation.importable());
    }

    @Override
    public DataImportValidationVO confirm(Long jobId) {
        DataImportJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Import job not found"));
        if (!"READY".equals(job.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Import job is not ready");
        }

        ImportProfile profile = profile(job.getImportType());
        Path stagedFile = importFileStorage.resolve(job.getStagedFileName());
        if (!Files.isRegularFile(stagedFile)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "Import file not found");
        }

        jobStatusService.markImporting(job.getId());

        try {
            ImportContext context = new ImportContext(
                    job.getId(),
                    job.getImportType(),
                    normalizeImportMode(job.getImportMode(), profile),
                    job.getFileFingerprint()
            );
            ImportResult result = importTransactionTemplate.execute(status -> profile.importFile(stagedFile, context));
            DataImportJob completedJob = jobStatusService.markCompleted(
                    job.getId(),
                    result == null ? 0 : result.importedRows(),
                    result == null ? 0 : result.skippedRows(),
                    result == null ? "Import completed" : result.summary(),
                    SecurityUtils.currentUsername()
            );
            operationAuditService.record(
                    "Data import",
                    "CONFIRM",
                    "DATA_IMPORT_JOB",
                    completedJob.getId(),
                    String.valueOf(completedJob.getId()),
                    completedJob.getOriginalFileName(),
                    "Import confirmed: imported=" + completedJob.getImportedRows()
                            + ", skipped=" + completedJob.getSkippedRows(),
                    SecurityUtils.currentUsername(),
                    completedJob.getSummary(),
                    "DATA_IMPORT_JOB",
                    completedJob.getId()
            );
            return toValidationVO(completedJob, List.of(), true);
        } catch (RuntimeException ex) {
            jobStatusService.markFailed(job.getId(), firstNonBlank(ex.getMessage(), "Import failed"), SecurityUtils.currentUsername());
            throw ex;
        }
    }

    private DataImportJob createJob(ImportProfile profile, String importMode, MultipartFile file) {
        DataImportJob job = new DataImportJob();
        job.setImportType(profile.code());
        job.setImportMode(importMode);
        job.setTemplateName(profile.templateName());
        job.setOriginalFileName(importFileStorage.originalFileName(file, profile.templateName() + ".xlsx"));
        job.setStatus("VALIDATING");
        job.setCreatedBy(SecurityUtils.currentUsername());
        return jobRepository.save(job);
    }

    private DataImportValidationVO toValidationVO(DataImportJob job, List<DataImportErrorVO> errors, boolean importable) {
        DataImportValidationVO vo = new DataImportValidationVO();
        vo.setJob(DataImportJobVO.fromEntity(job));
        vo.setErrors(errors);
        vo.setImportable(importable);
        return vo;
    }

    private ImportProfile profile(String importType) {
        String normalizedType = normalizeImportType(importType);
        if (VEHICLE_IMPORT_TYPES.contains(normalizedType)) {
            return new VehicleWorkbookProfile();
        }
        if (PART_IMPORT_TYPES.contains(normalizedType)) {
            return new PartsPurchaseProfile();
        }
        throw new BusinessException(ResultCode.PARAM_ERROR, "Unsupported import type");
    }

    private DataImportJob findJobOrThrow(Long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Import job not found"));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String normalizeImportType(String importType) {
        String normalized = trimToNull(importType);
        return normalized == null ? "" : normalized.toUpperCase(Locale.ROOT).replace("_", "-");
    }

    private String normalizeImportMode(String importMode, ImportProfile profile) {
        String normalized = trimToNull(importMode);
        if (normalized == null) {
            return profile.defaultImportMode();
        }
        normalized = normalized.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (ImportContext.MODE_OPENING_MIGRATION.equals(normalized)
                || ImportContext.MODE_BUSINESS_DOCUMENT.equals(normalized)
                || ImportContext.MODE_MASTER_DATA.equals(normalized)) {
            return normalized;
        }
        throw new BusinessException(ResultCode.PARAM_ERROR,
                "Import mode must be OPENING_MIGRATION, BUSINESS_DOCUMENT or MASTER_DATA");
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private interface ImportProfile {
        String code();

        String templateName();

        ValidationResult validate(Path file);

        ImportResult importFile(Path file, ImportContext context);

        String defaultImportMode();
    }

    private final class VehicleWorkbookProfile implements ImportProfile {
        @Override
        public String code() {
            return "vehicle-workbook";
        }

        @Override
        public String templateName() {
            return "vehicle-workbook";
        }

        @Override
        public ValidationResult validate(Path file) {
            WorkbookSnapshot snapshot = workbookReader.readVehicleWorkbook(file);
            List<DataImportErrorVO> errors = workbookValidator.validateVehicleRows(snapshot);
            int totalRows = snapshot.totalRows();
            int errorRows = distinctErrorRows(errors);
            int validRows = Math.max(0, totalRows - errorRows);
            return new ValidationResult(totalRows, validRows, errorRows, errors, errors.isEmpty(),
                    errors.isEmpty() ? "Workbook validated successfully" : "Workbook validation found " + errors.size() + " row issues",
                    Map.of("sheets", snapshot.sheetSizes()));
        }

        @Override
        public ImportResult importFile(Path file, ImportContext context) {
            WorkbookSnapshot snapshot = workbookReader.readVehicleWorkbook(file);
            return vehicleImporter.importWorkbook(snapshot, context);
        }

        @Override
        public String defaultImportMode() {
            return ImportContext.MODE_BUSINESS_DOCUMENT;
        }
    }

    private final class PartsPurchaseProfile implements ImportProfile {
        @Override
        public String code() {
            return "parts-purchase";
        }

        @Override
        public String templateName() {
            return "parts-purchase";
        }

        @Override
        public ValidationResult validate(Path file) {
            WorkbookSnapshot snapshot = workbookReader.readPartsWorkbook(file);
            List<DataImportErrorVO> errors = workbookValidator.validatePartRows(snapshot);
            int totalRows = snapshot.sheetRows("Parts").size();
            int errorRows = distinctErrorRows(errors);
            int validRows = Math.max(0, totalRows - errorRows);
            return new ValidationResult(totalRows, validRows, errorRows, errors, errors.isEmpty(),
                    errors.isEmpty() ? "Workbook validated successfully" : "Workbook validation found " + errors.size() + " row issues",
                    Map.of("sheet", "Parts", "rows", totalRows));
        }

        @Override
        public ImportResult importFile(Path file, ImportContext context) {
            WorkbookSnapshot snapshot = workbookReader.readPartsWorkbook(file);
            return partsImporter.importWorkbook(snapshot, context);
        }

        @Override
        public String defaultImportMode() {
            return ImportContext.MODE_OPENING_MIGRATION;
        }
    }

    private int distinctErrorRows(List<DataImportErrorVO> errors) {
        return (int) errors.stream()
                .map(error -> new ErrorRowKey(
                        Objects.toString(error.getSheetName(), ""),
                        error.getRowNumber()
                ))
                .distinct()
                .count();
    }

    private record ErrorRowKey(String sheetName, Integer rowNumber) {
    }

    private record ValidationResult(int totalRows, int validRows, int errorRows,
                                    List<DataImportErrorVO> errors, boolean importable,
                                    String summary, Map<String, Object> snapshot) {
    }

}
