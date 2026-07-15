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
    private RepairPartUsageService repairPartUsageService;

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
        repairPartUsageService.reverseAllInventory(existing);
        repairPartUsageService.deleteRows(existing.getId());
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
        repairPartUsageService.normalizeInput(dto);
        RepairRecord saved = save(dto.toEntity());
        saved = repairPartUsageService.sync(saved, dto);
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
        if (repairPartUsageService.hasLegacyUntrackedUsage(previousUsedPartIds, previousUsages)
                && repairPartUsageService.changesLegacyPartUsage(dto, previousUsedPartIds)) {
            repairPartUsageService.reportLegacyPartUsage(record,
                    "Historical repair material has no traceable stock movement/FIFO record; "
                            + "do not edit its material rows. Create a historical correction or inventory adjustment instead.");
            throw new BusinessException(ResultCode.CONFLICT,
                    "Historical repair material cannot be changed automatically because its stock trace is unavailable");
        }
        String requestedStatus = RepairStatus.normalizeOrDefault(dto.getStatus(), RepairStatus.PENDING);
        ensurePaymentHistoryAllowsStatus(record, requestedStatus);
        repairPartUsageService.normalizeInput(dto);
        dto.applyToEntity(record);
        RepairRecord saved = save(record);
        if (repairPartUsageService.hasLegacyUntrackedUsage(previousUsedPartIds, previousUsages)) {
            repairPartUsageService.reportLegacyPartUsage(saved,
                    "Historical repair material remains untracked; the record was updated without changing material rows.");
        } else {
            saved = repairPartUsageService.sync(saved, dto);
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
