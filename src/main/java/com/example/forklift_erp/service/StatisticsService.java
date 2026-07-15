package com.example.forklift_erp.service;

import com.example.forklift_erp.dto.ListSummaryVO;
import com.example.forklift_erp.dto.StatisticsDashboardVO;
import com.example.forklift_erp.repository.MigrationExceptionRepository;
import com.example.forklift_erp.repository.StatisticsProjectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        List<StatisticsProjectionRepository.FinancialSummary> monthlyHistory =
                projectionRepository.monthlyFinancial(historyStart, selectedEnd);
        dashboard.setMonthlyFinance(projectionMapper.monthlyRows(
                selectedYear,
                monthlyHistory.stream()
                        .filter(row -> row.period().startsWith(String.valueOf(selectedYear)))
                        .toList()
        ));
        dashboard.setYearlyFinance(projectionMapper.yearlyRows(
                aggregateYears(monthlyHistory)
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

    private List<StatisticsProjectionRepository.FinancialSummary> aggregateYears(
            List<StatisticsProjectionRepository.FinancialSummary> monthlyRows
    ) {
        Map<String, StatisticsProjectionRepository.FinancialSummary> years = new LinkedHashMap<>();
        for (StatisticsProjectionRepository.FinancialSummary row : monthlyRows) {
            String year = row.period().substring(0, 4);
            years.merge(year, rowWithPeriod(row, year), this::add);
        }
        return List.copyOf(years.values());
    }

    private StatisticsProjectionRepository.FinancialSummary rowWithPeriod(
            StatisticsProjectionRepository.FinancialSummary row,
            String period
    ) {
        return new StatisticsProjectionRepository.FinancialSummary(
                period,
                row.inboundQuantity(),
                row.outboundQuantity(),
                row.inboundCost(),
                row.outboundRevenue(),
                row.outboundCost(),
                row.repairIncome(),
                row.repairReceivable(),
                row.repairExpense(),
                row.repairPartsCost(),
                row.rentalIncome(),
                row.modificationIncome(),
                row.modificationExpense(),
                row.inventoryGain(),
                row.inventoryLoss(),
                row.netCashflow(),
                row.repairOrders(),
                row.rentalOrders(),
                row.modificationOrders()
        );
    }

    private StatisticsProjectionRepository.FinancialSummary add(
            StatisticsProjectionRepository.FinancialSummary left,
            StatisticsProjectionRepository.FinancialSummary right
    ) {
        return new StatisticsProjectionRepository.FinancialSummary(
                left.period(),
                left.inboundQuantity() + right.inboundQuantity(),
                left.outboundQuantity() + right.outboundQuantity(),
                money(left.inboundCost()).add(money(right.inboundCost())),
                money(left.outboundRevenue()).add(money(right.outboundRevenue())),
                money(left.outboundCost()).add(money(right.outboundCost())),
                money(left.repairIncome()).add(money(right.repairIncome())),
                money(left.repairReceivable()).add(money(right.repairReceivable())),
                money(left.repairExpense()).add(money(right.repairExpense())),
                money(left.repairPartsCost()).add(money(right.repairPartsCost())),
                money(left.rentalIncome()).add(money(right.rentalIncome())),
                money(left.modificationIncome()).add(money(right.modificationIncome())),
                money(left.modificationExpense()).add(money(right.modificationExpense())),
                money(left.inventoryGain()).add(money(right.inventoryGain())),
                money(left.inventoryLoss()).add(money(right.inventoryLoss())),
                money(left.netCashflow()).add(money(right.netCashflow())),
                left.repairOrders() + right.repairOrders(),
                left.rentalOrders() + right.rentalOrders(),
                left.modificationOrders() + right.modificationOrders()
        );
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
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
