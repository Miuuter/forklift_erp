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
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InventoryStatisticsBuilderTests {

    private final MachineInventoryRepository machineInventoryRepository = mock(MachineInventoryRepository.class);
    private final PartInventoryRepository partInventoryRepository = mock(PartInventoryRepository.class);
    private final StockBalanceRepository stockBalanceRepository = mock(StockBalanceRepository.class);
    private final StockLotRepository stockLotRepository = mock(StockLotRepository.class);
    private final InventoryStatisticsBuilder builder = new InventoryStatisticsBuilder(
            machineInventoryRepository,
            partInventoryRepository,
            stockBalanceRepository,
            stockLotRepository
    );

    @Test
    void stockValuesUsePhysicalBalancesAndFifoIncludingRentedAssets() {
        MachineInventory machine = new MachineInventory();
        machine.setId(1L);
        machine.setInventoryCount(0);
        machine.setLandedUnitCost(new BigDecimal("100.00"));
        machine.setSalePrice(new BigDecimal("200.00"));

        PartInventory part = new PartInventory();
        part.setId(2L);
        part.setQuantity(5);
        part.setLandedUnitCost(new BigDecimal("10.00"));
        part.setSalePrice(new BigDecimal("20.00"));

        StockBalance machineBalance = balance("MACHINE", 1L, 0, 0, 1);
        StockBalance partBalance = balance("PART", 2L, 5, 0, 0);
        StockLot machineLot = lot("MACHINE", 1L, 1, "120.00");
        StockLot partLot1 = lot("PART", 2L, 2, "8.00");
        StockLot partLot2 = lot("PART", 2L, 3, "12.00");

        when(machineInventoryRepository.findAll()).thenReturn(List.of(machine));
        when(partInventoryRepository.findAll()).thenReturn(List.of(part));
        when(stockBalanceRepository.findAll()).thenReturn(List.of(machineBalance, partBalance));
        when(stockLotRepository.findAll()).thenReturn(List.of(machineLot, partLot1, partLot2));

        List<StatisticsDashboardVO.StockValueRow> rows = builder.stockValues();

        StatisticsDashboardVO.StockValueRow machineRow = rows.getFirst();
        assertThat(machineRow.getResourceType()).isEqualTo("MACHINE");
        assertThat(machineRow.getItemCount()).isEqualTo(1);
        assertThat(machineRow.getStockQuantity()).isEqualTo(1);
        assertThat(machineRow.getCostValue()).isEqualByComparingTo("120.00");
        assertThat(machineRow.getSettlementValue()).isEqualByComparingTo("200.00");

        StatisticsDashboardVO.StockValueRow partRow = rows.get(1);
        assertThat(partRow.getResourceType()).isEqualTo("PART");
        assertThat(partRow.getItemCount()).isEqualTo(1);
        assertThat(partRow.getStockQuantity()).isEqualTo(5);
        assertThat(partRow.getCostValue()).isEqualByComparingTo("52.00");
        assertThat(partRow.getSettlementValue()).isEqualByComparingTo("100.00");
    }

    @Test
    void lowStocksMergeMachineAndPartRowsSortedByQuantity() {
        MachineInventory machine = new MachineInventory();
        machine.setVehicleProductNumber("M-001");
        machine.setName("Forklift");
        machine.setInventoryCount(0);

        PartInventory part = new PartInventory();
        part.setPartCode("P-001");
        part.setPartName("Filter");
        part.setQuantity(3);
        part.setUnit("pcs");

        when(partInventoryRepository.findLowStock(eq(5), any(Pageable.class))).thenReturn(List.of(part));

        List<StatisticsDashboardVO.LowStockRow> rows = builder.lowStocks();

        assertThat(rows).extracting(StatisticsDashboardVO.LowStockRow::getResourceType)
                .containsExactly("PART");
        assertThat(rows.getFirst().getQuantity()).isEqualTo(3);
        assertThat(rows.getFirst().getThreshold()).isEqualTo(5);
    }

    private StockBalance balance(
            String resourceType,
            Long resourceId,
            int available,
            int reserved,
            int locked
    ) {
        StockBalance balance = new StockBalance();
        balance.setResourceType(resourceType);
        balance.setResourceId(resourceId);
        balance.setAvailableQuantity(available);
        balance.setReservedQuantity(reserved);
        balance.setLockedQuantity(locked);
        return balance;
    }

    private StockLot lot(String resourceType, Long resourceId, int remaining, String unitCost) {
        StockLot lot = new StockLot();
        lot.setResourceType(resourceType);
        lot.setResourceId(resourceId);
        lot.setRemainingQuantity(remaining);
        lot.setUnitCost(new BigDecimal(unitCost));
        lot.setStatus(StockLot.STATUS_OPEN);
        return lot;
    }
}
