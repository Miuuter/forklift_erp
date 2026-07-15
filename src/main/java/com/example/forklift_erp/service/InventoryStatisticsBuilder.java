package com.example.forklift_erp.service;

import com.example.forklift_erp.dto.StatisticsDashboardVO;
import com.example.forklift_erp.repository.StatisticsProjectionRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class InventoryStatisticsBuilder {
    private final StatisticsProjectionRepository projectionRepository;
    private final StatisticsProjectionMapper projectionMapper;

    public InventoryStatisticsBuilder(
            StatisticsProjectionRepository projectionRepository,
            StatisticsProjectionMapper projectionMapper
    ) {
        this.projectionRepository = projectionRepository;
        this.projectionMapper = projectionMapper;
    }

    List<StatisticsDashboardVO.StockValueRow> stockValues() {
        return projectionMapper.stockValues(projectionRepository.stockValues());
    }

    List<StatisticsDashboardVO.LowStockRow> lowStocks() {
        return projectionMapper.lowStocks(projectionRepository.lowStocks(10));
    }
}
