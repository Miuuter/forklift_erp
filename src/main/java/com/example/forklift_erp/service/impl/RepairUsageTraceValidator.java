package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.entity.RepairPartUsage;
import com.example.forklift_erp.entity.RepairRecord;
import com.example.forklift_erp.entity.StockLotConsumption;
import com.example.forklift_erp.repository.StockLotConsumptionRepository;
import com.example.forklift_erp.service.StockLedgerService;

import java.util.List;
import java.util.Objects;

final class RepairUsageTraceValidator {

    private RepairUsageTraceValidator() {
    }

    static boolean hasExactFifoTrace(
            StockLotConsumptionRepository repository,
            RepairRecord record,
            RepairPartUsage usage,
            String sourceType
    ) {
        if (record.getId() == null
                || usage.getId() == null
                || usage.getPartId() == null
                || usage.getWarehouseId() == null
                || usage.getQuantity() == null
                || usage.getQuantity() <= 0) {
            return false;
        }
        List<StockLotConsumption> originals = repository
                .findBySourceTypeAndSourceIdAndSourceLineIdOrderByIdAsc(
                        sourceType, record.getId(), usage.getId());
        boolean exactIdentity = !originals.isEmpty() && originals.stream().allMatch(consumption ->
                consumption.getReversalOfConsumptionId() == null
                        && StockLedgerService.RESOURCE_PART.equals(consumption.getResourceType())
                        && Objects.equals(consumption.getResourceId(), usage.getPartId())
                        && Objects.equals(consumption.getWarehouseId(), usage.getWarehouseId())
                        && consumption.getQuantity() != null
                        && consumption.getQuantity() > 0);
        if (!exactIdentity) {
            return false;
        }
        int consumedQuantity = originals.stream()
                .map(StockLotConsumption::getQuantity)
                .mapToInt(Integer::intValue)
                .sum();
        if (consumedQuantity != usage.getQuantity()) {
            return false;
        }
        return usage.getStockLotConsumptionId() == null
                || originals.stream().anyMatch(consumption ->
                Objects.equals(consumption.getId(), usage.getStockLotConsumptionId()));
    }
}
