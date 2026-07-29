package com.example.forklift_erp.config;

import com.example.forklift_erp.entity.ConfigItem;
import com.example.forklift_erp.entity.ConfigValue;
import com.example.forklift_erp.entity.Warehouse;
import com.example.forklift_erp.repository.ConfigItemRepository;
import com.example.forklift_erp.repository.ConfigValueRepository;
import com.example.forklift_erp.repository.WarehouseRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Serializes demo-data default reconciliation so V49's unique guards remain
 * valid after every SQL statement, including repeated local seed runs.
 */
final class DemoDefaultIntegrity {

    private DemoDefaultIntegrity() {
    }

    static List<ConfigValue> lockConfigValues(
            ConfigItemRepository itemRepository,
            ConfigValueRepository valueRepository,
            ConfigItem item,
            String itemCode,
            List<String> desiredDefaults) {
        if (desiredDefaults.size() != 1) {
            throw new IllegalStateException(
                    "Demo config item must declare exactly one default: " + itemCode);
        }
        itemRepository.findByIdForUpdate(item.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Seeded config item disappeared: " + itemCode));
        List<ConfigValue> existingValues = valueRepository.findByConfigItemIdForUpdate(item.getId());
        String desiredDefaultCode = desiredDefaults.get(0);
        List<ConfigValue> retiredDefaults = existingValues.stream()
                .filter(value -> Boolean.TRUE.equals(value.getIsDefault()))
                .filter(value -> !Objects.equals(value.getValueCode(), desiredDefaultCode))
                .peek(value -> value.setIsDefault(false))
                .toList();
        if (!retiredDefaults.isEmpty()) {
            valueRepository.saveAllAndFlush(retiredDefaults);
        }
        return existingValues;
    }

    static Map<String, Warehouse> lockWarehouses(
            WarehouseRepository repository,
            String canonicalDefaultCode) {
        List<Warehouse> lockedWarehouses = repository.findAllForUpdate();
        Map<String, Warehouse> existingByCode = new LinkedHashMap<>();
        lockedWarehouses.forEach(warehouse ->
                existingByCode.put(warehouse.getWarehouseCode(), warehouse));
        List<Warehouse> retiredDefaults = lockedWarehouses.stream()
                .filter(warehouse -> Boolean.TRUE.equals(warehouse.getDefaultWarehouse()))
                .filter(warehouse -> !Objects.equals(
                        canonicalDefaultCode, warehouse.getWarehouseCode()))
                .peek(warehouse -> warehouse.setDefaultWarehouse(false))
                .toList();
        if (!retiredDefaults.isEmpty()) {
            repository.saveAllAndFlush(retiredDefaults);
        }
        return existingByCode;
    }
}
