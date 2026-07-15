package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.RentalBill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RentalBillRepository extends JpaRepository<RentalBill, Long> {
    Optional<RentalBill> findByRentalIdAndBillPeriod(Long rentalId, LocalDate billPeriod);

    List<RentalBill> findByRentalIdOrderByBillPeriodAsc(Long rentalId);
}
