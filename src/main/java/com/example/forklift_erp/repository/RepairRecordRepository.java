package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.RepairRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RepairRecordRepository extends JpaRepository<RepairRecord, Long> {
    boolean existsByMachineId(Long machineId);

    boolean existsByCustomerId(Long customerId);


    List<RepairRecord> findByMachineIdOrderByRepairDateDesc(Long machineId);
    List<RepairRecord> findByMachineIdAndIsLockedFalseOrderByRepairDateDesc(Long machineId);
    List<RepairRecord> findByRepairPerson(String repairPerson);
    List<RepairRecord> findByRepairPersonAndIsLockedFalse(String repairPerson);
    List<RepairRecord> findByStatus(String status);
    List<RepairRecord> findByStatusAndIsLockedFalse(String status);
    List<RepairRecord> findByRepairDateBetween(LocalDateTime start, LocalDateTime end);
    List<RepairRecord> findByRepairDateBetweenAndIsLockedFalse(LocalDateTime start, LocalDateTime end);

    boolean existsByRemarks(String remarks);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RepairRecord r where r.id = :id")
    Optional<RepairRecord> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RepairRecord r where r.id = :id and r.isLocked = false")
    Optional<RepairRecord> findByIdAndIsLockedFalseForUpdate(@Param("id") Long id);

    // ========== 普通用户专用（过滤锁定） ==========
    List<RepairRecord> findAllByIsLockedFalse();
    Optional<RepairRecord> findByIdAndIsLockedFalse(Long id);

    @Query("""
            select r from RepairRecord r
            where (:includeLocked = true or r.isLocked = false)
              and (:machineId is null or r.machineId = :machineId)
              and (:repairPerson is null or :repairPerson = '' or r.repairPerson = :repairPerson)
              and (:status is null or :status = '' or r.status = :status)
              and (:startDate is null or r.repairDate >= :startDate)
              and (:endDate is null or r.repairDate <= :endDate)
              and (:keyword is null or :keyword = ''
                   or lower(coalesce(r.vehicleNumber, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(r.customerName, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(r.customerAddress, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(r.repairPerson, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(r.status, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(r.faultDescription, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(r.repairContent, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(r.usedParts, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(r.remarks, '')) like lower(concat('%', :keyword, '%')))
            """)
    Page<RepairRecord> searchPage(
            @Param("keyword") String keyword,
            @Param("machineId") Long machineId,
            @Param("repairPerson") String repairPerson,
            @Param("status") String status,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("includeLocked") boolean includeLocked,
            Pageable pageable
    );

    @Query("""
            select count(r) from RepairRecord r
            where (:includeLocked = true or r.isLocked = false)
              and (r.status is null or r.status <> 'COMPLETED')
            """)
    long countPendingTodos(@Param("includeLocked") boolean includeLocked);

    @Query("""
            select r from RepairRecord r
            where (:includeLocked = true or r.isLocked = false)
              and (r.status is null or r.status <> 'COMPLETED')
            order by r.updatedAt desc, r.id desc
            """)
    List<RepairRecord> findPendingTodos(@Param("includeLocked") boolean includeLocked, Pageable pageable);
}
