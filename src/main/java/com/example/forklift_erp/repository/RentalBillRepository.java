package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.RentalBill;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RentalBillRepository extends JpaRepository<RentalBill, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select bill from RentalBill bill where bill.id = :id")
    Optional<RentalBill> findByIdForUpdate(@Param("id") Long id);

    Optional<RentalBill> findByRentalIdAndBillPeriod(Long rentalId, LocalDate billPeriod);

    List<RentalBill> findByRentalIdOrderByBillPeriodAsc(Long rentalId);
}
