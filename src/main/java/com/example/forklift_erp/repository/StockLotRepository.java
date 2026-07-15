package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.StockLot;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Comparator;

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

    @Query("""
            select l.id from StockLot l
            where l.resourceType = :resourceType
              and l.resourceId = :resourceId
              and l.warehouseId = :warehouseId
              and l.status = 'OPEN'
              and l.remainingQuantity > 0
            order by l.receivedBusinessDate asc, l.id asc
            """)
    List<Long> findOpenFifoIds(
            @Param("resourceType") String resourceType,
            @Param("resourceId") Long resourceId,
            @Param("warehouseId") Long warehouseId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from StockLot l where l.id in :ids")
    List<StockLot> findAllByIdInForUpdate(@Param("ids") List<Long> ids);

    /**
     * Resolve the FIFO range without a locking range scan, then lock the
     * exact primary-key rows. MySQL REPEATABLE READ otherwise applies
     * next-key gap locks to the composite FIFO index; concurrent transfers
     * for adjacent resource IDs can deadlock while inserting target lots.
     */
    default List<StockLot> findOpenFifoForUpdate(
            String resourceType,
            Long resourceId,
            Long warehouseId
    ) {
        List<Long> ids = findOpenFifoIds(resourceType, resourceId, warehouseId);
        if (ids.isEmpty()) {
            return List.of();
        }
        return findAllByIdInForUpdate(ids).stream()
                .sorted(Comparator
                        .comparing(StockLot::getReceivedBusinessDate)
                        .thenComparing(StockLot::getId))
                .toList();
    }
}
