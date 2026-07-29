package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.entity.ConfigItem;
import com.example.forklift_erp.entity.ConfigValue;
import com.example.forklift_erp.entity.MachineConfig;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.ConfigItemRepository;
import com.example.forklift_erp.repository.ConfigValueRepository;
import com.example.forklift_erp.repository.MachineConfigRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderLineRepository;
import com.example.forklift_erp.repository.PurchaseOrderRepository;
import com.example.forklift_erp.repository.VehicleConfigValueRepository;
import com.example.forklift_erp.service.CollaborationService;
import com.example.forklift_erp.service.ConfigItemService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
public class ConfigItemServiceImpl implements ConfigItemService {

    private final ConfigItemRepository configItemRepository;
    private final ConfigValueRepository configValueRepository;
    private final CollaborationService collaborationService;
    private final MachineConfigRepository machineConfigRepository;
    private final VehicleConfigValueRepository vehicleConfigValueRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final ModificationWorkOrderLineRepository modificationWorkOrderLineRepository;

    public ConfigItemServiceImpl(
            ConfigItemRepository configItemRepository,
            ConfigValueRepository configValueRepository,
            CollaborationService collaborationService,
            MachineConfigRepository machineConfigRepository,
            VehicleConfigValueRepository vehicleConfigValueRepository,
            PurchaseOrderRepository purchaseOrderRepository,
            ModificationWorkOrderLineRepository modificationWorkOrderLineRepository
    ) {
        this.configItemRepository = configItemRepository;
        this.configValueRepository = configValueRepository;
        this.collaborationService = collaborationService;
        this.machineConfigRepository = machineConfigRepository;
        this.vehicleConfigValueRepository = vehicleConfigValueRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.modificationWorkOrderLineRepository = modificationWorkOrderLineRepository;
    }

    @Override
    public List<ConfigItem> findAll() {
        return configItemRepository.findAllByOrderBySortOrderAsc();
    }

    @Override
    public Optional<ConfigItem> findById(Long id) {
        return configItemRepository.findById(id);
    }

    @Override
    public Optional<ConfigItem> findByIdForUpdate(Long id) {
        return configItemRepository.findByIdForUpdate(id);
    }

    @Override
    @Transactional
    public ConfigItem save(ConfigItem configItem) {
        normalizeConfigItem(configItem);
        if (configItem.getId() != null) {
            ConfigItem existing = configItemRepository.findByIdForUpdate(configItem.getId())
                    .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "配置项不存在"));
            collaborationService.validateWrite(existing, configItem.getVersion());
            ensureUniqueItemCode(configItem.getItemCode(), configItem.getId());
            existing.setCategory(configItem.getCategory());
            existing.setSubCategory(configItem.getSubCategory());
            existing.setItemName(configItem.getItemName());
            existing.setItemCode(configItem.getItemCode());
            existing.setInputType(configItem.getInputType());
            existing.setUnit(configItem.getUnit());
            existing.setIsRequired(configItem.getIsRequired());
            existing.setSortOrder(configItem.getSortOrder());
            collaborationService.stampWrite(existing);
            return configItemRepository.saveAndFlush(existing);
        }
        ensureUniqueItemCode(configItem.getItemCode(), null);
        collaborationService.stampWrite(configItem);
        return configItemRepository.saveAndFlush(configItem);
    }

    private void normalizeConfigItem(ConfigItem configItem) {
        configItem.setCategory(trimToNull(configItem.getCategory()));
        configItem.setSubCategory(trimToNull(configItem.getSubCategory()));
        configItem.setItemName(trimToNull(configItem.getItemName()));
        configItem.setItemCode(trimToNull(configItem.getItemCode()));
        configItem.setInputType(trimToNull(configItem.getInputType()));
        configItem.setUnit(trimToNull(configItem.getUnit()));
        if (configItem.getItemCode() == null) {
            configItem.setItemCode(nextItemCode());
        }
        if (configItem.getInputType() == null) {
            configItem.setInputType("SELECT");
        }
        if (configItem.getIsRequired() == null) {
            configItem.setIsRequired(true);
        }
        if (configItem.getSortOrder() == null) {
            configItem.setSortOrder(0);
        }
    }

    private String nextItemCode() {
        int max = configItemRepository.findAll().stream()
                .map(ConfigItem::getItemCode)
                .map(this::parseAutoCodeNumber)
                .filter(value -> value >= 0)
                .max(Integer::compareTo)
                .orElse(0);
        return String.format("CFG-%04d", max + 1);
    }

    private int parseAutoCodeNumber(String itemCode) {
        if (itemCode == null || !itemCode.matches("^CFG-\\d+$")) {
            return -1;
        }
        try {
            return Integer.parseInt(itemCode.substring(4));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private void ensureUniqueItemCode(String itemCode, Long currentId) {
        configItemRepository.findByItemCode(itemCode)
                .filter(existing -> currentId == null || !existing.getId().equals(currentId))
                .ifPresent(existing -> {
                    throw new BusinessException(ResultCode.DATA_DUPLICATE, "配置项编码已存在: " + itemCode);
                });
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 删除配置项
     * 前提：没有任何车辆正在使用该配置项
     */
    @Override
    @Transactional
    public void deleteById(Long id, Long expectedVersion) {
        // 1. 检查配置项是否存在
        ConfigItem configItem = configItemRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "配置项不存在，id=" + id));
        collaborationService.validateWrite(configItem, expectedVersion);

        // 2. 引用检查：是否有车辆使用了这个配置项
        List<MachineConfig> configs = machineConfigRepository.findByConfigItemId(id);
        if (!configs.isEmpty() || vehicleConfigValueRepository.existsByConfigItemId(id)) {
            log.warn("删除配置项被阻止：id={}, 名称={}, 被 {} 辆车使用", id, configItem.getItemName(), configs.size());
            throw new BusinessException(ResultCode.CONFIG_IN_USE,
                    "配置项【" + configItem.getItemName() + "】正在被车辆使用，无法删除");
        }

        List<ConfigValue> values = configValueRepository.findByConfigItemIdOrderBySortOrderAsc(id);
        boolean referencedByBusinessDocument = purchaseOrderRepository.existsByConfigItemId(id)
                || modificationWorkOrderLineRepository.existsByConfigItemId(id)
                || values.stream().anyMatch(value ->
                        purchaseOrderRepository.existsByConfigValueId(value.getId())
                                || modificationWorkOrderLineRepository.existsByNewConfigValueId(value.getId()));
        if (referencedByBusinessDocument) {
            throw new BusinessException(ResultCode.CONFIG_IN_USE,
                    "Config item is referenced by purchase or modification history and cannot be deleted");
        }

        // 3. 安全删除：先删该配置项下的所有可选值，再删配置项本身
        configValueRepository.deleteByConfigItemId(id);
        configItemRepository.deleteById(id);
        log.info("配置项删除成功: id={}, 名称={}", id, configItem.getItemName());
    }

    @Override
    public List<ConfigItem> findByCategory(String category) {
        return configItemRepository.findByCategoryOrderBySortOrderAsc(category);
    }

    @Override
    public List<ConfigValue> getValuesByItemId(Long itemId) {
        return configValueRepository.findByConfigItemIdOrderBySortOrderAsc(itemId);
    }

    @Override
    public Map<Long, List<ConfigValue>> getValuesByItemIds(List<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return Map.of();
        }
        List<Long> distinctIds = itemIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (distinctIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<ConfigValue>> grouped = new LinkedHashMap<>();
        distinctIds.forEach(id -> grouped.put(id, new java.util.ArrayList<>()));
        configValueRepository.findByConfigItemIdInOrderByConfigItemIdAscSortOrderAsc(distinctIds)
                .forEach(value -> grouped
                        .computeIfAbsent(value.getConfigItemId(), id -> new java.util.ArrayList<>())
                        .add(value));
        return grouped;
    }

    @Override
    @Transactional
    public ConfigValue saveValue(ConfigValue requested) {
        if (requested == null || requested.getConfigItemId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Config item is required");
        }

        Long configItemId = requested.getConfigItemId();
        if (requested.getId() != null) {
            Long currentOwnerId = configValueRepository.findConfigItemIdById(requested.getId())
                    .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Config value not found"));
            if (!Objects.equals(currentOwnerId, configItemId)) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "A config value cannot be moved to another config item");
            }
        }

        // The parent row is the serialization point for every value mutation of
        // one config item. This makes competing default switches deterministic.
        configItemRepository.findByIdForUpdate(configItemId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Config item not found"));

        List<ConfigValue> siblings = configValueRepository.findByConfigItemIdForUpdate(configItemId);
        ConfigValue current = null;
        if (requested.getId() != null) {
            current = siblings.stream()
                    .filter(value -> Objects.equals(value.getId(), requested.getId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Config value not found"));
            if (!Objects.equals(current.getConfigItemId(), configItemId)) {
                throw new BusinessException(ResultCode.CONFLICT,
                        "Config value ownership changed; reload and retry");
            }
            collaborationService.validateWrite(current, requested.getVersion());
        }

        List<ConfigValue> defaults = siblings.stream()
                .filter(value -> Boolean.TRUE.equals(value.getIsDefault()))
                .toList();
        if (defaults.size() > 1) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Config item has multiple default values; repair the data before editing");
        }
        ConfigValue previousDefault = defaults.isEmpty() ? null : defaults.get(0);
        boolean requestedDefault = Boolean.TRUE.equals(requested.getIsDefault());

        if (current != null
                && Boolean.TRUE.equals(current.getIsDefault())
                && !requestedDefault) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "The current default cannot be cleared without assigning another default");
        }

        // A non-empty value set always keeps one default. The first value also
        // becomes the default when old data did not have one.
        boolean makeDefault = requestedDefault || previousDefault == null;
        if (makeDefault
                && previousDefault != null
                && !Objects.equals(previousDefault.getId(), requested.getId())) {
            previousDefault.setIsDefault(false);
            collaborationService.stampWrite(previousDefault);
            // V49's unique generated guard sees statement order. Flush the old
            // default demotion before inserting/promoting the replacement.
            configValueRepository.saveAndFlush(previousDefault);
        }

        ConfigValue target;
        if (current == null) {
            target = requested;
        } else {
            target = current;
            target.setValueLabel(requested.getValueLabel());
            target.setValueCode(requested.getValueCode());
            target.setSortOrder(requested.getSortOrder());
            target.setRemark(requested.getRemark());
        }
        target.setIsDefault(makeDefault);
        collaborationService.stampWrite(target);
        return configValueRepository.saveAndFlush(target);
    }

    /**
     * 删除配置值
     * 前提：没有任何车辆正在使用该配置值
     */
    @Override
    @Transactional
    public void deleteValueById(Long valueId, Long expectedVersion) {
        Long configItemId = configValueRepository.findConfigItemIdById(valueId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Config value not found"));
        configItemRepository.findByIdForUpdate(configItemId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Config item not found"));
        List<ConfigValue> siblings = configValueRepository.findByConfigItemIdForUpdate(configItemId);
        ConfigValue configValue = siblings.stream()
                .filter(value -> Objects.equals(value.getId(), valueId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Config value not found"));
        if (!Objects.equals(configValue.getConfigItemId(), configItemId)) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Config value ownership changed; reload and retry");
        }
        collaborationService.validateWrite(configValue, expectedVersion);

        List<MachineConfig> configs = machineConfigRepository.findByConfigValueId(valueId);
        if (!configs.isEmpty() || vehicleConfigValueRepository.existsByConfigValueId(valueId)) {
            log.warn("删除配置值被阻止：id={}, 值={}, 被 {} 辆车使用", valueId, configValue.getValueLabel(), configs.size());
            throw new BusinessException(ResultCode.CONFIG_IN_USE,
                    "配置值【" + configValue.getValueLabel() + "】正在被车辆使用，无法删除");
        }
        if (purchaseOrderRepository.existsByConfigValueId(valueId)
                || modificationWorkOrderLineRepository.existsByNewConfigValueId(valueId)) {
            throw new BusinessException(ResultCode.CONFIG_IN_USE,
                    "Config value is referenced by purchase or modification history and cannot be deleted");
        }
        if (Boolean.TRUE.equals(configValue.getIsDefault()) && siblings.size() > 1) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Assign another default before deleting the current default");
        }

        configValueRepository.delete(configValue);
        log.info("配置值删除成功: id={}, 值={}", valueId, configValue.getValueLabel());
    }
}
