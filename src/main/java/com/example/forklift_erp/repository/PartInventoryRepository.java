package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.PartInventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface PartInventoryRepository extends JpaRepository<PartInventory, Long> {

    Optional<PartInventory> findByPartCode(String partCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PartInventory p where p.id = :id")
    Optional<PartInventory> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PartInventory p where p.id = :id and p.isLocked = false")
    Optional<PartInventory> findByIdAndIsLockedFalseForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PartInventory p where p.partCode = :partCode")
    Optional<PartInventory> findByPartCodeForUpdate(@Param("partCode") String partCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PartInventory p where p.partCode = :partCode and p.isLocked = false")
    Optional<PartInventory> findByPartCodeAndIsLockedFalseForUpdate(@Param("partCode") String partCode);
    List<PartInventory> findByPartCategory(String partCategory);
    List<PartInventory> findByPartCategoryAndIsLockedFalse(String partCategory);
    List<PartInventory> findByQuantityGreaterThan(Integer minQuantity);
    List<PartInventory> findByQuantityGreaterThanAndIsLockedFalse(Integer minQuantity);
    List<PartInventory> findBySource(String source);
    List<PartInventory> findBySourceAndIsLockedFalse(String source);
    List<PartInventory> findBySourceMachineId(Long machineId);
    List<PartInventory> findBySourceMachineIdAndIsLockedFalse(Long machineId);
    boolean existsBySourceMachineId(Long machineId);

    long countByWarehouseId(Long warehouseId);

    // ========== 普通用户专用（过滤锁定） ==========
    List<PartInventory> findAllByIsLockedFalse();
    Optional<PartInventory> findByIdAndIsLockedFalse(Long id);
    Optional<PartInventory> findByPartCodeAndIsLockedFalse(String partCode);

    @Query(value = """
            select p.*
            from part_inventory p
            where (:includeLocked = true or coalesce(p.is_locked, 0) = 0)
              and (:stock is null
                   or (:stock = 'available' and coalesce(p.quantity, 0) > 0)
                   or (:stock = 'low' and coalesce(p.quantity, 0) <= coalesce(p.reorder_point, 5)))
              and (:keywordPrefix is null
                   or p.part_code like :keywordPrefix escape '!'
                   or (:fullTextKeyword is not null
                       and match(
                         p.part_name,
                         p.part_brand,
                         p.specification,
                         p.part_category,
                         p.applicable_models,
                         p.source,
                         p.remarks
                       ) against (:fullTextKeyword in boolean mode)))
            order by p.id desc
            """,
            countQuery = """
            select count(*)
            from part_inventory p
            where (:includeLocked = true or coalesce(p.is_locked, 0) = 0)
              and (:stock is null
                   or (:stock = 'available' and coalesce(p.quantity, 0) > 0)
                   or (:stock = 'low' and coalesce(p.quantity, 0) <= coalesce(p.reorder_point, 5)))
              and (:keywordPrefix is null
                   or p.part_code like :keywordPrefix escape '!'
                   or (:fullTextKeyword is not null
                       and match(
                         p.part_name,
                         p.part_brand,
                         p.specification,
                         p.part_category,
                         p.applicable_models,
                         p.source,
                         p.remarks
                       ) against (:fullTextKeyword in boolean mode)))
            """,
            nativeQuery = true)
    Page<PartInventory> searchPage(
            @Param("keywordPrefix") String keywordPrefix,
            @Param("fullTextKeyword") String fullTextKeyword,
            @Param("includeLocked") boolean includeLocked,
            @Param("stock") String stock,
            Pageable pageable
    );

    @Query("""
            select
              count(p) as itemCount,
              sum(coalesce(p.quantity, 0)) as stockQuantity,
              sum(coalesce(coalesce(p.landedUnitCost, p.purchasePrice), 0) * coalesce(p.quantity, 0)) as costValue,
              sum(coalesce(coalesce(p.salePrice, p.settlementPrice), 0) * coalesce(p.quantity, 0)) as settlementValue
            from PartInventory p
            """)
    StockValueProjection stockValue();

    @Query("""
            select p from PartInventory p
            where coalesce(p.quantity, 0) <= coalesce(p.reorderPoint, 5)
            order by coalesce(p.quantity, 0) asc, p.id asc
    """)
    List<PartInventory> findLowStock(Pageable pageable);

    @Query("""
            select count(p) from PartInventory p
            where (:includeLocked = true or p.isLocked = false)
              and coalesce(p.quantity, 0) <= coalesce(p.reorderPoint, 5)
            """)
    long countLowStockTodos(@Param("includeLocked") boolean includeLocked);

    @Query("""
            select p from PartInventory p
            where (:includeLocked = true or p.isLocked = false)
              and coalesce(p.quantity, 0) <= coalesce(p.reorderPoint, 5)
            order by p.updatedAt desc, p.id desc
            """)
    List<PartInventory> findLowStockTodos(
            @Param("includeLocked") boolean includeLocked,
            Pageable pageable
    );

    interface StockValueProjection {
        Long getItemCount();
        Long getStockQuantity();
        BigDecimal getCostValue();
        BigDecimal getSettlementValue();

        default BigDecimal getRetailValue() {
            return getSettlementValue();
        }
    }
}
