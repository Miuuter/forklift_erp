package com.example.forklift_erp.service;

import com.example.forklift_erp.constant.MachineStockStatus;
import com.example.forklift_erp.repository.DailyReconciliationProjectionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DailyReconciliationServiceTests {

    @Test
    void buildsReconciliationFromDatabaseProjections() {
        DailyReconciliationProjectionRepository repository =
                mock(DailyReconciliationProjectionRepository.class);
        LocalDate activityDate = LocalDate.of(2026, 7, 15);
        when(repository.resourceProfiles()).thenReturn(List.of(
                new DailyReconciliationProjectionRepository.ResourceProfileRow(
                        "MACHINE", 1L, "CAR-1", "Forklift", 0, MachineStockStatus.RENTED.code()),
                new DailyReconciliationProjectionRepository.ResourceProfileRow(
                        "PART", 2L, "PART-2", "Seal", 5, null)
        ));
        when(repository.balances()).thenReturn(List.of(
                new DailyReconciliationProjectionRepository.BalanceRow("MACHINE", 1L, 10L, 0, 0, 1),
                new DailyReconciliationProjectionRepository.BalanceRow("PART", 2L, 10L, 5, 0, 0)
        ));
        when(repository.fifoTotals()).thenReturn(List.of(
                new DailyReconciliationProjectionRepository.FifoRow("MACHINE", 1L, 10L, 1),
                new DailyReconciliationProjectionRepository.FifoRow("PART", 2L, 10L, 5)
        ));
        when(repository.movementStates(activityDate)).thenReturn(List.of(
                new DailyReconciliationProjectionRepository.MovementStateRow("MACHINE", 1L, 10L, 0, -1),
                new DailyReconciliationProjectionRepository.MovementStateRow("PART", 2L, 10L, 5, 0)
        ));
        when(repository.sales(activityDate)).thenReturn(List.of(
                new DailyReconciliationProjectionRepository.SalesRow(
                        20L,
                        "OUT-20",
                        "Customer",
                        new BigDecimal("100.00"),
                        new BigDecimal("120.00"),
                        BigDecimal.ZERO,
                        new BigDecimal("20.00")
                )
        ));
        when(repository.activeRentals()).thenReturn(List.of(
                new DailyReconciliationProjectionRepository.ActiveRentalRow(
                        30L,
                        "RENT-30",
                        1L,
                        "CAR-1",
                        10L,
                        MachineStockStatus.RENTED.code(),
                        0,
                        0,
                        1
                )
        ));
        when(repository.unmatchedRentalLocks()).thenReturn(List.of());

        var result = new DailyReconciliationService(repository).reconcile(activityDate);

        assertThat(result.getStockIssues()).isEmpty();
        assertThat(result.getRentalIssues()).isEmpty();
        assertThat(result.getSales()).singleElement()
                .satisfies(row -> {
                    assertThat(row.getUnpaid()).isEqualByComparingTo("-20.00");
                    assertThat(row.getStatus()).isEqualTo("OVERPAID");
                });
        assertThat(result.getSummary().getOverpaidSalesCount()).isEqualTo(1);
    }
}
