package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.PageResult;
import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.constant.MachineStockStatus;
import com.example.forklift_erp.constant.RentalStatus;
import com.example.forklift_erp.dto.InboundRequestDTO;
import com.example.forklift_erp.dto.MachineConfigVO;
import com.example.forklift_erp.dto.MachineInventoryCreateDTO;
import com.example.forklift_erp.dto.MachineInventoryVO;
import com.example.forklift_erp.dto.StockAdjustRequestDTO;
import com.example.forklift_erp.dto.VehicleModelSummaryVO;
import com.example.forklift_erp.entity.ConfigItem;
import com.example.forklift_erp.entity.ConfigValue;
import com.example.forklift_erp.entity.MachineConfig;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.StockOperationLog;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.ConfigReplaceLogRepository;
import com.example.forklift_erp.repository.ConfigItemRepository;
import com.example.forklift_erp.repository.ConfigValueRepository;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderRepository;
import com.example.forklift_erp.repository.OutboundOrderRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.PurchaseOrderRepository;
import com.example.forklift_erp.repository.RentalRecordRepository;
import com.example.forklift_erp.repository.RepairRecordRepository;
import com.example.forklift_erp.repository.ResourceAttachmentRepository;
import com.example.forklift_erp.repository.StocktakingRecordRepository;
import com.example.forklift_erp.repository.StockLotRepository;
import com.example.forklift_erp.repository.SupplierRepository;
import com.example.forklift_erp.service.CollaborationService;
import com.example.forklift_erp.service.InventoryAdjustmentAccountingService;
import com.example.forklift_erp.service.MachineConfigService;
import com.example.forklift_erp.service.MachineInventoryService;
import com.example.forklift_erp.service.OperationAuditService;
import com.example.forklift_erp.service.ResourceVisibilityPolicy;
import com.example.forklift_erp.service.StockLedgerService;
import com.example.forklift_erp.service.StockLotService;
import com.example.forklift_erp.util.InventoryQuantities;
import com.example.forklift_erp.util.ListPageSupport;
import com.example.forklift_erp.util.MoneyValues;
import com.example.forklift_erp.util.SearchKeywordSupport;
import com.example.forklift_erp.util.SecurityUtils;
import org.springframework.data.domain.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MachineInventoryServiceImpl implements MachineInventoryService {
    private static final long LONG_IDLE_DAYS = 90;

    @Autowired
    private MachineInventoryRepository repository;

    @Autowired
    private CollaborationService collaborationService;

    @Autowired
    private StockLedgerService stockLedgerService;

    @Autowired
    private StockLotService stockLotService;

    @Autowired
    private StockLotRepository stockLotRepository;

    @Autowired
    private InventoryAdjustmentAccountingService inventoryAdjustmentAccountingService;

    @Autowired
    private MachineConfigService machineConfigService;

    @Autowired
    private ConfigItemRepository configItemRepository;

    @Autowired
    private ConfigValueRepository configValueRepository;

    @Autowired
    private OperationAuditService operationAuditService;

    @Autowired
    private StockOperationRecorder stockOperationRecorder;

    @Autowired
    private ResourceVisibilityPolicy visibilityPolicy;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private OutboundOrderRepository outboundOrderRepository;

    @Autowired
    private RentalRecordRepository rentalRecordRepository;

    @Autowired
    private RepairRecordRepository repairRecordRepository;

    @Autowired
    private ModificationWorkOrderRepository modificationWorkOrderRepository;

    @Autowired
    private ConfigReplaceLogRepository configReplaceLogRepository;

    @Autowired
    private PartInventoryRepository partInventoryRepository;

    @Autowired
    private StocktakingRecordRepository stocktakingRecordRepository;

    @Autowired
    private ResourceAttachmentRepository resourceAttachmentRepository;

    @Autowired
    private InventoryMasterDeletionGuard deletionGuard;

    @Autowired
    private MachineConfigUpdateCoordinator machineConfigUpdateCoordinator;

    @Override
    public List<MachineInventory> findAll() {
        if (SecurityUtils.isAdminOrSuperAdmin()) {
            return repository.findAll();
        } else {
            return repository.findAllByIsLockedFalse();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<MachineInventoryVO> findPage(String keyword, Integer page, Integer size) {
        int normalizedPage = ListPageSupport.page(page);
        int normalizedSize = ListPageSupport.size(size);
        Page<MachineInventory> result = repository.searchPage(
                SearchKeywordSupport.likePrefix(keyword),
                SearchKeywordSupport.fullTextBoolean(keyword),
                SecurityUtils.isAdminOrSuperAdmin(),
                ListPageSupport.pageRequest(page, size)
        );
        return PageResult.of(
                result.getContent().stream().map(MachineInventoryVO::fromEntity).toList(),
                normalizedPage,
                normalizedSize,
                result.getTotalElements()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<VehicleModelSummaryVO> findModelPage(String keyword, Integer page, Integer size) {
        return findModelPage(keyword, null, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<VehicleModelSummaryVO> findModelPage(String keyword, String stock, Integer page, Integer size) {
        int normalizedPage = ListPageSupport.page(page);
        int normalizedSize = ListPageSupport.size(size);
        boolean longIdleOnly = "longIdle".equalsIgnoreCase(normalizeModelField(stock));
        Page<MachineInventoryRepository.VehicleModelSummaryProjection> result = repository.searchModelSummaries(
                SearchKeywordSupport.likePrefix(keyword),
                SearchKeywordSupport.fullTextBoolean(keyword),
                SecurityUtils.isAdminOrSuperAdmin(),
                longIdleOnly,
                LocalDateTime.now().minusDays(LONG_IDLE_DAYS),
                MachineStockStatus.IN_STOCK.code(),
                RentalStatus.ACTIVE.code(),
                ListPageSupport.pageRequest(page, size)
        );
        return PageResult.of(
                result.getContent().stream().map(VehicleModelSummaryVO::fromProjection).toList(),
                normalizedPage,
                normalizedSize,
                result.getTotalElements()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<MachineInventoryVO> findVehiclesByModel(String name, String specificationModel, String machineType) {
        return findVehiclesByModel(name, specificationModel, machineType, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MachineInventoryVO> findVehiclesByModel(String name, String specificationModel, String machineType, String stock) {
        boolean longIdleOnly = "longIdle".equalsIgnoreCase(normalizeModelField(stock));
        return repository.findVehiclesByModel(
                        normalizeModelField(name),
                        normalizeModelField(specificationModel),
                        normalizeModelField(machineType),
                        SecurityUtils.isAdminOrSuperAdmin(),
                        longIdleOnly,
                        LocalDateTime.now().minusDays(LONG_IDLE_DAYS),
                        MachineStockStatus.IN_STOCK.code(),
                        RentalStatus.ACTIVE.code()
                ).stream()
                .map(MachineInventoryVO::fromEntity)
                .toList();
    }

    @Override
    public Optional<MachineInventory> findById(Long id) {
        if (SecurityUtils.isAdminOrSuperAdmin()) {
            return repository.findById(id);
        } else {
            return repository.findByIdAndIsLockedFalse(id);
        }
    }

    @Override
    public Optional<MachineInventory> findByIdForUpdate(Long id) {
        if (SecurityUtils.isAdminOrSuperAdmin()) {
            return repository.findByIdForUpdate(id);
        } else {
            return repository.findByIdAndIsLockedFalseForUpdate(id);
        }
    }

    @Override
    public Optional<MachineInventory> findByVehicleProductNumber(String vehicleProductNumber) {
        if (SecurityUtils.isAdminOrSuperAdmin()) {
            return repository.findByVehicleProductNumber(vehicleProductNumber);
        } else {
            return repository.findByVehicleProductNumberAndIsLockedFalse(vehicleProductNumber);
        }
    }

    @Override
    @Transactional
    public MachineInventory save(MachineInventory machineInventory) {
        boolean creating = machineInventory.getId() == null;
        // 更新操作时，检查原记录是否被锁定且当前用户非管理员
        if (machineInventory.getId() != null) {
            Optional<MachineInventory> existingOpt = findById(machineInventory.getId()); // 此处已应用角色过滤
            if (existingOpt.isPresent()) {
                MachineInventory existing = existingOpt.get();
                // 如果原记录是锁定的，且当前用户不是管理员，则拒绝更新
                visibilityPolicy.ensureWritable(existing.getIsLocked(), "该记录已被锁定，您无权修改");
            } else {
                throw new BusinessException(ResultCode.NOT_FOUND, "要更新的记录不存在");
            }
        }
        normalizeVehicleIdentity(machineInventory);
        normalizeInventoryCount(machineInventory, creating);
        if (Boolean.TRUE.equals(machineInventory.getModelOnly())) {
            machineInventory.setInventoryCount(0);
            machineInventory.setStockStatus(MachineStockStatus.PENDING_INBOUND.code());
            machineInventory.setEngineNumber(null);
            machineInventory.setFrameNumber(null);
            machineInventory.setWarrantyCardNumber(null);
            machineInventory.setInboundDate(null);
        }
        if (creating && machineInventory.getInboundDate() == null && machineInventory.getInventoryCount() > 0) {
            machineInventory.setInboundDate(java.time.LocalDateTime.now());
        }
        if (machineInventory.getWarehouseId() == null) {
            machineInventory.setWarehouseId(stockLedgerService.resolveWarehouseId(null));
        }
        if (machineInventory.getStockStatus() == null || machineInventory.getStockStatus().isBlank()) {
            machineInventory.setStockStatus(machineInventory.getInventoryCount() > 0
                    ? MachineStockStatus.IN_STOCK.code()
                    : MachineStockStatus.PENDING_INBOUND.code());
        }
        machineInventory.setPurchasePrice(MoneyValues.zeroIfNegative(machineInventory.getPurchasePrice()));
        machineInventory.setLandedUnitCost(MoneyValues.zeroIfNegative(machineInventory.getLandedUnitCost()));
        machineInventory.setSalePrice(MoneyValues.zeroIfNegative(machineInventory.getSalePrice()));
        machineInventory.setSettlementPrice(MoneyValues.zeroIfNegative(machineInventory.getSettlementPrice()));
        normalizeSupplier(machineInventory);

        collaborationService.stampWrite(machineInventory);
        MachineInventory saved = repository.save(machineInventory);
        if (creating) {
            stockLedgerService.reconcileAvailableQuantity(
                    StockLedgerService.RESOURCE_MACHINE,
                    saved.getId(),
                    saved.getWarehouseId(),
                    saved.getInventoryCount()
            );
        }
        return saved;
    }

    @Override
    @Transactional
    public MachineInventoryVO create(MachineInventoryCreateDTO dto) {
        MachineInventory saved = save(dto.toEntity());
        int quantity = saved.getInventoryCount() == null ? 0 : saved.getInventoryCount();
        if (quantity > 0) {
            createInitialLot(saved, quantity, "INITIAL_BALANCE", businessDate(saved.getInboundDate()), "INITIAL-LOT:MACHINE:" + saved.getId());
            saveStockLog(saved, "INITIAL", quantity, 0, quantity, null, "Initial machine stock");
        }
        String summary = Boolean.TRUE.equals(saved.getModelOnly()) ? "Create machine model" : "Create machine";
        operationAuditService.record("Machine", "CREATE", "MACHINE", saved.getId(),
                saved.getVehicleProductNumber(), saved.getName(), summary, null, saved.getRemarks());
        return MachineInventoryVO.fromEntity(saved);
    }

    @Override
    @Transactional
    public MachineInventoryVO update(Long id, MachineInventoryCreateDTO dto) {
        MachineInventory machine = findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.VEHICLE_NOT_FOUND));
        collaborationService.validateWrite(machine, dto.getVersion());
        int beforeQuantity = machine.getInventoryCount() == null ? 0 : machine.getInventoryCount();
        Long beforeWarehouseId = machine.getWarehouseId();
        String beforeStockStatus = machine.getStockStatus();
        BigDecimal beforePurchasePrice = machine.getPurchasePrice();
        BigDecimal beforeLandedUnitCost = machine.getLandedUnitCost();
        dto.updateEntity(machine);
        if (!Objects.equals(beforeQuantity, machine.getInventoryCount())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Inventory quantity must be changed through an explicit stock adjustment");
        }
        if (!Objects.equals(beforeWarehouseId, machine.getWarehouseId())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Vehicle warehouse must be changed through a warehouse transfer");
        }
        if (!Boolean.TRUE.equals(machine.getModelOnly())
                && !Objects.equals(beforeStockStatus, machine.getStockStatus())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Vehicle stock status is managed by inventory workflows and cannot be edited directly");
        }
        if (stockLotRepository.existsByResourceTypeAndResourceId(StockLedgerService.RESOURCE_MACHINE, machine.getId())
                && (!sameMoney(beforePurchasePrice, machine.getPurchasePrice())
                || !sameMoney(beforeLandedUnitCost, machine.getLandedUnitCost()))) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Posted vehicle cost cannot be edited directly; create a cost correction or reversal");
        }
        MachineInventory saved = save(machine);
        operationAuditService.record("Machine", "UPDATE", "MACHINE", saved.getId(),
                saved.getVehicleProductNumber(), saved.getName(), "Update machine", null, saved.getRemarks());
        return MachineInventoryVO.fromEntity(saved);
    }

    @Override
    @Transactional
    public void delete(Long id, Long version) {
        MachineInventory machine = findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.VEHICLE_NOT_FOUND));
        collaborationService.validateWrite(machine, version);
        deleteById(id);
        operationAuditService.record("Machine", "DELETE", "MACHINE", machine.getId(),
                machine.getVehicleProductNumber(), machine.getName(), "Delete machine", null, machine.getRemarks());
    }

    @Override
    @Transactional
    public void setLocked(Long id, boolean locked, Long version) {
        MachineInventory machine = findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.VEHICLE_NOT_FOUND, "Vehicle not found"));
        collaborationService.validateWrite(machine, version);
        machine.setIsLocked(locked);
        MachineInventory saved = save(machine);
        operationAuditService.record("Machine", locked ? "LOCK" : "UNLOCK", "MACHINE", saved.getId(),
                saved.getVehicleProductNumber(), saved.getName(), locked ? "Lock machine" : "Unlock machine", null, null);
    }

    @Override
    @Transactional
    public MachineInventoryVO inbound(InboundRequestDTO request) {
        List<InboundRequestDTO.ConfigSelection> requestedConfigs =
                request.getConfigs() == null ? List.of() : request.getConfigs();
        if (requestedConfigs.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Inbound configurations cannot contain null rows");
        }
        Set<Long> configItemIds = requestedConfigs.stream()
                .map(InboundRequestDTO.ConfigSelection::getConfigItemId)
                .collect(Collectors.toSet());
        if (configItemIds.contains(null) || configItemIds.size() != requestedConfigs.size()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Each inbound configuration item must be selected exactly once");
        }
        if (requestedConfigs.stream().anyMatch(config -> config.getConfigValueId() == null)) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Inbound configuration value is required");
        }
        MachineInventory savedMachine = save(request.getMachineInventory().toEntity());
        Long machineId = savedMachine.getId();

        if (!requestedConfigs.isEmpty()) {
            List<MachineConfig> configList = new ArrayList<>();
            for (InboundRequestDTO.ConfigSelection config : requestedConfigs) {
                ConfigItem item = configItemRepository.findById(config.getConfigItemId())
                        .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Config item not found"));
                ConfigValue value = configValueRepository.findById(config.getConfigValueId())
                        .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Config value not found"));
                if (!item.getId().equals(value.getConfigItemId())) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "Config value does not belong to selected item");
                }
                MachineConfig mc = new MachineConfig();
                mc.setMachineId(machineId);
                mc.setConfigItemId(item.getId());
                mc.setConfigValueId(value.getId());
                mc.setItemName(item.getItemName());
                mc.setSelectedValue(value.getValueLabel());
                String configSource = config.getConfigSource() == null || config.getConfigSource().isBlank()
                        ? "FACTORY_STANDARD"
                        : config.getConfigSource();
                mc.setConfigSource(configSource);
                mc.setIsStandard(config.getIsStandard() != null
                        ? config.getIsStandard()
                        : !"FACTORY_OPTIONAL".equals(configSource));
                mc.setInstalledDate(LocalDateTime.now());
                configList.add(mc);
            }
            machineConfigService.saveAll(configList);
        }
        int quantity = savedMachine.getInventoryCount() == null ? 0 : savedMachine.getInventoryCount();
        if (quantity > 0) {
            createInitialLot(savedMachine, quantity, "MACHINE_INBOUND", businessDate(savedMachine.getInboundDate()),
                    "MACHINE-INBOUND-LOT:" + savedMachine.getId());
            saveStockLog(savedMachine, "INBOUND", quantity, 0, quantity, null, "Machine inbound profile created");
        }
        return MachineInventoryVO.fromEntity(savedMachine);
    }

    @Override
    @Transactional
    public MachineInventoryVO inboundStock(Long id, StockAdjustRequestDTO request) {
        return MachineInventoryVO.fromEntity(adjustStock(id, request, true));
    }

    @Override
    @Transactional
    public MachineInventoryVO outboundStock(Long id, StockAdjustRequestDTO request) {
        return MachineInventoryVO.fromEntity(adjustStock(id, request, false));
    }

    @Override
    @Transactional
    public List<MachineConfigVO> updateConfigs(Long id, Long version, List<MachineConfigVO> configVOs) {
        MachineInventory machine = findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.VEHICLE_NOT_FOUND));
        collaborationService.validateWrite(machine, version);
        List<MachineConfig> saved = machineConfigUpdateCoordinator.apply(id, configVOs);
        save(machine);
        List<MachineConfigVO> result = saved.stream().map(MachineConfigVO::fromEntity).collect(Collectors.toList());
        operationAuditService.record("Machine config", "CONFIG_UPDATE", "MACHINE", id,
                null, "Machine ID " + id, "Update machine configs: " + result.size(), null, null);
        return result;
    }

    private void normalizeVehicleIdentity(MachineInventory machineInventory) {
        if (machineInventory.getModelOnly() == null) {
            machineInventory.setModelOnly(false);
        }
        String vehicleNumber = trimToNull(machineInventory.getVehicleProductNumber());
        if (vehicleNumber != null) {
            machineInventory.setVehicleProductNumber(vehicleNumber);
            return;
        }
        if (Boolean.TRUE.equals(machineInventory.getModelOnly())) {
            machineInventory.setVehicleProductNumber(generateVehicleNumber("MODEL"));
            return;
        }
        if (isManualForklift(machineInventory.getMachineType())) {
            machineInventory.setVehicleProductNumber(generateVehicleNumber("MAN"));
            return;
        }
        throw new BusinessException(ResultCode.PARAM_ERROR, "车号不能为空");
    }

    private void normalizeInventoryCount(MachineInventory machineInventory, boolean creating) {
        Integer inventoryCount = machineInventory.getInventoryCount();
        if (Boolean.TRUE.equals(machineInventory.getModelOnly())) {
            machineInventory.setInventoryCount(0);
            return;
        }
        if (inventoryCount == null) {
            machineInventory.setInventoryCount(creating ? 1 : 0);
            return;
        }
        InventoryQuantities.requireNonNegative(inventoryCount, "Inventory count cannot be negative");
        if (creating && inventoryCount > 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "A concrete vehicle is serialized and can only be created with zero or one unit of stock");
        }
    }

    static void normalizeAvailableStock(MachineInventory machineInventory) {
        // Deliberately no-op. A zero balance while marked in stock is a data
        // quality issue that must be corrected with an explicit adjustment,
        // never silently turned into a phantom vehicle.
    }

    private boolean isManualForklift(String machineType) {
        String normalized = trimToNull(machineType);
        return normalized != null && normalized.contains("手动");
    }

    private void normalizeSupplier(MachineInventory machine) {
        if (machine.getSupplierId() != null) {
            var supplier = supplierRepository.findById(machine.getSupplierId())
                    .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Supplier not found"));
            if (!Boolean.TRUE.equals(supplier.getActive())) {
                throw new BusinessException(ResultCode.CONFLICT, "Inactive supplier cannot be selected for vehicle inventory");
            }
            machine.setSupplierNameSnapshot(supplier.getSupplierName());
            machine.setSupplier(supplier.getSupplierName());
            return;
        }
        String supplierName = trimToNull(machine.getSupplierNameSnapshot());
        if (supplierName == null) {
            supplierName = trimToNull(machine.getSupplier());
        }
        if (supplierName != null) {
            supplierRepository.findBySupplierName(supplierName).ifPresent(supplier -> {
                machine.setSupplierId(supplier.getId());
                machine.setSupplierNameSnapshot(supplier.getSupplierName());
                machine.setSupplier(supplier.getSupplierName());
            });
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String generateVehicleNumber(String prefix) {
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = "%s-%s".formatted(prefix, UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
            if (!repository.existsByVehicleProductNumber(candidate)) {
                return candidate;
            }
        }
        throw new BusinessException(ResultCode.PARAM_ERROR, "系统生成车号失败，请重试");
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        Optional<MachineInventory> existingOpt = findByIdForUpdate(id); // 角色过滤
        if (existingOpt.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "车辆不存在");
        }
        MachineInventory existing = existingOpt.get();
        visibilityPolicy.ensureWritable(existing.getIsLocked(), "该记录已被锁定，您无权删除");
        deletionGuard.ensureMachineDeletable(id);
        stockLedgerService.deleteEmptyBalances(StockLedgerService.RESOURCE_MACHINE, id);
        machineConfigService.deleteByMachineId(id);
        repository.deleteById(id);
    }

    private MachineInventory adjustStock(Long id, StockAdjustRequestDTO request, boolean inbound) {
        MachineInventory machine = findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.VEHICLE_NOT_FOUND, "Vehicle not found"));
        if (Boolean.TRUE.equals(machine.getModelOnly())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Vehicle model templates cannot be adjusted as physical stock");
        }
        if (MachineStockStatus.RENTED.code().equals(machine.getStockStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "A rented vehicle cannot be adjusted with a normal stock operation");
        }
        if (MachineStockStatus.isActiveModification(machine.getStockStatus())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "A vehicle in an active modification workflow cannot be adjusted");
        }
        Integer quantity = request.getQuantity();
        if (quantity == null || quantity != 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "A serialized vehicle adjustment must be exactly one unit");
        }
        collaborationService.validateWrite(machine, request.getVersion());
        Long warehouseId = stockLedgerService.resolveWarehouseId(request.getWarehouseId());
        int before = stockLedgerService.availableQuantity(StockLedgerService.RESOURCE_MACHINE, machine.getId(), warehouseId);
        if (inbound && stockLedgerService.totalAvailableQuantity(StockLedgerService.RESOURCE_MACHINE, machine.getId()) > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "A concrete vehicle cannot have more than one unit of stock");
        }
        if (!inbound && before < 1) {
            throw new BusinessException(ResultCode.INSUFFICIENT_STOCK, "Vehicle is not available in the selected warehouse");
        }
        int after = inbound ? before + 1 : before - 1;
        LocalDate businessDate = request.getBusinessDate() == null ? LocalDate.now() : request.getBusinessDate();
        BigDecimal unitCost = stockUnitCost(machine);
        String idempotencyBase = "MACHINE-ADJUST:" + machine.getId() + ":" + request.getVersion()
                + ":" + businessDate + ":" + (inbound ? "IN" : "OUT");
        if (inbound) {
            machine.setInboundDate(businessDate.atStartOfDay());
        }
        boolean openingBalance = Boolean.TRUE.equals(request.getOpeningBalance());
        inventoryAdjustmentAccountingService.post(new InventoryAdjustmentAccountingService.Command(
                "Machine stock",
                StockLedgerService.RESOURCE_MACHINE,
                machine.getId(),
                machine.getVehicleProductNumber(),
                machine.getName(),
                warehouseId,
                before,
                after,
                unitCost,
                request.getOperator(),
                explicitAdjustmentRemark(request),
                openingBalance ? "OPENING_MIGRATION" : "STOCK_ADJUSTMENT",
                machine.getId(),
                openingBalance
                        ? "Opening machine balance"
                        : inbound ? "Explicit machine inbound adjustment" : "Explicit machine outbound adjustment",
                businessDate,
                openingBalance
                        ? com.example.forklift_erp.constant.StockBusinessType.INITIAL_BALANCE
                        : com.example.forklift_erp.constant.StockBusinessType.STOCK_ADJUSTMENT,
                idempotencyBase,
                !openingBalance
        ));
        machine.setWarehouseId(warehouseId);
        machine.setInventoryCount(stockLedgerService.totalAvailableQuantity(StockLedgerService.RESOURCE_MACHINE, machine.getId()));
        machine.setStockStatus(machine.getInventoryCount() > 0 ? MachineStockStatus.IN_STOCK.code()
                : (inbound ? MachineStockStatus.PENDING_INBOUND.code() : MachineStockStatus.OUTBOUND.code()));
        collaborationService.stampWrite(machine);
        return repository.saveAndFlush(machine);
    }

    private StockOperationLog saveStockLog(MachineInventory machine, String operationType, Integer quantity,
                                           Integer beforeQuantity, Integer afterQuantity, String operator, String remark) {
        BigDecimal unitCost = stockUnitCost(machine);
        return stockOperationRecorder.recordMachine(machine, operationType, quantity,
                beforeQuantity, afterQuantity, unitCost, operator, remark);
    }

    private BigDecimal stockUnitCost(MachineInventory machine) {
        return MoneyValues.firstNonNegativeOrZero(machine.getLandedUnitCost(), machine.getPurchasePrice());
    }

    private void createInitialLot(
            MachineInventory machine,
            int quantity,
            String sourceType,
            LocalDate businessDate,
            String idempotencyKey
    ) {
        if (Boolean.TRUE.equals(machine.getModelOnly()) || quantity <= 0) {
            return;
        }
        stockLotService.createReceiptLot(
                StockLedgerService.RESOURCE_MACHINE,
                machine.getId(),
                machine.getWarehouseId(),
                quantity,
                stockUnitCost(machine),
                BigDecimal.ZERO,
                sourceType,
                machine.getId(),
                null,
                businessDate,
                idempotencyKey
        );
    }

    private LocalDate businessDate(LocalDateTime dateTime) {
        return dateTime == null ? LocalDate.now() : dateTime.toLocalDate();
    }

    private boolean sameMoney(BigDecimal left, BigDecimal right) {
        return MoneyValues.zeroIfNullOrNegative(left).compareTo(MoneyValues.zeroIfNullOrNegative(right)) == 0;
    }

    private String explicitAdjustmentRemark(StockAdjustRequestDTO request) {
        String reason = request.getReason() == null ? "" : request.getReason().trim();
        String remark = request.getRemark() == null ? "" : request.getRemark().trim();
        if (reason.isBlank() && remark.isBlank()) {
            return "Explicit inventory adjustment";
        }
        return reason.isBlank() ? remark : remark.isBlank() ? reason : reason + "; " + remark;
    }

    private String normalizeModelField(String value) {
        return value == null ? "" : value.trim();
    }
}
