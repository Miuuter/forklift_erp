package com.example.forklift_erp.controller;

import com.example.forklift_erp.common.Result;
import com.example.forklift_erp.dto.RentalRecordCreateDTO;
import com.example.forklift_erp.dto.RentalRecordUpdateDTO;
import com.example.forklift_erp.dto.RentalRecordVO;
import com.example.forklift_erp.dto.RentalBillVO;
import com.example.forklift_erp.security.PermissionCodes;
import com.example.forklift_erp.service.RentalRecordService;
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

import java.util.List;

@RestController
@RequestMapping("/api/rentals")
public class RentalRecordController {
    @Autowired
    private RentalRecordService service;

    @GetMapping
    @PreAuthorize(PermissionCodes.HAS_STOCK_ADJUST)
    public Result<?> getAll(@RequestParam(defaultValue = "true") boolean paged,
                            @RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) Integer page,
                            @RequestParam(required = false) Integer size) {
        if (paged) {
            return Result.success(service.findPage(keyword, status, page, size));
        }
        return Result.success(service.findAll());
    }

    @GetMapping("/{id}")
    @PreAuthorize(PermissionCodes.HAS_STOCK_ADJUST)
    public Result<RentalRecordVO> getById(@PathVariable Long id) {
        return Result.success(service.findById(id));
    }

    @GetMapping("/{id}/bills")
    @PreAuthorize(PermissionCodes.HAS_STOCK_ADJUST)
    public Result<List<RentalBillVO>> getBills(@PathVariable Long id) {
        return Result.success(service.findBills(id));
    }

    @PostMapping("/{id}/bills/refresh")
    @PreAuthorize(PermissionCodes.HAS_STOCK_ADJUST)
    public Result<List<RentalBillVO>> refreshBills(@PathVariable Long id) {
        return Result.success("租赁账单已刷新", service.refreshBills(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(PermissionCodes.HAS_STOCK_ADJUST)
    public Result<RentalRecordVO> create(@Valid @RequestBody RentalRecordCreateDTO request) {
        return Result.success("租赁记录创建成功", service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize(PermissionCodes.HAS_STOCK_ADJUST)
    public Result<RentalRecordVO> update(@PathVariable Long id, @Valid @RequestBody RentalRecordUpdateDTO request) {
        return Result.success("租赁记录更新成功", service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(PermissionCodes.HAS_STOCK_ADJUST)
    public Result<Void> delete(@PathVariable Long id, @RequestParam(required = false) Long version) {
        service.delete(id, version);
        return Result.success("租赁记录删除成功", null);
    }
}
