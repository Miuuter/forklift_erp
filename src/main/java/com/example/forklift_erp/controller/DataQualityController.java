package com.example.forklift_erp.controller;

import com.example.forklift_erp.common.Result;
import com.example.forklift_erp.dto.HistoricalRepairReportVO;
import com.example.forklift_erp.dto.HistoricalRepairRequestDTO;
import com.example.forklift_erp.dto.MigrationExceptionVO;
import com.example.forklift_erp.security.PermissionCodes;
import com.example.forklift_erp.service.HistoricalDataRepairService;
import com.example.forklift_erp.service.HistoricalRepairBackupService;
import com.example.forklift_erp.service.MigrationExceptionService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Super-admin-only tools for safely repairing historical data and handling
 * records that intentionally require a human decision.
 */
@RestController
@RequestMapping("/api/data-quality")
@PreAuthorize(PermissionCodes.HAS_SUPER_ADMIN)
public class DataQualityController {
    private final HistoricalDataRepairService historicalDataRepairService;
    private final MigrationExceptionService migrationExceptionService;
    private final HistoricalRepairBackupService historicalRepairBackupService;

    public DataQualityController(
            HistoricalDataRepairService historicalDataRepairService,
            MigrationExceptionService migrationExceptionService,
            HistoricalRepairBackupService historicalRepairBackupService
    ) {
        this.historicalDataRepairService = historicalDataRepairService;
        this.migrationExceptionService = migrationExceptionService;
        this.historicalRepairBackupService = historicalRepairBackupService;
    }

    @GetMapping("/historical-repair/dry-run")
    public Result<HistoricalRepairReportVO> dryRun(
            @RequestParam(required = false) LocalDate fallbackBusinessDate
    ) {
        return Result.success(historicalDataRepairService.dryRun(fallbackBusinessDate));
    }

    @PostMapping("/historical-repair")
    public Result<HistoricalRepairReportVO> repair(@RequestBody HistoricalRepairRequestDTO request) {
        return Result.success("Historical repair completed", historicalDataRepairService.repair(request));
    }

    @GetMapping("/historical-repair/backups/{filename:.+}")
    public ResponseEntity<byte[]> downloadBackup(@PathVariable String filename) {
        byte[] payload = historicalRepairBackupService.read(filename);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(payload);
    }

    @GetMapping("/migration-exceptions")
    public Result<List<MigrationExceptionVO>> exceptions(@RequestParam(required = false) String status) {
        return Result.success(migrationExceptionService.findAll(status));
    }

    @PutMapping("/migration-exceptions/{id}/resolve")
    public Result<MigrationExceptionVO> resolve(@PathVariable Long id) {
        return Result.success("Migration exception resolved", migrationExceptionService.resolve(id));
    }
}
