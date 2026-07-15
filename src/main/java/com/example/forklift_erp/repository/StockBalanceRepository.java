package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.StockBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockBalanceRepository extends JpaRepository<StockBalance, Long> {

    Optional<StockBalance> findByResourceTypeAndResourceIdAndWarehouseId(
            String resourceType,
            Long resourceId,
            Long warehouseId
    );

    List<StockBalance> findByResourceTypeAndResourceId(String resourceType, Long resourceId);

    List<StockBalance> findByResourceTypeAndResourceIdIn(String resourceType, List<Long> resourceIds);

    List<StockBalance> findByWarehouseId(Long warehouseId);

    boolean existsByWarehouseId(Long warehouseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select b from StockBalance b
            where b.resourceType = :resourceType
              and b.resourceId = :resourceId
              and b.warehouseId = :warehouseId
            """)
    Optional<StockBalance> findForUpdate(
            @Param("resourceType") String resourceType,
            @Param("resourceId") Long resourceId,
            @Param("warehouseId") Long warehouseId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select b from StockBalance b
            where b.resourceType = :resourceType
              and b.resourceId = :resourceId
            order by b.id asc
            """)
    List<StockBalance> findAllForUpdate(
            @Param("resourceType") String resourceType,
            @Param("resourceId") Long resourceId
    );
}
