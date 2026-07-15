package com.example.forklift_erp.controller;

import com.example.forklift_erp.common.Result;
import com.example.forklift_erp.dto.StocktakingRecordDTO;
import com.example.forklift_erp.dto.StocktakingRecordVO;
import com.example.forklift_erp.dto.VersionedBatchRequest;
import com.example.forklift_erp.security.PermissionCodes;
import com.example.forklift_erp.service.StocktakingRecordService;
import com.example.forklift_erp.service.BatchBusinessOperationService;
import jakarta.validation.Valid;
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

@RestController
@RequestMapping("/api/stocktaking-records")
public class StocktakingRecordController {
    @Autowired
    private StocktakingRecordService service;

    @Autowired
    private BatchBusinessOperationService batchBusinessOperationService;

    @GetMapping
    public Result<?> getAll(@RequestParam(defaultValue = "true") boolean paged,
                            @RequestParam(required = false) String keyword,
                            @RequestParam(required = false) Integer page,
                            @RequestParam(required = false) Integer size) {
        if (paged) {
            return Result.success(service.findPage(keyword, page, size));
        }
        return Result.success(service.findAll());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(PermissionCodes.HAS_STOCK_ADJUST)
    public Result<StocktakingRecordVO> create(@Valid @RequestBody StocktakingRecordDTO request) {
        return Result.success("库存盘点创建成功", service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize(PermissionCodes.HAS_STOCK_ADJUST)
    public Result<StocktakingRecordVO> update(@PathVariable Long id, @Valid @RequestBody StocktakingRecordDTO request) {
        return Result.success("库存盘点更新成功", service.update(id, request));
    }

    @PutMapping("/{id}/complete")
    @PreAuthorize(PermissionCodes.HAS_STOCK_ADJUST)
    public Result<StocktakingRecordVO> complete(@PathVariable Long id, @RequestParam(required = false) Long version) {
        return Result.success("库存盘点已入账", service.complete(id, version));
    }

    @PostMapping("/batch-complete")
    @PreAuthorize(PermissionCodes.HAS_STOCK_ADJUST)
    public Result<java.util.List<StocktakingRecordVO>> batchComplete(
            @Valid @RequestBody VersionedBatchRequest request
    ) {
        return Result.success("库存盘点已批量入账",
                batchBusinessOperationService.completeStocktaking(request));
    }

    @PostMapping("/batch-delete-drafts")
    @PreAuthorize(PermissionCodes.HAS_STOCK_ADJUST)
    public Result<Integer> batchDeleteDrafts(@Valid @RequestBody VersionedBatchRequest request) {
        return Result.success("盘点草稿已批量删除",
                batchBusinessOperationService.deleteStocktakingDrafts(request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(PermissionCodes.HAS_STOCK_ADJUST)
    public Result<Void> delete(@PathVariable Long id, @RequestParam(required = false) Long version) {
        service.delete(id, version);
        return Result.success("库存盘点删除成功");
    }
}
