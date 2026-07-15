package com.example.forklift_erp.service;

import com.example.forklift_erp.constant.RentalStatus;
import com.example.forklift_erp.entity.RentalRecord;
import com.example.forklift_erp.util.MoneyValues;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class RentalRevenueCalculator {
    private static final int MONEY_SCALE = 2;

    private final Clock clock;

    public RentalRevenueCalculator() {
        this(Clock.systemDefaultZone());
    }

    RentalRevenueCalculator(Clock clock) {
        this.clock = clock;
    }

    public BigDecimal totalAmount(RentalRecord rental) {
        RentalPeriod period = rentalPeriod(rental);
        if (period == null) {
            return BigDecimal.ZERO;
        }
        return amountForRange(rental, period.start(), period.end());
    }

    public BigDecimal amountForRange(RentalRecord rental, LocalDate rangeStart, LocalDate rangeEnd) {
        return monthlyAmounts(rental, rangeStart, rangeEnd).values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public Map<YearMonth, BigDecimal> monthlyAmounts(RentalRecord rental, LocalDate rangeStart, LocalDate rangeEnd) {
        RentalPeriod period = rentalPeriod(rental);
        BigDecimal monthlyPrice = rentalMonthlyPrice(rental);
        if (period == null || monthlyPrice.signum() <= 0 || rangeStart == null || rangeEnd == null || rangeEnd.isBefore(rangeStart)) {
            return Map.of();
        }
        LocalDate start = period.start().isBefore(rangeStart) ? rangeStart : period.start();
        LocalDate end = period.end().isAfter(rangeEnd) ? rangeEnd : period.end();
        if (end.isBefore(start)) {
            return Map.of();
        }
        Map<YearMonth, BigDecimal> amounts = new LinkedHashMap<>();
        YearMonth cursor = YearMonth.from(start);
        YearMonth last = YearMonth.from(end);
        while (!cursor.isAfter(last)) {
            LocalDate segmentStart = start.isAfter(cursor.atDay(1)) ? start : cursor.atDay(1);
            LocalDate segmentEnd = end.isBefore(cursor.atEndOfMonth()) ? end : cursor.atEndOfMonth();
            long days = ChronoUnit.DAYS.between(segmentStart, segmentEnd) + 1;
            if (days > 0) {
                BigDecimal amount = monthlyPrice
                        .multiply(BigDecimal.valueOf(days))
                        .divide(BigDecimal.valueOf(cursor.lengthOfMonth()), MONEY_SCALE, RoundingMode.HALF_UP);
                amounts.put(cursor, amount);
            }
            cursor = cursor.plusMonths(1);
        }
        return amounts;
    }

    private RentalPeriod rentalPeriod(RentalRecord rental) {
        LocalDate start = rentalStartDate(rental);
        if (start == null) {
            return null;
        }
        LocalDate end = rentalEndDate(rental, start);
        if (end == null || end.isBefore(start)) {
            return null;
        }
        return new RentalPeriod(start, end);
    }

    private LocalDate rentalStartDate(RentalRecord rental) {
        if (rental.getStartDate() != null) {
            return rental.getStartDate();
        }
        return rental.getCreatedAt() == null ? null : rental.getCreatedAt().toLocalDate();
    }

    private LocalDate rentalEndDate(RentalRecord rental, LocalDate start) {
        if (RentalStatus.RETURNED.code().equals(rental.getStatus())) {
            return rental.getReturnDate() != null ? rental.getReturnDate() : rental.getEndDate();
        }
        LocalDate today = LocalDate.now(clock);
        return today.isBefore(start) ? null : today;
    }

    private BigDecimal rentalMonthlyPrice(RentalRecord rental) {
        return MoneyValues.firstPresentAsNonNegativeOrZero(rental.getMonthlyRentalPrice(), rental.getRentalPrice());
    }

    private record RentalPeriod(LocalDate start, LocalDate end) {
    }
}
