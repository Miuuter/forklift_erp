package com.example.forklift_erp.service;

import com.example.forklift_erp.common.PageResult;
import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.constant.MachineStockStatus;
import com.example.forklift_erp.dto.StockTransferDTO;
import com.example.forklift_erp.dto.WarehouseDTO;
import com.example.forklift_erp.dto.WarehouseVO;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.StockBalance;
import com.example.forklift_erp.entity.StockMovement;
import com.example.forklift_erp.entity.Warehouse;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderLineRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderRepository;
import com.example.forklift_erp.repository.OutboundOrderRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.PurchaseOrderRepository;
import com.example.forklift_erp.repository.RentalRecordRepository;
import com.example.forklift_erp.repository.RepairPartUsageRepository;
import com.example.forklift_erp.repository.StockBalanceRepository;
import com.example.forklift_erp.repository.StockLotConsumptionRepository;
import com.example.forklift_erp.repository.StockLotRepository;
import com.example.forklift_erp.repository.StockMovementLineRepository;
import com.example.forklift_erp.repository.StocktakingRecordRepository;
import com.example.forklift_erp.repository.WarehouseRepository;
import com.example.forklift_erp.util.ListPageSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class WarehouseService {
    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private StockBalanceRepository stockBalanceRepository;

    @Autowired
    private MachineInventoryRepository machineInventoryRepository;

    @Autowired
    private PartInventoryRepository partInventoryRepository;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private RentalRecordRepository rentalRecordRepository;

    @Autowired
    private StocktakingRecordRepository stocktakingRecordRepository;

    @Autowired
    private ModificationWorkOrderRepository modificationWorkOrderRepository;

    @Autowired
    private ModificationWorkOrderLineRepository modificationWorkOrderLineRepository;

    @Autowired
    private RepairPartUsageRepository repairPartUsageRepository;

    @Autowired
    private OutboundOrderRepository outboundOrderRepository;

    @Autowired
    private StockMovementLineRepository stockMovementLineRepository;

    @Autowired
    private StockLotRepository stockLotRepository;

    @Autowired
    private StockLotConsumptionRepository stockLotConsumptionRepository;

    @Autowired
    private StockLedgerService stockLedgerService;

    @Autowired
    private StockLotService stockLotService;

    @Autowired
    private CollaborationService collaborationService;

    @Autowired
    private OperationAuditService operationAuditService;

    @Autowired
    private ResourceVisibilityPolicy visibilityPolicy;

    @Transactional(readOnly = true)
    public List<WarehouseVO> findAll() {
        return enrich(warehouseRepository.findAll(Sort.by(Sort.Direction.ASC, "warehouseName")).stream()
                .map(WarehouseVO::fromEntity)
                .toList());
    }

    @Transactional(readOnly = true)
    public PageResult<WarehouseVO> findPage(String keyword, Integer page, Integer size) {
        int normalizedPage = ListPageSupport.page(page);
        int normalizedSize = ListPageSupport.size(size);
        Page<Warehouse> result = warehouseRepository.searchPage(
                normalizeKeyword(keyword),
                ListPageSupport.pageRequest(page, size, Sort.by(Sort.Direction.ASC, "warehouseName"))
        );
        return PageResult.of(
                enrich(result.getContent().stream().map(WarehouseVO::fromEntity).toList()),
                normalizedPage,
                normalizedSize,
                result.getTotalElements()
        );
    }

    @Transactional
    public WarehouseVO create(WarehouseDTO request) {
        ensureUniqueCode(request.getWarehouseCode(), null);
        Warehouse warehouse = new Warehouse();
        copy(request, warehouse);
        Warehouse saved = warehouseRepository.saveAndFlush(warehouse);
        if (Boolean.TRUE.equals(saved.getDefaultWarehouse())) {
            clearOtherDefaultWarehouses(saved.getId());
        }
        operationAuditService.record("Warehouse", "CREATE", "WAREHOUSE", saved.getId(),
                saved.getWarehouseCode(), saved.getWarehouseName(), "Create warehouse", null, saved.getAddress());
        return enrichOne(saved);
    }

    @Transactional
    public WarehouseVO update(Long id, WarehouseDTO request) {
        Warehouse warehouse = warehouseRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Warehouse not found"));
        validateVersion(warehouse, request.getVersion());
        ensureUniqueCode(request.getWarehouseCode(), id);
        copy(request, warehouse);
        Warehouse saved = warehouseRepository.saveAndFlush(warehouse);
        if (Boolean.TRUE.equals(saved.getDefaultWarehouse())) {
            clearOtherDefaultWarehouses(saved.getId());
        }
        operationAuditService.record("Warehouse", "UPDATE", "WAREHOUSE", saved.getId(),
                saved.getWarehouseCode(), saved.getWarehouseName(), "Update warehouse", null, saved.getAddress());
        return enrichOne(saved);
    }

    @Transactional
    public void delete(Long id, Long version) {
        Warehouse warehouse = warehouseRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Warehouse not found"));
        validateVersion(warehouse, version);
        if (Boolean.TRUE.equals(warehouse.getDefaultWarehouse())) {
            throw new BusinessException(ResultCode.CONFLICT, "Default warehouse cannot be deleted");
        }
        if (machineInventoryRepository.countByWarehouseId(id) > 0 || partInventoryRepository.countByWarehouseId(id) > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "Warehouse is used by inventory profiles");
        }
        List<StockBalance> balances = stockBalanceRepository.findByWarehouseId(id);
        if (!balances.isEmpty()) {
            boolean hasStock = balances.stream()
                    .anyMatch(balance -> nonZero(balance.getAvailableQuantity())
                            || nonZero(balance.getReservedQuantity())
                            || nonZero(balance.getLockedQuantity()));
            throw new BusinessException(ResultCode.CONFLICT, hasStock
                    ? "Warehouse still has stock balance"
                    : "Warehouse has inventory ledger history and cannot be deleted");
        }
        if (purchaseOrderRepository.existsByWarehouseId(id)) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Warehouse is referenced by purchase orders and cannot be deleted");
        }
        if (rentalRecordRepository.existsByWarehouseId(id)) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Warehouse is referenced by rental records and cannot be deleted");
        }
        if (stocktakingRecordRepository.existsByWarehouseId(id)) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Warehouse is referenced by stocktaking records and cannot be deleted");
        }
        if (modificationWorkOrderRepository.existsByWarehouseId(id)
                || modificationWorkOrderLineRepository.existsByWarehouseIdOrOldPartWarehouseId(id, id)) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Warehouse is referenced by modification work orders and cannot be deleted");
        }
        if (repairPartUsageRepository.existsByWarehouseId(id)) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Warehouse is referenced by repair material usage and cannot be deleted");
        }
        if (outboundOrderRepository.existsBySourceWarehouseId(id)) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Warehouse is referenced by outbound orders and cannot be deleted");
        }
        if (stockMovementLineRepository.existsByWarehouseId(id)
                || stockLotRepository.existsByWarehouseId(id)
                || stockLotConsumptionRepository.existsByWarehouseId(id)) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Warehouse has inventory ledger or FIFO history and cannot be deleted");
        }
        warehouseRepository.delete(warehouse);
        operationAuditService.record("Warehouse", "DELETE", "WAREHOUSE", id,
                warehouse.getWarehouseCode(), warehouse.getWarehouseName(), "Delete warehouse", null, warehouse.getAddress());
    }

    @Transactional
    public WarehouseVO transfer(StockTransferDTO request) {
        String resourceType = normalizeResourceType(request.getResourceType());
        if (StockLedgerService.RESOURCE_MACHINE.equals(resourceType)) {
            transferMachine(request);
        } else if (StockLedgerService.RESOURCE_PART.equals(resourceType)) {
            transferPart(request);
        } else {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Unsupported resource type: " + request.getResourceType());
        }
        Warehouse target = warehouseRepository.findById(request.getToWarehouseId())
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Target warehouse not found"));
        return enrichOne(target);
    }

    @Transactional(readOnly = true)
    public int availableQuantity(String resourceType, Long resourceId, Long warehouseId) {
        String normalizedType = normalizeResourceType(resourceType);
        if (resourceId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Resource ID is required");
        }
        Long resolvedWarehouseId = stockLedgerService.resolveWarehouseId(warehouseId);
        if (StockLedgerService.RESOURCE_MACHINE.equals(normalizedType)) {
            if (!machineInventoryRepository.existsById(resourceId)) {
                throw new BusinessException(ResultCode.VEHICLE_NOT_FOUND, "Vehicle not found");
            }
        } else if (StockLedgerService.RESOURCE_PART.equals(normalizedType)) {
            if (!partInventoryRepository.existsById(resourceId)) {
                throw new BusinessException(ResultCode.PART_NOT_FOUND, "Part not found");
            }
        } else {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Unsupported resource type: " + resourceType);
        }
        return stockLedgerService.availableQuantity(normalizedType, resourceId, resolvedWarehouseId);
    }

    private void transferMachine(StockTransferDTO request) {
        MachineInventory machine = machineInventoryRepository.findByIdForUpdate(request.getResourceId())
                .orElseThrow(() -> new BusinessException(ResultCode.VEHICLE_NOT_FOUND, "Vehicle not found"));
        visibilityPolicy.ensureWritable(machine.getIsLocked(), "Vehicle is locked and cannot be transferred");
        collaborationService.validateWrite(machine, request.getVersion());
        if (Boolean.TRUE.equals(machine.getModelOnly())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Model templates cannot be transferred");
        }
        if (MachineStockStatus.RENTED.code().equals(machine.getStockStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "A rented vehicle cannot be transferred");
        }
        if (!MachineStockStatus.canTransfer(machine.getStockStatus())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Vehicle status does not allow warehouse transfer: " + machine.getStockStatus());
        }
        if (request.getQuantity() == null || request.getQuantity() != 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "A serialized vehicle must be transferred as exactly one unit");
        }
        int currentQuantity = machine.getInventoryCount() == null ? 0 : machine.getInventoryCount();
        if (currentQuantity > 1) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Concrete vehicle inventory is invalid (greater than one); create an explicit correction before transfer");
        }
        if (Objects.equals(machine.getWarehouseId(), request.getFromWarehouseId())) {
            ensureBalanceIfMissing(StockLedgerService.RESOURCE_MACHINE, machine.getId(),
                    request.getFromWarehouseId(), currentQuantity);
        }
        int sourceAvailableBeforeTransfer = stockLedgerService.availableQuantity(
                StockLedgerService.RESOURCE_MACHINE, machine.getId(), request.getFromWarehouseId());
        StockMovement movement = stockLedgerService.transferBalance(
                StockLedgerService.RESOURCE_MACHINE,
                machine.getId(),
                machine.getVehicleProductNumber(),
                machine.getName(),
                request.getFromWarehouseId(),
                request.getToWarehouseId(),
                request.getQuantity(),
                request.getOperator(),
                request.getRemark(),
                "STOCK_TRANSFER",
                machine.getId()
        );
        stockLotService.transferFifo(
                StockLedgerService.RESOURCE_MACHINE,
                machine.getId(),
                request.getFromWarehouseId(),
                request.getToWarehouseId(),
                request.getQuantity(),
                machineCost(machine),
                sourceAvailableBeforeTransfer,
                movement.getId(),
                LocalDate.now()
        );
        if (Objects.equals(currentQuantity, request.getQuantity())) {
            Warehouse target = warehouseRepository.findById(request.getToWarehouseId())
                    .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Target warehouse not found"));
            machine.setWarehouseId(target.getId());
            machine.setWarehouseName(target.getWarehouseName());
        }
        machine.setUpdatedAt(LocalDateTime.now());
        collaborationService.stampWrite(machine);
        machineInventoryRepository.saveAndFlush(machine);
        operationAuditService.record("Warehouse transfer", "TRANSFER", "MACHINE", machine.getId(),
                machine.getVehicleProductNumber(), machine.getName(), "Transfer machine stock", request.getOperator(),
                request.getRemark(), "STOCK_MOVEMENT", movement.getId());
    }

    private void transferPart(StockTransferDTO request) {
        PartInventory part = partInventoryRepository.findByIdForUpdate(request.getResourceId())
                .orElseThrow(() -> new BusinessException(ResultCode.PART_NOT_FOUND, "Part not found"));
        visibilityPolicy.ensureWritable(part.getIsLocked(), "Part is locked and cannot be transferred");
        collaborationService.validateWrite(part, request.getVersion());
        int currentQuantity = part.getQuantity() == null ? 0 : part.getQuantity();
        if (Objects.equals(part.getWarehouseId(), request.getFromWarehouseId())) {
            ensureBalanceIfMissing(StockLedgerService.RESOURCE_PART, part.getId(), request.getFromWarehouseId(), currentQuantity);
        }
        int sourceAvailableBeforeTransfer = stockLedgerService.availableQuantity(
                StockLedgerService.RESOURCE_PART, part.getId(), request.getFromWarehouseId());
        StockMovement movement = stockLedgerService.transferBalance(
                StockLedgerService.RESOURCE_PART,
                part.getId(),
                part.getPartCode(),
                part.getPartName(),
                request.getFromWarehouseId(),
                request.getToWarehouseId(),
                request.getQuantity(),
                request.getOperator(),
                request.getRemark(),
                "STOCK_TRANSFER",
                part.getId()
        );
        stockLotService.transferFifo(
                StockLedgerService.RESOURCE_PART,
                part.getId(),
                request.getFromWarehouseId(),
                request.getToWarehouseId(),
                request.getQuantity(),
                partCost(part),
                sourceAvailableBeforeTransfer,
                movement.getId(),
                LocalDate.now()
        );
        if (Objects.equals(currentQuantity, request.getQuantity())) {
            part.setWarehouseId(request.getToWarehouseId());
        }
        part.setUpdatedAt(LocalDateTime.now());
        collaborationService.stampWrite(part);
        partInventoryRepository.saveAndFlush(part);
        operationAuditService.record("Warehouse transfer", "TRANSFER", "PART", part.getId(),
                part.getPartCode(), part.getPartName(), "Transfer part stock", request.getOperator(),
                request.getRemark(), "STOCK_MOVEMENT", movement.getId());
    }

    private void ensureBalanceIfMissing(String resourceType, Long resourceId, Long warehouseId, Integer quantity) {
        if (stockBalanceRepository.findByResourceTypeAndResourceId(resourceType, resourceId).isEmpty()) {
            stockLedgerService.syncBalance(resourceType, resourceId, warehouseId, quantity);
        }
    }

    private WarehouseVO enrichOne(Warehouse warehouse) {
        return enrich(List.of(WarehouseVO.fromEntity(warehouse))).get(0);
    }

    private List<WarehouseVO> enrich(List<WarehouseVO> rows) {
        Map<Long, List<StockBalance>> balancesByWarehouse = stockBalanceRepository.findAll().stream()
                .collect(Collectors.groupingBy(StockBalance::getWarehouseId));
        rows.forEach(row -> {
            List<StockBalance> balances = balancesByWarehouse.getOrDefault(row.getId(), List.of());
            long vehicleCount = balances.stream()
                    .filter(balance -> StockLedgerService.RESOURCE_MACHINE.equals(balance.getResourceType()))
                    .mapToLong(balance -> Math.max(0, value(balance.getAvailableQuantity())))
                    .sum();
            long partSkuCount = balances.stream()
                    .filter(balance -> StockLedgerService.RESOURCE_PART.equals(balance.getResourceType()))
                    .filter(balance -> value(balance.getAvailableQuantity()) > 0)
                    .count();
            long partQuantity = balances.stream()
                    .filter(balance -> StockLedgerService.RESOURCE_PART.equals(balance.getResourceType()))
                    .mapToLong(balance -> Math.max(0, value(balance.getAvailableQuantity())))
                    .sum();
            row.setVehicleCount(vehicleCount);
            row.setPartSkuCount(partSkuCount);
            row.setPartQuantity(partQuantity);
        });
        return rows;
    }

    private void copy(WarehouseDTO request, Warehouse warehouse) {
        warehouse.setWarehouseCode(required(request.getWarehouseCode(), "Warehouse code is required"));
        warehouse.setWarehouseName(required(request.getWarehouseName(), "Warehouse name is required"));
        warehouse.setWarehouseType(blankToDefault(request.getWarehouseType(), "MAIN"));
        warehouse.setAddress(blankToNull(request.getAddress()));
        warehouse.setDefaultWarehouse(Boolean.TRUE.equals(request.getDefaultWarehouse()));
    }

    private void clearOtherDefaultWarehouses(Long defaultId) {
        warehouseRepository.findAll().stream()
                .filter(warehouse -> !Objects.equals(warehouse.getId(), defaultId))
                .filter(warehouse -> Boolean.TRUE.equals(warehouse.getDefaultWarehouse()))
                .forEach(warehouse -> {
                    warehouse.setDefaultWarehouse(false);
                    warehouseRepository.save(warehouse);
                });
    }

    private void ensureUniqueCode(String code, Long currentId) {
        String normalized = required(code, "Warehouse code is required");
        boolean duplicated = currentId == null
                ? warehouseRepository.existsByWarehouseCode(normalized)
                : warehouseRepository.existsByWarehouseCodeAndIdNot(normalized, currentId);
        if (duplicated) {
            throw new BusinessException(ResultCode.DATA_DUPLICATE, "Warehouse code already exists: " + normalized);
        }
    }

    private void validateVersion(Warehouse warehouse, Long expectedVersion) {
        if (expectedVersion == null) {
            throw new BusinessException(ResultCode.CONFLICT, "Warehouse version is required");
        }
        if (!expectedVersion.equals(warehouse.getVersion())) {
            throw new BusinessException(ResultCode.CONFLICT, "Warehouse has been updated, please refresh and retry");
        }
    }

    private String normalizeResourceType(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeKeyword(String keyword) {
        return keyword == null || keyword.isBlank() ? null : keyword.trim();
    }

    private String required(String value, String message) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, message);
        }
        return normalized;
    }

    private String blankToDefault(String value, String fallback) {
        String normalized = blankToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean nonZero(Integer value) {
        return value(value) != 0;
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private BigDecimal partCost(PartInventory part) {
        if (part.getLandedUnitCost() != null && part.getLandedUnitCost().signum() >= 0) {
            return part.getLandedUnitCost();
        }
        return part.getPurchasePrice() == null ? BigDecimal.ZERO : part.getPurchasePrice().max(BigDecimal.ZERO);
    }

    private BigDecimal machineCost(MachineInventory machine) {
        if (machine.getLandedUnitCost() != null && machine.getLandedUnitCost().signum() >= 0) {
            return machine.getLandedUnitCost();
        }
        return machine.getPurchasePrice() == null ? BigDecimal.ZERO : machine.getPurchasePrice().max(BigDecimal.ZERO);
    }
}
