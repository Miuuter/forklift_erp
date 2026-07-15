package com.example.forklift_erp.service;

import com.example.forklift_erp.common.PageResult;
import com.example.forklift_erp.dto.PartInventoryCreateDTO;
import com.example.forklift_erp.dto.PartInventoryVO;
import com.example.forklift_erp.dto.PartStockAdjustRequestDTO;
import com.example.forklift_erp.dto.RemovedPartValuationDTO;
import com.example.forklift_erp.entity.PartInventory;

import java.util.List;
import java.util.Optional;

public interface PartInventoryService {
    List<PartInventory> findAll();

    PageResult<PartInventoryVO> findPage(String keyword, String stock, Integer page, Integer size);

    default PageResult<PartInventoryVO> findPage(String keyword, Integer page, Integer size) {
        return findPage(keyword, null, page, size);
    }

    Optional<PartInventory> findById(Long id);

    Optional<PartInventory> findByIdForUpdate(Long id);

    Optional<PartInventory> findByPartCode(String partCode);

    Optional<PartInventory> findByPartCodeForUpdate(String partCode);

    PartInventory save(PartInventory part);

    void deleteById(Long id);

    List<PartInventory> findByCategory(String category);

    List<PartInventory> findAvailableParts();

    List<PartInventory> findBySource(String source);

    List<PartInventory> findBySourceMachineId(Long machineId);

    PartInventory inbound(String partCode, int quantity, Long expectedVersion);

    PartInventory outbound(String partCode, int quantity, Long expectedVersion);

    PartInventoryVO create(PartInventoryCreateDTO dto);

    PartInventoryVO update(Long id, PartInventoryCreateDTO dto);

    PartInventoryVO valueRemovedPart(Long id, RemovedPartValuationDTO dto);

    void delete(Long id, Long version);

    PartInventoryVO inbound(PartStockAdjustRequestDTO request);

    PartInventoryVO outbound(PartStockAdjustRequestDTO request);
}
