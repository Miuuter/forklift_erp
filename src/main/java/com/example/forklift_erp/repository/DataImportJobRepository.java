package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.DataImportJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
                j.finishedAt = null,
                j.updatedAt = CURRENT_TIMESTAMP,
                j.version = j.version + 1
            where j.id = :jobId
              and j.status = 'READY'
            """)
    int claimReadyForImport(@Param("jobId") Long jobId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DataImportJob j
            set j.status = 'FAILED',
                j.summary = :summary,
                j.importedBy = :importedBy,
                j.finishedAt = :finishedAt,
                j.updatedAt = :finishedAt,
                j.version = j.version + 1
            where j.id = :jobId
              and j.status = 'IMPORTING'
            """)
    int failImportIfInProgress(
            @Param("jobId") Long jobId,
            @Param("summary") String summary,
            @Param("importedBy") String importedBy,
            @Param("finishedAt") LocalDateTime finishedAt
    );

    @Query("""
            select j.id from DataImportJob j
            where j.status = 'IMPORTING'
              and (
                (j.startedAt is not null and j.startedAt < :cutoff) or
                (j.startedAt is null and j.updatedAt < :cutoff)
              )
            order by j.id asc
            """)
    List<Long> findStaleImportingJobIds(@Param("cutoff") LocalDateTime cutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DataImportJob j
            set j.status = 'FAILED',
                j.summary = :summary,
                j.importedBy = :recoveredBy,
                j.finishedAt = :finishedAt,
                j.updatedAt = :finishedAt,
                j.version = j.version + 1
            where j.id = :jobId
              and j.status = 'IMPORTING'
              and (
                (j.startedAt is not null and j.startedAt < :cutoff) or
                (j.startedAt is null and j.updatedAt < :cutoff)
              )
            """)
    int failStaleImportIfStillInProgress(
            @Param("jobId") Long jobId,
            @Param("cutoff") LocalDateTime cutoff,
            @Param("summary") String summary,
            @Param("recoveredBy") String recoveredBy,
            @Param("finishedAt") LocalDateTime finishedAt
    );

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

    @Query("""
            select j.id from DataImportJob j
            where j.stagedFileName is not null
              and j.createdAt < :cutoff
              and j.status in :statuses
            order by j.id asc
            """)
    List<Long> findTerminalExpiredFileJobIds(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("statuses") List<String> statuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from DataImportJob j where j.id = :jobId")
    java.util.Optional<DataImportJob> findByIdForUpdate(@Param("jobId") Long jobId);
}
