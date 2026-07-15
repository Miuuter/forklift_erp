package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.PageResult;
import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.constant.MachineStockStatus;
import com.example.forklift_erp.constant.ModificationWorkOrderStatus;
import com.example.forklift_erp.constant.PartChangeAction;
import com.example.forklift_erp.constant.FinancialEventType;
import com.example.forklift_erp.dto.ModificationWorkOrderActionDTO;
import com.example.forklift_erp.dto.ModificationWorkOrderCreateDTO;
import com.example.forklift_erp.dto.ModificationWorkOrderVO;
import com.example.forklift_erp.dto.PartReplaceRequestDTO;
import com.example.forklift_erp.entity.ConfigReplaceLog;
import com.example.forklift_erp.entity.ConfigValue;
import com.example.forklift_erp.entity.MachineConfig;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.ModificationWorkOrder;
import com.example.forklift_erp.entity.ModificationWorkOrderLine;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.StockLotConsumption;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.MachineConfigRepository;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderLineRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.ConfigItemRepository;
import com.example.forklift_erp.repository.ConfigValueRepository;
import com.example.forklift_erp.repository.StockLotConsumptionRepository;
import com.example.forklift_erp.service.CollaborationService;
import com.example.forklift_erp.service.ConfigReplaceService;
import com.example.forklift_erp.service.FinancialEventService;
import com.example.forklift_erp.service.ModificationWorkOrderService;
import com.example.forklift_erp.service.OperationAuditService;
import com.example.forklift_erp.service.ResourceVisibilityPolicy;
import com.example.forklift_erp.service.StockLedgerService;
import com.example.forklift_erp.service.StockLotService;
import com.example.forklift_erp.util.BusinessNumberGenerator;
import com.example.forklift_erp.util.ListPageSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ModificationWorkOrderServiceImpl implements ModificationWorkOrderService {
    private static final String MOVEMENT_SOURCE_TYPE = "MODIFICATION_WORK_ORDER";

    @Autowired
    private ModificationWorkOrderRepository workOrderRepository;

    @Autowired
    private ModificationWorkOrderLineRepository lineRepository;

    @Autowired
    private MachineInventoryRepository machineRepository;

    @Autowired
    private MachineConfigRepository machineConfigRepository;

    @Autowired
    private PartInventoryRepository partRepository;

    @Autowired
    private ConfigItemRepository configItemRepository;

    @Autowired
    private ConfigValueRepository configValueRepository;

    @Autowired
    private ConfigReplaceService configReplaceService;

    @Autowired
    private CollaborationService collaborationService;

    @Autowired
    private OperationAuditService operationAuditService;

    @Autowired
    private ResourceVisibilityPolicy visibilityPolicy;

    @Autowired
    private StockLedgerService stockLedgerService;

    @Autowired
    private StockLotService stockLotService;

    @Autowired
    private StockLotConsumptionRepository stockLotConsumptionRepository;

    @Autowired
    private FinancialEventService financialEventService;

    @Override
    @Transactional(readOnly = true)
    public List<ModificationWorkOrderVO> findAll() {
        return workOrderRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<ModificationWorkOrderVO> findPage(String keyword, Integer page, Integer size) {
        int normalizedPage = ListPageSupport.page(page);
        int normalizedSize = ListPageSupport.size(size);
        Page<ModificationWorkOrder> result = workOrderRepository.searchPage(
                normalizeKeyword(keyword),
                ListPageSupport.pageRequest(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        );
        return PageResult.of(
                result.getContent().stream().map(this::toVO).toList(),
                normalizedPage,
                normalizedSize,
                result.getTotalElements()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModificationWorkOrderVO> findByMachineId(Long machineId) {
        return workOrderRepository.findByMachineIdOrderByCreatedAtDesc(machineId).stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ModificationWorkOrderVO findById(Long id) {
        return toVO(workOrderRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Modification work order not found")));
    }

    @Override
    @Transactional
    public ModificationWorkOrderVO create(ModificationWorkOrderCreateDTO request) {
        MachineInventory machine = machineRepository.findByIdForUpdate(request.getMachineId())
                .orElseThrow(() -> new BusinessException(ResultCode.VEHICLE_NOT_FOUND, "Vehicle not found"));
        visibilityPolicy.ensureWritable(machine.getIsLocked(), "Vehicle is locked and cannot create modification work order");
        collaborationService.validateWrite(machine, request.getMachineVersion());
        if (MachineStockStatus.RENTED.code().equals(machine.getStockStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "A rented vehicle cannot enter a modification work order");
        }
        if (!MachineStockStatus.canSell(machine.getStockStatus())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Vehicle status does not allow a modification work order: " + machine.getStockStatus());
        }
        if (workOrderRepository.existsByMachineIdAndStatusIn(
                machine.getId(),
                List.of(
                        ModificationWorkOrderStatus.WAITING_PARTS.code(),
                        ModificationWorkOrderStatus.IN_PROGRESS.code()
                ))) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Vehicle already has an active modification work order");
        }
        Long workOrderWarehouseId = stockLedgerService.resolveWarehouseId(
                request.getWarehouseId() == null ? machine.getWarehouseId() : request.getWarehouseId());
        int machineQuantity = stockLedgerService.availableQuantity(
                StockLedgerService.RESOURCE_MACHINE, machine.getId(), workOrderWarehouseId);
        if (machineQuantity < 1) {
            throw new BusinessException(ResultCode.INSUFFICIENT_STOCK, "Vehicle is not in stock and cannot create modification work order");
        }

        Map<PartWarehouseKey, Integer> requestedPartQuantities = new HashMap<>();
        Map<Long, PartInventory> parts = new HashMap<>();
        List<ModificationWorkOrderLine> preparedLines = request.getLines().stream()
                .map(lineRequest -> prepareLine(
                        machine, lineRequest, workOrderWarehouseId, requestedPartQuantities, parts))
                .toList();
        requestedPartQuantities.forEach((key, quantity) -> {
            PartInventory part = parts.get(key.partId());
            int available = stockLedgerService.availableQuantity(
                    StockLedgerService.RESOURCE_PART, key.partId(), key.warehouseId());
            if (available < quantity) {
                throw new BusinessException(ResultCode.INSUFFICIENT_STOCK,
                        "Part stock is insufficient in selected warehouse: " + part.getPartName()
                                + ", required " + quantity + ", available " + available);
            }
        });

        ModificationWorkOrder workOrder = new ModificationWorkOrder();
        workOrder.setWorkOrderNo(nextWorkOrderNo());
        workOrder.setMachineId(machine.getId());
        workOrder.setCustomerName(blankToNull(request.getCustomerName()));
        workOrder.setSalesOrderNo(blankToNull(request.getSalesOrderNo()));
        workOrder.setWorkOrderType(blankToDefault(request.getWorkOrderType(), "PRE_SALE").toUpperCase(Locale.ROOT));
        workOrder.setWarehouseId(workOrderWarehouseId);
        workOrder.setBusinessDate(request.getBusinessDate() == null ? LocalDate.now() : request.getBusinessDate());
        workOrder.setOperator(blankToNull(request.getOperator()));
        workOrder.setRemark(blankToNull(request.getRemark()));
        workOrder.setStatus(ModificationWorkOrderStatus.WAITING_PARTS.code());
        ModificationWorkOrder savedOrder = workOrderRepository.save(workOrder);

        preparedLines.forEach(line -> line.setWorkOrderId(savedOrder.getId()));
        lineRepository.saveAllAndFlush(preparedLines);

        machine.setStockStatus(MachineStockStatus.PENDING_MODIFICATION.code());
        collaborationService.stampWrite(machine);
        machineRepository.save(machine);

        operationAuditService.record("Modification work order", "CREATE", "MODIFICATION_WORK_ORDER", savedOrder.getId(),
                savedOrder.getWorkOrderNo(), "Vehicle " + savedOrder.getMachineId(),
                "Create modification work order lines: " + preparedLines.size(), request.getOperator(), request.getRemark(),
                MOVEMENT_SOURCE_TYPE, savedOrder.getId());
        return toVO(savedOrder);
    }

    @Override
    @Transactional
    public ModificationWorkOrderVO complete(Long id, ModificationWorkOrderActionDTO request) {
        ModificationWorkOrder workOrder = workOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Modification work order not found"));
        validateVersion(workOrder.getVersion(), request == null ? null : request.getVersion(), "Modification work order was updated by another user");
        if (ModificationWorkOrderStatus.COMPLETED.code().equals(workOrder.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "Modification work order is already completed");
        }
        if (ModificationWorkOrderStatus.CANCELED.code().equals(workOrder.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "Modification work order is already completed");
        }

        List<ModificationWorkOrderLine> lines = lineRepository.findByWorkOrderIdOrderByIdAsc(workOrder.getId());
        if (lines.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Modification work order has no lines to complete");
        }

        MachineInventory machine = machineRepository.findByIdForUpdate(workOrder.getMachineId())
                .orElseThrow(() -> new BusinessException(ResultCode.VEHICLE_NOT_FOUND, "Vehicle not found"));
        visibilityPolicy.ensureWritable(machine.getIsLocked(), "Vehicle is locked and cannot complete modification work order");
        machine.setStockStatus(MachineStockStatus.MODIFYING.code());
        collaborationService.stampWrite(machine);
        machineRepository.save(machine);

        workOrder.setStatus(ModificationWorkOrderStatus.IN_PROGRESS.code());
        workOrderRepository.save(workOrder);

        String operator = firstNonBlank(request == null ? null : request.getOperator(), workOrder.getOperator());
        String actionRemark = request == null ? null : request.getRemark();
        for (ModificationWorkOrderLine line : lines) {
            if (isDiscountLine(line)) {
                applyDiscountLine(workOrder, line, operator, actionRemark);
            } else {
                ResolvedLine resolvedLine = resolveLineForExecution(workOrder.getMachineId(), line);
            PartReplaceRequestDTO replaceRequest = new PartReplaceRequestDTO();
            replaceRequest.setMachineId(workOrder.getMachineId());
            replaceRequest.setMachineVersion(resolvedLine.machine().getVersion());
            replaceRequest.setMachineConfigId(line.getMachineConfigId());
            replaceRequest.setMachineConfigVersion(resolvedLine.config().getVersion());
            replaceRequest.setNewPartId(line.getNewPartId());
            replaceRequest.setNewPartVersion(resolvedLine.part().getVersion());
            replaceRequest.setQuantity(line.getQuantity());
            replaceRequest.setOldPartAction(line.getOldPartAction());
            replaceRequest.setOldPartDisposition(line.getOldPartDisposition());
            replaceRequest.setOldPartWarehouseId(line.getOldPartWarehouseId());
            replaceRequest.setOldPartCondition(line.getOldPartCondition());
            replaceRequest.setOldPartValuationSource(line.getOldPartValuationSource());
            replaceRequest.setOldPartUnitCost(line.getOldPartUnitCost());
            replaceRequest.setWarehouseId(line.getWarehouseId() == null ? workOrder.getWarehouseId() : line.getWarehouseId());
            replaceRequest.setBusinessDate(workOrder.getBusinessDate());
            replaceRequest.setWorkOrderType(workOrder.getWorkOrderType());
            replaceRequest.setOperator(operator);
            replaceRequest.setRemark(joinRemark("Modification work order " + workOrder.getWorkOrderNo(), line.getRemark(), actionRemark));
            replaceRequest.setStockMovementSourceType(MOVEMENT_SOURCE_TYPE);
            replaceRequest.setStockMovementSourceId(workOrder.getId());
            replaceRequest.setStockMovementSourceLineId(line.getId());
            ConfigReplaceLog replaceLog = configReplaceService.performPartReplace(replaceRequest);
            line.setReplaceLogId(replaceLog.getId());
            line.setCostAmount(fifoCostForLine(workOrder.getId(), line.getId()));
            BigDecimal capitalizationAmount = capitalizationAmount(line);
            if ("PRE_SALE".equalsIgnoreCase(workOrder.getWorkOrderType())
                    && capitalizationAmount.signum() != 0) {
                stockLotService.capitalizeSerializedAssetCost(
                        StockLedgerService.RESOURCE_MACHINE,
                        workOrder.getMachineId(),
                        workOrder.getWarehouseId(),
                        capitalizationAmount,
                        MOVEMENT_SOURCE_TYPE,
                        workOrder.getId(),
                        line.getId(),
                        workOrder.getBusinessDate(),
                        "MODIFICATION-CAPITALIZATION:" + workOrder.getId() + ":" + line.getId()
                );
            }
            }
            lineRepository.save(line);
        }

        workOrder.setStatus(ModificationWorkOrderStatus.COMPLETED.code());
        workOrder.setCompletedAt(LocalDateTime.now());
        if (operator != null) {
            workOrder.setOperator(operator);
        }
        if (actionRemark != null && !actionRemark.isBlank()) {
            workOrder.setRemark(joinRemark(workOrder.getRemark(), actionRemark));
        }
        ModificationWorkOrder savedOrder = workOrderRepository.save(workOrder);
        postModificationFinancial(savedOrder, lines);

        MachineInventory completedMachine = machineRepository.findByIdForUpdate(workOrder.getMachineId())
                .orElseThrow(() -> new BusinessException(ResultCode.VEHICLE_NOT_FOUND, "Vehicle not found"));
        if ("PRE_SALE".equalsIgnoreCase(savedOrder.getWorkOrderType())) {
            BigDecimal capitalizedCost = lines.stream()
                    .map(this::capitalizationAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (capitalizedCost.signum() != 0) {
                BigDecimal updatedLandedCost =
                        amountOrZero(completedMachine.getLandedUnitCost()).add(capitalizedCost);
                if (updatedLandedCost.signum() < 0) {
                    throw new BusinessException(ResultCode.CONFLICT,
                            "Modification would make the vehicle landed cost negative");
                }
                completedMachine.setLandedUnitCost(updatedLandedCost);
            }
        }
        completedMachine.setStockStatus(MachineStockStatus.PENDING_OUTBOUND.code());
        collaborationService.stampWrite(completedMachine);
        machineRepository.save(completedMachine);

        operationAuditService.record("Modification work order", "COMPLETE", "MODIFICATION_WORK_ORDER", savedOrder.getId(),
                savedOrder.getWorkOrderNo(), "Vehicle " + savedOrder.getMachineId(),
                "Complete modification work order lines: " + lines.size(), operator, actionRemark,
                MOVEMENT_SOURCE_TYPE, savedOrder.getId());
        return toVO(savedOrder);
    }

    @Override
    @Transactional
    public ModificationWorkOrderVO cancel(Long id, ModificationWorkOrderActionDTO request) {
        ModificationWorkOrder workOrder = workOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Modification work order not found"));
        validateVersion(workOrder.getVersion(), request == null ? null : request.getVersion(), "Modification work order was updated by another user");
        if (ModificationWorkOrderStatus.COMPLETED.code().equals(workOrder.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "Completed modification work order cannot be canceled");
        }
        if (ModificationWorkOrderStatus.CANCELED.code().equals(workOrder.getStatus())) {
            return toVO(workOrder);
        }
        MachineInventory machine = machineRepository.findByIdForUpdate(workOrder.getMachineId())
                .orElseThrow(() -> new BusinessException(ResultCode.VEHICLE_NOT_FOUND, "Vehicle not found"));
        visibilityPolicy.ensureWritable(machine.getIsLocked(), "Vehicle is locked and cannot cancel modification work order");
        workOrder.setStatus(ModificationWorkOrderStatus.CANCELED.code());
        workOrder.setCanceledAt(LocalDateTime.now());
        String operator = request == null ? null : request.getOperator();
        String remark = request == null ? null : request.getRemark();
        if (operator != null && !operator.isBlank()) {
            workOrder.setOperator(operator);
        }
        if (remark != null && !remark.isBlank()) {
            workOrder.setRemark(joinRemark(workOrder.getRemark(), remark));
        }
        ModificationWorkOrder savedOrder = workOrderRepository.save(workOrder);
        restoreMachineStatusIfNoActiveOrder(savedOrder.getMachineId(), savedOrder.getId());

        operationAuditService.record("Modification work order", "CANCEL", "MODIFICATION_WORK_ORDER", savedOrder.getId(),
                savedOrder.getWorkOrderNo(), "Vehicle " + savedOrder.getMachineId(),
                "Cancel modification work order", operator, remark, MOVEMENT_SOURCE_TYPE, savedOrder.getId());
        return toVO(savedOrder);
    }

    private ModificationWorkOrderLine prepareLine(
            MachineInventory machine,
            ModificationWorkOrderCreateDTO.Line lineRequest,
            Long workOrderWarehouseId,
            Map<PartWarehouseKey, Integer> requestedPartQuantities,
            Map<Long, PartInventory> parts
    ) {
        MachineConfig config = machineConfigRepository.findByIdForUpdate(lineRequest.getMachineConfigId())
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Machine config not found"));
        validateVersion(config.getVersion(), lineRequest.getMachineConfigVersion(), "Machine config was updated by another user");
        if (!config.getMachineId().equals(machine.getId())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Machine config does not belong to this vehicle");
        }
        String oldPartAction = blankToDefault(lineRequest.getOldPartAction(), PartChangeAction.STOCK_IN.code());
        if (PartChangeAction.DISCOUNT.code().equals(oldPartAction)) {
            int discountQuantity = lineRequest.getQuantity() == null ? 1 : lineRequest.getQuantity();
            if (discountQuantity < 1) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "Quantity must be greater than 0");
            }
            if (lineRequest.getNewConfigValueId() == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "Target config value is required");
            }
            ConfigValue value = configValueRepository.findByIdForUpdate(lineRequest.getNewConfigValueId())
                    .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Target config value not found"));
            validateVersion(value.getVersion(), lineRequest.getNewConfigValueVersion(), "Config value was updated by another user");
            ensureSameConfigItem(config, value);

            ModificationWorkOrderLine line = new ModificationWorkOrderLine();
            line.setMachineConfigId(config.getId());
            line.setConfigItemId(config.getConfigItemId());
            line.setItemName(config.getItemName());
            line.setOldValue(config.getSelectedValue());
            line.setNewConfigValueId(value.getId());
            line.setNewPartName(value.getValueLabel());
            line.setNewValue(value.getValueLabel());
            line.setQuantity(discountQuantity);
            line.setOldPartAction(oldPartAction);
            line.setPriceDifference(amountOrZero(lineRequest.getPriceDifference()));
            line.setWarehouseId(stockLedgerService.resolveWarehouseId(
                    lineRequest.getWarehouseId() == null ? workOrderWarehouseId : lineRequest.getWarehouseId()));
            line.setChargeUnitPrice(amountOrZero(lineRequest.getChargeUnitPrice()));
            line.setDiscountAmount(amountOrZero(lineRequest.getDiscountAmount()));
            line.setChargeAmount(chargeAmount(
                    line.getChargeUnitPrice(), discountQuantity, line.getDiscountAmount()));
            line.setOldPartDisposition(blankToNull(lineRequest.getOldPartDisposition()));
            line.setOldPartWarehouseId(lineRequest.getOldPartWarehouseId());
            line.setOldPartCondition(blankToNull(lineRequest.getOldPartCondition()));
            line.setOldPartValuationSource(blankToNull(lineRequest.getOldPartValuationSource()));
            line.setOldPartUnitCost(amountOrNull(lineRequest.getOldPartUnitCost()));
            line.setRemark(blankToNull(lineRequest.getRemark()));
            return line;
        }
        if (lineRequest.getNewPartId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "New part is required");
        }
        PartInventory part = partRepository.findByIdForUpdate(lineRequest.getNewPartId())
                .orElseThrow(() -> new BusinessException(ResultCode.PART_NOT_FOUND, "New part not found"));
        visibilityPolicy.ensureWritable(part.getIsLocked(), "Part is locked and cannot be used in modification work order");
        validateVersion(part.getVersion(), lineRequest.getNewPartVersion(), "Part inventory was updated by another user");
        ensureCompatible(config, part);

        int quantity = lineRequest.getQuantity() == null ? 1 : lineRequest.getQuantity();
        if (quantity < 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Quantity must be greater than 0");
        }
        Long lineWarehouseId = stockLedgerService.resolveWarehouseId(
                lineRequest.getWarehouseId() == null ? workOrderWarehouseId : lineRequest.getWarehouseId());
        requestedPartQuantities.merge(new PartWarehouseKey(part.getId(), lineWarehouseId), quantity, Integer::sum);
        parts.put(part.getId(), part);

        ModificationWorkOrderLine line = new ModificationWorkOrderLine();
        line.setMachineConfigId(config.getId());
        line.setConfigItemId(config.getConfigItemId());
        line.setItemName(config.getItemName());
        line.setOldValue(config.getSelectedValue());
        line.setNewPartId(part.getId());
        line.setNewPartCode(part.getPartCode());
        line.setNewPartName(part.getPartName());
        line.setNewValue(partDisplayName(part));
        line.setQuantity(quantity);
        line.setWarehouseId(lineWarehouseId);
        line.setOldPartAction(oldPartAction);
        line.setPriceDifference(amountOrZero(lineRequest.getPriceDifference()));
        line.setChargeUnitPrice(amountOrZero(lineRequest.getChargeUnitPrice()));
        line.setDiscountAmount(amountOrZero(lineRequest.getDiscountAmount()));
        line.setChargeAmount(chargeAmount(line.getChargeUnitPrice(), quantity, line.getDiscountAmount()));
        line.setOldPartDisposition(blankToNull(lineRequest.getOldPartDisposition()));
        line.setOldPartWarehouseId(lineRequest.getOldPartWarehouseId());
        line.setOldPartCondition(blankToNull(lineRequest.getOldPartCondition()));
        line.setOldPartValuationSource(blankToNull(lineRequest.getOldPartValuationSource()));
        line.setOldPartUnitCost(amountOrNull(lineRequest.getOldPartUnitCost()));
        line.setRemark(blankToNull(lineRequest.getRemark()));
        return line;
    }

    private ResolvedLine resolveLineForExecution(Long machineId, ModificationWorkOrderLine line) {
        MachineInventory machine = machineRepository.findByIdForUpdate(machineId)
                .orElseThrow(() -> new BusinessException(ResultCode.VEHICLE_NOT_FOUND, "Vehicle not found"));
        visibilityPolicy.ensureWritable(machine.getIsLocked(), "Vehicle is locked and cannot complete modification work order");
        MachineConfig config = machineConfigRepository.findByIdForUpdate(line.getMachineConfigId())
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Machine config not found"));
        if (!config.getMachineId().equals(machineId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Machine config does not belong to this vehicle");
        }
        if (line.getOldValue() != null && !line.getOldValue().equals(config.getSelectedValue())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Vehicle config has changed before replacement: " + line.getItemName());
        }
        PartInventory part = partRepository.findByIdForUpdate(line.getNewPartId())
                .orElseThrow(() -> new BusinessException(ResultCode.PART_NOT_FOUND, "New part not found"));
        visibilityPolicy.ensureWritable(part.getIsLocked(), "Part is locked and cannot be used in modification work order");
        ensureCompatible(config, part);
        return new ResolvedLine(machine, config, part);
    }

    private record ResolvedLine(MachineInventory machine, MachineConfig config, PartInventory part) {
    }

    private record PartWarehouseKey(Long partId, Long warehouseId) {
    }

    private void applyDiscountLine(
            ModificationWorkOrder workOrder,
            ModificationWorkOrderLine line,
            String operator,
            String actionRemark
    ) {
        MachineConfig config = machineConfigRepository.findByIdForUpdate(line.getMachineConfigId())
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Machine config not found"));
        if (!config.getMachineId().equals(workOrder.getMachineId())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Machine config does not belong to this vehicle");
        }
        if (line.getOldValue() != null && !line.getOldValue().equals(config.getSelectedValue())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Vehicle config has changed before discount replacement: " + line.getItemName());
        }
        ConfigValue value = configValueRepository.findByIdForUpdate(line.getNewConfigValueId())
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Target config value not found"));
        ensureSameConfigItem(config, value);

        config.setConfigValueId(value.getId());
        config.setSelectedValue(value.getValueLabel());
        config.setConfigSource("DISCOUNT");
        config.setInstalledDate(LocalDateTime.now());
        config.setRemark(joinRemark(config.getRemark(),
                "Discount replacement; work order " + workOrder.getWorkOrderNo(),
                "Price difference=" + amountOrZero(line.getPriceDifference()),
                line.getRemark(),
                actionRemark));
        collaborationService.stampWrite(config);
        machineConfigRepository.save(config);

        line.setNewConfigValueId(value.getId());
        line.setNewPartName(value.getValueLabel());
        line.setNewValue(value.getValueLabel());
        line.setReplaceLogId(null);

        operationAuditService.record("Modification work order", "DISCOUNT_REPLACE", "MODIFICATION_WORK_ORDER", workOrder.getId(),
                workOrder.getWorkOrderNo(), "Discount replacement " + workOrder.getMachineId(),
                "Discount line amount: " + amountOrZero(line.getPriceDifference()), operator, actionRemark,
                MOVEMENT_SOURCE_TYPE, workOrder.getId());
    }

    private void postModificationFinancial(ModificationWorkOrder order, List<ModificationWorkOrderLine> lines) {
        if (Boolean.TRUE.equals(order.getFinancialPosted())) {
            return;
        }
        BigDecimal charge = lines.stream()
                .map(ModificationWorkOrderLine::getChargeAmount)
                .map(this::amountOrZero)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cost = lines.stream()
                .map(ModificationWorkOrderLine::getCostAmount)
                .map(this::amountOrZero)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal returnedPartValue = lines.stream()
                .map(this::returnedPartValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String sourceType = "MODIFICATION_WORK_ORDER";
        LocalDate date = order.getBusinessDate() == null ? LocalDate.now() : order.getBusinessDate();
        if ("AFTER_SALE".equalsIgnoreCase(order.getWorkOrderType()) && charge.signum() > 0) {
            financialEventService.post(FinancialEventType.ACCOUNTS_RECEIVABLE, charge, date,
                    sourceType, order.getId(), null, "CUSTOMER", null, order.getCustomerName(),
                    "After-sale modification receivable", "MODIFICATION:" + order.getId() + ":AR");
            financialEventService.post(FinancialEventType.REVENUE, charge, date,
                    sourceType, order.getId(), null, "CUSTOMER", null, order.getCustomerName(),
                    "After-sale modification revenue", "MODIFICATION:" + order.getId() + ":REV");
        }
        if ("AFTER_SALE".equalsIgnoreCase(order.getWorkOrderType()) && cost.signum() > 0) {
            financialEventService.post(
                    FinancialEventType.OPERATING_COST,
                    cost,
                    date,
                    sourceType,
                    order.getId(),
                    null,
                    null,
                    null,
                    null,
                    "Modification FIFO cost",
                    "MODIFICATION:" + order.getId() + ":COST"
            );
        }
        if ("AFTER_SALE".equalsIgnoreCase(order.getWorkOrderType()) && returnedPartValue.signum() > 0) {
            financialEventService.post(
                    FinancialEventType.INVENTORY_GAIN,
                    returnedPartValue,
                    date,
                    sourceType,
                    order.getId(),
                    null,
                    null,
                    null,
                    null,
                    "Recovered old-part inventory value",
                    "MODIFICATION:" + order.getId() + ":OLD-PART-RECOVERY"
            );
        }
        order.setFinancialPosted(true);
        workOrderRepository.save(order);
    }

    private BigDecimal fifoCostForLine(Long workOrderId, Long workOrderLineId) {
        return stockLotConsumptionRepository
                .findBySourceTypeAndSourceIdAndSourceLineIdOrderByIdAsc(
                        MOVEMENT_SOURCE_TYPE, workOrderId, workOrderLineId)
                .stream()
                .filter(consumption -> consumption.getReversalOfConsumptionId() == null)
                .map(StockLotConsumption::getTotalCost)
                .map(this::amountOrZero)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void restoreMachineStatusIfNoActiveOrder(Long machineId, Long canceledOrderId) {
        List<ModificationWorkOrder> machineOrders =
                workOrderRepository.findByMachineIdOrderByCreatedAtDesc(machineId);
        boolean hasActive = machineOrders.stream()
                .anyMatch(order -> !order.getId().equals(canceledOrderId)
                        && !ModificationWorkOrderStatus.COMPLETED.code().equals(order.getStatus())
                        && !ModificationWorkOrderStatus.CANCELED.code().equals(order.getStatus()));
        if (hasActive) {
            return;
        }
        boolean hasCompleted = machineOrders.stream()
                .anyMatch(order -> ModificationWorkOrderStatus.COMPLETED.code().equals(order.getStatus()));
        machineRepository.findByIdForUpdate(machineId).ifPresent(machine -> {
            machine.setStockStatus(hasCompleted
                    ? MachineStockStatus.PENDING_OUTBOUND.code()
                    : MachineStockStatus.IN_STOCK.code());
            collaborationService.stampWrite(machine);
            machineRepository.save(machine);
        });
    }

    private void ensureCompatible(MachineConfig config, PartInventory part) {
        String actualType = normalizeType(part.getPartCategory());
        List<String> expectedTypes = configItemRepository.findById(config.getConfigItemId())
                .map(item -> List.of(normalizeType(item.getSubCategory()), normalizeType(item.getItemName()), normalizeType(config.getItemName())))
                .orElseGet(() -> List.of(normalizeType(config.getItemName())));
        boolean matched = !actualType.isEmpty() && expectedTypes.stream()
                .filter(value -> value != null && !value.isBlank())
                .anyMatch(value -> value.equals(actualType));
        if (!matched) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Part category does not match config item: expected " + config.getItemName()
                            + ", actual " + part.getPartCategory());
        }
    }

    private void ensureSameConfigItem(MachineConfig config, ConfigValue value) {
        if (value == null || value.getConfigItemId() == null || !value.getConfigItemId().equals(config.getConfigItemId())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Target config value must belong to the same config item");
        }
    }

    private boolean isDiscountLine(ModificationWorkOrderLine line) {
        return PartChangeAction.DISCOUNT.code().equals(line.getOldPartAction());
    }

    private BigDecimal chargeAmount(BigDecimal unitPrice, int quantity, BigDecimal discountAmount) {
        BigDecimal gross = amountOrZero(unitPrice).multiply(BigDecimal.valueOf(quantity));
        BigDecimal discount = amountOrZero(discountAmount);
        if (gross.signum() < 0 || discount.signum() < 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Modification charges and discounts cannot be negative");
        }
        if (discount.compareTo(gross) > 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Modification discount cannot exceed the line charge");
        }
        return gross.subtract(discount);
    }

    private BigDecimal capitalizationAmount(ModificationWorkOrderLine line) {
        return amountOrZero(line.getCostAmount()).subtract(returnedPartValue(line));
    }

    private BigDecimal returnedPartValue(ModificationWorkOrderLine line) {
        if (!oldPartReturnsToInventory(line)) {
            return BigDecimal.ZERO;
        }
        int quantity = line.getQuantity() == null || line.getQuantity() < 1 ? 1 : line.getQuantity();
        BigDecimal unitCost = amountOrZero(line.getOldPartUnitCost());
        return unitCost.signum() < 0 ? BigDecimal.ZERO : unitCost.multiply(BigDecimal.valueOf(quantity));
    }

    private boolean oldPartReturnsToInventory(ModificationWorkOrderLine line) {
        String disposition = blankToNull(line.getOldPartDisposition());
        if (disposition != null) {
            disposition = disposition.toUpperCase(Locale.ROOT);
            if ("SCRAP".equals(disposition) || "DISCARD".equals(disposition) || "NONE".equals(disposition)) {
                return false;
            }
        }
        return PartChangeAction.STOCK_IN.code().equalsIgnoreCase(line.getOldPartAction());
    }

    private BigDecimal amountOrZero(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value;
    }

    private BigDecimal amountOrNull(BigDecimal value) {
        return value == null || value.signum() < 0 ? null : value;
    }

    private ModificationWorkOrderVO toVO(ModificationWorkOrder workOrder) {
        MachineInventory machine = workOrder.getMachineId() == null
                ? null
                : machineRepository.findById(workOrder.getMachineId()).orElse(null);
        return ModificationWorkOrderVO.fromEntity(
                workOrder,
                lineRepository.findByWorkOrderIdOrderByIdAsc(workOrder.getId()),
                machine
        );
    }

    private void validateVersion(Long currentVersion, Long requestVersion, String message) {
        if (requestVersion != null && currentVersion != null && !currentVersion.equals(requestVersion)) {
            throw new BusinessException(ResultCode.CONFLICT, message);
        }
    }

    private String nextWorkOrderNo() {
        return BusinessNumberGenerator.next("MO", 6);
    }

    private String partDisplayName(PartInventory part) {
        if (part.getSpecification() == null || part.getSpecification().isBlank()) {
            return part.getPartName();
        }
        return part.getPartName() + " / " + part.getSpecification();
    }

    private String normalizeType(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeKeyword(String keyword) {
        return keyword == null || keyword.isBlank() ? null : keyword.trim();
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return blankToNull(second);
    }

    private String joinRemark(String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append("; ");
            }
            builder.append(value.trim());
        }
        return builder.isEmpty() ? null : builder.toString();
    }
}
