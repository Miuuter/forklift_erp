package com.example.forklift_erp.controller;

import com.example.forklift_erp.common.Result;
import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.constant.RepairStatus;
import com.example.forklift_erp.dto.RepairRecordCreateDTO;
import com.example.forklift_erp.dto.RepairRecordVO;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.security.PermissionCodes;
import com.example.forklift_erp.service.RepairRecordService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@Slf4j
@RestController
@RequestMapping("/api/repairs")
public class RepairRecordController {

    @Autowired
    private RepairRecordService repairService;

    @Data
    public static class StatusUpdateRequest {
        private Long version;

        @Pattern(regexp = RepairStatus.VALIDATION_PATTERN, message = "Invalid status")
        private String status;
    }

    @GetMapping
    public Result<?> getAll(
            @RequestParam(defaultValue = "true") boolean paged,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Long machineId,
            @RequestParam(required = false) String repairPerson,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        if (paged) {
            return Result.success(repairService.findPage(keyword, page, size, machineId, repairPerson, status, startDate, endDate));
        }
        if (machineId != null) {
            return Result.success(repairService.findByMachineId(machineId).stream().map(repairService::toVO).toList());
        }
        if (repairPerson != null) {
            return Result.success(repairService.findByRepairPerson(repairPerson).stream().map(repairService::toVO).toList());
        }
        if (status != null) {
            return Result.success(repairService.findByStatus(status).stream().map(repairService::toVO).toList());
        }
        if (startDate != null && endDate != null) {
            return Result.success(repairService.findByDateRange(startDate, endDate).stream().map(repairService::toVO).toList());
        }
        return Result.success(repairService.findAll().stream().map(repairService::toVO).toList());
    }

    @GetMapping("/{id}")
    public Result<RepairRecordVO> getById(@PathVariable Long id) {
        return repairService.findById(id)
                .map(repairService::toVO)
                .map(Result::success)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Repair record not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(PermissionCodes.HAS_REPAIR_WRITE)
    public Result<RepairRecordVO> create(@Valid @RequestBody RepairRecordCreateDTO dto) {
        return Result.success("Created", repairService.create(dto));
    }

    @PutMapping("/{id}")
    @PreAuthorize(PermissionCodes.HAS_REPAIR_WRITE)
    public Result<RepairRecordVO> update(@PathVariable Long id, @Valid @RequestBody RepairRecordCreateDTO dto) {
        return Result.success("Updated", repairService.update(id, dto));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize(PermissionCodes.HAS_REPAIR_WRITE)
    public Result<RepairRecordVO> updateStatus(@PathVariable Long id, @Valid @RequestBody StatusUpdateRequest request) {
        return Result.success("Repair status updated", repairService.updateStatus(id, request.getStatus(), request.getVersion()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(PermissionCodes.HAS_REPAIR_WRITE)
    public Result<Void> delete(@PathVariable Long id, @RequestParam(required = false) Long version) {
        repairService.delete(id, version);
        return Result.success("Deleted");
    }
}
