package com.example.forklift_erp.controller;

import com.example.forklift_erp.common.Result;
import com.example.forklift_erp.dto.StockTransferDTO;
import com.example.forklift_erp.dto.WarehouseDTO;
import com.example.forklift_erp.dto.WarehouseVO;
import com.example.forklift_erp.security.PermissionCodes;
import com.example.forklift_erp.service.WarehouseService;
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
@RequestMapping("/api/warehouses")
public class WarehouseController {
    @Autowired
    private WarehouseService service;

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

    @GetMapping("/balance")
    @PreAuthorize(PermissionCodes.HAS_STOCK_ADJUST)
    public Result<Integer> getAvailableQuantity(
            @RequestParam String resourceType,
            @RequestParam Long resourceId,
            @RequestParam(required = false) Long warehouseId
    ) {
        return Result.success(service.availableQuantity(resourceType, resourceId, warehouseId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(PermissionCodes.HAS_STOCK_ADJUST)
    public Result<WarehouseVO> create(@Valid @RequestBody WarehouseDTO request) {
        return Result.success("Warehouse created", service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize(PermissionCodes.HAS_STOCK_ADJUST)
    public Result<WarehouseVO> update(@PathVariable Long id, @Valid @RequestBody WarehouseDTO request) {
        return Result.success("Warehouse updated", service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(PermissionCodes.HAS_STOCK_ADJUST)
    public Result<Void> delete(@PathVariable Long id, @RequestParam(required = false) Long version) {
        service.delete(id, version);
        return Result.success("Warehouse deleted");
    }

    @PostMapping("/transfer")
    @PreAuthorize(PermissionCodes.HAS_STOCK_ADJUST)
    public Result<WarehouseVO> transfer(@Valid @RequestBody StockTransferDTO request) {
        return Result.success("Stock transferred", service.transfer(request));
    }
}
