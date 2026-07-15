package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.StocktakingRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StocktakingRecordRepository extends JpaRepository<StocktakingRecord, Long> {
    boolean existsByStocktakingNo(String stocktakingNo);
    boolean existsByResourceTypeAndResourceId(String resourceType, Long resourceId);
    boolean existsByWarehouseId(Long warehouseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StocktakingRecord s where s.id = :id")
    Optional<StocktakingRecord> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select s from StocktakingRecord s
            where (:keyword is null or :keyword = ''
                   or lower(coalesce(s.stocktakingNo, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.resourceType, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.resourceCode, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.resourceName, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.specificationModel, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.status, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.operator, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.remark, '')) like lower(concat('%', :keyword, '%')))
            """)
    Page<StocktakingRecord> searchPage(@Param("keyword") String keyword, Pageable pageable);

    @Query("""
            select
              count(s) as totalCount,
              sum(case when s.status = 'COMPLETED' then 0 else 1 end) as drafts,
              sum(case when s.status = 'COMPLETED' then 1 else 0 end) as completed,
              sum(case when coalesce(s.differenceQuantity, 0) <> 0 then 1 else 0 end) as differences
            from StocktakingRecord s
            where (:keyword is null or :keyword = ''
                   or lower(coalesce(s.stocktakingNo, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.resourceType, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.resourceCode, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.resourceName, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.specificationModel, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.status, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.operator, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.remark, '')) like lower(concat('%', :keyword, '%')))
            """)
    StocktakingSummaryProjection summarize(@Param("keyword") String keyword);

    interface StocktakingSummaryProjection {
        Long getTotalCount();
        Long getDrafts();
        Long getCompleted();
        Long getDifferences();
    }
}
