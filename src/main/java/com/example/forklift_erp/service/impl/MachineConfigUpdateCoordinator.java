package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.dto.MachineConfigVO;
import com.example.forklift_erp.entity.ConfigItem;
import com.example.forklift_erp.entity.ConfigValue;
import com.example.forklift_erp.entity.MachineConfig;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.ConfigItemRepository;
import com.example.forklift_erp.repository.ConfigReplaceLogRepository;
import com.example.forklift_erp.repository.ConfigValueRepository;
import com.example.forklift_erp.repository.MachineConfigRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderLineRepository;
import com.example.forklift_erp.service.MachineConfigService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class MachineConfigUpdateCoordinator {
    private final MachineConfigRepository machineConfigRepository;
    private final ConfigItemRepository configItemRepository;
    private final ConfigValueRepository configValueRepository;
    private final ModificationWorkOrderLineRepository modificationWorkOrderLineRepository;
    private final ConfigReplaceLogRepository configReplaceLogRepository;
    private final MachineConfigService machineConfigService;

    public MachineConfigUpdateCoordinator(
            MachineConfigRepository machineConfigRepository,
            ConfigItemRepository configItemRepository,
            ConfigValueRepository configValueRepository,
            ModificationWorkOrderLineRepository modificationWorkOrderLineRepository,
            ConfigReplaceLogRepository configReplaceLogRepository,
            MachineConfigService machineConfigService
    ) {
        this.machineConfigRepository = machineConfigRepository;
        this.configItemRepository = configItemRepository;
        this.configValueRepository = configValueRepository;
        this.modificationWorkOrderLineRepository = modificationWorkOrderLineRepository;
        this.configReplaceLogRepository = configReplaceLogRepository;
        this.machineConfigService = machineConfigService;
    }

    List<MachineConfig> apply(Long machineId, List<MachineConfigVO> configVOs) {
        List<MachineConfig> existingConfigs = machineConfigRepository.findByMachineIdForUpdate(machineId);
        Map<Long, MachineConfig> existingByItem = indexExistingConfigs(existingConfigs);
        List<MachineConfigVO> requestedConfigs = validateRequestedConfigs(configVOs);
        Set<Long> itemIds = requestedConfigs.stream()
                .map(MachineConfigVO::getConfigItemId)
                .collect(Collectors.toSet());
        Set<Long> valueIds = requestedConfigs.stream()
                .map(MachineConfigVO::getConfigValueId)
                .collect(Collectors.toSet());
        Map<Long, ConfigItem> items = lockConfigItems(itemIds);
        Map<Long, ConfigValue> values = lockConfigValues(valueIds);
        List<MachineConfig> configs = requestedConfigs.stream()
                .map(vo -> merge(machineId, vo, items, values, existingByItem))
                .collect(Collectors.toList());

        removeObsoleteConfigs(existingByItem);
        return configs.isEmpty() ? List.of() : machineConfigService.saveAll(configs);
    }

    private Map<Long, MachineConfig> indexExistingConfigs(List<MachineConfig> existingConfigs) {
        Map<Long, MachineConfig> existingByItem = new LinkedHashMap<>();
        for (MachineConfig existing : existingConfigs) {
            if (existingByItem.put(existing.getConfigItemId(), existing) != null) {
                throw new BusinessException(ResultCode.CONFLICT,
                        "Vehicle has duplicate configuration items; repair the data before editing");
            }
        }
        return existingByItem;
    }

    private List<MachineConfigVO> validateRequestedConfigs(List<MachineConfigVO> configVOs) {
        List<MachineConfigVO> requestedConfigs = configVOs == null ? List.of() : configVOs;
        if (requestedConfigs.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Machine configuration cannot contain null rows");
        }
        Set<Long> itemIds = requestedConfigs.stream()
                .map(MachineConfigVO::getConfigItemId)
                .collect(Collectors.toSet());
        if (itemIds.contains(null) || itemIds.size() != requestedConfigs.size()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Each machine configuration item must be selected exactly once");
        }
        if (requestedConfigs.stream().map(MachineConfigVO::getConfigValueId).anyMatch(Objects::isNull)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Machine configuration value is required");
        }
        return requestedConfigs;
    }

    private Map<Long, ConfigItem> lockConfigItems(Set<Long> itemIds) {
        Map<Long, ConfigItem> items = new LinkedHashMap<>();
        itemIds.stream().sorted().forEach(itemId -> items.put(itemId,
                configItemRepository.findByIdForUpdate(itemId)
                        .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Config item not found"))));
        return items;
    }

    private Map<Long, ConfigValue> lockConfigValues(Set<Long> valueIds) {
        Map<Long, ConfigValue> values = new LinkedHashMap<>();
        valueIds.stream().sorted().forEach(valueId -> values.put(valueId,
                configValueRepository.findByIdForUpdate(valueId)
                        .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Config value not found"))));
        return values;
    }

    private MachineConfig merge(
            Long machineId,
            MachineConfigVO vo,
            Map<Long, ConfigItem> items,
            Map<Long, ConfigValue> values,
            Map<Long, MachineConfig> existingByItem
    ) {
        ConfigItem item = items.get(vo.getConfigItemId());
        ConfigValue value = values.get(vo.getConfigValueId());
        if (!Objects.equals(value.getConfigItemId(), item.getId())) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Config value does not belong to selected config item");
        }
        MachineConfig config = existingByItem.remove(item.getId());
        if (config == null) {
            if (vo.getId() != null) {
                throw invalidConfigIdentity();
            }
            config = new MachineConfig();
            config.setMachineId(machineId);
            config.setConfigItemId(item.getId());
        } else if (vo.getId() != null && !Objects.equals(vo.getId(), config.getId())) {
            throw invalidConfigIdentity();
        }
        config.setConfigValueId(value.getId());
        config.setItemName(item.getItemName());
        config.setSelectedValue(value.getValueLabel());
        config.setIsStandard(vo.getIsStandard());
        config.setConfigSource(vo.getConfigSource() != null ? vo.getConfigSource() : "FACTORY");
        if (vo.getInstalledDate() != null || config.getInstalledDate() == null) {
            config.setInstalledDate(vo.getInstalledDate() != null ? vo.getInstalledDate() : LocalDateTime.now());
        }
        config.setRemark(vo.getRemark());
        return config;
    }

    private BusinessException invalidConfigIdentity() {
        return new BusinessException(ResultCode.PARAM_ERROR,
                "Configuration ID does not belong to the selected item");
    }

    private void removeObsoleteConfigs(Map<Long, MachineConfig> existingByItem) {
        List<MachineConfig> removedConfigs = new ArrayList<>(existingByItem.values());
        for (MachineConfig removed : removedConfigs) {
            if (modificationWorkOrderLineRepository.existsByMachineConfigId(removed.getId())) {
                throw new BusinessException(ResultCode.CONFLICT,
                        "Configuration has modification history and cannot be removed: "
                                + removed.getItemName());
            }
            if (configReplaceLogRepository.existsByMachineConfigId(removed.getId())) {
                throw new BusinessException(ResultCode.CONFLICT,
                        "Configuration has replacement history and cannot be removed: "
                                + removed.getItemName());
            }
        }
        if (!removedConfigs.isEmpty()) {
            machineConfigRepository.deleteAll(removedConfigs);
            machineConfigRepository.flush();
        }
    }
}
