package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.RentalRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RentalRecordRepository extends JpaRepository<RentalRecord, Long> {
    List<RentalRecord> findAllByOrderByCreatedAtDesc();

    boolean existsByRentalNo(String rentalNo);

    @Query("""
            select r from RentalRecord r
            where (:keyword is null or :keyword = ''
               or lower(r.rentalNo) like lower(concat('%', :keyword, '%'))
               or lower(r.vehicleNumber) like lower(concat('%', :keyword, '%'))
               or lower(r.machineName) like lower(concat('%', :keyword, '%'))
               or lower(r.specificationModel) like lower(concat('%', :keyword, '%'))
               or lower(r.customerName) like lower(concat('%', :keyword, '%'))
               or lower(r.customerAddress) like lower(concat('%', :keyword, '%'))
               or lower(r.destination) like lower(concat('%', :keyword, '%'))
               or lower(r.status) like lower(concat('%', :keyword, '%'))
               or lower(r.operator) like lower(concat('%', :keyword, '%'))
               or lower(r.remark) like lower(concat('%', :keyword, '%')))
              and (:status is null or :status = ''
               or (:status = 'active' and r.status = 'ACTIVE')
               or (:status = 'returned' and r.status = 'RETURNED')
               or (:status = 'duesoon' and r.status = 'ACTIVE' and r.endDate is not null and r.endDate <= :dueSoonDate)
               or (:status = 'overdue' and r.status = 'ACTIVE' and r.endDate is not null and r.endDate < :today))
            """)
    Page<RentalRecord> searchPage(@Param("keyword") String keyword,
                                  @Param("status") String status,
                                  @Param("today") LocalDate today,
                                  @Param("dueSoonDate") LocalDate dueSoonDate,
                                  Pageable pageable);

    @Query("""
            select r from RentalRecord r
            where (:keyword is null or :keyword = ''
               or lower(r.rentalNo) like lower(concat('%', :keyword, '%'))
               or lower(r.vehicleNumber) like lower(concat('%', :keyword, '%'))
               or lower(r.machineName) like lower(concat('%', :keyword, '%'))
               or lower(r.specificationModel) like lower(concat('%', :keyword, '%'))
               or lower(r.customerName) like lower(concat('%', :keyword, '%'))
               or lower(r.customerAddress) like lower(concat('%', :keyword, '%'))
               or lower(r.destination) like lower(concat('%', :keyword, '%'))
               or lower(r.status) like lower(concat('%', :keyword, '%'))
               or lower(r.operator) like lower(concat('%', :keyword, '%'))
               or lower(r.remark) like lower(concat('%', :keyword, '%')))
              and (:status is null or :status = ''
               or (:status = 'active' and r.status = 'ACTIVE')
               or (:status = 'returned' and r.status = 'RETURNED')
               or (:status = 'duesoon' and r.status = 'ACTIVE' and r.endDate is not null and r.endDate <= :dueSoonDate)
               or (:status = 'overdue' and r.status = 'ACTIVE' and r.endDate is not null and r.endDate < :today))
            order by r.createdAt desc
            """)
    List<RentalRecord> searchForSummary(@Param("keyword") String keyword,
                                        @Param("status") String status,
                                        @Param("today") LocalDate today,
                                        @Param("dueSoonDate") LocalDate dueSoonDate);

    @Query("""
            select r from RentalRecord r
            where ((r.startDate is not null
                    and r.startDate <= :endDate
                    and (r.status = 'ACTIVE' or r.endDate is null or r.endDate >= :startDate))
                   or (r.startDate is null
                       and r.createdAt < :endAt
                       and (r.createdAt >= :startAt or r.status = 'ACTIVE' or r.endDate is null or r.endDate >= :startDate)))
            order by r.createdAt desc
            """)
    List<RentalRecord> findInDateRange(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt
    );

    @Query("""
            select
              count(r) as totalCount,
              sum(case when r.status = 'ACTIVE' then 1 else 0 end) as activeRows,
              sum(case when r.status = 'RETURNED' then 1 else 0 end) as returnedRows,
              count(distinct r.machineId) as vehicleCount,
              sum(coalesce(r.monthlyRentalPrice, r.rentalPrice, 0)) as rentalIncome
            from RentalRecord r
            where (:keyword is null or :keyword = ''
               or lower(coalesce(r.rentalNo, '')) like lower(concat('%', :keyword, '%'))
               or lower(coalesce(r.vehicleNumber, '')) like lower(concat('%', :keyword, '%'))
               or lower(coalesce(r.machineName, '')) like lower(concat('%', :keyword, '%'))
               or lower(coalesce(r.specificationModel, '')) like lower(concat('%', :keyword, '%'))
               or lower(coalesce(r.customerName, '')) like lower(concat('%', :keyword, '%'))
               or lower(coalesce(r.customerAddress, '')) like lower(concat('%', :keyword, '%'))
               or lower(coalesce(r.destination, '')) like lower(concat('%', :keyword, '%'))
               or lower(coalesce(r.status, '')) like lower(concat('%', :keyword, '%'))
               or lower(coalesce(r.operator, '')) like lower(concat('%', :keyword, '%'))
               or lower(coalesce(r.remark, '')) like lower(concat('%', :keyword, '%')))
            """)
    RentalSummaryProjection summarize(@Param("keyword") String keyword);

    List<RentalRecord> findByMachineIdOrderByCreatedAtDesc(Long machineId);

    boolean existsByMachineIdAndStatus(Long machineId, String status);

    boolean existsByMachineId(Long machineId);

    boolean existsByCustomerId(Long customerId);

    boolean existsByWarehouseId(Long warehouseId);

    long countByStatus(String status);

    List<RentalRecord> findByStatusOrderByUpdatedAtDescIdDesc(String status, Pageable pageable);

    @Query("select r.id from RentalRecord r where r.status = :status order by r.id asc")
    List<Long> findIdsByStatus(@Param("status") String status);

    @Query("""
            select count(r) from RentalRecord r
            where r.status = :status
              and r.endDate is not null
              and r.endDate <= :cutoffDate
            """)
    long countDueSoonTodos(
            @Param("status") String status,
            @Param("cutoffDate") LocalDate cutoffDate
    );

    @Query("""
            select r from RentalRecord r
            where r.status = :status
              and r.endDate is not null
              and r.endDate <= :cutoffDate
            order by r.endDate asc, r.updatedAt desc, r.id desc
            """)
    List<RentalRecord> findDueSoonTodos(
            @Param("status") String status,
            @Param("cutoffDate") LocalDate cutoffDate,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RentalRecord r where r.id = :id")
    Optional<RentalRecord> findByIdForUpdate(@Param("id") Long id);

    interface RentalSummaryProjection {
        Long getTotalCount();
        Long getActiveRows();
        Long getReturnedRows();
        Long getVehicleCount();
        BigDecimal getRentalIncome();
    }
}
