package com.example.forklift_erp.service;

import com.example.forklift_erp.entity.RentalRecord;
import com.example.forklift_erp.repository.RentalRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RentalBillingScheduler {
    private final RentalRecordRepository rentalRecordRepository;
    private final RentalBillingService rentalBillingService;

    public RentalBillingScheduler(
            RentalRecordRepository rentalRecordRepository,
            RentalBillingService rentalBillingService
    ) {
        this.rentalRecordRepository = rentalRecordRepository;
        this.rentalBillingService = rentalBillingService;
    }

    @Scheduled(cron = "0 30 0 * * *", zone = "Asia/Shanghai")
    public void refreshEndedMonths() {
        for (Long rentalId : rentalRecordRepository.findIdsByStatus(RentalRecord.STATUS_ACTIVE)) {
            try {
                rentalBillingService.refresh(rentalId);
            } catch (RuntimeException ex) {
                log.error("Failed to refresh rental bills for rentalId={}", rentalId, ex);
            }
        }
    }
}
