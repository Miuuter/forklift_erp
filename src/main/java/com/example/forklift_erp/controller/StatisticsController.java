package com.example.forklift_erp.controller;

import com.example.forklift_erp.common.Result;
import com.example.forklift_erp.dto.ListSummaryVO;
import com.example.forklift_erp.dto.StatisticsDashboardVO;
import com.example.forklift_erp.dto.DailyReconciliationVO;
import com.example.forklift_erp.service.DailyReconciliationService;
import com.example.forklift_erp.security.PermissionCodes;
import com.example.forklift_erp.service.StatisticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/statistics")
public class StatisticsController {

    @Autowired
    private StatisticsService statisticsService;

    @Autowired
    private DailyReconciliationService dailyReconciliationService;

    @GetMapping("/finance")
    @PreAuthorize(PermissionCodes.HAS_LOG_READ)
    public Result<StatisticsDashboardVO> finance(@RequestParam(required = false) Integer year) {
        return Result.success(statisticsService.financeDashboard(year));
    }

    @GetMapping("/reconciliation/daily")
    @PreAuthorize(PermissionCodes.HAS_LOG_READ)
    public Result<DailyReconciliationVO> dailyReconciliation(
            @RequestParam(required = false) java.time.LocalDate date
    ) {
        return Result.success(dailyReconciliationService.reconcile(date));
    }

    @GetMapping("/list-summary")
    @PreAuthorize(PermissionCodes.HAS_LIST_SUMMARY_ACCESS)
    public Result<ListSummaryVO> listSummary(@RequestParam String type,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String resourceType,
                                             @RequestParam(required = false) String status) {
        return Result.success(statisticsService.listSummary(type, keyword, resourceType, status));
    }
}
