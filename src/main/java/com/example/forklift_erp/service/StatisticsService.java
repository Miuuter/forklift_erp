package com.example.forklift_erp.service;

import com.example.forklift_erp.dto.ListSummaryVO;
import com.example.forklift_erp.dto.StatisticsDashboardVO;
import com.example.forklift_erp.repository.MigrationExceptionRepository;
import com.example.forklift_erp.repository.StatisticsProjectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class StatisticsService {
    private static final int REPORTING_YEAR_SPAN = 5;

    private final StatisticsProjectionRepository projectionRepository;
    private final StatisticsProjectionMapper projectionMapper;
    private final MigrationExceptionRepository migrationExceptionRepository;
    private final ListSummaryService listSummaryService;

    public StatisticsService(
            StatisticsProjectionRepository projectionRepository,
            StatisticsProjectionMapper projectionMapper,
            MigrationExceptionRepository migrationExceptionRepository,
            ListSummaryService listSummaryService
    ) {
        this.projectionRepository = projectionRepository;
        this.projectionMapper = projectionMapper;
        this.migrationExceptionRepository = migrationExceptionRepository;
        this.listSummaryService = listSummaryService;
    }

    @Transactional(readOnly = true)
    public StatisticsDashboardVO financeDashboard(Integer year) {
        int selectedYear = year == null ? LocalDate.now().getYear() : year;
        LocalDate selectedStart = LocalDate.of(selectedYear, 1, 1);
        LocalDate selectedEnd = LocalDate.of(selectedYear, 12, 31);
        LocalDate historyStart = LocalDate.of(selectedYear - REPORTING_YEAR_SPAN + 1, 1, 1);

        StatisticsDashboardVO dashboard = new StatisticsDashboardVO();
        dashboard.setSelectedYear(selectedYear);
        dashboard.setGeneratedAt(LocalDateTime.now());
        dashboard.setMonthlyFinance(projectionMapper.monthlyRows(
                selectedYear,
                projectionRepository.monthlyFinancial(selectedStart, selectedEnd)
        ));
        dashboard.setYearlyFinance(projectionMapper.yearlyRows(
                projectionRepository.yearlyFinancial(historyStart, selectedEnd)
        ));
        dashboard.setAnnualSummary(projectionMapper.annualRow(
                selectedYear, dashboard.getYearlyFinance()));
        dashboard.setResourceFlows(projectionMapper.resourceFlows(
                projectionRepository.resourceFlows(selectedStart, selectedEnd)));
        dashboard.setTopOutbounds(projectionMapper.topOutbounds(
                projectionRepository.topOutbounds(selectedStart, selectedEnd, 8)));
        dashboard.setTopRentals(projectionMapper.topRentals(
                projectionRepository.topRentals(selectedStart, selectedEnd, 8)));
        dashboard.setStockValues(projectionMapper.stockValues(
                projectionRepository.stockValues()));
        dashboard.setLowStocks(projectionMapper.lowStocks(
                projectionRepository.lowStocks(10)));

        long openExceptions = migrationExceptionRepository.countByStatus("OPEN");
        dashboard.setDataComplete(openExceptions == 0);
        if (openExceptions > 0) {
            dashboard.getDataWarnings().add(
                    "存在 " + openExceptions
                            + " 条未解决迁移异常；本报表仅展示已过账的库存与财务事实，未使用旧主档字段补值。"
            );
        }
        return dashboard;
    }

    public ListSummaryVO listSummary(String type, String keyword) {
        return listSummaryService.summarize(type, keyword);
    }

    public ListSummaryVO listSummary(String type, String keyword, String resourceType) {
        return listSummaryService.summarize(type, keyword, resourceType);
    }

    public ListSummaryVO listSummary(String type, String keyword, String resourceType, String status) {
        return listSummaryService.summarize(type, keyword, resourceType, status);
    }
}
