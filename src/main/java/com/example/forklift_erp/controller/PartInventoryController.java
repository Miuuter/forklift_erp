package com.example.forklift_erp.controller;

import com.example.forklift_erp.common.Result;
import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.dto.PartInventoryCreateDTO;
import com.example.forklift_erp.dto.PartInventoryVO;
import com.example.forklift_erp.dto.PartStockAdjustRequestDTO;
import com.example.forklift_erp.dto.RemovedPartValuationDTO;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.security.PermissionCodes;
import com.example.forklift_erp.service.PartInventoryService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/parts")
public class PartInventoryController {

    @Autowired
    private PartInventoryService partService;

    @GetMapping
    public Result<?> getAll(@RequestParam(defaultValue = "true") boolean paged,
                            @RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String stock,
                            @RequestParam(required = false) Integer page,
                            @RequestParam(required = false) Integer size) {
        if (paged) {
            return Result.success(partService.findPage(keyword, stock, page, size));
        }
        return Result.success(partService.toVOs(partService.findAll()));
    }

    @GetMapping("/{id}")
    public Result<PartInventoryVO> getById(@PathVariable Long id) {
        return partService.findById(id)
                .map(partService::toVO)
                .map(Result::success)
                .orElseThrow(() -> new BusinessException(ResultCode.PART_NOT_FOUND, "Part not found"));
    }

    @GetMapping("/code/{partCode}")
    public Result<PartInventoryVO> getByPartCode(@PathVariable String partCode) {
        return partService.findByPartCode(partCode)
                .map(partService::toVO)
                .map(Result::success)
                .orElseThrow(() -> new BusinessException(ResultCode.PART_NOT_FOUND, "Part code not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(PermissionCodes.HAS_PART_WRITE)
    public Result<PartInventoryVO> create(@Valid @RequestBody PartInventoryCreateDTO dto) {
        return Result.success("新增成功", partService.create(dto));
    }

    @PutMapping("/{id}")
    @PreAuthorize(PermissionCodes.HAS_PART_WRITE)
    public Result<PartInventoryVO> update(@PathVariable Long id, @Valid @RequestBody PartInventoryCreateDTO dto) {
        return Result.success("更新成功", partService.update(id, dto));
    }

    @PutMapping("/{id}/valuation")
    @PreAuthorize(PermissionCodes.HAS_ADMIN_OR_SUPER_ADMIN)
    public Result<PartInventoryVO> valueRemovedPart(
            @PathVariable Long id,
            @Valid @RequestBody RemovedPartValuationDTO dto
    ) {
        return Result.success("旧件估值已确认", partService.valueRemovedPart(id, dto));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(PermissionCodes.HAS_PART_WRITE)
    public Result<Void> delete(@PathVariable Long id, @RequestParam(required = false) Long version) {
        partService.delete(id, version);
        return Result.success("删除成功");
    }

    @GetMapping("/category/{category}")
    public Result<List<PartInventoryVO>> getByCategory(@PathVariable String category) {
        return Result.success(partService.toVOs(partService.findByCategory(category)));
    }

    @GetMapping("/available")
    public Result<List<PartInventoryVO>> getAvailable() {
        return Result.success(partService.toVOs(partService.findAvailableParts()));
    }

    @GetMapping("/source/{source}")
    public Result<List<PartInventoryVO>> getBySource(@PathVariable String source) {
        return Result.success(partService.toVOs(partService.findBySource(source)));
    }

    @GetMapping("/sourceMachine/{machineId}")
    public Result<List<PartInventoryVO>> getBySourceMachineId(@PathVariable Long machineId) {
        return Result.success(partService.toVOs(partService.findBySourceMachineId(machineId)));
    }

    @PutMapping("/inbound")
    @PreAuthorize(PermissionCodes.HAS_STOCK_ADJUST)
    public Result<PartInventoryVO> inbound(@Valid @RequestBody PartStockAdjustRequestDTO request) {
        return Result.success("入库成功", partService.inbound(request));
    }

    @PutMapping("/outbound")
    @PreAuthorize(PermissionCodes.HAS_ADMIN_OR_SUPER_ADMIN)
    public Result<PartInventoryVO> outbound(@Valid @RequestBody PartStockAdjustRequestDTO request) {
        return Result.success("出库成功", partService.outbound(request));
    }
}
