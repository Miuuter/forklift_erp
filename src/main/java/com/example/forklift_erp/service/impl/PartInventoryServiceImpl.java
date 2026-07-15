package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.PageResult;
import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.constant.StockBusinessType;
import com.example.forklift_erp.constant.FinancialEventType;
import com.example.forklift_erp.dto.PartInventoryCreateDTO;
import com.example.forklift_erp.dto.PartInventoryVO;
import com.example.forklift_erp.dto.PartStockAdjustRequestDTO;
import com.example.forklift_erp.dto.RemovedPartValuationDTO;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.StockOperationLog;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.ConfigReplaceLogRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderLineRepository;
import com.example.forklift_erp.repository.OutboundOrderRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.PurchaseOrderRepository;
import com.example.forklift_erp.repository.RepairPartUsageRepository;
import com.example.forklift_erp.repository.ResourceAttachmentRepository;
import com.example.forklift_erp.repository.StocktakingRecordRepository;
import com.example.forklift_erp.repository.StockLotRepository;
import com.example.forklift_erp.service.CollaborationService;
import com.example.forklift_erp.service.InventoryAdjustmentAccountingService;
import com.example.forklift_erp.service.FinancialEventService;
import com.example.forklift_erp.service.OperationAuditService;
import com.example.forklift_erp.service.PartInventoryService;
import com.example.forklift_erp.service.PartInventoryViewAssembler;
import com.example.forklift_erp.service.ResourceVisibilityPolicy;
import com.example.forklift_erp.service.StockLedgerService;
import com.example.forklift_erp.service.StockLotService;
import com.example.forklift_erp.util.InventoryQuantities;
import com.example.forklift_erp.util.ListPageSupport;
import com.example.forklift_erp.util.MoneyValues;
import com.example.forklift_erp.util.SearchKeywordSupport;
import com.example.forklift_erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
public class PartInventoryServiceImpl implements PartInventoryService {

    @Autowired
    private PartInventoryRepository partRepository;

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
    private FinancialEventService financialEventService;

    @Autowired
    private OperationAuditService operationAuditService;

    @Autowired
    private StockOperationRecorder stockOperationRecorder;

    @Autowired
    private ResourceVisibilityPolicy visibilityPolicy;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private OutboundOrderRepository outboundOrderRepository;

    @Autowired
    private ModificationWorkOrderLineRepository modificationWorkOrderLineRepository;

    @Autowired
    private RepairPartUsageRepository repairPartUsageRepository;

    @Autowired
    private ConfigReplaceLogRepository configReplaceLogRepository;

    @Autowired
    private StocktakingRecordRepository stocktakingRecordRepository;

    @Autowired
    private ResourceAttachmentRepository resourceAttachmentRepository;

    @Autowired
    private PartInventoryViewAssembler partInventoryViewAssembler;

    @Autowired
    private InventoryMasterDeletionGuard deletionGuard;

    @Override
    public List<PartInventory> findAll() {
        if (SecurityUtils.isAdminOrSuperAdmin()) {
            return partRepository.findAll();
        }
        return partRepository.findAllByIsLockedFalse();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<PartInventoryVO> findPage(String keyword, String stock, Integer page, Integer size) {
        int normalizedPage = ListPageSupport.page(page);
        int normalizedSize = ListPageSupport.size(size);
        Page<PartInventory> result = partRepository.searchPage(
                SearchKeywordSupport.likePrefix(keyword),
                SearchKeywordSupport.fullTextBoolean(keyword),
                SecurityUtils.isAdminOrSuperAdmin(),
                stock == null || stock.isBlank() ? null : stock.trim(),
                ListPageSupport.pageRequest(page, size)
        );
        return PageResult.of(
                partInventoryViewAssembler.toVOs(result.getContent()),
                normalizedPage,
                normalizedSize,
                result.getTotalElements()
        );
    }

    @Override
    public PartInventoryVO toVO(PartInventory part) {
        return partInventoryViewAssembler.toVO(part);
    }

    @Override
    public List<PartInventoryVO> toVOs(List<PartInventory> parts) {
        return partInventoryViewAssembler.toVOs(parts);
    }

    @Override
    public Optional<PartInventory> findById(Long id) {
        if (SecurityUtils.isAdminOrSuperAdmin()) {
            return partRepository.findById(id);
        }
        return partRepository.findByIdAndIsLockedFalse(id);
    }

    @Override
    public Optional<PartInventory> findByIdForUpdate(Long id) {
        if (SecurityUtils.isAdminOrSuperAdmin()) {
            return partRepository.findByIdForUpdate(id);
        }
        return partRepository.findByIdAndIsLockedFalseForUpdate(id);
    }

    @Override
    public Optional<PartInventory> findByPartCode(String partCode) {
        if (SecurityUtils.isAdminOrSuperAdmin()) {
            return partRepository.findByPartCode(partCode);
        }
        return partRepository.findByPartCodeAndIsLockedFalse(partCode);
    }

    @Override
    public Optional<PartInventory> findByPartCodeForUpdate(String partCode) {
        if (SecurityUtils.isAdminOrSuperAdmin()) {
            return partRepository.findByPartCodeForUpdate(partCode);
        }
        return partRepository.findByPartCodeAndIsLockedFalseForUpdate(partCode);
    }

    @Override
    @Transactional
    public PartInventory save(PartInventory part) {
        boolean creating = part.getId() == null;
        if (part.getId() != null) {
            Optional<PartInventory> existingOpt = findById(part.getId());
            if (existingOpt.isPresent()) {
                PartInventory existing = existingOpt.get();
                visibilityPolicy.ensureWritable(existing.getIsLocked(), "Part is locked and cannot be modified");
            } else {
                throw new BusinessException(ResultCode.NOT_FOUND, "Part not found");
            }
        }

        if (part.getId() == null) {
            Optional<PartInventory> exist = partRepository.findByPartCode(part.getPartCode());
            if (exist.isPresent()) {
                throw new BusinessException(ResultCode.DATA_DUPLICATE, "Part code already exists: " + part.getPartCode());
            }
        } else {
            Optional<PartInventory> exist = partRepository.findByPartCode(part.getPartCode());
            if (exist.isPresent() && !exist.get().getId().equals(part.getId())) {
                throw new BusinessException(ResultCode.DATA_DUPLICATE, "Part code is already used: " + part.getPartCode());
            }
        }

        if (part.getQuantity() == null) {
            part.setQuantity(0);
        }
        if (part.getReorderPoint() == null) {
            part.setReorderPoint(5);
        }
        InventoryQuantities.requireNonNegative(part.getQuantity(), "Part quantity cannot be negative");
        InventoryQuantities.requireNonNegative(part.getReorderPoint(), "Part reorder point cannot be negative");
        if (part.getWarehouseId() == null) {
            part.setWarehouseId(stockLedgerService.resolveWarehouseId(null));
        }
        part.setPurchasePrice(MoneyValues.zeroIfNegative(part.getPurchasePrice()));
        part.setLandedUnitCost(MoneyValues.zeroIfNegative(part.getLandedUnitCost()));
        part.setSalePrice(MoneyValues.zeroIfNegative(part.getSalePrice()));
        part.setSettlementPrice(MoneyValues.zeroIfNegative(part.getSettlementPrice()));
        log.info("Save part: partCode={}, name={}, quantity={}", part.getPartCode(), part.getPartName(), part.getQuantity());
        collaborationService.stampWrite(part);
        PartInventory saved = partRepository.save(part);
        if (creating) {
            stockLedgerService.reconcileAvailableQuantity(
                    StockLedgerService.RESOURCE_PART,
                    saved.getId(),
                    saved.getWarehouseId(),
                    saved.getQuantity()
            );
        }
        return saved;
    }

    @Override
    @Transactional
    public PartInventoryVO create(PartInventoryCreateDTO dto) {
        PartInventory saved = save(dto.toEntity());
        int quantity = saved.getQuantity() == null ? 0 : saved.getQuantity();
        if (quantity > 0) {
            createInitialLot(saved, quantity, "INITIAL_BALANCE", businessDate(saved.getInboundDate()),
                    "INITIAL-LOT:PART:" + saved.getId());
            saveStockLog(saved, "INITIAL", quantity, 0, quantity, null, "Initial part stock");
        }
        operationAuditService.record("Part", "CREATE", "PART", saved.getId(),
                saved.getPartCode(), saved.getPartName(), "Create part", null, saved.getRemarks());
        return toVO(saved);
    }

    @Override
    @Transactional
    public PartInventoryVO update(Long id, PartInventoryCreateDTO dto) {
        PartInventory part = findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.PART_NOT_FOUND));
        collaborationService.validateWrite(part, dto.getVersion());
        int beforeQuantity = part.getQuantity() == null ? 0 : part.getQuantity();
        Long beforeWarehouseId = part.getWarehouseId();
        BigDecimal beforePurchasePrice = part.getPurchasePrice();
        BigDecimal beforeLandedUnitCost = part.getLandedUnitCost();
        dto.updateEntity(part);
        if (!Objects.equals(beforeQuantity, part.getQuantity())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Inventory quantity must be changed through an explicit stock adjustment");
        }
        if (!Objects.equals(beforeWarehouseId, part.getWarehouseId())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Part warehouse must be changed through a warehouse transfer");
        }
        if (stockLotRepository.existsByResourceTypeAndResourceId(StockLedgerService.RESOURCE_PART, part.getId())
                && (!sameMoney(beforePurchasePrice, part.getPurchasePrice())
                || !sameMoney(beforeLandedUnitCost, part.getLandedUnitCost()))) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Posted part cost cannot be edited directly; create a cost correction or reversal");
        }
        PartInventory saved = save(part);
        operationAuditService.record("Part", "UPDATE", "PART", saved.getId(),
                saved.getPartCode(), saved.getPartName(), "Update part", null, saved.getRemarks());
        return toVO(saved);
    }

    @Override
    @Transactional
    public PartInventoryVO valueRemovedPart(Long id, RemovedPartValuationDTO dto) {
        PartInventory part = findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.PART_NOT_FOUND));
        collaborationService.validateWrite(part, dto.getVersion());
        if (!"REMOVED".equalsIgnoreCase(part.getSource()) || !Boolean.TRUE.equals(part.getIsLocked())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Only a quarantined removed part can use the valuation workflow");
        }
        LocalDate businessDate = dto.getBusinessDate() == null ? LocalDate.now() : dto.getBusinessDate();
        String idempotencyBase = "REMOVED-PART-VALUATION:" + part.getId() + ":" + part.getVersion();
        BigDecimal adjustment = stockLotService.revalueUnconsumedReceiptLots(
                StockLedgerService.RESOURCE_PART,
                part.getId(),
                part.getWarehouseId(),
                dto.getUnitCost(),
                "REMOVED_PART_VALUATION",
                part.getId(),
                businessDate,
                idempotencyBase + ":FIFO"
        );
        part.setPurchasePrice(dto.getUnitCost());
        part.setLandedUnitCost(dto.getUnitCost());
        part.setIsLocked(false);
        String valuationRemark = "Valuation confirmed: source=" + dto.getValuationSource().trim()
                + "; condition=" + (dto.getCondition() == null || dto.getCondition().isBlank()
                ? "UNSPECIFIED" : dto.getCondition().trim())
                + (dto.getRemark() == null || dto.getRemark().isBlank() ? "" : "; " + dto.getRemark().trim());
        part.setRemarks(part.getRemarks() == null || part.getRemarks().isBlank()
                ? valuationRemark
                : part.getRemarks() + "; " + valuationRemark);
        collaborationService.stampWrite(part);
        PartInventory saved = partRepository.saveAndFlush(part);
        if (adjustment.signum() > 0) {
            financialEventService.post(
                    FinancialEventType.INVENTORY_GAIN,
                    adjustment,
                    businessDate,
                    "REMOVED_PART_VALUATION",
                    saved.getId(),
                    null,
                    null,
                    null,
                    null,
                    valuationRemark,
                    idempotencyBase + ":GAIN"
            );
        }
        operationAuditService.record(
                "Removed part valuation",
                "VALUE",
                "PART",
                saved.getId(),
                saved.getPartCode(),
                saved.getPartName(),
                "Confirm removed-part unit cost " + dto.getUnitCost(),
                dto.getOperator(),
                valuationRemark
        );
        return toVO(saved);
    }

    @Override
    @Transactional
    public void delete(Long id, Long version) {
        PartInventory part = findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.PART_NOT_FOUND));
        collaborationService.validateWrite(part, version);
        deleteById(id);
        operationAuditService.record("Part", "DELETE", "PART", part.getId(),
                part.getPartCode(), part.getPartName(), "Delete part", null, part.getRemarks());
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        Optional<PartInventory> existingOpt = findByIdForUpdate(id);
        if (existingOpt.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "Part not found, id=" + id);
        }
        PartInventory existing = existingOpt.get();
        visibilityPolicy.ensureWritable(existing.getIsLocked(), "Part is locked and cannot be deleted");
        deletionGuard.ensurePartDeletable(id);
        stockLedgerService.deleteEmptyBalances(StockLedgerService.RESOURCE_PART, id);
        partRepository.deleteById(id);
        log.info("Delete part: id={}", id);
    }

    @Override
    public List<PartInventory> findByCategory(String category) {
        if (SecurityUtils.isAdminOrSuperAdmin()) {
            return partRepository.findByPartCategory(category);
        }
        return partRepository.findByPartCategoryAndIsLockedFalse(category);
    }

    @Override
    public List<PartInventory> findAvailableParts() {
        if (SecurityUtils.isAdminOrSuperAdmin()) {
            return partRepository.findByQuantityGreaterThan(0);
        }
        return partRepository.findByQuantityGreaterThanAndIsLockedFalse(0);
    }

    @Override
    public List<PartInventory> findBySource(String source) {
        if (SecurityUtils.isAdminOrSuperAdmin()) {
            return partRepository.findBySource(source);
        }
        return partRepository.findBySourceAndIsLockedFalse(source);
    }

    @Override
    public List<PartInventory> findBySourceMachineId(Long machineId) {
        if (SecurityUtils.isAdminOrSuperAdmin()) {
            return partRepository.findBySourceMachineId(machineId);
        }
        return partRepository.findBySourceMachineIdAndIsLockedFalse(machineId);
    }

    @Override
    @Transactional
    public PartInventory inbound(String partCode, int quantity, Long expectedVersion) {
        PartStockAdjustRequestDTO request = new PartStockAdjustRequestDTO();
        request.setPartCode(partCode);
        request.setQuantity(quantity);
        request.setVersion(expectedVersion);
        request.setBusinessDate(LocalDate.now());
        request.setReason("Service-layer inbound adjustment");
        PartInventoryVO saved = inbound(request);
        return partRepository.findById(saved.getId())
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Part not found after inbound"));
    }

    @Override
    @Transactional
    public PartInventory outbound(String partCode, int quantity, Long expectedVersion) {
        PartStockAdjustRequestDTO request = new PartStockAdjustRequestDTO();
        request.setPartCode(partCode);
        request.setQuantity(quantity);
        request.setVersion(expectedVersion);
        request.setBusinessDate(LocalDate.now());
        request.setReason("Service-layer outbound adjustment");
        PartInventoryVO saved = outbound(request);
        return partRepository.findById(saved.getId())
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Part not found after outbound"));
    }

    @Override
    @Transactional
    public PartInventoryVO inbound(PartStockAdjustRequestDTO request) {
        PartInventory part = findByPartCodeForUpdate(request.getPartCode())
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Part code not found: " + request.getPartCode()));
        visibilityPolicy.ensureWritable(part.getIsLocked(), "Part is locked and cannot be inbounded");
        collaborationService.validateWrite(part, request.getVersion());
        Long warehouseId = stockLedgerService.resolveWarehouseId(request.getWarehouseId());
        int before = stockLedgerService.availableQuantity(StockLedgerService.RESOURCE_PART, part.getId(), warehouseId);
        int after = before + request.getQuantity();
        LocalDate businessDate = request.getBusinessDate() == null ? LocalDate.now() : request.getBusinessDate();
        String idempotencyBase = "PART-ADJUST:" + part.getId() + ":" + request.getVersion()
                + ":" + businessDate + ":IN";
        boolean openingBalance = Boolean.TRUE.equals(request.getOpeningBalance());
        inventoryAdjustmentAccountingService.post(new InventoryAdjustmentAccountingService.Command(
                "Part stock",
                StockLedgerService.RESOURCE_PART,
                part.getId(),
                part.getPartCode(),
                part.getPartName(),
                warehouseId,
                before,
                after,
                stockUnitCost(part),
                request.getOperator(),
                explicitAdjustmentRemark(request),
                openingBalance ? "OPENING_MIGRATION" : "STOCK_ADJUSTMENT",
                part.getId(),
                openingBalance ? "Opening part balance" : "Explicit part inbound adjustment",
                businessDate,
                openingBalance ? StockBusinessType.INITIAL_BALANCE : StockBusinessType.STOCK_ADJUSTMENT,
                idempotencyBase,
                !openingBalance
        ));
        part.setQuantity(stockLedgerService.totalAvailableQuantity(StockLedgerService.RESOURCE_PART, part.getId()));
        part.setInboundDate(businessDate.atStartOfDay());
        collaborationService.stampWrite(part);
        partRepository.saveAndFlush(part);
        return toVO(part);
    }

    @Override
    @Transactional
    public PartInventoryVO outbound(PartStockAdjustRequestDTO request) {
        PartInventory part = findByPartCodeForUpdate(request.getPartCode())
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Part code not found: " + request.getPartCode()));
        visibilityPolicy.ensureWritable(part.getIsLocked(), "Part is locked and cannot be outbounded");
        collaborationService.validateWrite(part, request.getVersion());
        Long warehouseId = stockLedgerService.resolveWarehouseId(request.getWarehouseId());
        int before = stockLedgerService.availableQuantity(StockLedgerService.RESOURCE_PART, part.getId(), warehouseId);
        if (before < request.getQuantity()) {
            throw new BusinessException(ResultCode.INSUFFICIENT_STOCK, "Insufficient source warehouse stock: " + before);
        }
        int after = before - request.getQuantity();
        LocalDate businessDate = request.getBusinessDate() == null ? LocalDate.now() : request.getBusinessDate();
        String idempotencyBase = "PART-ADJUST:" + part.getId() + ":" + request.getVersion()
                + ":" + businessDate + ":OUT";
        inventoryAdjustmentAccountingService.post(new InventoryAdjustmentAccountingService.Command(
                "Part stock",
                StockLedgerService.RESOURCE_PART,
                part.getId(),
                part.getPartCode(),
                part.getPartName(),
                warehouseId,
                before,
                after,
                stockUnitCost(part),
                request.getOperator(),
                explicitAdjustmentRemark(request),
                "STOCK_ADJUSTMENT",
                part.getId(),
                "Explicit part outbound adjustment",
                businessDate,
                StockBusinessType.STOCK_ADJUSTMENT,
                idempotencyBase,
                true
        ));
        part.setQuantity(stockLedgerService.totalAvailableQuantity(StockLedgerService.RESOURCE_PART, part.getId()));
        collaborationService.stampWrite(part);
        partRepository.saveAndFlush(part);
        return toVO(part);
    }

    private StockOperationLog saveStockLog(PartInventory part, String operationType, Integer quantity,
                                           Integer beforeQuantity, Integer afterQuantity, String operator, String remark) {
        BigDecimal unitCost = stockUnitCost(part);
        return stockOperationRecorder.recordPart(part, operationType, quantity,
                beforeQuantity, afterQuantity, unitCost, operator, remark);
    }

    private BigDecimal stockUnitCost(PartInventory part) {
        return MoneyValues.firstNonNegativeOrZero(part.getLandedUnitCost(), part.getPurchasePrice());
    }

    private void createInitialLot(
            PartInventory part,
            int quantity,
            String sourceType,
            LocalDate businessDate,
            String idempotencyKey
    ) {
        if (quantity <= 0) {
            return;
        }
        stockLotService.createReceiptLot(
                StockLedgerService.RESOURCE_PART,
                part.getId(),
                part.getWarehouseId(),
                quantity,
                stockUnitCost(part),
                BigDecimal.ZERO,
                sourceType,
                part.getId(),
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

    private String explicitAdjustmentRemark(PartStockAdjustRequestDTO request) {
        String reason = request.getReason() == null ? "" : request.getReason().trim();
        String remark = request.getRemark() == null ? "" : request.getRemark().trim();
        if (reason.isBlank() && remark.isBlank()) {
            return "Explicit inventory adjustment";
        }
        return reason.isBlank() ? remark : remark.isBlank() ? reason : reason + "; " + remark;
    }

}
