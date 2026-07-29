package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.PurchaseOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {
    boolean existsByResourceTypeAndResourceId(String resourceType, Long resourceId);

    boolean existsByWarehouseId(Long warehouseId);

    boolean existsBySupplierId(Long supplierId);

    boolean existsByConfigItemId(Long configItemId);

    boolean existsByConfigValueId(Long configValueId);

    boolean existsByPurchaseNo(String purchaseNo);

    boolean existsByResourceTypeAndResourceIdAndReceivedStockMovementIdGreaterThan(
            String resourceType,
            Long resourceId,
            Long receivedStockMovementId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PurchaseOrder p where p.id = :id")
    Optional<PurchaseOrder> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select p from PurchaseOrder p
            where (:resourceType is null or :resourceType = '' or p.resourceType = :resourceType)
              and (:keyword is null or :keyword = ''
                   or lower(coalesce(p.purchaseNo, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(p.supplierName, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(p.resourceType, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(p.resourceCode, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(p.resourceName, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(p.specificationModel, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(p.status, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(p.operator, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(p.remark, '')) like lower(concat('%', :keyword, '%')))
            """)
    Page<PurchaseOrder> searchPage(@Param("keyword") String keyword,
                                   @Param("resourceType") String resourceType,
                                   Pageable pageable);

    @Query("""
            select
              count(p) as totalCount,
              sum(coalesce(p.totalAmount, 0)) as totalAmount,
              sum(coalesce(p.freightAmount, 0)) as freightTotal,
              sum(case when p.status = 'RECEIVED' then 1 else 0 end) as received,
              sum(case when p.status <> 'RECEIVED' and p.status <> 'CANCELED' then 1 else 0 end) as pending
            from PurchaseOrder p
            where (:resourceType is null or :resourceType = '' or p.resourceType = :resourceType)
              and (:keyword is null or :keyword = ''
                   or lower(coalesce(p.purchaseNo, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(p.supplierName, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(p.resourceType, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(p.resourceCode, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(p.resourceName, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(p.specificationModel, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(p.status, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(p.operator, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(p.remark, '')) like lower(concat('%', :keyword, '%')))
            """)
    PurchaseSummaryProjection summarize(@Param("keyword") String keyword,
                                        @Param("resourceType") String resourceType);

    @Query("select count(distinct p.supplierName) from PurchaseOrder p where p.supplierName is not null and p.supplierName <> ''")
    long countDistinctSupplierNames();

    interface PurchaseSummaryProjection {
        Long getTotalCount();
        BigDecimal getTotalAmount();
        BigDecimal getFreightTotal();
        Long getReceived();
        Long getPending();
    }
}
