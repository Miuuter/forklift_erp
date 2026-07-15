package com.example.forklift_erp.service;

import com.example.forklift_erp.dto.PartInventoryVO;
import com.example.forklift_erp.dto.PartWarehouseBalanceVO;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.StockBalance;
import com.example.forklift_erp.entity.Warehouse;
import com.example.forklift_erp.repository.StockBalanceRepository;
import com.example.forklift_erp.repository.WarehouseRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class PartInventoryViewAssembler {
    private final StockBalanceRepository stockBalanceRepository;
    private final WarehouseRepository warehouseRepository;

    public PartInventoryViewAssembler(
            StockBalanceRepository stockBalanceRepository,
            WarehouseRepository warehouseRepository
    ) {
        this.stockBalanceRepository = stockBalanceRepository;
        this.warehouseRepository = warehouseRepository;
    }

    @Transactional(readOnly = true)
    public PartInventoryVO toVO(PartInventory part) {
        return toVOs(List.of(part)).getFirst();
    }

    @Transactional(readOnly = true)
    public List<PartInventoryVO> toVOs(List<PartInventory> parts) {
        if (parts == null || parts.isEmpty()) {
            return List.of();
        }
        List<Long> partIds = parts.stream()
                .map(PartInventory::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<StockBalance> balances = partIds.isEmpty()
                ? List.of()
                : stockBalanceRepository.findByResourceTypeAndResourceIdIn(
                        StockLedgerService.RESOURCE_PART, partIds);
        Map<Long, List<StockBalance>> balancesByPart = balances.stream()
                .collect(Collectors.groupingBy(StockBalance::getResourceId));
        Map<Long, Warehouse> warehouses = warehouseRepository.findAllById(
                        balances.stream().map(StockBalance::getWarehouseId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Warehouse::getId, Function.identity()));

        return parts.stream()
                .map(part -> assemble(part, balancesByPart.getOrDefault(part.getId(), List.of()), warehouses))
                .toList();
    }

    private PartInventoryVO assemble(
            PartInventory part,
            List<StockBalance> balances,
            Map<Long, Warehouse> warehouses
    ) {
        PartInventoryVO vo = PartInventoryVO.fromEntity(part);
        List<PartWarehouseBalanceVO> warehouseBalances = balances.stream()
                .map(balance -> warehouseBalance(balance, warehouses.get(balance.getWarehouseId())))
                .sorted(Comparator.comparing(
                        PartWarehouseBalanceVO::getWarehouseName,
                        Comparator.nullsLast(String::compareToIgnoreCase)
                ))
                .toList();
        vo.setWarehouseBalances(warehouseBalances);
        if (!warehouseBalances.isEmpty()) {
            vo.setQuantity(warehouseBalances.stream()
                    .map(PartWarehouseBalanceVO::getAvailableQuantity)
                    .filter(Objects::nonNull)
                    .mapToInt(Integer::intValue)
                    .sum());
        }
        return vo;
    }

    private PartWarehouseBalanceVO warehouseBalance(StockBalance balance, Warehouse warehouse) {
        PartWarehouseBalanceVO vo = new PartWarehouseBalanceVO();
        vo.setWarehouseId(balance.getWarehouseId());
        vo.setWarehouseCode(warehouse == null ? null : warehouse.getWarehouseCode());
        vo.setWarehouseName(warehouse == null ? null : warehouse.getWarehouseName());
        vo.setAvailableQuantity(value(balance.getAvailableQuantity()));
        vo.setReservedQuantity(value(balance.getReservedQuantity()));
        vo.setLockedQuantity(value(balance.getLockedQuantity()));
        return vo;
    }

    private int value(Integer quantity) {
        return quantity == null ? 0 : quantity;
    }
}
