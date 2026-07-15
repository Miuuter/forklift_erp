package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.Supplier;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SupplierRepository extends JpaRepository<Supplier, Long> {
    List<Supplier> findAllByOrderBySupplierNameAsc();

    boolean existsBySupplierName(String supplierName);
    Optional<Supplier> findBySupplierName(String supplierName);

    boolean existsBySupplierNameAndIdNot(String supplierName, Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Supplier s where s.id = :id")
    Optional<Supplier> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select s from Supplier s
            where (:keyword is null or :keyword = ''
                   or lower(coalesce(s.supplierName, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.supplierType, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.contactName, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.contactPhone, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.address, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.taxNumber, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.remarks, '')) like lower(concat('%', :keyword, '%')))
            """)
    Page<Supplier> searchPage(@Param("keyword") String keyword, Pageable pageable);

    @Query("""
            select
              count(s) as totalCount,
              sum(case when s.supplierType is null or s.supplierType = '' then 0 else 1 end) as typed,
              sum(case when (s.contactName is null or s.contactName = '')
                            and (s.contactPhone is null or s.contactPhone = '')
                       then 0 else 1 end) as contacts
            from Supplier s
            where (:keyword is null or :keyword = ''
                   or lower(coalesce(s.supplierName, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.supplierType, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.contactName, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.contactPhone, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.address, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.taxNumber, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(s.remarks, '')) like lower(concat('%', :keyword, '%')))
            """)
    SupplierSummaryProjection summarize(@Param("keyword") String keyword);

    interface SupplierSummaryProjection {
        Long getTotalCount();
        Long getTyped();
        Long getContacts();
    }
}
