package com.example.forklift_erp.service;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.dto.RentalBillVO;
import com.example.forklift_erp.entity.RentalBill;
import com.example.forklift_erp.entity.RentalRecord;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.PaymentRecordRepository;
import com.example.forklift_erp.repository.RentalBillRepository;
import com.example.forklift_erp.repository.RentalRecordRepository;
import com.example.forklift_erp.util.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

@Service
public class RentalBillingService {
    private final RentalRecordRepository rentalRecordRepository;
    private final RentalBillRepository rentalBillRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final RentalRevenueCalculator rentalRevenueCalculator;
    private final FinancialEventService financialEventService;
    private final OperationAuditService operationAuditService;

    public RentalBillingService(
            RentalRecordRepository rentalRecordRepository,
            RentalBillRepository rentalBillRepository,
            PaymentRecordRepository paymentRecordRepository,
            RentalRevenueCalculator rentalRevenueCalculator,
            FinancialEventService financialEventService,
            OperationAuditService operationAuditService
    ) {
        this.rentalRecordRepository = rentalRecordRepository;
        this.rentalBillRepository = rentalBillRepository;
        this.paymentRecordRepository = paymentRecordRepository;
        this.rentalRevenueCalculator = rentalRevenueCalculator;
        this.financialEventService = financialEventService;
        this.operationAuditService = operationAuditService;
    }

    @Transactional
    public List<RentalBillVO> refresh(Long rentalId) {
        RentalRecord rental = rentalRecordRepository.findByIdForUpdate(rentalId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "租赁记录不存在"));
        boolean finalBill = RentalRecord.STATUS_RETURNED.equals(rental.getStatus());
        generate(rental, finalBill);
        return billViews(rentalId);
    }

    @Transactional
    public void refreshFinal(RentalRecord rental) {
        RentalRecord locked = rentalRecordRepository.findByIdForUpdate(rental.getId())
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "租赁记录不存在"));
        generate(locked, true);
    }

    @Transactional(readOnly = true)
    public List<RentalBillVO> billViews(Long rentalId) {
        return rentalBillRepository.findByRentalIdOrderByBillPeriodAsc(rentalId).stream()
                .map(bill -> RentalBillVO.fromEntity(
                        bill,
                        paymentRecordRepository.totalForSource(
                                FinancialEventService.SOURCE_RENTAL_BILL,
                                bill.getId(),
                                com.example.forklift_erp.entity.PaymentRecord.DIRECTION_RECEIPT
                        )
                ))
                .toList();
    }

    private void generate(RentalRecord rental, boolean finalBill) {
        LocalDate start = rental.getStartDate();
        if (start == null) {
            return;
        }
        LocalDate end = billingEnd(rental, finalBill);
        if (end == null || end.isBefore(start)) {
            return;
        }

        List<RentalBill> generated = new ArrayList<>();
        rentalRevenueCalculator.monthlyAmounts(rental, start, end).forEach((period, amount) -> {
            LocalDate billPeriod = period.atDay(1);
            if (rentalBillRepository.findByRentalIdAndBillPeriod(rental.getId(), billPeriod).isPresent()) {
                return;
            }
            RentalBill bill = new RentalBill();
            bill.setRentalId(rental.getId());
            bill.setBillPeriod(billPeriod);
            bill.setBusinessDate(finalBill && period.equals(YearMonth.from(end))
                    ? end
                    : period.atEndOfMonth());
            bill.setAmount(amount);
            RentalBill savedBill = rentalBillRepository.saveAndFlush(bill);
            var event = financialEventService.postRentalBill(
                    savedBill.getId(),
                    rental.getId(),
                    rental.getCustomerId(),
                    rental.getCustomerName(),
                    savedBill.getBusinessDate(),
                    amount
            );
            savedBill.setFinancialEventId(event.getId());
            generated.add(rentalBillRepository.save(savedBill));
        });
        if (generated.isEmpty()) {
            return;
        }
        rental.setFinancialPosted(true);
        rentalRecordRepository.save(rental);
        operationAuditService.record(
                "Rental billing",
                finalBill ? "FINAL_REFRESH" : "MONTHLY_REFRESH",
                "RENTAL_RECORD",
                rental.getId(),
                rental.getRentalNo(),
                rental.getVehicleNumber(),
                "Generated " + generated.size() + " rental bill(s)",
                SecurityUtils.currentUsername(),
                "Billing through " + end,
                "RENTAL_RECORD",
                rental.getId()
        );
    }

    private LocalDate billingEnd(RentalRecord rental, boolean finalBill) {
        if (finalBill) {
            return rental.getReturnDate() == null ? rental.getEndDate() : rental.getReturnDate();
        }
        return YearMonth.from(LocalDate.now()).minusMonths(1).atEndOfMonth();
    }
}
