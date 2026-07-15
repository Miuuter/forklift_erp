// MachineInventoryRepository.java
package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.MachineInventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MachineInventoryRepository extends JpaRepository<MachineInventory, Long> {

    Optional<MachineInventory> findByVehicleProductNumber(String vehicleProductNumber);

    boolean existsByVehicleProductNumber(String vehicleProductNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MachineInventory m where m.id = :id")
    Optional<MachineInventory> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MachineInventory m where m.id = :id and m.isLocked = false")
    Optional<MachineInventory> findByIdAndIsLockedFalseForUpdate(@Param("id") Long id);

    // ========== 以下为普通用户（过滤锁定记录）专用查询 ==========
    List<MachineInventory> findAllByIsLockedFalse();
    Optional<MachineInventory> findByIdAndIsLockedFalse(Long id);
    Optional<MachineInventory> findByVehicleProductNumberAndIsLockedFalse(String vehicleProductNumber);

    long countByWarehouseId(Long warehouseId);
    boolean existsBySupplierId(Long supplierId);

    @Query(value = """
            select m.*
            from machine_inventory m
            where (:includeLocked = true or coalesce(m.is_locked, 0) = 0)
              and (:keywordPrefix is null
                   or m.vehicle_number like :keywordPrefix escape '!'
                   or m.application_number like :keywordPrefix escape '!'
                   or m.material_number like :keywordPrefix escape '!'
                   or m.stock_status like :keywordPrefix escape '!'
                   or (:fullTextKeyword is not null
                       and match(
                         m.name,
                         m.specification_model,
                         m.configuration,
                         m.machine_type,
                         m.supplier,
                         m.warehouse_name,
                         m.destination1,
                         m.destination2,
                         m.destination3,
                         m.destination4,
                         m.destination5,
                         m.remarks
                       ) against (:fullTextKeyword in boolean mode)))
            order by m.id desc
            """,
            countQuery = """
            select count(*)
            from machine_inventory m
            where (:includeLocked = true or coalesce(m.is_locked, 0) = 0)
              and (:keywordPrefix is null
                   or m.vehicle_number like :keywordPrefix escape '!'
                   or m.application_number like :keywordPrefix escape '!'
                   or m.material_number like :keywordPrefix escape '!'
                   or m.stock_status like :keywordPrefix escape '!'
                   or (:fullTextKeyword is not null
                       and match(
                         m.name,
                         m.specification_model,
                         m.configuration,
                         m.machine_type,
                         m.supplier,
                         m.warehouse_name,
                         m.destination1,
                         m.destination2,
                         m.destination3,
                         m.destination4,
                         m.destination5,
                         m.remarks
                       ) against (:fullTextKeyword in boolean mode)))
            """,
            nativeQuery = true)
    Page<MachineInventory> searchPage(
            @Param("keywordPrefix") String keywordPrefix,
            @Param("fullTextKeyword") String fullTextKeyword,
            @Param("includeLocked") boolean includeLocked,
            Pageable pageable
    );

    @Query("""
            select
              count(m) as itemCount,
              sum(coalesce(m.inventoryCount, 0)) as stockQuantity,
              sum(coalesce(coalesce(m.landedUnitCost, m.purchasePrice), 0) * coalesce(m.inventoryCount, 0)) as costValue,
              sum(coalesce(coalesce(m.salePrice, m.settlementPrice), 0) * coalesce(m.inventoryCount, 0)) as settlementValue
            from MachineInventory m
            where coalesce(m.modelOnly, false) = false
            """)
    StockValueProjection stockValue();

    @Query("""
            select m from MachineInventory m
            where coalesce(m.modelOnly, false) = false
              and coalesce(m.inventoryCount, 0) <= :threshold
            order by coalesce(m.inventoryCount, 0) asc, m.id asc
    """)
    List<MachineInventory> findLowStock(@Param("threshold") int threshold, Pageable pageable);

    @Query("""
            select count(m) from MachineInventory m
            where (:includeLocked = true or m.isLocked = false)
              and coalesce(m.modelOnly, false) = false
              and coalesce(m.inventoryCount, 0) > 0
            """)
    long countInStockVehicleTodos(@Param("includeLocked") boolean includeLocked);

    @Query("""
            select count(m) from MachineInventory m
            where (:includeLocked = true or m.isLocked = false)
              and coalesce(m.modelOnly, false) = false
              and coalesce(m.inventoryCount, 0) > 0
              and (m.stockStatus is null or m.stockStatus = :stockStatus)
              and coalesce(m.inboundDate, m.createdAt) <= :cutoff
              and not exists (
                  select 1 from RentalRecord r
                  where r.machineId = m.id
                    and r.status = :activeRentalStatus
              )
            """)
    long countLongIdleVehicleTodos(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("stockStatus") String stockStatus,
            @Param("activeRentalStatus") String activeRentalStatus,
            @Param("includeLocked") boolean includeLocked
    );

    @Query("""
            select m from MachineInventory m
            where (:includeLocked = true or m.isLocked = false)
              and coalesce(m.modelOnly, false) = false
              and coalesce(m.inventoryCount, 0) > 0
              and (m.stockStatus is null or m.stockStatus = :stockStatus)
              and coalesce(m.inboundDate, m.createdAt) <= :cutoff
              and not exists (
                  select 1 from RentalRecord r
                  where r.machineId = m.id
                    and r.status = :activeRentalStatus
              )
            order by coalesce(m.inboundDate, m.createdAt) asc, m.id asc
            """)
    List<MachineInventory> findLongIdleVehicleTodos(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("stockStatus") String stockStatus,
            @Param("activeRentalStatus") String activeRentalStatus,
            @Param("includeLocked") boolean includeLocked,
            Pageable pageable
    );

    @Query(value = """
            select
              coalesce(m.name, '') as name,
              coalesce(m.specification_model, '') as specificationModel,
              coalesce(m.machine_type, '') as machineType,
              min(nullif(m.supplier, '')) as supplier,
              min(nullif(m.warehouse_name, '')) as warehouseName,
              max(m.purchase_price) as purchasePrice,
              max(m.sale_price) as salePrice,
              max(m.settlement_price) as settlementPrice,
              max(case when coalesce(m.model_only, 0) = 1 then m.id else null end) as modelTemplateId,
              sum(case when coalesce(m.model_only, 0) = 0 then 1 else 0 end) as unitCount,
              sum(case when coalesce(m.model_only, 0) = 0 then coalesce(m.inventory_count, 0) else 0 end) as inventoryCount,
              group_concat(case when coalesce(m.model_only, 0) = 0 then m.vehicle_number else null end order by m.vehicle_number separator ' ') as vehicleNumbers
            from machine_inventory m
            where (:includeLocked = true or coalesce(m.is_locked, 0) = 0)
              and (:longIdleOnly = false or (
                   coalesce(m.model_only, 0) = 0
                   and coalesce(m.inventory_count, 0) > 0
                   and (m.stock_status is null or m.stock_status = :inStockStatus)
                   and coalesce(m.inbound_date, m.created_at) <= :idleCutoff
                   and not exists (
                     select 1 from rental_record r
                     where r.machine_id = m.id and r.status = :activeRentalStatus
                   )))
              and (:keywordPrefix is null
                   or m.vehicle_number like :keywordPrefix escape '!'
                   or (:fullTextKeyword is not null
                       and match(
                         m.name,
                         m.specification_model,
                         m.configuration,
                         m.machine_type,
                         m.supplier,
                         m.warehouse_name
                       ) against (:fullTextKeyword in boolean mode)))
            group by coalesce(m.name, ''), coalesce(m.specification_model, ''), coalesce(m.machine_type, '')
            order by coalesce(m.name, ''), coalesce(m.specification_model, ''), coalesce(m.machine_type, '')
            """,
            countQuery = """
            select count(*) from (
              select 1
              from machine_inventory m
              where (:includeLocked = true or coalesce(m.is_locked, 0) = 0)
                and (:longIdleOnly = false or (
                     coalesce(m.model_only, 0) = 0
                     and coalesce(m.inventory_count, 0) > 0
                     and (m.stock_status is null or m.stock_status = :inStockStatus)
                     and coalesce(m.inbound_date, m.created_at) <= :idleCutoff
                     and not exists (
                       select 1 from rental_record r
                       where r.machine_id = m.id and r.status = :activeRentalStatus
                     )))
                and (:keywordPrefix is null
                     or m.vehicle_number like :keywordPrefix escape '!'
                     or (:fullTextKeyword is not null
                         and match(
                           m.name,
                           m.specification_model,
                           m.configuration,
                           m.machine_type,
                           m.supplier,
                           m.warehouse_name
                         ) against (:fullTextKeyword in boolean mode)))
              group by coalesce(m.name, ''), coalesce(m.specification_model, ''), coalesce(m.machine_type, '')
            ) model_count
            """,
            nativeQuery = true)
    Page<VehicleModelSummaryProjection> searchModelSummaries(
            @Param("keywordPrefix") String keywordPrefix,
            @Param("fullTextKeyword") String fullTextKeyword,
            @Param("includeLocked") boolean includeLocked,
            @Param("longIdleOnly") boolean longIdleOnly,
            @Param("idleCutoff") LocalDateTime idleCutoff,
            @Param("inStockStatus") String inStockStatus,
            @Param("activeRentalStatus") String activeRentalStatus,
            Pageable pageable
    );

    @Query("""
            select m from MachineInventory m
            where (:includeLocked = true or m.isLocked = false)
              and coalesce(m.modelOnly, false) = false
              and coalesce(m.name, '') = :name
              and coalesce(m.specificationModel, '') = :specificationModel
              and coalesce(m.machineType, '') = :machineType
              and (:longIdleOnly = false or (
                   coalesce(m.inventoryCount, 0) > 0
                   and (m.stockStatus is null or m.stockStatus = :inStockStatus)
                   and coalesce(m.inboundDate, m.createdAt) <= :idleCutoff
                   and not exists (
                     select 1 from RentalRecord r
                     where r.machineId = m.id and r.status = :activeRentalStatus
                   )))
            order by m.vehicleProductNumber asc
            """)
    List<MachineInventory> findVehiclesByModel(
            @Param("name") String name,
            @Param("specificationModel") String specificationModel,
            @Param("machineType") String machineType,
            @Param("includeLocked") boolean includeLocked,
            @Param("longIdleOnly") boolean longIdleOnly,
            @Param("idleCutoff") LocalDateTime idleCutoff,
            @Param("inStockStatus") String inStockStatus,
            @Param("activeRentalStatus") String activeRentalStatus
    );

    interface VehicleModelSummaryProjection {
        String getName();
        String getSpecificationModel();
        String getMachineType();
        String getSupplier();
        String getWarehouseName();
        BigDecimal getPurchasePrice();
        BigDecimal getSalePrice();
        BigDecimal getSettlementPrice();
        Long getModelTemplateId();
        Long getUnitCount();
        Long getInventoryCount();
        String getVehicleNumbers();
    }

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
