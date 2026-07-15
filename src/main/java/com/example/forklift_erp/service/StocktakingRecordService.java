package com.example.forklift_erp.service;

import com.example.forklift_erp.common.PageResult;
import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.constant.MachineStockStatus;
import com.example.forklift_erp.constant.StockBusinessType;
import com.example.forklift_erp.dto.StocktakingRecordDTO;
import com.example.forklift_erp.dto.StocktakingRecordVO;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.StocktakingRecord;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.StocktakingRecordRepository;
import com.example.forklift_erp.repository.StockBalanceRepository;
import com.example.forklift_erp.service.impl.StockOperationRecorder;
import com.example.forklift_erp.util.BusinessNumberGenerator;
import com.example.forklift_erp.util.InventoryQuantities;
import com.example.forklift_erp.util.ListPageSupport;
import com.example.forklift_erp.util.MoneyValues;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

@Service
public class StocktakingRecordService {
    @Autowired
    private StocktakingRecordRepository stocktakingRepository;

    @Autowired
    private MachineInventoryRepository machineRepository;

    @Autowired
    private PartInventoryRepository partRepository;

    @Autowired
    private CollaborationService collaborationService;

    @Autowired
    private OperationAuditService operationAuditService;

    @Autowired
    private StockOperationRecorder stockOperationRecorder;

    @Autowired
    private StockLedgerService stockLedgerService;

    @Autowired
    private ResourceVisibilityPolicy visibilityPolicy;

    @Autowired
    private StockBalanceRepository stockBalanceRepository;

    @Autowired
    private InventoryAdjustmentAccountingService inventoryAdjustmentAccountingService;

    @Transactional(readOnly = true)
    public List<StocktakingRecordVO> findAll() {
        return stocktakingRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(StocktakingRecordVO::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResult<StocktakingRecordVO> findPage(String keyword, Integer page, Integer size) {
        int normalizedPage = ListPageSupport.page(page);
        int normalizedSize = ListPageSupport.size(size);
        Page<StocktakingRecord> result = stocktakingRepository.searchPage(
                normalizeKeyword(keyword),
                ListPageSupport.pageRequest(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        );
        return PageResult.of(
                result.getContent().stream().map(StocktakingRecordVO::fromEntity).toList(),
                normalizedPage,
                normalizedSize,
                result.getTotalElements()
        );
    }

    @Transactional
    public StocktakingRecordVO create(StocktakingRecordDTO request) {
        StocktakingRecord record = new StocktakingRecord();
        record.setStocktakingNo(nextStocktakingNo());
        copy(request, record);
        collaborationService.stampWrite(record);
        StocktakingRecord saved = stocktakingRepository.saveAndFlush(record);
        operationAuditService.record("Stocktaking", "CREATE", "STOCKTAKING", saved.getId(),
                saved.getStocktakingNo(), saved.getResourceName(), "Create stocktaking record",
                saved.getOperator(), saved.getRemark());
        return StocktakingRecordVO.fromEntity(saved);
    }

    @Transactional
    public StocktakingRecordVO update(Long id, StocktakingRecordDTO request) {
        StocktakingRecord record = stocktakingRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Stocktaking record not found"));
        collaborationService.validateWrite(record, request.getVersion());
        if ("COMPLETED".equals(record.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "Completed stocktaking record cannot be edited");
        }
        copy(request, record);
        collaborationService.stampWrite(record);
        StocktakingRecord saved = stocktakingRepository.saveAndFlush(record);
        operationAuditService.record("Stocktaking", "UPDATE", "STOCKTAKING", saved.getId(),
                saved.getStocktakingNo(), saved.getResourceName(), "Update stocktaking record",
                saved.getOperator(), saved.getRemark());
        return StocktakingRecordVO.fromEntity(saved);
    }

    @Transactional
    public StocktakingRecordVO complete(Long id, Long version) {
        StocktakingRecord record = stocktakingRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Stocktaking record not found"));
        collaborationService.validateWrite(record, version);
        if ("COMPLETED".equals(record.getStatus())) {
            return StocktakingRecordVO.fromEntity(record);
        }
        applyInventoryDifference(record);
        record.setStatus("COMPLETED");
        collaborationService.stampWrite(record);
        StocktakingRecord saved = stocktakingRepository.saveAndFlush(record);
        operationAuditService.record("Stocktaking", "COMPLETE", "STOCKTAKING", saved.getId(),
                saved.getStocktakingNo(), saved.getResourceName(),
                "Complete stocktaking: " + saved.getBookQuantity() + " -> " + saved.getActualQuantity(),
                saved.getOperator(), saved.getRemark());
        return StocktakingRecordVO.fromEntity(saved);
    }

    @Transactional
    public void delete(Long id, Long version) {
        StocktakingRecord record = stocktakingRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Stocktaking record not found"));
        collaborationService.validateWrite(record, version);
        if ("COMPLETED".equals(record.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "Completed stocktaking record cannot be deleted");
        }
        stocktakingRepository.delete(record);
        operationAuditService.record("Stocktaking", "DELETE", "STOCKTAKING", id,
                record.getStocktakingNo(), record.getResourceName(), "Delete stocktaking record",
                record.getOperator(), record.getRemark());
    }

    private void copy(StocktakingRecordDTO request, StocktakingRecord record) {
        String resourceType = normalizeResourceType(request.getResourceType());
        record.setResourceType(resourceType);
        record.setResourceId(request.getResourceId());
        Long warehouseId = stockLedgerService.resolveWarehouseId(request.getWarehouseId());
        record.setWarehouseId(warehouseId);
        if (StocktakingRecord.RESOURCE_MACHINE.equals(resourceType)) {
            MachineInventory machine = machineRepository.findById(request.getResourceId())
                    .orElseThrow(() -> new BusinessException(ResultCode.VEHICLE_NOT_FOUND, "Vehicle not found"));
            visibilityPolicy.ensureWritable(machine.getIsLocked(), "Vehicle is locked and cannot be stocktaken");
            if (MachineStockStatus.RENTED.code().equals(machine.getStockStatus())) {
                throw new BusinessException(ResultCode.CONFLICT, "A rented vehicle cannot be stocktaken with a normal count");
            }
            if (MachineStockStatus.isActiveModification(machine.getStockStatus())) {
                throw new BusinessException(ResultCode.CONFLICT,
                        "A vehicle in an active modification workflow cannot be stocktaken");
            }
            record.setResourceCode(machine.getVehicleProductNumber());
            record.setResourceName(machine.getName());
            record.setSpecificationModel(machine.getSpecificationModel());
            record.setBookQuantity(stockLedgerService.availableQuantity(
                    StockLedgerService.RESOURCE_MACHINE, machine.getId(), warehouseId));
        } else if (StocktakingRecord.RESOURCE_PART.equals(resourceType)) {
            PartInventory part = partRepository.findById(request.getResourceId())
                    .orElseThrow(() -> new BusinessException(ResultCode.PART_NOT_FOUND, "Part not found"));
            visibilityPolicy.ensureWritable(part.getIsLocked(), "Part is locked and cannot be stocktaken");
            record.setResourceCode(part.getPartCode());
            record.setResourceName(part.getPartName());
            record.setSpecificationModel(part.getSpecification());
            record.setBookQuantity(stockLedgerService.availableQuantity(
                    StockLedgerService.RESOURCE_PART, part.getId(), warehouseId));
        } else {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Unsupported stocktaking resource type: " + resourceType);
        }
        record.setBookBalanceVersion(stockBalanceRepository
                .findByResourceTypeAndResourceIdAndWarehouseId(resourceType, record.getResourceId(), warehouseId)
                .map(balance -> balance.getVersion())
                .orElse(0L));
        record.setActualQuantity(quantity(request.getActualQuantity()));
        record.setDifferenceQuantity(record.getActualQuantity() - record.getBookQuantity());
        record.setStocktakingDate(request.getStocktakingDate() == null ? LocalDate.now() : request.getStocktakingDate());
        String requestedStatus = blankToNull(request.getStatus()) == null ? "DRAFT" : request.getStatus().trim().toUpperCase(Locale.ROOT);
        record.setStatus("COMPLETED".equals(requestedStatus) ? "DRAFT" : requestedStatus);
        record.setOperator(blankToNull(request.getOperator()));
        record.setRemark(blankToNull(request.getRemark()));
    }

    private void applyInventoryDifference(StocktakingRecord record) {
        if (StocktakingRecord.RESOURCE_MACHINE.equals(record.getResourceType())) {
            MachineInventory machine = machineRepository.findByIdForUpdate(record.getResourceId())
                    .orElseThrow(() -> new BusinessException(ResultCode.VEHICLE_NOT_FOUND, "Vehicle not found"));
            visibilityPolicy.ensureWritable(machine.getIsLocked(), "Vehicle is locked and cannot be stocktaken");
            if (MachineStockStatus.RENTED.code().equals(machine.getStockStatus())) {
                throw new BusinessException(ResultCode.CONFLICT, "A rented vehicle cannot be stocktaken with a normal count");
            }
            if (MachineStockStatus.isActiveModification(machine.getStockStatus())) {
                throw new BusinessException(ResultCode.CONFLICT,
                        "A vehicle in an active modification workflow cannot be stocktaken");
            }
            int currentWarehouseQuantity = stockLedgerService.availableQuantity(
                    StockLedgerService.RESOURCE_MACHINE, machine.getId(), record.getWarehouseId());
            assertStocktakingSnapshotCurrent(record, currentWarehouseQuantity);
            if (record.getActualQuantity() > 1) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "A serialized vehicle stocktake can only confirm 0 or 1 unit");
            }
            int currentTotalQuantity = stockLedgerService.totalAvailableQuantity(
                    StockLedgerService.RESOURCE_MACHINE, machine.getId());
            int projectedTotalQuantity = currentTotalQuantity - currentWarehouseQuantity + record.getActualQuantity();
            if (projectedTotalQuantity > 1) {
                throw new BusinessException(ResultCode.CONFLICT,
                        "A serialized vehicle cannot exist in more than one warehouse");
            }
            InventoryQuantities.QuantityChange change = InventoryQuantities.adjustTo(
                    currentWarehouseQuantity,
                    record.getActualQuantity(),
                    "Inventory count cannot be negative"
            );
            recordStocktakingMovement(
                    record,
                    StockLedgerService.RESOURCE_MACHINE,
                    machine.getId(),
                    machine.getVehicleProductNumber(),
                    machine.getName(),
                    record.getWarehouseId(),
                    change.beforeQuantity(),
                    change.afterQuantity(),
                    stockUnitCost(machine)
            );
            machine.setInventoryCount(stockLedgerService.totalAvailableQuantity(StockLedgerService.RESOURCE_MACHINE, machine.getId()));
            machine.setStockStatus(machine.getInventoryCount() > 0 ? MachineStockStatus.IN_STOCK.code() : MachineStockStatus.OUTBOUND.code());
            collaborationService.stampWrite(machine);
            machineRepository.saveAndFlush(machine);
            return;
        }
        if (StocktakingRecord.RESOURCE_PART.equals(record.getResourceType())) {
            PartInventory part = partRepository.findByIdForUpdate(record.getResourceId())
                    .orElseThrow(() -> new BusinessException(ResultCode.PART_NOT_FOUND, "Part not found"));
            visibilityPolicy.ensureWritable(part.getIsLocked(), "Part is locked and cannot be stocktaken");
            int currentWarehouseQuantity = stockLedgerService.availableQuantity(
                    StockLedgerService.RESOURCE_PART, part.getId(), record.getWarehouseId());
            assertStocktakingSnapshotCurrent(record, currentWarehouseQuantity);
            InventoryQuantities.QuantityChange change = InventoryQuantities.adjustTo(
                    currentWarehouseQuantity,
                    record.getActualQuantity(),
                    "Inventory count cannot be negative"
            );
            recordStocktakingMovement(
                    record,
                    StockLedgerService.RESOURCE_PART,
                    part.getId(),
                    part.getPartCode(),
                    part.getPartName(),
                    record.getWarehouseId(),
                    change.beforeQuantity(),
                    change.afterQuantity(),
                    stockUnitCost(part)
            );
            part.setQuantity(stockLedgerService.totalAvailableQuantity(StockLedgerService.RESOURCE_PART, part.getId()));
            collaborationService.stampWrite(part);
            partRepository.saveAndFlush(part);
        }
    }

    private void recordStocktakingMovement(
            StocktakingRecord record,
            String resourceType,
            Long resourceId,
            String resourceCode,
            String resourceName,
            Long warehouseId,
            int beforeQuantity,
            int afterQuantity,
            BigDecimal fallbackUnitCost
    ) {
        int delta = afterQuantity - beforeQuantity;
        if (delta == 0) {
            return;
        }
        InventoryAdjustmentAccountingService.AdjustmentResult result =
                inventoryAdjustmentAccountingService.post(new InventoryAdjustmentAccountingService.Command(
                "Stocktaking stock",
                resourceType,
                resourceId,
                resourceCode,
                resourceName,
                warehouseId,
                beforeQuantity,
                afterQuantity,
                fallbackUnitCost,
                record.getOperator(),
                record.getRemark(),
                "STOCKTAKING",
                record.getId(),
                "Post stocktaking difference " + beforeQuantity + " -> " + afterQuantity,
                record.getStocktakingDate(),
                delta > 0 ? StockBusinessType.STOCKTAKING_GAIN : StockBusinessType.STOCKTAKING_LOSS,
                "STOCKTAKING:" + record.getId(),
                true
        ));
        if (result.movement() != null) {
            record.setSnapshotMovementId(result.movement().getId());
        }
    }

    private BigDecimal stockUnitCost(MachineInventory machine) {
        return MoneyValues.firstNonNegativeOrZero(machine.getLandedUnitCost(), machine.getPurchasePrice());
    }

    private BigDecimal stockUnitCost(PartInventory part) {
        return MoneyValues.firstNonNegativeOrZero(part.getLandedUnitCost(), part.getPurchasePrice());
    }

    private void assertStocktakingSnapshotCurrent(StocktakingRecord record, int currentWarehouseQuantity) {
        Long currentVersion = stockBalanceRepository
                .findByResourceTypeAndResourceIdAndWarehouseId(record.getResourceType(), record.getResourceId(), record.getWarehouseId())
                .map(balance -> balance.getVersion())
                .orElse(0L);
        if (!java.util.Objects.equals(record.getBookBalanceVersion(), currentVersion)
                || record.getBookQuantity() == null
                || record.getBookQuantity() != currentWarehouseQuantity) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Stock changed after the stocktaking snapshot; recalculate the count before completing it");
        }
    }

    private int quantity(Integer value) {
        return InventoryQuantities.nonNegative(value);
    }

    private String normalizeResourceType(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Resource type is required");
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private String nextStocktakingNo() {
        return BusinessNumberGenerator.next("ST", 6);
    }

    private String normalizeKeyword(String keyword) {
        return keyword == null || keyword.isBlank() ? null : keyword.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
