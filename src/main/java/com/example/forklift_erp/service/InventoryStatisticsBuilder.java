package com.example.forklift_erp.service;

import com.example.forklift_erp.dto.StatisticsDashboardVO;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.StockBalance;
import com.example.forklift_erp.entity.StockLot;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.StockBalanceRepository;
import com.example.forklift_erp.repository.StockLotRepository;
import com.example.forklift_erp.util.MoneyValues;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class InventoryStatisticsBuilder {
    private static final int LOW_PART_THRESHOLD = 5;

    private final MachineInventoryRepository machineInventoryRepository;
    private final PartInventoryRepository partInventoryRepository;
    private final StockBalanceRepository stockBalanceRepository;
    private final StockLotRepository stockLotRepository;

    public InventoryStatisticsBuilder(
            MachineInventoryRepository machineInventoryRepository,
            PartInventoryRepository partInventoryRepository,
            StockBalanceRepository stockBalanceRepository,
            StockLotRepository stockLotRepository
    ) {
        this.machineInventoryRepository = machineInventoryRepository;
        this.partInventoryRepository = partInventoryRepository;
        this.stockBalanceRepository = stockBalanceRepository;
        this.stockLotRepository = stockLotRepository;
    }

    List<StatisticsDashboardVO.StockValueRow> stockValues() {
        Map<ResourceKey, Integer> physicalQuantities = physicalQuantities();
        Map<ResourceKey, LotValue> lotValues = lotValues();

        StatisticsDashboardVO.StockValueRow machineRow = new StatisticsDashboardVO.StockValueRow();
        machineRow.setResourceType("MACHINE");
        machineRow.setLabel("Vehicle inventory");
        long machineItems = 0;
        long machineQuantity = 0;
        BigDecimal machineCost = BigDecimal.ZERO;
        BigDecimal machineSettlement = BigDecimal.ZERO;
        for (MachineInventory machine : machineInventoryRepository.findAll()) {
            if (Boolean.TRUE.equals(machine.getModelOnly())) {
                continue;
            }
            machineItems++;
            ResourceKey key = new ResourceKey(StockLedgerService.RESOURCE_MACHINE, machine.getId());
            int physical = physicalQuantities.getOrDefault(key, quantity(machine.getInventoryCount()));
            LotValue lots = lotValues.getOrDefault(key, LotValue.empty());
            machineQuantity += physical;
            machineCost = machineCost.add(inventoryCost(
                    physical,
                    lots,
                    MoneyValues.firstNonNegativeOrZero(machine.getLandedUnitCost(), machine.getPurchasePrice())
            ));
            machineSettlement = machineSettlement.add(
                    MoneyValues.firstNonNegativeOrZero(machine.getSalePrice(), machine.getSettlementPrice())
                            .multiply(BigDecimal.valueOf(physical)));
        }
        applyStockValue(machineRow, machineItems, machineQuantity, machineCost, machineSettlement);

        StatisticsDashboardVO.StockValueRow partRow = new StatisticsDashboardVO.StockValueRow();
        partRow.setResourceType("PART");
        partRow.setLabel("Part inventory");
        long partItems = 0;
        long partQuantity = 0;
        BigDecimal partCost = BigDecimal.ZERO;
        BigDecimal partSettlement = BigDecimal.ZERO;
        for (PartInventory part : partInventoryRepository.findAll()) {
            partItems++;
            ResourceKey key = new ResourceKey(StockLedgerService.RESOURCE_PART, part.getId());
            int physical = physicalQuantities.getOrDefault(key, quantity(part.getQuantity()));
            LotValue lots = lotValues.getOrDefault(key, LotValue.empty());
            partQuantity += physical;
            partCost = partCost.add(inventoryCost(
                    physical,
                    lots,
                    MoneyValues.firstNonNegativeOrZero(part.getLandedUnitCost(), part.getPurchasePrice())
            ));
            partSettlement = partSettlement.add(
                    MoneyValues.firstNonNegativeOrZero(part.getSalePrice(), part.getSettlementPrice())
                            .multiply(BigDecimal.valueOf(physical)));
        }
        applyStockValue(partRow, partItems, partQuantity, partCost, partSettlement);
        return List.of(machineRow, partRow);
    }

    List<StatisticsDashboardVO.LowStockRow> lowStocks() {
        List<StatisticsDashboardVO.LowStockRow> rows = new java.util.ArrayList<>();
        for (PartInventory part : partInventoryRepository.findLowStock(LOW_PART_THRESHOLD, PageRequest.of(0, 10))) {
            StatisticsDashboardVO.LowStockRow row = new StatisticsDashboardVO.LowStockRow();
            row.setResourceType("PART");
            row.setResourceCode(part.getPartCode());
            row.setResourceName(part.getPartName());
            row.setQuantity(quantity(part.getQuantity()));
            row.setUnit(part.getUnit());
            row.setThreshold(LOW_PART_THRESHOLD);
            rows.add(row);
        }
        return rows.stream()
                .sorted(Comparator.comparing(StatisticsDashboardVO.LowStockRow::getQuantity))
                .limit(10)
                .toList();
    }

    private void applyStockValue(
            StatisticsDashboardVO.StockValueRow row,
            long itemCount,
            long stockQuantity,
            BigDecimal costValue,
            BigDecimal settlementValue
    ) {
        row.setItemCount(toInt(itemCount));
        row.setStockQuantity(toInt(stockQuantity));
        row.setCostValue(amount(costValue));
        row.setSettlementValue(amount(settlementValue));
        row.setRetailValueForCompatibility(amount(settlementValue));
    }

    private int quantity(Integer value) {
        return value == null ? 0 : value;
    }

    private BigDecimal amount(BigDecimal value) {
        return MoneyValues.zeroIfNullOrNegative(value);
    }

    private int toInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private Map<ResourceKey, Integer> physicalQuantities() {
        Map<ResourceKey, Integer> result = new HashMap<>();
        for (StockBalance balance : stockBalanceRepository.findAll()) {
            ResourceKey key = new ResourceKey(balance.getResourceType(), balance.getResourceId());
            int physical = quantity(balance.getAvailableQuantity())
                    + quantity(balance.getReservedQuantity())
                    + quantity(balance.getLockedQuantity());
            result.merge(key, physical, Integer::sum);
        }
        return result;
    }

    private Map<ResourceKey, LotValue> lotValues() {
        Map<ResourceKey, LotValue> result = new LinkedHashMap<>();
        for (StockLot lot : stockLotRepository.findAll()) {
            if (StockLot.STATUS_REVERSED.equals(lot.getStatus())) {
                continue;
            }
            int remaining = quantity(lot.getRemainingQuantity());
            if (remaining <= 0) {
                continue;
            }
            ResourceKey key = new ResourceKey(lot.getResourceType(), lot.getResourceId());
            LotValue existing = result.getOrDefault(key, LotValue.empty());
            result.put(key, new LotValue(
                    existing.quantity() + remaining,
                    existing.cost().add(amount(lot.getUnitCost()).multiply(BigDecimal.valueOf(remaining)))
            ));
        }
        return result;
    }

    private BigDecimal inventoryCost(int physicalQuantity, LotValue lots, BigDecimal fallbackUnitCost) {
        int missingQuantity = Math.max(0, physicalQuantity - lots.quantity());
        return lots.cost().add(amount(fallbackUnitCost).multiply(BigDecimal.valueOf(missingQuantity)));
    }

    private record ResourceKey(String resourceType, Long resourceId) {
    }

    private record LotValue(int quantity, BigDecimal cost) {
        static LotValue empty() {
            return new LotValue(0, BigDecimal.ZERO);
        }
    }
}
