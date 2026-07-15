package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.ModificationWorkOrder;
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
public interface ModificationWorkOrderRepository extends JpaRepository<ModificationWorkOrder, Long> {

    List<ModificationWorkOrder> findAllByOrderByCreatedAtDesc();

    @Query("""
            select w from ModificationWorkOrder w
            left join MachineInventory m on m.id = w.machineId
            where (:keyword is null
                or lower(coalesce(w.workOrderNo, '')) like lower(concat('%', :keyword, '%'))
                or lower(coalesce(m.vehicleProductNumber, '')) like lower(concat('%', :keyword, '%'))
                or lower(coalesce(m.name, '')) like lower(concat('%', :keyword, '%'))
                or lower(coalesce(m.specificationModel, '')) like lower(concat('%', :keyword, '%'))
                or lower(coalesce(w.customerName, '')) like lower(concat('%', :keyword, '%'))
                or lower(coalesce(w.salesOrderNo, '')) like lower(concat('%', :keyword, '%'))
                or lower(coalesce(w.status, '')) like lower(concat('%', :keyword, '%'))
                or lower(coalesce(w.operator, '')) like lower(concat('%', :keyword, '%'))
                or lower(coalesce(w.remark, '')) like lower(concat('%', :keyword, '%')))
            """)
    Page<ModificationWorkOrder> searchPage(@Param("keyword") String keyword, Pageable pageable);

    @Query("""
            select w from ModificationWorkOrder w
            where w.status = :status
              and w.completedAt >= :start
              and w.completedAt < :end
            order by w.completedAt desc
            """)
    List<ModificationWorkOrder> findCompletedInRange(
            @Param("status") String status,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );

    List<ModificationWorkOrder> findByMachineIdOrderByCreatedAtDesc(Long machineId);

    boolean existsByMachineIdAndStatusIn(Long machineId, List<String> statuses);

    boolean existsByMachineId(Long machineId);

    boolean existsByWarehouseId(Long warehouseId);

    Optional<ModificationWorkOrder> findByWorkOrderNo(String workOrderNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from ModificationWorkOrder w where w.id = :id")
    Optional<ModificationWorkOrder> findByIdForUpdate(@Param("id") Long id);
}
