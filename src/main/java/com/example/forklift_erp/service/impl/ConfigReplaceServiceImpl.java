// src/main/java/com/example/forklift_erp/service/impl/ConfigReplaceServiceImpl.java
package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.constant.PartChangeAction;
import com.example.forklift_erp.constant.MachineStockStatus;
import com.example.forklift_erp.constant.StockBusinessType;
import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.dto.ConfigReplaceRequestDTO;
import com.example.forklift_erp.dto.PartReplaceRequestDTO;
import com.example.forklift_erp.dto.VehiclePartInstallRequestDTO;
import com.example.forklift_erp.entity.*;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.*;
import com.example.forklift_erp.service.CollaborationService;
import com.example.forklift_erp.service.ConfigReplaceService;
import com.example.forklift_erp.service.ResourceVisibilityPolicy;
import com.example.forklift_erp.service.StockLedgerService;
import com.example.forklift_erp.service.StockLotService;
import com.example.forklift_erp.util.MoneyValues;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class ConfigReplaceServiceImpl implements ConfigReplaceService {

    @Autowired
    private MachineInventoryRepository machineRepository;

    @Autowired
    private MachineConfigRepository machineConfigRepository;

    @Autowired
    private ConfigValueRepository configValueRepository;

    @Autowired
    private PartInventoryRepository partRepository;

    @Autowired
    private ConfigReplaceLogRepository replaceLogRepository;

    @Autowired
    private CollaborationService collaborationService;

    @Autowired
    private StockLedgerService stockLedgerService;

    @Autowired
    private StockOperationRecorder stockOperationRecorder;

    @Autowired
    private ResourceVisibilityPolicy visibilityPolicy;

    @Autowired
    private StockLotService stockLotService;

    @Override
    @Transactional
    public ConfigReplaceLog performReplace(ConfigReplaceRequestDTO request) {
        if (request.getNewPartId() != null) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Inventory-backed replacement must use the dedicated /api/replace/part workflow");
        }
        // 1. 校验车辆存在
        MachineInventory machine = machineRepository.findByIdForUpdate(request.getMachineId())
                .orElseThrow(() -> new BusinessException(ResultCode.VEHICLE_NOT_FOUND, "车辆不存在"));
        if (MachineStockStatus.RENTED.code().equals(machine.getStockStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "A rented vehicle cannot be modified");
        }

        // 2. 校验新配置值存在
        visibilityPolicy.ensureWritable(machine.getIsLocked(), "Vehicle is locked and cannot be modified");
        collaborationService.validateWrite(machine, request.getMachineVersion());

        ConfigValue newConfigValue = configValueRepository.findById(request.getNewConfigValueId())
                .orElseThrow(() -> new BusinessException(ResultCode.PART_NOT_FOUND, "新配置值不存在"));

        // 3. 找到旧的配置记录（同一车辆的同一配置项）
        List<MachineConfig> oldConfigs = machineConfigRepository.findByMachineId(request.getMachineId());
        MachineConfig oldConfig = oldConfigs.stream()
                .filter(c -> c.getConfigItemId().equals(request.getConfigItemId()))
                .map(c -> machineConfigRepository.findByIdForUpdate(c.getId()).orElse(c))
                .findFirst()
                .orElse(null);  // 可能没有旧配置，比如首次添加配置
        if (oldConfig != null) {
            collaborationService.validateWrite(oldConfig, request.getOldConfigVersion());
        }

        // 4. 记录旧值（如果没有旧值则为空）
        String oldValue = oldConfig != null ? oldConfig.getSelectedValue() : null;
        Long oldConfigId = oldConfig != null ? oldConfig.getId() : null;

        // 5. 执行业务：根据是否有旧配置做更新或新增
        if (oldConfig != null) {
            // 更新旧配置记录为新值
            oldConfig.setConfigValueId(request.getNewConfigValueId());
            oldConfig.setSelectedValue(newConfigValue.getValueLabel());
            oldConfig.setConfigSource(determineSource(request.getReplaceType()));
            oldConfig.setInstalledDate(LocalDateTime.now());
            oldConfig.setUpdatedAt(LocalDateTime.now());
            collaborationService.stampWrite(oldConfig);
            machineConfigRepository.save(oldConfig);
            log.info("更新车辆配置: machineId={}, 配置项={}, 旧值={} -> 新值={}",
                    machine.getId(), oldConfig.getItemName(), oldValue, newConfigValue.getValueLabel());
        } else {
            // 如果没有旧配置，则新增一条配置记录（可能需要从 ConfigItem 获取名称，这里简化，认为前端传递了 itemName，但DTO里没有，可以从数据库查）
            // 更好的做法：DTO里包含 itemName 或从 ConfigItem 查
            // 此处我们查一下
            ConfigItem configItem = configItemRepository.findById(request.getConfigItemId())
                    .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "配置项不存在"));
            MachineConfig newConfig = new MachineConfig();
            newConfig.setMachineId(machine.getId());
            newConfig.setConfigItemId(request.getConfigItemId());
            newConfig.setConfigValueId(request.getNewConfigValueId());
            newConfig.setItemName(configItem.getItemName());
            newConfig.setSelectedValue(newConfigValue.getValueLabel());
            newConfig.setConfigSource(determineSource(request.getReplaceType()));
            newConfig.setIsStandard(false);  // 非原厂，因为不是入库时设置的
            newConfig.setInstalledDate(LocalDateTime.now());
            collaborationService.stampWrite(newConfig);
            machineConfigRepository.save(newConfig);
            log.info("新增车辆配置: machineId={}, 配置项={}, 值={}",
                    machine.getId(), configItem.getItemName(), newConfigValue.getValueLabel());
        }

        // 6. 处理配件库存变动
        // 6.1 如果新配件来自库存（newPartId 不为空），扣减配件库存
        if (request.getNewPartId() != null) {
            PartInventory newPart = partRepository.findByIdForUpdate(request.getNewPartId())
                    .orElseThrow(() -> new BusinessException(ResultCode.PART_NOT_FOUND, "新配件不存在"));
            visibilityPolicy.ensureWritable(newPart.getIsLocked(), "Part is locked and cannot be used for replacement");
            collaborationService.validateWrite(newPart, request.getNewPartVersion());
            if (newPart.getQuantity() < 1) {
                throw new BusinessException(ResultCode.INSUFFICIENT_STOCK, "配件库存不足: " + newPart.getPartName());
            }
            int beforeQuantity = newPart.getQuantity();
            newPart.setQuantity(beforeQuantity - 1);
            collaborationService.stampWrite(newPart);
            newPart = partRepository.save(newPart);
            savePartStockLog(newPart, "OUTBOUND", 1, beforeQuantity, newPart.getQuantity(),
                    request.getOperator(), "Config replace outbound; machineId=" + machine.getId());
            log.info("配件出库: partCode={}, 剩余库存={}", newPart.getPartCode(), newPart.getQuantity());
        }

        // 6.2 如果旧件处理为入库（STOCK_IN），我们需要把旧件作为配件入库。
        //     注意：这里可能需要知道旧件的配件编码，实际业务中旧件应当已有对应的 PartInventory 记录，
        //     但目前没有。我们可以简化处理：如果旧件要入库，要求提供旧件的 partCode 或 ID。
        //     既然你目前没有旧件编码的管理，这里先做占位，实际可能需要扩展DTO。
        if (PartChangeAction.STOCK_IN.code().equals(request.getOldPartAction()) && oldConfig != null) {
            // 假设旧件的配件信息：如果旧配置关联了某个配件（需要额外字段），这里没有。
            // 你可以设计：当需要旧件入库时，要求前端提供 oldPartCode，然后调用配件入库逻辑。
            // 为保持简单，这里只记录日志，不实际操作。
            log.info("旧件入库未实现：需要提供旧件配件编码");
        }

        collaborationService.stampWrite(machine);
        machineRepository.save(machine);

        // 7. 记录替换日志
        ConfigReplaceLog logEntry = new ConfigReplaceLog();
        logEntry.setMachineId(machine.getId());
        logEntry.setMachineConfigId(oldConfigId); // 可能为 null
        logEntry.setItemName(newConfigValue.getValueLabel()); // 这里应该填配置项名称，但request里没有，可以从 configItem 查，稍后修正
        // 修正：获取配置项名称
        String itemName = oldConfig != null ? oldConfig.getItemName() :
                configItemRepository.findById(request.getConfigItemId()).map(ConfigItem::getItemName).orElse("未知");
        logEntry.setItemName(itemName);
        logEntry.setOldValue(oldValue);
        logEntry.setNewValue(newConfigValue.getValueLabel());
        logEntry.setReplaceType(request.getReplaceType());
        logEntry.setNewPartId(request.getNewPartId());
        logEntry.setOperator(request.getOperator());
        logEntry.setRemark(request.getRemark());
        logEntry.setCreatedAt(LocalDateTime.now());

        ConfigReplaceLog savedLog = replaceLogRepository.save(logEntry);
        log.info("配置替换日志记录成功: id={}, machineId={}", savedLog.getId(), machine.getId());
        return savedLog;
    }

    @Override
    @Transactional
    public ConfigReplaceLog performPartReplace(PartReplaceRequestDTO request) {
        MachineInventory machine = machineRepository.findByIdForUpdate(request.getMachineId())
                .orElseThrow(() -> new BusinessException(ResultCode.VEHICLE_NOT_FOUND, "车辆不存在"));
        if (MachineStockStatus.RENTED.code().equals(machine.getStockStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "A rented vehicle cannot be modified");
        }
        boolean workOrderExecution = "MODIFICATION_WORK_ORDER".equalsIgnoreCase(
                request.getStockMovementSourceType());
        if (!workOrderExecution && !MachineStockStatus.canSell(machine.getStockStatus())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Vehicle status does not allow direct part replacement: " + machine.getStockStatus());
        }
        visibilityPolicy.ensureWritable(machine.getIsLocked(), "Vehicle is locked and cannot be modified");
        collaborationService.validateWrite(machine, request.getMachineVersion());

        MachineConfig oldConfig = machineConfigRepository.findByIdForUpdate(request.getMachineConfigId())
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "车辆配置不存在"));
        collaborationService.validateWrite(oldConfig, request.getMachineConfigVersion());
        if (!oldConfig.getMachineId().equals(machine.getId())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "车辆配置不属于该车辆");
        }
        if (oldConfig.getSelectedValue() == null || oldConfig.getSelectedValue().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该配置没有可替换的旧配件");
        }

        PartInventory newPart = partRepository.findByIdForUpdate(request.getNewPartId())
                .orElseThrow(() -> new BusinessException(ResultCode.PART_NOT_FOUND, "新配件不存在"));
        visibilityPolicy.ensureWritable(newPart.getIsLocked(), "Part is locked and cannot be used for replacement");
        collaborationService.validateWrite(newPart, request.getNewPartVersion());
        int quantity = request.getQuantity() == null ? 1 : request.getQuantity();
        if (quantity < 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "替换数量必须大于0");
        }
        Long sourceWarehouseId = stockLedgerService.resolveWarehouseId(
                request.getWarehouseId() == null ? newPart.getWarehouseId() : request.getWarehouseId());
        int warehouseAvailable = stockLedgerService.availableQuantity(
                StockLedgerService.RESOURCE_PART, newPart.getId(), sourceWarehouseId);
        if (warehouseAvailable < quantity) {
            throw new BusinessException(ResultCode.INSUFFICIENT_STOCK,
                    "Part stock is insufficient in the selected warehouse: " + newPart.getPartName());
        }

        String actualType = normalizeType(newPart.getPartCategory());
        List<String> expectedTypes = configItemRepository.findById(oldConfig.getConfigItemId())
                .map(item -> List.of(normalizeType(item.getSubCategory()), normalizeType(item.getItemName()), normalizeType(oldConfig.getItemName())))
                .orElseGet(() -> List.of(normalizeType(oldConfig.getItemName())));
        boolean matched = !actualType.isEmpty() && expectedTypes.stream()
                .filter(value -> value != null && !value.isBlank())
                .anyMatch(value -> value.equals(actualType));
        if (!matched) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "只能替换同类型配件：车辆配置类型=" + oldConfig.getItemName()
                            + "，库存配件类型=" + newPart.getPartCategory());
        }

        String oldValue = oldConfig.getSelectedValue();
        String newValue = partDisplayName(newPart);

        LocalDate businessDate = request.getBusinessDate() == null ? LocalDate.now() : request.getBusinessDate();
        String movementSourceType = firstNonBlank(request.getStockMovementSourceType(), "CONFIG_REPLACE");
        String movementKey = movementKey(request, machine, oldConfig);
        StockLotService.ConsumptionResult fifo = stockLotService.consumeFifo(
                StockLedgerService.RESOURCE_PART,
                newPart.getId(),
                sourceWarehouseId,
                quantity,
                unitCost(newPart),
                warehouseAvailable,
                movementSourceType,
                request.getStockMovementSourceId(),
                request.getStockMovementSourceLineId(),
                businessDate,
                movementKey == null ? null : movementKey + ":FIFO"
        );
        stockLedgerService.recordMovement(
                "OUTBOUND",
                StockLedgerService.RESOURCE_PART,
                newPart.getId(),
                newPart.getPartCode(),
                newPart.getPartName(),
                sourceWarehouseId,
                warehouseAvailable,
                warehouseAvailable - quantity,
                fifo.unitCost(),
                request.getOperator(),
                "Part replacement consumption; machineId=" + machine.getId(),
                movementSourceType,
                request.getStockMovementSourceId(),
                request.getStockMovementSourceLineId(),
                businessDate,
                StockBusinessType.MODIFICATION_USE,
                BigDecimal.ZERO,
                movementKey == null ? null : movementKey + ":MOVEMENT",
                fifo.consumptions().isEmpty() ? null : fifo.consumptions().get(0).getStockLotId()
        );
        newPart.setQuantity(stockLedgerService.totalAvailableQuantity(StockLedgerService.RESOURCE_PART, newPart.getId()));
        collaborationService.stampWrite(newPart);
        newPart = partRepository.save(newPart);

        boolean oldPartStockIn = shouldStockInOldPart(request);
        PartInventory removedPart = null;
        BigDecimal removedPartValue = BigDecimal.ZERO;
        if (oldPartStockIn) {
            removedPart = new PartInventory();
            removedPart.setPartCode(generateRemovedPartCode(machine.getId(), oldConfig.getConfigItemId()));
            removedPart.setPartName(oldValue);
            removedPart.setSpecification(oldValue);
            removedPart.setPartCategory(configPartCategory(oldConfig));
            removedPart.setApplicableModels(machine.getSpecificationModel());
            removedPart.setSource("REMOVED");
            removedPart.setSourceMachineId(machine.getId());
            Long oldWarehouseId = stockLedgerService.resolveWarehouseId(
                    request.getOldPartWarehouseId() == null ? sourceWarehouseId : request.getOldPartWarehouseId());
            BigDecimal oldUnitCost = MoneyValues.zeroIfNullOrNegative(request.getOldPartUnitCost());
            removedPartValue = oldUnitCost.multiply(BigDecimal.valueOf(quantity));
            boolean pendingValuation = request.getOldPartUnitCost() == null
                    || request.getOldPartValuationSource() == null
                    || request.getOldPartValuationSource().isBlank();
            removedPart.setWarehouseId(oldWarehouseId);
            removedPart.setQuantity(0);
            removedPart.setUnit(newPart.getUnit() != null ? newPart.getUnit() : "个");
            removedPart.setPurchasePrice(oldUnitCost);
            removedPart.setLandedUnitCost(oldUnitCost);
            removedPart.setIsLocked(pendingValuation);
            removedPart.setInboundDate(businessDate.atStartOfDay());
            removedPart.setRemarks("Removed part disposition=" + oldPartDisposition(request)
                    + "; condition=" + firstNonBlank(request.getOldPartCondition(), "UNSPECIFIED")
                    + "; valuation=" + firstNonBlank(request.getOldPartValuationSource(), "PENDING")
                    + "; machineId=" + machine.getId()
                    + (request.getRemark() == null || request.getRemark().isBlank() ? "" : "; " + request.getRemark()));
            collaborationService.stampWrite(removedPart);
            removedPart = partRepository.save(removedPart);
            StockLot lot = stockLotService.createReceiptLot(
                    StockLedgerService.RESOURCE_PART,
                    removedPart.getId(),
                    oldWarehouseId,
                    quantity,
                    oldUnitCost,
                    BigDecimal.ZERO,
                    movementSourceType,
                    request.getStockMovementSourceId(),
                    request.getStockMovementSourceLineId(),
                    businessDate,
                    movementKey == null ? null : movementKey + ":OLD-LOT"
            );
            stockLedgerService.recordMovement(
                    "INBOUND",
                    StockLedgerService.RESOURCE_PART,
                    removedPart.getId(),
                    removedPart.getPartCode(),
                    removedPart.getPartName(),
                    oldWarehouseId,
                    0,
                    quantity,
                    oldUnitCost,
                    request.getOperator(),
                    "Removed part receipt; machineId=" + machine.getId(),
                    movementSourceType,
                    request.getStockMovementSourceId(),
                    request.getStockMovementSourceLineId(),
                    businessDate,
                    StockBusinessType.MODIFICATION_RETURN,
                    BigDecimal.ZERO,
                    movementKey == null ? null : movementKey + ":OLD-MOVEMENT",
                    lot.getId()
            );
            removedPart.setQuantity(stockLedgerService.totalAvailableQuantity(StockLedgerService.RESOURCE_PART, removedPart.getId()));
            collaborationService.stampWrite(removedPart);
            removedPart = partRepository.save(removedPart);
        }

        if (!workOrderExecution) {
            BigDecimal capitalization = fifo.totalCost().subtract(removedPartValue);
            if (capitalization.signum() != 0) {
                stockLotService.capitalizeSerializedAssetCost(
                        StockLedgerService.RESOURCE_MACHINE,
                        machine.getId(),
                        machine.getWarehouseId(),
                        capitalization,
                        "CONFIG_REPLACE",
                        machine.getId(),
                        oldConfig.getId(),
                        businessDate,
                        null
                );
                BigDecimal updatedLandedCost =
                        MoneyValues.zeroIfNullOrNegative(machine.getLandedUnitCost()).add(capitalization);
                if (updatedLandedCost.signum() < 0) {
                    throw new BusinessException(ResultCode.CONFLICT,
                            "Part replacement would make the vehicle landed cost negative");
                }
                machine.setLandedUnitCost(updatedLandedCost);
            }
        }

        oldConfig.setSelectedValue(newValue);
        oldConfig.setConfigSource("WAREHOUSE");
        oldConfig.setIsStandard(false);
        oldConfig.setInstalledDate(LocalDateTime.now());
        oldConfig.setRemark(request.getRemark());
        collaborationService.stampWrite(oldConfig);
        machineConfigRepository.save(oldConfig);

        collaborationService.stampWrite(machine);
        machineRepository.save(machine);

        ConfigReplaceLog logEntry = new ConfigReplaceLog();
        logEntry.setMachineId(machine.getId());
        logEntry.setMachineConfigId(oldConfig.getId());
        logEntry.setItemName(oldConfig.getItemName());
        logEntry.setOldValue(oldValue);
        logEntry.setNewValue(newValue);
        logEntry.setReplaceType("PART_REPLACE");
        logEntry.setNewPartId(newPart.getId());
        logEntry.setOperator(request.getOperator());
        String removedSummary = removedPart == null ? "拆下件未入库" : "拆下件已自动入库：" + removedPart.getPartCode();
        logEntry.setRemark(removedSummary
                + (quantity > 1 ? "；数量=" + quantity : "")
                + (request.getRemark() == null || request.getRemark().isBlank() ? "" : "；" + request.getRemark()));
        ConfigReplaceLog savedLog = replaceLogRepository.save(logEntry);

        log.info("整车配件替换成功: machineId={}, configId={}, newPart={}, removedPart={}",
                machine.getId(), oldConfig.getId(), newPart.getPartCode(), removedPart == null ? "-" : removedPart.getPartCode());
        return savedLog;
    }

    @Override
    @Transactional
    public ConfigReplaceLog performPartInstall(VehiclePartInstallRequestDTO request) {
        MachineInventory machine = machineRepository.findByIdForUpdate(request.getMachineId())
                .orElseThrow(() -> new BusinessException(ResultCode.VEHICLE_NOT_FOUND, "车辆不存在"));
        if (MachineStockStatus.RENTED.code().equals(machine.getStockStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "A rented vehicle cannot be modified");
        }
        if (!MachineStockStatus.canSell(machine.getStockStatus())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Vehicle status does not allow part installation: " + machine.getStockStatus());
        }
        visibilityPolicy.ensureWritable(machine.getIsLocked(), "Vehicle is locked and cannot be modified");
        collaborationService.validateWrite(machine, request.getMachineVersion());

        ConfigItem configItem = configItemRepository.findById(request.getConfigItemId())
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "配件分类不存在"));

        PartInventory newPart = partRepository.findByIdForUpdate(request.getNewPartId())
                .orElseThrow(() -> new BusinessException(ResultCode.PART_NOT_FOUND, "仓库配件不存在"));
        visibilityPolicy.ensureWritable(newPart.getIsLocked(), "Part is locked and cannot be installed");
        collaborationService.validateWrite(newPart, request.getNewPartVersion());

        int quantity = request.getQuantity() == null ? 1 : request.getQuantity();
        if (quantity < 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "装车数量必须大于0");
        }
        Long warehouseId = stockLedgerService.resolveWarehouseId(
                request.getWarehouseId() == null ? newPart.getWarehouseId() : request.getWarehouseId());
        int warehouseAvailable = stockLedgerService.availableQuantity(
                StockLedgerService.RESOURCE_PART, newPart.getId(), warehouseId);
        if (warehouseAvailable < quantity) {
            throw new BusinessException(ResultCode.INSUFFICIENT_STOCK,
                    "Part stock is insufficient in the selected warehouse: " + newPart.getPartName());
        }

        String expectedType = normalizeType(configPartCategory(configItem));
        String actualType = normalizeType(newPart.getPartCategory());
        if (!expectedType.isEmpty() && !expectedType.equals(actualType)) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "所选配件不属于该分类: 分类=" + configPartCategory(configItem) + "，配件分类=" + newPart.getPartCategory());
        }

        ConfigValue configValue = resolveConfigValueForInstall(configItem, newPart);
        String installedValue = partDisplayName(newPart);

        LocalDate businessDate = request.getBusinessDate() == null ? LocalDate.now() : request.getBusinessDate();
        StockLotService.ConsumptionResult fifo = stockLotService.consumeFifo(
                StockLedgerService.RESOURCE_PART,
                newPart.getId(),
                warehouseId,
                quantity,
                unitCost(newPart),
                warehouseAvailable,
                "VEHICLE_PART_INSTALL",
                machine.getId(),
                null,
                businessDate,
                null
        );
        stockLedgerService.recordMovement(
                "OUTBOUND",
                StockLedgerService.RESOURCE_PART,
                newPart.getId(),
                newPart.getPartCode(),
                newPart.getPartName(),
                warehouseId,
                warehouseAvailable,
                warehouseAvailable - quantity,
                fifo.unitCost(),
                request.getOperator(),
                "Vehicle part installation; machineId=" + machine.getId(),
                "VEHICLE_PART_INSTALL",
                machine.getId(),
                null,
                businessDate,
                StockBusinessType.MODIFICATION_USE,
                BigDecimal.ZERO,
                null,
                fifo.consumptions().isEmpty() ? null : fifo.consumptions().get(0).getStockLotId()
        );
        newPart.setQuantity(stockLedgerService.totalAvailableQuantity(StockLedgerService.RESOURCE_PART, newPart.getId()));
        collaborationService.stampWrite(newPart);
        newPart = partRepository.save(newPart);

        MachineConfig newConfig = new MachineConfig();
        newConfig.setMachineId(machine.getId());
        newConfig.setConfigItemId(configItem.getId());
        newConfig.setConfigValueId(configValue.getId());
        newConfig.setItemName(configItem.getItemName());
        newConfig.setSelectedValue(installedValue);
        newConfig.setConfigSource("WAREHOUSE");
        newConfig.setIsStandard(false);
        newConfig.setInstalledDate(LocalDateTime.now());
        newConfig.setRemark("从配件仓库装车；配件编码=" + newPart.getPartCode()
                + "；数量=" + quantity
                + (request.getRemark() == null || request.getRemark().isBlank() ? "" : "；" + request.getRemark()));
        collaborationService.stampWrite(newConfig);
        newConfig = machineConfigRepository.save(newConfig);

        stockLotService.capitalizeSerializedAssetCost(
                StockLedgerService.RESOURCE_MACHINE,
                machine.getId(),
                machine.getWarehouseId(),
                fifo.totalCost(),
                "VEHICLE_PART_INSTALL",
                machine.getId(),
                newConfig.getId(),
                businessDate,
                "VEHICLE-PART-INSTALL:" + machine.getId() + ":" + newConfig.getId()
        );
        machine.setLandedUnitCost(MoneyValues.zeroIfNullOrNegative(machine.getLandedUnitCost()).add(fifo.totalCost()));
        collaborationService.stampWrite(machine);
        machineRepository.save(machine);

        ConfigReplaceLog logEntry = new ConfigReplaceLog();
        logEntry.setMachineId(machine.getId());
        logEntry.setMachineConfigId(newConfig.getId());
        logEntry.setItemName(configItem.getItemName());
        logEntry.setOldValue(null);
        logEntry.setNewValue(installedValue);
        logEntry.setReplaceType("PART_INSTALL");
        logEntry.setNewPartId(newPart.getId());
        logEntry.setOperator(request.getOperator());
        logEntry.setRemark("从配件仓库新增装车；配件编码=" + newPart.getPartCode()
                + "；分类=" + configPartCategory(configItem)
                + (quantity > 1 ? "；数量=" + quantity : "")
                + (request.getRemark() == null || request.getRemark().isBlank() ? "" : "；" + request.getRemark()));
        ConfigReplaceLog savedLog = replaceLogRepository.save(logEntry);

        log.info("整车新增配件装车成功: machineId={}, configId={}, part={}, quantity={}",
                machine.getId(), newConfig.getId(), newPart.getPartCode(), quantity);
        return savedLog;
    }

    /**
     * 根据替换类型决定配置来源
     */
    private String determineSource(String replaceType) {
        if ("SWAP".equals(replaceType)) {
            return "WAREHOUSE";
        } else if ("UPGRADE".equals(replaceType)) {
            return "CUSTOM";
        } else {
            return "REPAIR";
        }
    }

    // 需要注入 ConfigItemRepository
    @Autowired
    private ConfigItemRepository configItemRepository;

    private String normalizeType(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String configPartCategory(MachineConfig config) {
        return configItemRepository.findById(config.getConfigItemId())
                .map(item -> firstNonBlank(item.getSubCategory(), item.getItemName(), config.getItemName()))
                .orElse(config.getItemName());
    }

    private String configPartCategory(ConfigItem item) {
        return firstNonBlank(item.getSubCategory(), item.getItemName(), item.getCategory());
    }

    private ConfigValue resolveConfigValueForInstall(ConfigItem item, PartInventory part) {
        List<ConfigValue> values = configValueRepository.findByConfigItemIdOrderBySortOrderAsc(item.getId());
        if (values.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "该配件分类还没有配置字典值");
        }
        String partName = normalizeType(part.getPartName());
        String specification = normalizeType(part.getSpecification());
        String displayName = normalizeType(partDisplayName(part));
        Optional<ConfigValue> matched = values.stream()
                .filter(value -> {
                    String label = normalizeType(value.getValueLabel());
                    return label.equals(partName) || label.equals(specification) || label.equals(displayName);
                })
                .findFirst();
        if (matched.isPresent()) {
            return matched.get();
        }
        ConfigValue defaultValue = configValueRepository.findByConfigItemIdAndIsDefaultTrue(item.getId());
        return defaultValue != null ? defaultValue : values.get(0);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String partDisplayName(PartInventory part) {
        if (part.getSpecification() == null || part.getSpecification().isBlank()) {
            return part.getPartName();
        }
        return part.getPartName() + " / " + part.getSpecification();
    }

    private String generateRemovedPartCode(Long machineId, Long configItemId) {
        return "REMOVED-" + machineId + "-" + configItemId + "-" + System.currentTimeMillis();
    }

    private boolean shouldStockInOldPart(String oldPartAction) {
        return oldPartAction == null
                || oldPartAction.isBlank()
                || PartChangeAction.STOCK_IN.code().equalsIgnoreCase(oldPartAction)
                || "REMOVED".equalsIgnoreCase(oldPartAction)
                || "REUSABLE".equalsIgnoreCase(oldPartAction);
    }

    private boolean shouldStockInOldPart(PartReplaceRequestDTO request) {
        String disposition = oldPartDisposition(request);
        if ("SCRAP".equals(disposition) || "DISCARD".equals(disposition) || "NONE".equals(disposition)) {
            return false;
        }
        return shouldStockInOldPart(request.getOldPartAction());
    }

    private String oldPartDisposition(PartReplaceRequestDTO request) {
        String disposition = request.getOldPartDisposition();
        if (disposition == null || disposition.isBlank()) {
            return PartChangeAction.STOCK_IN.code().equalsIgnoreCase(request.getOldPartAction())
                    ? "QUARANTINE"
                    : "NONE";
        }
        return disposition.trim().toUpperCase();
    }

    private BigDecimal unitCost(PartInventory part) {
        return MoneyValues.firstNonNegativeOrZero(part.getLandedUnitCost(), part.getPurchasePrice());
    }

    private String movementKey(PartReplaceRequestDTO request, MachineInventory machine, MachineConfig config) {
        if (request.getStockMovementSourceId() == null || request.getStockMovementSourceLineId() == null) {
            return null;
        }
        return "MODIFICATION:" + request.getStockMovementSourceType() + ":"
                + request.getStockMovementSourceId() + ":" + request.getStockMovementSourceLineId()
                + ":" + machine.getId() + ":" + config.getId();
    }

    private StockOperationLog savePartStockLog(PartInventory part, String operationType, Integer quantity,
                                  Integer beforeQuantity, Integer afterQuantity, String operator, String remark) {
        return savePartStockLog(part, operationType, quantity, beforeQuantity, afterQuantity,
                operator, remark, "STOCK_LOG", null);
    }

    private StockOperationLog savePartStockLog(PartInventory part, String operationType, Integer quantity,
                                  Integer beforeQuantity, Integer afterQuantity, String operator, String remark,
                                  String movementSourceType, Long movementSourceId) {
        BigDecimal unitCost = MoneyValues.firstNonNegativeOrZero(part.getLandedUnitCost(), part.getPurchasePrice());
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
                movementSourceType,
                movementSourceId,
                ("INBOUND".equals(operationType) ? "Part inbound " : "Part outbound ") + quantity
        ));
    }

}
