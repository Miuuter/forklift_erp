package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.constant.StockBusinessType;
import com.example.forklift_erp.dto.RepairPartUsageDTO;
import com.example.forklift_erp.dto.RepairRecordCreateDTO;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.RepairPartUsage;
import com.example.forklift_erp.entity.RepairRecord;
import com.example.forklift_erp.entity.StockLotConsumption;
import com.example.forklift_erp.entity.StockMovement;
import com.example.forklift_erp.entity.StockMovementLine;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.RepairPartUsageRepository;
import com.example.forklift_erp.repository.RepairRecordRepository;
import com.example.forklift_erp.repository.StockLotConsumptionRepository;
import com.example.forklift_erp.repository.StockMovementLineRepository;
import com.example.forklift_erp.service.CollaborationService;
import com.example.forklift_erp.service.MigrationExceptionService;
import com.example.forklift_erp.service.ResourceVisibilityPolicy;
import com.example.forklift_erp.service.StockLedgerService;
import com.example.forklift_erp.service.StockLotService;
import com.example.forklift_erp.util.MoneyValues;
import com.example.forklift_erp.util.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Keeps repair material rows, warehouse balances, FIFO lots and repair costs
 * in one transaction.
 */
@Service
public class RepairPartUsageService {
    private static final String SOURCE_TYPE = "REPAIR";

    private final RepairRecordRepository repairRepository;
    private final RepairPartUsageRepository usageRepository;
    private final PartInventoryRepository partRepository;
    private final StockLedgerService stockLedgerService;
    private final StockLotService stockLotService;
    private final StockMovementLineRepository movementLineRepository;
    private final StockLotConsumptionRepository consumptionRepository;
    private final MigrationExceptionService migrationExceptionService;
    private final CollaborationService collaborationService;
    private final ResourceVisibilityPolicy visibilityPolicy;

    public RepairPartUsageService(
            RepairRecordRepository repairRepository,
            RepairPartUsageRepository usageRepository,
            PartInventoryRepository partRepository,
            StockLedgerService stockLedgerService,
            StockLotService stockLotService,
            StockMovementLineRepository movementLineRepository,
            StockLotConsumptionRepository consumptionRepository,
            MigrationExceptionService migrationExceptionService,
            CollaborationService collaborationService,
            ResourceVisibilityPolicy visibilityPolicy
    ) {
        this.repairRepository = repairRepository;
        this.usageRepository = usageRepository;
        this.partRepository = partRepository;
        this.stockLedgerService = stockLedgerService;
        this.stockLotService = stockLotService;
        this.movementLineRepository = movementLineRepository;
        this.consumptionRepository = consumptionRepository;
        this.migrationExceptionService = migrationExceptionService;
        this.collaborationService = collaborationService;
        this.visibilityPolicy = visibilityPolicy;
    }

    public void normalizeInput(RepairRecordCreateDTO dto) {
        if (dto.getPartUsages() == null || dto.getPartUsages().isEmpty()) {
            return;
        }
        List<Long> ids = new ArrayList<>();
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

    @Transactional
    public RepairRecord sync(RepairRecord record, RepairRecordCreateDTO dto) {
        List<RepairPartUsage> existing =
                new ArrayList<>(usageRepository.findByRepairIdOrderByIdAsc(record.getId()));
        Map<Long, RepairPartUsage> existingById = existing.stream()
                .filter(usage -> usage.getId() != null)
                .collect(Collectors.toMap(
                        RepairPartUsage::getId,
                        usage -> usage,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Set<Long> retainedIds = new HashSet<>();
        List<RepairPartUsageDTO> requests = requestedPartUsages(dto, record, existing);
        assertTrackedUsagesBeforePhysicalChange(record, existingById, existing, requests);
        LocalDate businessDate = repairBusinessDate(record);

        for (RepairPartUsageDTO request : requests) {
            if (request == null
                    || request.getPartId() == null
                    || request.getQuantity() == null
                    || request.getQuantity() < 1) {
                throw new BusinessException(
                        ResultCode.PARAM_ERROR,
                        "Repair part rows require a part and a positive quantity"
                );
            }
            RepairPartUsage usage = request.getId() == null ? null : existingById.get(request.getId());
            if (request.getId() != null && usage == null) {
                throw new BusinessException(
                        ResultCode.CONFLICT,
                        "Repair part line does not belong to this repair record"
                );
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
                usage = usageRepository.saveAndFlush(usage);
                consume(record, usage, selectedPart, businessDate);
                retainedIds.add(usage.getId());
                continue;
            }

            retainedIds.add(usage.getId());
            boolean physicalChange = usage.getPartId() == null
                    || !usage.getPartId().equals(selectedPart.getId())
                    || !warehouseId.equals(usage.getWarehouseId())
                    || !request.getQuantity().equals(usage.getQuantity());
            if (physicalChange) {
                restore(record, usage, businessDate);
            }
            copyUsageDetails(usage, selectedPart, warehouseId, request, pricing);
            if (physicalChange) {
                usage.setUnitCost(BigDecimal.ZERO);
                usage.setCostAmount(BigDecimal.ZERO);
                usage.setStockMovementId(null);
                usage.setStockLotConsumptionId(null);
            }
            usage = usageRepository.saveAndFlush(usage);
            if (physicalChange) {
                consume(record, usage, selectedPart, businessDate);
            }
        }

        for (RepairPartUsage usage : existing) {
            if (usage.getId() != null && !retainedIds.contains(usage.getId())) {
                restore(record, usage, businessDate);
                usageRepository.delete(usage);
            }
        }
        usageRepository.flush();
        return refreshTotals(record);
    }

    public boolean hasLegacyUntrackedUsage(String usedPartIds, List<RepairPartUsage> usages) {
        return (usages == null || usages.isEmpty()) && !parseIds(usedPartIds).isEmpty();
    }

    public boolean changesLegacyPartUsage(RepairRecordCreateDTO dto, String existingUsedPartIds) {
        List<Long> requestedIds;
        if (dto.getPartUsages() != null && !dto.getPartUsages().isEmpty()) {
            requestedIds = dto.getPartUsages().stream()
                    .filter(usage -> usage != null
                            && usage.getPartId() != null
                            && usage.getQuantity() != null)
                    .flatMap(usage -> IntStream.range(0, Math.max(0, usage.getQuantity()))
                            .mapToObj(index -> usage.getPartId()))
                    .toList();
        } else {
            requestedIds = dto.getUsedPartIds() == null ? List.of() : dto.getUsedPartIds();
        }
        return !countedPartIds(requestedIds).equals(countedPartIds(parseIds(existingUsedPartIds)));
    }

    public void reportLegacyPartUsage(RepairRecord record, String detail) {
        migrationExceptionService.openOnce(
                "REPAIR_PART_USAGE_UNTRACKED",
                SOURCE_TYPE,
                record.getId(),
                detail
        );
    }

    @Transactional
    public void reverseAllInventory(RepairRecord record) {
        List<RepairPartUsage> rows =
                new ArrayList<>(usageRepository.findByRepairIdOrderByIdAsc(record.getId()));
        if (hasLegacyUntrackedUsage(record.getUsedPartIds(), rows)) {
            reportLegacyPartUsage(
                    record,
                    "Historical repair material has no detail row or linked stock movement; "
                            + "deletion cannot auto-return stock."
            );
            throw new BusinessException(
                    ResultCode.CONFLICT,
                    "Historical repair material cannot be deleted automatically because its stock trace is unavailable"
            );
        }
        for (RepairPartUsage usage : rows) {
            requireTrustedUsage(record, usage);
        }
        LocalDate businessDate = repairBusinessDate(record);
        for (RepairPartUsage usage : rows) {
            restore(record, usage, businessDate);
        }
    }

    @Transactional
    public void deleteRows(Long repairId) {
        List<RepairPartUsage> rows = usageRepository.findByRepairIdOrderByIdAsc(repairId);
        if (!rows.isEmpty()) {
            usageRepository.deleteAll(rows);
            usageRepository.flush();
        }
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
            return ids.stream()
                    .filter(id -> id != null && id > 0)
                    .map(partId -> {
                        RepairPartUsageDTO usage = new RepairPartUsageDTO();
                        usage.setPartId(partId);
                        usage.setQuantity(1);
                        usage.setChargeUnitPrice(legacyUnitCharge);
                        usage.setDiscountAmount(BigDecimal.ZERO);
                        return usage;
                    })
                    .toList();
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
            result.add(usageRequestFromExisting(existingUsage, retainedQuantity));
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
        usage.setDiscountAmount(existingQuantity > 0 && quantity != existingQuantity
                ? existingDiscount.multiply(BigDecimal.valueOf(quantity))
                        .divide(BigDecimal.valueOf(existingQuantity), 2, RoundingMode.HALF_UP)
                : existingDiscount);
        usage.setRemark(existing.getRemark());
        return usage;
    }

    private BigDecimal legacyUnitCharge(BigDecimal totalPartsFee, List<Long> ids) {
        int quantity = ids == null ? 0 : (int) ids.stream()
                .filter(id -> id != null && id > 0)
                .count();
        return quantity == 0
                ? BigDecimal.ZERO
                : MoneyValues.zeroIfNullOrNegative(totalPartsFee)
                        .divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_UP);
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
                    || !Objects.equals(requestedWarehouseId, usage.getWarehouseId())
                    || !Objects.equals(request.getQuantity(), usage.getQuantity());
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
        reportLegacyPartUsage(
                record,
                "Repair material row " + usage.getId()
                        + " has no linked stock movement/FIFO consumption "
                        + "and cannot be returned or reissued automatically."
        );
        throw new BusinessException(
                ResultCode.CONFLICT,
                "Repair material has no traceable stock history; use an explicit inventory adjustment"
        );
    }

    private boolean hasTrustedStockTrace(RepairRecord record, RepairPartUsage usage) {
        return RepairUsageTraceValidator.hasExactFifoTrace(
                consumptionRepository, record, usage, SOURCE_TYPE);
    }

    private UsagePricing usagePricing(RepairPartUsageDTO request, PartInventory part) {
        BigDecimal chargeUnitPrice = MoneyValues.firstNonNegativeOrZero(
                request.getChargeUnitPrice(),
                part.getSalePrice(),
                part.getSettlementPrice()
        );
        BigDecimal discount = MoneyValues.zeroIfNullOrNegative(request.getDiscountAmount());
        BigDecimal chargeAmount = chargeUnitPrice
                .multiply(BigDecimal.valueOf(request.getQuantity()))
                .subtract(discount);
        if (chargeAmount.signum() < 0) {
            throw new BusinessException(
                    ResultCode.PARAM_ERROR,
                    "Repair part discount cannot exceed the line amount"
            );
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

    private void consume(
            RepairRecord repair,
            RepairPartUsage usage,
            PartInventory part,
            LocalDate businessDate
    ) {
        int before = stockLedgerService.availableQuantity(
                StockLedgerService.RESOURCE_PART,
                part.getId(),
                usage.getWarehouseId()
        );
        if (before < usage.getQuantity()) {
            throw new BusinessException(
                    ResultCode.INSUFFICIENT_STOCK,
                    "Insufficient repair part stock in selected warehouse: " + part.getPartCode()
            );
        }
        String revision = revisionKey(repair, usage, "USE");
        StockLotService.ConsumptionResult fifo = stockLotService.consumeFifo(
                StockLedgerService.RESOURCE_PART,
                part.getId(),
                usage.getWarehouseId(),
                usage.getQuantity(),
                stockUnitCost(part),
                before,
                SOURCE_TYPE,
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
                SOURCE_TYPE,
                repair.getId(),
                usage.getId(),
                businessDate,
                StockBusinessType.REPAIR_USE,
                usage.getChargeUnitPrice(),
                revision + ":MOVEMENT",
                fifo.consumptions().isEmpty() ? null : fifo.consumptions().get(0).getStockLotId(),
                null
        );
        usage.setUnitCost(fifo.unitCost());
        usage.setCostAmount(fifo.totalCost());
        usage.setStockMovementId(movement.getId());
        usage.setStockLotConsumptionId(
                fifo.consumptions().isEmpty() ? null : fifo.consumptions().get(0).getId());
        usageRepository.save(usage);
        finalizeMovement(movement, usage, fifo);
        refreshPartQuantityCache(part);
    }

    private void restore(RepairRecord repair, RepairPartUsage usage, LocalDate businessDate) {
        requireTrustedUsage(repair, usage);
        PartInventory part = findPartForStockChange(usage.getPartId());
        Long warehouseId = stockLedgerService.resolveWarehouseId(
                usage.getWarehouseId() == null ? part.getWarehouseId() : usage.getWarehouseId());
        int before = stockLedgerService.availableQuantity(
                StockLedgerService.RESOURCE_PART,
                part.getId(),
                warehouseId
        );
        String revision = revisionKey(repair, usage, "RESTORE");
        StockLotService.ConsumptionResult restored = stockLotService.restoreSourceLineConsumption(
                SOURCE_TYPE,
                repair.getId(),
                usage.getId(),
                businessDate,
                revision + ":FIFO"
        );
        int restoredQuantity = restored.consumptions().stream()
                .map(StockLotConsumption::getQuantity)
                .mapToInt(quantity -> Math.abs(quantity == null ? 0 : quantity))
                .sum();
        if (restoredQuantity != usage.getQuantity()) {
            throw new BusinessException(
                    ResultCode.CONFLICT,
                    "Repair material FIFO consumption is missing or already reversed; stock was not restored"
            );
        }
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
                SOURCE_TYPE,
                repair.getId(),
                usage.getId(),
                businessDate,
                StockBusinessType.REPAIR_RESTORE,
                BigDecimal.ZERO,
                revision + ":MOVEMENT",
                null,
                null
        );
        for (StockMovementLine line : movementLineRepository.findByMovementIdOrderByIdAsc(movement.getId())) {
            line.setCostAmount(MoneyValues.zeroIfNullOrNegative(usage.getCostAmount()));
            line.setLineAmount(BigDecimal.ZERO);
            line.setSourceLineId(usage.getId());
            movementLineRepository.save(line);
        }
        refreshPartQuantityCache(part);
    }

    private void finalizeMovement(
            StockMovement movement,
            RepairPartUsage usage,
            StockLotService.ConsumptionResult fifo
    ) {
        Long firstLotId = fifo.consumptions().isEmpty()
                ? null
                : fifo.consumptions().get(0).getStockLotId();
        for (StockMovementLine line : movementLineRepository.findByMovementIdOrderByIdAsc(movement.getId())) {
            line.setUnitCost(fifo.unitCost());
            line.setCostAmount(fifo.totalCost());
            line.setUnitRevenue(usage.getChargeUnitPrice());
            line.setLineAmount(usage.getChargeAmount());
            line.setStockLotId(firstLotId);
            line.setSourceLineId(usage.getId());
            movementLineRepository.save(line);
        }
    }

    private void refreshPartQuantityCache(PartInventory part) {
        part.setQuantity(stockLedgerService.totalAvailableQuantity(
                StockLedgerService.RESOURCE_PART,
                part.getId()
        ));
        collaborationService.stampWrite(part);
        partRepository.save(part);
    }

    private RepairRecord refreshTotals(RepairRecord record) {
        List<RepairPartUsage> rows = usageRepository.findByRepairIdOrderByIdAsc(record.getId());
        record.setPartsFee(rows.stream()
                .map(RepairPartUsage::getChargeAmount)
                .map(MoneyValues::zeroIfNullOrNegative)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        record.setPartsCost(rows.stream()
                .map(RepairPartUsage::getCostAmount)
                .map(MoneyValues::zeroIfNullOrNegative)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        record.setUsedPartIds(rows.stream()
                .flatMap(usage -> IntStream.range(
                                0,
                                usage.getQuantity() == null ? 0 : usage.getQuantity()
                        )
                        .mapToObj(index -> String.valueOf(usage.getPartId())))
                .collect(Collectors.joining(",")));
        record.setUsedParts(rows.stream()
                .map(usage -> usage.getPartCode() + "/" + usage.getPartName()
                        + (usage.getQuantity() != null && usage.getQuantity() > 1
                        ? " x" + usage.getQuantity()
                        : ""))
                .collect(Collectors.joining(";")));
        normalizeFees(record);
        return repairRepository.saveAndFlush(record);
    }

    private PartInventory findPartForStockChange(Long partId) {
        Optional<PartInventory> part = SecurityUtils.isAdminOrSuperAdmin()
                ? partRepository.findByIdForUpdate(partId)
                : partRepository.findByIdAndIsLockedFalseForUpdate(partId);
        PartInventory resolved = part.orElseThrow(() ->
                new BusinessException(ResultCode.NOT_FOUND, "Used part not found: " + partId));
        visibilityPolicy.ensureWritable(
                resolved.getIsLocked(),
                "Used part is locked and cannot be consumed"
        );
        return resolved;
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

    private BigDecimal stockUnitCost(PartInventory part) {
        return MoneyValues.firstNonNegativeOrZero(
                part.getLandedUnitCost(),
                part.getPurchasePrice()
        );
    }

    private LocalDate repairBusinessDate(RepairRecord repair) {
        return repair.getRepairDate() == null
                ? LocalDate.now()
                : repair.getRepairDate().toLocalDate();
    }

    private String revisionKey(RepairRecord repair, RepairPartUsage usage, String action) {
        return "REPAIR:" + repair.getId()
                + ":USAGE:" + usage.getId()
                + ":" + action
                + ":REV:" + repair.getVersion();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record UsagePricing(
            BigDecimal chargeUnitPrice,
            BigDecimal discountAmount,
            BigDecimal chargeAmount
    ) {
    }
}
