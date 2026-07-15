package com.example.forklift_erp.service;

import com.example.forklift_erp.dto.StatisticsDashboardVO;
import com.example.forklift_erp.repository.StatisticsProjectionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InventoryStatisticsBuilderTests {
    private final StatisticsProjectionRepository projectionRepository =
            mock(StatisticsProjectionRepository.class);
    private final InventoryStatisticsBuilder builder = new InventoryStatisticsBuilder(
            projectionRepository,
            new StatisticsProjectionMapper()
    );

    @Test
    void stockValuesUseDatabaseProjection() {
        when(projectionRepository.stockValues()).thenReturn(List.of(
                new StatisticsProjectionRepository.StockValueSummary(
                        "MACHINE", "Vehicle inventory", 1, 1,
                        new BigDecimal("120.00"), new BigDecimal("200.00")),
                new StatisticsProjectionRepository.StockValueSummary(
                        "PART", "Part inventory", 1, 5,
                        new BigDecimal("52.00"), new BigDecimal("100.00"))
        ));

        List<StatisticsDashboardVO.StockValueRow> rows = builder.stockValues();

        assertThat(rows).extracting(StatisticsDashboardVO.StockValueRow::getResourceType)
                .containsExactly("MACHINE", "PART");
        assertThat(rows.getFirst().getCostValue()).isEqualByComparingTo("120.00");
        assertThat(rows.get(1).getStockQuantity()).isEqualTo(5);
    }

    @Test
    void lowStocksUseEachPartsReorderPoint() {
        when(projectionRepository.lowStocks(10)).thenReturn(List.of(
                new StatisticsProjectionRepository.LowStockSummary(
                        "P-001", "Filter", 3, "pcs", 7)
        ));

        List<StatisticsDashboardVO.LowStockRow> rows = builder.lowStocks();

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getQuantity()).isEqualTo(3);
        assertThat(rows.getFirst().getThreshold()).isEqualTo(7);
    }
}
