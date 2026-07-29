package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.Warehouse;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {
    Optional<Warehouse> findByWarehouseCode(String warehouseCode);
    Optional<Warehouse> findFirstByDefaultWarehouseTrueOrderByIdAsc();

    boolean existsByWarehouseCode(String warehouseCode);
    boolean existsByWarehouseCodeAndIdNot(String warehouseCode, Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Warehouse w where w.id = :id")
    Optional<Warehouse> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Warehouse w order by w.id")
    List<Warehouse> findAllForUpdate();

    @Query("""
            select w from Warehouse w
            where (:keyword is null or :keyword = ''
                   or lower(coalesce(w.warehouseCode, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(w.warehouseName, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(w.warehouseType, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(w.address, '')) like lower(concat('%', :keyword, '%')))
            """)
    Page<Warehouse> searchPage(@Param("keyword") String keyword, Pageable pageable);
}
