package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.PageResult;
import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.constant.JobTag;
import com.example.forklift_erp.constant.FinancialEventType;
import com.example.forklift_erp.constant.RepairStatus;
import com.example.forklift_erp.constant.StockBusinessType;
import com.example.forklift_erp.dto.RepairRecordCreateDTO;
import com.example.forklift_erp.dto.RepairPartUsageDTO;
import com.example.forklift_erp.dto.RepairRecordVO;
import com.example.forklift_erp.entity.Customer;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.PaymentRecord;
import com.example.forklift_erp.entity.RepairRecord;
import com.example.forklift_erp.entity.RepairPartUsage;
import com.example.forklift_erp.entity.StockOperationLog;
import com.example.forklift_erp.entity.StockMovement;
import com.example.forklift_erp.entity.StockMovementLine;
import com.example.forklift_erp.entity.User;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.CustomerRepository;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.PaymentRecordRepository;
import com.example.forklift_erp.repository.RepairRecordRepository;
import com.example.forklift_erp.repository.RepairPartUsageRepository;
import com.example.forklift_erp.repository.StockLotConsumptionRepository;
import com.example.forklift_erp.repository.StockMovementLineRepository;
import com.example.forklift_erp.repository.UserRepository;
import com.example.forklift_erp.service.CollaborationService;
import com.example.forklift_erp.service.FinancialEventService;
import com.example.forklift_erp.service.MigrationExceptionService;
import com.example.forklift_erp.service.OperationAuditService;
import com.example.forklift_erp.service.RepairRecordService;
import com.example.forklift_erp.service.ResourceVisibilityPolicy;
import com.example.forklift_erp.service.StockLedgerService;
import com.example.forklift_erp.service.StockLotService;
import com.example.forklift_erp.util.ListPageSupport;
import com.example.forklift_erp.util.MoneyValues;
import com.example.forklift_erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RepairRecordServiceImpl implements RepairRecordService {
    private static final String REPAIR_SOURCE_TYPE = "REPAIR";
    private static final String REPAIR_USE_OPERATION = "REPAIR_USE";
    private static final String REPAIR_RESTORE_OPERATION = "REPAIR_RESTORE";

    @Autowired
    private RepairRecordRepository repairRepository;

    @Autowired
    private MachineInventoryRepository machineRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private PartInventoryRepository partRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CollaborationService collaborationService;

    @Autowired
    private OperationAuditService operationAuditService;

    @Autowired
    private StockOperationRecorder stockOperationRecorder;

    @Autowired
    private ResourceVisibilityPolicy visibilityPolicy;

    @Autowired
    private RepairPartUsageRepository repairPartUsageRepository;

    @Autowired
    private PaymentRecordRepository paymentRecordRepository;

    @Autowired
    private FinancialEventService financialEventService;

    @Autowired
    private StockLedgerService stockLedgerService;

    @Autowired
    private StockLotService stockLotService;

    @Autowired
    private StockMovementLineRepository stockMovementLineRepository;

    @Autowired
    private StockLotConsumptionRepository stockLotConsumptionRepository;

    @Autowired
    private MigrationExceptionService migrationExceptionService;

    @Override
    public List<RepairRecord> findAll() {
        if (SecurityUtils.isAdminOrSuperAdmin()) {
            return repairRepository.findAll();
        }
        return repairRepository.findAllByIsLockedFalse();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<RepairRecordVO> findPage(
            String keyword,
            Integer page,
            Integer size,
            Long machineId,
            String repairPerson,
            String status,
            LocalDateTime startDate,
            LocalDateTime endDate
    ) {
        int normalizedPage = ListPageSupport.page(page);
        int normalizedSize = ListPageSupport.size(size);
        Page<RepairRecord> result = repairRepository.searchPage(
                normalizeKeyword(keyword),
                machineId,
                normalizeKeyword(repairPerson),
                normalizeKeyword(status),
                startDate,
                endDate,
                SecurityUtils.isAdminOrSuperAdmin(),
                ListPageSupport.pageRequest(page, size, Sort.by(Sort.Direction.DESC, "repairDate"))
        );
        return PageResult.of(
                result.getContent().stream().map(this::toVO).toList(),
                normalizedPage,
                normalizedSize,
                result.getTotalElements()
        );
    }

    @Override
    public Optional<RepairRecord> findById(Long id) {
        if (SecurityUtils.isAdminOrSuperAdmin()) {
            return repairRepository.findById(id);
        }
        return repairRepository.findByIdAndIsLockedFalse(id);
    }

    @Override
    public Optional<RepairRecord> findByIdForUpdate(Long id) {
        if (SecurityUtils.isAdminOrSuperAdmin()) {
            return repairRepository.findByIdForUpdate(id);
        }
        return repairRepository.findByIdAndIsLockedFalseForUpdate(id);
    }

    @Override
    @Transactional(readOnly = true)
    public RepairRecordVO toVO(RepairRecord record) {
        return RepairRecordVO.fromEntity(
                record,
                repairPartUsageRepository.findByRepairIdOrderByIdAsc(record.getId())
        );
    }

    @Override
    @Transactional
    public RepairRecord save(RepairRecord record) {
        if (record.getId() != null) {
            Optional<RepairRecord> existingOpt = findById(record.getId());
            if (existingOpt.isPresent()) {
                RepairRecord existing = existingOpt.get();
                visibilityPolicy.ensureWritable(existing.getIsLocked(), "Repair record is locked and cannot be modified");
            } else {
                throw new BusinessException(ResultCode.NOT_FOUND, "Repair record not found");
            }
        }

        if (record.getRepairDate() == null) {
            record.setRepairDate(LocalDateTime.now());
        }
        if (record.getStatus() == null) {
            record.setStatus(RepairStatus.PENDING.code());
        } else {
            record.setStatus(RepairStatus.normalizeOrDefault(record.getStatus(), RepairStatus.PENDING));
        }
        normalizeReferences(record);
        normalizeFees(record);
        log.info("Save repair record: id={}, vehicleNumber={}, status={}", record.getId(), record.getVehicleNumber(), record.getStatus());
        collaborationService.stampWrite(record);
        return repairRepository.saveAndFlush(record);
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        Optional<RepairRecord> existingOpt = findByIdForUpdate(id);
        if (existingOpt.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "Repair record not found, id=" + id);
        }
        RepairRecord existing = existingOpt.get();
        visibilityPolicy.ensureWritable(existing.getIsLocked(), "Repair record is locked and cannot be deleted");
        if (paymentRecordRepository.existsBySourceTypeAndSourceId(
                FinancialEventService.SOURCE_REPAIR, existing.getId())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "A repair record with payment history cannot be deleted");
        }
        reverseAllRepairPartUsageInventory(existing);
        deleteRepairPartUsageRows(existing.getId());
        reverseRepairFinancial(existing);
        repairRepository.deleteById(id);
        log.info("Delete repair record: id={}", id);
    }

    @Override
    public List<RepairRecord> findByMachineId(Long machineId) {
        if (SecurityUtils.isAdminOrSuperAdmin()) {
            return repairRepository.findByMachineIdOrderByRepairDateDesc(machineId);
        }
        return repairRepository.findByMachineIdAndIsLockedFalseOrderByRepairDateDesc(machineId);
    }

    @Override
    public List<RepairRecord> findByRepairPerson(String repairPerson) {
        if (SecurityUtils.isAdminOrSuperAdmin()) {
            return repairRepository.findByRepairPerson(repairPerson);
        }
        return repairRepository.findByRepairPersonAndIsLockedFalse(repairPerson);
    }

    @Override
    public List<RepairRecord> findByStatus(String status) {
        if (SecurityUtils.isAdminOrSuperAdmin()) {
            return repairRepository.findByStatus(status);
        }
        return repairRepository.findByStatusAndIsLockedFalse(status);
    }

    @Override
    public List<RepairRecord> findByDateRange(LocalDateTime start, LocalDateTime end) {
        if (SecurityUtils.isAdminOrSuperAdmin()) {
            return repairRepository.findByRepairDateBetween(start, end);
        }
        return repairRepository.findByRepairDateBetweenAndIsLockedFalse(start, end);
    }

    @Override
    @Transactional
    public RepairRecordVO create(RepairRecordCreateDTO dto) {
        normalizeUsageInput(dto);
        RepairRecord saved = save(dto.toEntity());
        saved = syncRepairPartUsages(saved, dto);
        syncRepairFinancial(saved);
        operationAuditService.record("Repair", "CREATE", "REPAIR", saved.getId(),
                saved.getVehicleNumber(), saved.getCustomerName(), saved.getFaultDescription(),
                saved.getRepairPerson(), saved.getRemarks(), "REPAIR", saved.getId());
        return toVO(saved);
    }

    @Override
    @Transactional
    public RepairRecordVO update(Long id, RepairRecordCreateDTO dto) {
        RepairRecord record = findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Repair record not found"));
        collaborationService.validateWrite(record, dto.getVersion());
        List<RepairPartUsage> previousUsages = repairPartUsageRepository.findByRepairIdOrderByIdAsc(record.getId());
        String previousUsedPartIds = record.getUsedPartIds();
        if (hasLegacyUntrackedUsage(previousUsedPartIds, previousUsages)
                && changesLegacyPartUsage(dto, previousUsedPartIds)) {
            reportLegacyPartUsage(record,
                    "Historical repair material has no traceable stock movement/FIFO record; "
                            + "do not edit its material rows. Create a historical correction or inventory adjustment instead.");
            throw new BusinessException(ResultCode.CONFLICT,
                    "Historical repair material cannot be changed automatically because its stock trace is unavailable");
        }
        String requestedStatus = RepairStatus.normalizeOrDefault(dto.getStatus(), RepairStatus.PENDING);
        ensurePaymentHistoryAllowsStatus(record, requestedStatus);
        normalizeUsageInput(dto);
        dto.applyToEntity(record);
        RepairRecord saved = save(record);
        if (hasLegacyUntrackedUsage(previousUsedPartIds, previousUsages)) {
            reportLegacyPartUsage(saved,
                    "Historical repair material remains untracked; the record was updated without changing material rows.");
        } else {
            saved = syncRepairPartUsages(saved, dto);
        }
        syncRepairFinancial(saved);
        operationAuditService.record("Repair", "UPDATE", "REPAIR", saved.getId(),
                saved.getVehicleNumber(), saved.getCustomerName(), "Update repair: " + saved.getStatus(),
                saved.getRepairPerson(), saved.getRemarks(), "REPAIR", saved.getId());
        return toVO(saved);
    }

    @Override
    @Transactional
    public RepairRecordVO updateStatus(Long id, String status, Long version) {
        RepairRecord record = findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Repair record not found"));
        collaborationService.validateWrite(record, version);
        String nextStatus = RepairStatus.normalizeOrDefault(status, RepairStatus.PENDING);
        ensurePaymentHistoryAllowsStatus(record, nextStatus);
        record.setStatus(nextStatus);
        RepairRecord saved = save(record);
        syncRepairFinancial(saved);
        operationAuditService.record("Repair", saved.getStatus(), "REPAIR", saved.getId(),
                saved.getVehicleNumber(), saved.getCustomerName(), "Switch repair status: " + saved.getStatus(),
                saved.getRepairPerson(), saved.getRemarks(), "REPAIR", saved.getId());
        return toVO(saved);
    }

    @Override
    @Transactional
    public void delete(Long id, Long version) {
        RepairRecord record = findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Repair record not found"));
        collaborationService.validateWrite(record, version);
        deleteById(id);
        operationAuditService.record("Repair", "DELETE", "REPAIR", record.getId(),
                record.getVehicleNumber(), record.getCustomerName(), "Delete repair",
                record.getRepairPerson(), record.getRemarks(), "REPAIR", record.getId());
    }

    private void normalizeReferences(RepairRecord record) {
        if (record.getMachineId() != null) {
            MachineInventory machine = machineRepository.findById(record.getMachineId())
                    .orElseThrow(() -> new BusinessException(ResultCode.VEHICLE_NOT_FOUND, "Repair vehicle not found"));
            if (Boolean.TRUE.equals(machine.getModelOnly())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "Repair record must select a concrete vehicle");
            }
            record.setVehicleNumber(machine.getVehicleProductNumber());
        }
        if (record.getCustomerId() != null) {
            Customer customer = customerRepository.findById(record.getCustomerId())
                    .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Repair customer not found"));
            record.setCustomerName(customer.getCompanyName());
            record.setCustomerAddress(customer.getAddress());
        }
        if (record.getCustomerName() == null || record.getCustomerName().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Repair customer is required");
        }
        normalizeRepairPerson(record);
        normalizeUsedParts(record);
        record.setWorkHours(null);
    }

    private void normalizeUsageInput(RepairRecordCreateDTO dto) {
        if (dto.getPartUsages() == null || dto.getPartUsages().isEmpty()) {
            return;
        }
        List<Long> ids = new java.util.ArrayList<>();
        for (RepairPartUsageDTO usage : dto.getPartUsages()) {
            if (usage == null || usage.getPartId() == null || usage.getQuantity() == null) {
                continue;
            }
            for (int index = 0; index < usage.getQuantity(); index++) {
                ids.add(usage.getPartId());
            }
        }
        dto.setUsedPartIds(ids);
    }

    /**
     * Keeps repair material rows, warehouse balances, FIFO lots and repair
     * cost in lockstep.  Rows are updated by their own id; unchanged rows are
     * left untouched, so editing one material line cannot return unrelated
     * parts to stock.
     */
    private RepairRecord syncRepairPartUsages(RepairRecord record, RepairRecordCreateDTO dto) {
        List<RepairPartUsage> existing = new ArrayList<>(
                repairPartUsageRepository.findByRepairIdOrderByIdAsc(record.getId()));

        Map<Long, RepairPartUsage> existingById = existing.stream()
                .filter(usage -> usage.getId() != null)
                .collect(Collectors.toMap(RepairPartUsage::getId, usage -> usage, (left, right) -> left, LinkedHashMap::new));
        Set<Long> retainedIds = new HashSet<>();
        List<RepairPartUsageDTO> requests = requestedPartUsages(dto, record, existing);
        assertTrackedUsagesBeforePhysicalChange(record, existingById, existing, requests);
        LocalDate businessDate = repairBusinessDate(record);

        for (RepairPartUsageDTO request : requests) {
            if (request == null || request.getPartId() == null || request.getQuantity() == null || request.getQuantity() < 1) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "Repair part rows require a part and a positive quantity");
            }
            RepairPartUsage usage = request.getId() == null ? null : existingById.get(request.getId());
            if (request.getId() != null && usage == null) {
                throw new BusinessException(ResultCode.CONFLICT, "Repair part line does not belong to this repair record");
            }

            PartInventory selectedPart = findPartForStockChange(request.getPartId());
            Long requestedWarehouseId = request.getWarehouseId() == null
                    ? (usage == null ? selectedPart.getWarehouseId() : usage.getWarehouseId())
                    : request.getWarehouseId();
            Long warehouseId = stockLedgerService.resolveWarehouseId(requestedWarehouseId);
            UsagePricing pricing = usagePricing(request, selectedPart);

            if (usage == null) {
                usage = new RepairPartUsage();
                usage.setRepairId(record.getId());
                copyUsageDetails(usage, selectedPart, warehouseId, request, pricing);
                usage = repairPartUsageRepository.saveAndFlush(usage);
                consumeRepairPartUsage(record, usage, selectedPart, businessDate);
                retainedIds.add(usage.getId());
                continue;
            }

            retainedIds.add(usage.getId());
            boolean physicalChange = usage.getPartId() == null
                    || !usage.getPartId().equals(selectedPart.getId())
                    || !warehouseId.equals(usage.getWarehouseId())
                    || !request.getQuantity().equals(usage.getQuantity());
            if (physicalChange) {
                restoreRepairPartUsage(record, usage, businessDate);
            }
            copyUsageDetails(usage, selectedPart, warehouseId, request, pricing);
            if (physicalChange) {
                usage.setUnitCost(BigDecimal.ZERO);
                usage.setCostAmount(BigDecimal.ZERO);
                usage.setStockMovementId(null);
                usage.setStockLotConsumptionId(null);
            }
            usage = repairPartUsageRepository.saveAndFlush(usage);
            if (physicalChange) {
                consumeRepairPartUsage(record, usage, selectedPart, businessDate);
            }
        }

        for (RepairPartUsage usage : existing) {
            if (usage.getId() != null && !retainedIds.contains(usage.getId())) {
                restoreRepairPartUsage(record, usage, businessDate);
                repairPartUsageRepository.delete(usage);
            }
        }
        repairPartUsageRepository.flush();
        return refreshRepairPartTotals(record);
    }

    private List<RepairPartUsageDTO> requestedPartUsages(
            RepairRecordCreateDTO dto,
            RepairRecord record,
            List<RepairPartUsage> existing
    ) {
        if (dto.getPartUsages() != null && !dto.getPartUsages().isEmpty()) {
            return dto.getPartUsages();
        }
        List<Long> ids = dto.getUsedPartIds() == null || dto.getUsedPartIds().isEmpty()
                ? parseIds(record.getUsedPartIds())
                : dto.getUsedPartIds();
        if (existing == null || existing.isEmpty()) {
            BigDecimal legacyUnitCharge = legacyUnitCharge(dto.getPartsFee(), ids);
            return ids.stream().filter(id -> id != null && id > 0).map(partId -> {
                RepairPartUsageDTO usage = new RepairPartUsageDTO();
                usage.setPartId(partId);
                usage.setQuantity(1);
                usage.setChargeUnitPrice(legacyUnitCharge);
                usage.setDiscountAmount(BigDecimal.ZERO);
                return usage;
            }).toList();
        }

        Map<Long, Integer> requestedQuantityByPart = countedPartIds(ids);
        List<RepairPartUsageDTO> result = new ArrayList<>();
        for (RepairPartUsage existingUsage : existing) {
            int requestedQuantity = requestedQuantityByPart.getOrDefault(existingUsage.getPartId(), 0);
            if (requestedQuantity <= 0) {
                continue;
            }
            int existingQuantity = existingUsage.getQuantity() == null ? 0 : existingUsage.getQuantity();
            int retainedQuantity = Math.min(existingQuantity, requestedQuantity);
            if (retainedQuantity <= 0) {
                continue;
            }
            RepairPartUsageDTO usage = usageRequestFromExisting(existingUsage, retainedQuantity);
            result.add(usage);
            requestedQuantityByPart.put(existingUsage.getPartId(), requestedQuantity - retainedQuantity);
        }
        requestedQuantityByPart.forEach((partId, quantity) -> {
            if (quantity == null || quantity <= 0) {
                return;
            }
            RepairPartUsageDTO usage = new RepairPartUsageDTO();
            usage.setPartId(partId);
            usage.setQuantity(quantity);
            result.add(usage);
        });
        return result;
    }

    private RepairPartUsageDTO usageRequestFromExisting(RepairPartUsage existing, int quantity) {
        RepairPartUsageDTO usage = new RepairPartUsageDTO();
        usage.setId(existing.getId());
        usage.setPartId(existing.getPartId());
        usage.setWarehouseId(existing.getWarehouseId());
        usage.setQuantity(quantity);
        usage.setChargeUnitPrice(existing.getChargeUnitPrice());
        BigDecimal existingDiscount = MoneyValues.zeroIfNullOrNegative(existing.getDiscountAmount());
        int existingQuantity = existing.getQuantity() == null ? 0 : existing.getQuantity();
        if (existingQuantity > 0 && quantity != existingQuantity) {
            usage.setDiscountAmount(existingDiscount
                    .multiply(BigDecimal.valueOf(quantity))
                    .divide(BigDecimal.valueOf(existingQuantity), 2, java.math.RoundingMode.HALF_UP));
        } else {
            usage.setDiscountAmount(existingDiscount);
        }
        usage.setRemark(existing.getRemark());
        return usage;
    }

    private BigDecimal legacyUnitCharge(BigDecimal totalPartsFee, List<Long> ids) {
        int quantity = ids == null ? 0 : (int) ids.stream().filter(id -> id != null && id > 0).count();
        if (quantity == 0) {
            return BigDecimal.ZERO;
        }
        return MoneyValues.zeroIfNullOrNegative(totalPartsFee)
                .divide(BigDecimal.valueOf(quantity), 2, java.math.RoundingMode.HALF_UP);
    }

    private void assertTrackedUsagesBeforePhysicalChange(
            RepairRecord record,
            Map<Long, RepairPartUsage> existingById,
            List<RepairPartUsage> existing,
            List<RepairPartUsageDTO> requests
    ) {
        Set<Long> requestedIds = new HashSet<>();
        for (RepairPartUsageDTO request : requests) {
            if (request == null || request.getId() == null) {
                continue;
            }
            RepairPartUsage usage = existingById.get(request.getId());
            if (usage == null) {
                continue;
            }
            requestedIds.add(usage.getId());
            Long requestedWarehouseId = stockLedgerService.resolveWarehouseId(
                    request.getWarehouseId() == null ? usage.getWarehouseId() : request.getWarehouseId());
            boolean physicalChange = usage.getPartId() == null
                    || !usage.getPartId().equals(request.getPartId())
                    || !java.util.Objects.equals(requestedWarehouseId, usage.getWarehouseId())
                    || !java.util.Objects.equals(request.getQuantity(), usage.getQuantity());
            if (physicalChange) {
                requireTrustedUsage(record, usage);
            }
        }
        for (RepairPartUsage usage : existing) {
            if (usage.getId() != null && !requestedIds.contains(usage.getId())) {
                requireTrustedUsage(record, usage);
            }
        }
    }

    private void requireTrustedUsage(RepairRecord record, RepairPartUsage usage) {
        if (hasTrustedStockTrace(record, usage)) {
            return;
        }
        reportLegacyPartUsage(record,
                "Repair material row " + usage.getId()
                        + " has no linked stock movement/FIFO consumption and cannot be returned or reissued automatically.");
        throw new BusinessException(ResultCode.CONFLICT,
                "Repair material has no traceable stock history; use an explicit inventory adjustment");
    }

    private boolean hasTrustedStockTrace(RepairRecord record, RepairPartUsage usage) {
        if (usage.getStockMovementId() != null || usage.getStockLotConsumptionId() != null) {
            return true;
        }
        return usage.getId() != null && !stockLotConsumptionRepository
                .findBySourceTypeAndSourceIdAndSourceLineIdOrderByIdAsc(
                        REPAIR_SOURCE_TYPE, record.getId(), usage.getId())
                .isEmpty();
    }

    private boolean hasLegacyUntrackedUsage(String usedPartIds, List<RepairPartUsage> usages) {
        return (usages == null || usages.isEmpty()) && !parseIds(usedPartIds).isEmpty();
    }

    private boolean changesLegacyPartUsage(RepairRecordCreateDTO dto, String existingUsedPartIds) {
        List<Long> requestedIds;
        if (dto.getPartUsages() != null && !dto.getPartUsages().isEmpty()) {
            requestedIds = dto.getPartUsages().stream()
                    .filter(usage -> usage != null && usage.getPartId() != null && usage.getQuantity() != null)
                    .flatMap(usage -> java.util.stream.IntStream.range(0, Math.max(0, usage.getQuantity()))
                            .mapToObj(index -> usage.getPartId()))
                    .toList();
        } else {
            requestedIds = dto.getUsedPartIds() == null ? List.of() : dto.getUsedPartIds();
        }
        return !countedPartIds(requestedIds).equals(countedPartIds(parseIds(existingUsedPartIds)));
    }

    private void reportLegacyPartUsage(RepairRecord record, String detail) {
        migrationExceptionService.openOnce(
                "REPAIR_PART_USAGE_UNTRACKED",
                REPAIR_SOURCE_TYPE,
                record.getId(),
                detail
        );
    }

    private UsagePricing usagePricing(RepairPartUsageDTO request, PartInventory part) {
        BigDecimal chargeUnitPrice = MoneyValues.firstNonNegativeOrZero(
                request.getChargeUnitPrice(), part.getSalePrice(), part.getSettlementPrice());
        BigDecimal discount = MoneyValues.zeroIfNullOrNegative(request.getDiscountAmount());
        BigDecimal chargeAmount = chargeUnitPrice.multiply(BigDecimal.valueOf(request.getQuantity())).subtract(discount);
        if (chargeAmount.signum() < 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Repair part discount cannot exceed the line amount");
        }
        return new UsagePricing(chargeUnitPrice, discount, chargeAmount);
    }

    private void copyUsageDetails(
            RepairPartUsage usage,
            PartInventory part,
            Long warehouseId,
            RepairPartUsageDTO request,
            UsagePricing pricing
    ) {
        usage.setPartId(part.getId());
        usage.setPartCode(part.getPartCode());
        usage.setPartName(part.getPartName());
        usage.setWarehouseId(warehouseId);
        usage.setQuantity(request.getQuantity());
        usage.setUnitPrice(pricing.chargeUnitPrice());
        usage.setChargeUnitPrice(pricing.chargeUnitPrice());
        usage.setDiscountAmount(pricing.discountAmount());
        usage.setChargeAmount(pricing.chargeAmount());
        usage.setRemark(blankToNull(request.getRemark()));
    }

    private void consumeRepairPartUsage(
            RepairRecord repair,
            RepairPartUsage usage,
            PartInventory part,
            LocalDate businessDate
    ) {
        int before = stockLedgerService.availableQuantity(
                StockLedgerService.RESOURCE_PART, part.getId(), usage.getWarehouseId());
        if (before < usage.getQuantity()) {
            throw new BusinessException(ResultCode.INSUFFICIENT_STOCK,
                    "Insufficient repair part stock in selected warehouse: " + part.getPartCode());
        }
        String revision = repairRevisionKey(repair, usage, "USE");
        StockLotService.ConsumptionResult fifo = stockLotService.consumeFifo(
                StockLedgerService.RESOURCE_PART,
                part.getId(),
                usage.getWarehouseId(),
                usage.getQuantity(),
                stockUnitCost(part),
                before,
                REPAIR_SOURCE_TYPE,
                repair.getId(),
                usage.getId(),
                businessDate,
                revision + ":FIFO"
        );
        StockMovement movement = stockLedgerService.recordMovement(
                "OUTBOUND",
                StockLedgerService.RESOURCE_PART,
                part.getId(),
                part.getPartCode(),
                part.getPartName(),
                usage.getWarehouseId(),
                before,
                before - usage.getQuantity(),
                fifo.unitCost(),
                repair.getRepairPerson(),
                "Repair material usage " + repair.getId(),
                REPAIR_SOURCE_TYPE,
                repair.getId(),
                usage.getId(),
                businessDate,
                StockBusinessType.REPAIR_USE,
                usage.getChargeUnitPrice(),
                revision + ":MOVEMENT",
                fifo.consumptions().isEmpty() ? null : fifo.consumptions().get(0).getStockLotId()
        );
        usage.setUnitCost(fifo.unitCost());
        usage.setCostAmount(fifo.totalCost());
        usage.setStockMovementId(movement.getId());
        usage.setStockLotConsumptionId(fifo.consumptions().isEmpty() ? null : fifo.consumptions().get(0).getId());
        repairPartUsageRepository.save(usage);
        finalizeRepairUsageMovement(movement, usage, fifo);
        refreshPartQuantityCache(part);
    }

    private void restoreRepairPartUsage(RepairRecord repair, RepairPartUsage usage, LocalDate businessDate) {
        requireTrustedUsage(repair, usage);
        PartInventory part = findPartForStockChange(usage.getPartId());
        Long warehouseId = stockLedgerService.resolveWarehouseId(
                usage.getWarehouseId() == null ? part.getWarehouseId() : usage.getWarehouseId());
        int before = stockLedgerService.availableQuantity(StockLedgerService.RESOURCE_PART, part.getId(), warehouseId);
        String revision = repairRevisionKey(repair, usage, "RESTORE");
        stockLotService.restoreSourceLineConsumption(
                REPAIR_SOURCE_TYPE, repair.getId(), usage.getId(), businessDate, revision + ":FIFO");
        StockMovement movement = stockLedgerService.recordMovement(
                "INBOUND",
                StockLedgerService.RESOURCE_PART,
                part.getId(),
                part.getPartCode(),
                part.getPartName(),
                warehouseId,
                before,
                before + usage.getQuantity(),
                MoneyValues.zeroIfNullOrNegative(usage.getUnitCost()),
                repair.getRepairPerson(),
                "Reverse repair material usage " + repair.getId(),
                REPAIR_SOURCE_TYPE,
                repair.getId(),
                usage.getId(),
                businessDate,
                StockBusinessType.REPAIR_RESTORE,
                BigDecimal.ZERO,
                revision + ":MOVEMENT",
                null
        );
        for (StockMovementLine line : stockMovementLineRepository.findByMovementIdOrderByIdAsc(movement.getId())) {
            line.setCostAmount(MoneyValues.zeroIfNullOrNegative(usage.getCostAmount()));
            line.setLineAmount(BigDecimal.ZERO);
            line.setSourceLineId(usage.getId());
            stockMovementLineRepository.save(line);
        }
        refreshPartQuantityCache(part);
    }

    private void finalizeRepairUsageMovement(
            StockMovement movement,
            RepairPartUsage usage,
            StockLotService.ConsumptionResult fifo
    ) {
        Long firstLotId = fifo.consumptions().isEmpty() ? null : fifo.consumptions().get(0).getStockLotId();
        for (StockMovementLine line : stockMovementLineRepository.findByMovementIdOrderByIdAsc(movement.getId())) {
            line.setUnitCost(fifo.unitCost());
            line.setCostAmount(fifo.totalCost());
            line.setUnitRevenue(usage.getChargeUnitPrice());
            line.setLineAmount(usage.getChargeAmount());
            line.setStockLotId(firstLotId);
            line.setSourceLineId(usage.getId());
            stockMovementLineRepository.save(line);
        }
    }

    private void refreshPartQuantityCache(PartInventory part) {
        part.setQuantity(stockLedgerService.totalAvailableQuantity(StockLedgerService.RESOURCE_PART, part.getId()));
        collaborationService.stampWrite(part);
        partRepository.save(part);
    }

    private RepairRecord refreshRepairPartTotals(RepairRecord record) {
        List<RepairPartUsage> rows = repairPartUsageRepository.findByRepairIdOrderByIdAsc(record.getId());
        record.setPartsFee(rows.stream()
                .map(RepairPartUsage::getChargeAmount)
                .map(MoneyValues::zeroIfNullOrNegative)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        record.setPartsCost(rows.stream()
                .map(RepairPartUsage::getCostAmount)
                .map(MoneyValues::zeroIfNullOrNegative)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        record.setUsedPartIds(rows.stream()
                .flatMap(usage -> java.util.stream.IntStream.range(0, usage.getQuantity() == null ? 0 : usage.getQuantity())
                        .mapToObj(index -> String.valueOf(usage.getPartId())))
                .collect(Collectors.joining(",")));
        record.setUsedParts(rows.stream()
                .map(usage -> usage.getPartCode() + "/" + usage.getPartName()
                        + (usage.getQuantity() != null && usage.getQuantity() > 1 ? " x" + usage.getQuantity() : ""))
                .collect(Collectors.joining(";")));
        normalizeFees(record);
        return repairRepository.saveAndFlush(record);
    }

    private void reverseAllRepairPartUsageInventory(RepairRecord record) {
        List<RepairPartUsage> rows = new ArrayList<>(repairPartUsageRepository.findByRepairIdOrderByIdAsc(record.getId()));
        if (hasLegacyUntrackedUsage(record.getUsedPartIds(), rows)) {
            reportLegacyPartUsage(record,
                    "Historical repair material has no detail row or linked stock movement; deletion cannot auto-return stock.");
            throw new BusinessException(ResultCode.CONFLICT,
                    "Historical repair material cannot be deleted automatically because its stock trace is unavailable");
        }
        for (RepairPartUsage usage : rows) {
            requireTrustedUsage(record, usage);
        }
        LocalDate businessDate = repairBusinessDate(record);
        for (RepairPartUsage usage : rows) {
            restoreRepairPartUsage(record, usage, businessDate);
        }
    }

    private LocalDate repairBusinessDate(RepairRecord repair) {
        return repair.getRepairDate() == null ? LocalDate.now() : repair.getRepairDate().toLocalDate();
    }

    private String repairRevisionKey(RepairRecord repair, RepairPartUsage usage, String action) {
        return "REPAIR:" + repair.getId() + ":USAGE:" + usage.getId() + ":" + action + ":REV:" + repair.getVersion();
    }

    private record UsagePricing(BigDecimal chargeUnitPrice, BigDecimal discountAmount, BigDecimal chargeAmount) {
    }

    private void deleteRepairPartUsageRows(Long repairId) {
        List<RepairPartUsage> rows = repairPartUsageRepository.findByRepairIdOrderByIdAsc(repairId);
        if (!rows.isEmpty()) {
            repairPartUsageRepository.deleteAll(rows);
            repairPartUsageRepository.flush();
        }
    }

    private void syncRepairFinancial(RepairRecord repair) {
        if (!RepairStatus.COMPLETED.code().equals(repair.getStatus())) {
            reverseRepairFinancial(repair);
            return;
        }
        if (Boolean.TRUE.equals(repair.getFinancialPosted())) {
            reverseRepairFinancial(repair);
        }
        financialEventService.postRepair(repair, MoneyValues.zeroIfNullOrNegative(repair.getPartsCost()),
                "REVISION:" + repair.getVersion());
        repairRepository.save(repair);
    }

    private void reverseRepairFinancial(RepairRecord repair) {
        if (!Boolean.TRUE.equals(repair.getFinancialPosted())) {
            return;
        }
        financialEventService.reverseSourceEvents(
                FinancialEventService.SOURCE_REPAIR,
                repair.getId(),
                List.of(
                        FinancialEventType.ACCOUNTS_RECEIVABLE,
                        FinancialEventType.REVENUE,
                        FinancialEventType.COST_OF_GOODS_SOLD,
                        FinancialEventType.OPERATING_COST
                ),
                repair.getRepairDate() == null ? LocalDateTime.now().toLocalDate() : repair.getRepairDate().toLocalDate(),
                "Repair amendment",
                "REPAIR-EVENT-REVERSAL:" + repair.getId() + ":" + repair.getVersion()
        );
        repair.setFinancialPosted(false);
        repairRepository.save(repair);
    }

    private void ensurePaymentHistoryAllowsStatus(RepairRecord repair, String nextStatus) {
        if (RepairStatus.COMPLETED.code().equals(nextStatus)) {
            return;
        }
        BigDecimal receipt = paymentRecordRepository.totalForSource(
                FinancialEventService.SOURCE_REPAIR,
                repair.getId(),
                PaymentRecord.DIRECTION_RECEIPT
        );
        BigDecimal payment = paymentRecordRepository.totalForSource(
                FinancialEventService.SOURCE_REPAIR,
                repair.getId(),
                PaymentRecord.DIRECTION_PAYMENT
        );
        if ((receipt != null && receipt.signum() != 0) || (payment != null && payment.signum() != 0)) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "A repair with payment history cannot be reopened; reverse the payments first");
        }
    }

    private void normalizeRepairPerson(RepairRecord record) {
        if (Boolean.TRUE.equals(record.getRepairExternal())) {
            record.setRepairPersonUserId(null);
            if (record.getRepairPerson() == null || record.getRepairPerson().isBlank() || "External".equalsIgnoreCase(record.getRepairPerson())) {
                record.setRepairPerson("其他");
            }
            return;
        }
        if (record.getRepairPersonUserId() == null) {
            return;
        }
        User user = userRepository.findById(record.getRepairPersonUserId())
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Repair user not found"));
        if (!user.isEnabled() || !JobTag.REPAIR.code().equals(normalizeJobTag(user.getJobTag()))) {
            throw new BusinessException(ResultCode.FORBIDDEN, "Repair user must be enabled and tagged as REPAIR");
        }
        record.setRepairPerson(user.getUsername());
        record.setRepairExternal(false);
    }

    private String normalizeJobTag(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private void normalizeUsedParts(RepairRecord record) {
        List<Long> ids = parseIds(record.getUsedPartIds());
        if (ids.isEmpty()) {
            record.setUsedPartIds(null);
            if (record.getUsedParts() != null && record.getUsedParts().isBlank()) {
                record.setUsedParts(null);
            }
            return;
        }
        List<PartInventory> parts = ids.stream()
                .map(id -> partRepository.findById(id)
                        .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Used part not found: " + id)))
                .toList();
        record.setUsedPartIds(parts.stream()
                .map(part -> String.valueOf(part.getId()))
                .collect(Collectors.joining(",")));
        record.setUsedParts(parts.stream()
                .map(part -> "%s/%s".formatted(part.getPartCode(), part.getPartName()))
                .collect(Collectors.joining(";")));
    }

    private RepairRecord syncUsedPartInventory(
            RepairRecord record,
            List<Long> previousPartIds,
            List<Long> currentPartIds,
            boolean force
    ) {
        if (!force && samePartUsage(previousPartIds, currentPartIds)) {
            record.setPartsCost(MoneyValues.zeroIfNullOrNegative(record.getPartsCost()));
            return repairRepository.saveAndFlush(record);
        }
        if (!previousPartIds.isEmpty()) {
            applyUsedPartMovement(record, previousPartIds, false);
        }
        BigDecimal partsCost = applyUsedPartMovement(record, currentPartIds, true);
        record.setPartsCost(partsCost);
        return repairRepository.saveAndFlush(record);
    }

    private BigDecimal applyUsedPartMovement(RepairRecord record, List<Long> partIds, boolean consume) {
        BigDecimal totalCost = BigDecimal.ZERO;
        for (Map.Entry<Long, Integer> entry : countedPartIds(partIds).entrySet()) {
            int quantity = entry.getValue();
            if (quantity <= 0) {
                continue;
            }
            PartInventory part = findPartForStockChange(entry.getKey());
            int before = part.getQuantity() == null ? 0 : part.getQuantity();
            if (consume && before < quantity) {
                throw new BusinessException(ResultCode.INSUFFICIENT_STOCK,
                        "Insufficient used part stock: " + part.getPartCode() + ", available=" + before);
            }
            int after = consume ? before - quantity : before + quantity;
            BigDecimal unitCost = stockUnitCost(part);
            part.setQuantity(after);
            collaborationService.stampWrite(part);
            PartInventory savedPart = partRepository.save(part);
            saveRepairPartStockLog(savedPart, consume ? REPAIR_USE_OPERATION : REPAIR_RESTORE_OPERATION,
                    quantity, before, after, unitCost, record);
            if (consume) {
                totalCost = totalCost.add(unitCost.multiply(BigDecimal.valueOf(quantity)));
            }
        }
        return totalCost;
    }

    private Map<Long, Integer> countedPartIds(List<Long> ids) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Long id : ids) {
            if (id != null && id > 0) {
                counts.merge(id, 1, Integer::sum);
            }
        }
        return counts;
    }

    private boolean samePartUsage(List<Long> previousPartIds, List<Long> currentPartIds) {
        return countedPartIds(previousPartIds).equals(countedPartIds(currentPartIds));
    }

    private PartInventory findPartForStockChange(Long partId) {
        Optional<PartInventory> part = SecurityUtils.isAdminOrSuperAdmin()
                ? partRepository.findByIdForUpdate(partId)
                : partRepository.findByIdAndIsLockedFalseForUpdate(partId);
        PartInventory resolved = part.orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Used part not found: " + partId));
        visibilityPolicy.ensureWritable(resolved.getIsLocked(), "Used part is locked and cannot be consumed");
        return resolved;
    }

    private StockOperationLog saveRepairPartStockLog(
            PartInventory part,
            String operationType,
            Integer quantity,
            Integer beforeQuantity,
            Integer afterQuantity,
            BigDecimal unitCost,
            RepairRecord repair
    ) {
        String operator = SecurityUtils.currentUsername();
        String remark = "Repair " + repair.getId() + " " + operationType;
        return stockOperationRecorder.record(new StockOperationRecorder.Command(
                "Part stock",
                StockLedgerService.RESOURCE_PART,
                part.getId(),
                part.getPartCode(),
                part.getPartName(),
                part.getWarehouseId(),
                operationType,
                quantity,
                beforeQuantity,
                afterQuantity,
                unitCost,
                BigDecimal.ZERO,
                operator,
                remark,
                REPAIR_SOURCE_TYPE,
                repair.getId(),
                "Repair part stock " + quantity
        ));
    }

    private BigDecimal stockUnitCost(PartInventory part) {
        return MoneyValues.firstNonNegativeOrZero(part.getLandedUnitCost(), part.getPurchasePrice());
    }

    private void normalizeFees(RepairRecord record) {
        BigDecimal repairFee = MoneyValues.zeroIfNullOrNegative(record.getRepairFee());
        BigDecimal repairExpense = Boolean.TRUE.equals(record.getRepairExternal())
                ? MoneyValues.zeroIfNullOrNegative(record.getRepairExpense())
                : BigDecimal.ZERO;
        BigDecimal partsFee = MoneyValues.zeroIfNullOrNegative(record.getPartsFee());
        BigDecimal passThroughAmount = MoneyValues.zeroIfNullOrNegative(record.getPassThroughAmount());
        record.setRepairExpense(repairExpense);
        record.setPassThroughAmount(passThroughAmount);
        record.setReceivableAmount(repairFee.add(partsFee).add(passThroughAmount));
        record.setTotalFee(record.getReceivableAmount());
    }

    private List<Long> parseIds(String ids) {
        if (ids == null || ids.isBlank()) {
            return List.of();
        }
        return Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(Long::parseLong)
                .toList();
    }

    private String normalizeKeyword(String keyword) {
        return keyword == null || keyword.isBlank() ? null : keyword.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
