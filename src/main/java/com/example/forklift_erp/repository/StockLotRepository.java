package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.StockLot;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockLotRepository extends JpaRepository<StockLot, Long> {
    Optional<StockLot> findByIdempotencyKey(String idempotencyKey);

    boolean existsByResourceTypeAndResourceId(String resourceType, Long resourceId);

    boolean existsByWarehouseId(Long warehouseId);

    boolean existsByResourceTypeAndResourceIdAndRemainingQuantityGreaterThan(
            String resourceType,
            Long resourceId,
            Integer remainingQuantity
    );

    List<StockLot> findByResourceTypeAndResourceIdOrderByIdAsc(String resourceType, Long resourceId);

    List<StockLot> findBySourceTypeAndSourceIdOrderByIdAsc(String sourceType, Long sourceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select l from StockLot l
            where l.resourceType = :resourceType
              and l.resourceId = :resourceId
              and l.warehouseId = :warehouseId
              and l.status = 'OPEN'
              and l.remainingQuantity > 0
            order by l.receivedBusinessDate asc, l.id asc
            """)
    List<StockLot> findOpenFifoForUpdate(
            @Param("resourceType") String resourceType,
            @Param("resourceId") Long resourceId,
            @Param("warehouseId") Long warehouseId
    );
}
