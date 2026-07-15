package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.DataImportJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DataImportJobRepository extends JpaRepository<DataImportJob, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DataImportJob j
            set j.status = 'IMPORTING',
                j.startedAt = CURRENT_TIMESTAMP,
                j.finishedAt = null
            where j.id = :jobId
              and j.status = 'READY'
            """)
    int claimReadyForImport(@Param("jobId") Long jobId);

    @Query("""
            select j from DataImportJob j
            where (:importType is null or :importType = '' or j.importType = :importType)
              and (
                :keyword is null or :keyword = '' or
                lower(coalesce(j.originalFileName, '')) like lower(concat('%', :keyword, '%')) or
                lower(coalesce(j.templateName, '')) like lower(concat('%', :keyword, '%')) or
                lower(coalesce(j.summary, '')) like lower(concat('%', :keyword, '%'))
              )
            order by j.createdAt desc, j.id desc
            """)
    Page<DataImportJob> search(
            @Param("importType") String importType,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    List<DataImportJob> findByStagedFileNameIsNotNullAndCreatedAtBeforeOrderByIdAsc(
            LocalDateTime cutoff
    );
}
