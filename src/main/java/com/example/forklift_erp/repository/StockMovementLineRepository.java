package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.StockMovementLine;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockMovementLineRepository extends JpaRepository<StockMovementLine, Long> {
    List<StockMovementLine> findByResourceTypeAndResourceIdOrderByCreatedAtDesc(String resourceType, Long resourceId);
    List<StockMovementLine> findByMovementIdOrderByIdAsc(Long movementId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from StockMovementLine l where l.movementId = :movementId order by l.id asc")
    List<StockMovementLine> findByMovementIdForUpdate(@Param("movementId") Long movementId);

    boolean existsByWarehouseId(Long warehouseId);
    boolean existsByResourceTypeAndResourceId(String resourceType, Long resourceId);

    @Query("""
            select l from StockMovementLine l
            left join StockMovement m on m.id = l.movementId
            where (:resourceType is null or :resourceType = '' or l.resourceType = :resourceType)
              and (:movementType is null or :movementType = '' or m.movementType = :movementType)
              and (:warehouseId is null or l.warehouseId = :warehouseId)
              and (:keyword is null or :keyword = ''
                   or lower(coalesce(l.resourceCode, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(l.resourceName, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(m.movementNo, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(m.operator, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(m.remark, '')) like lower(concat('%', :keyword, '%')))
            """)
    Page<StockMovementLine> searchPage(
            @Param("keyword") String keyword,
            @Param("resourceType") String resourceType,
            @Param("movementType") String movementType,
            @Param("warehouseId") Long warehouseId,
            Pageable pageable
    );
}
