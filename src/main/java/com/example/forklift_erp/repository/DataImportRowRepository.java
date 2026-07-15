package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.DataImportRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DataImportRowRepository extends JpaRepository<DataImportRow, Long> {
    @Modifying
    @Query(value = """
            INSERT IGNORE INTO data_import_row (
                import_job_id,
                import_type,
                import_mode,
                file_fingerprint,
                sheet_name,
                row_no,
                business_key,
                idempotency_key,
                created_at
            ) VALUES (
                :importJobId,
                :importType,
                :importMode,
                :fileFingerprint,
                :sheetName,
                :rowNumber,
                :businessKey,
                :idempotencyKey,
                NOW(6)
            )
            """, nativeQuery = true)
    int reserve(
            @Param("importJobId") Long importJobId,
            @Param("importType") String importType,
            @Param("importMode") String importMode,
            @Param("fileFingerprint") String fileFingerprint,
            @Param("sheetName") String sheetName,
            @Param("rowNumber") int rowNumber,
            @Param("businessKey") String businessKey,
            @Param("idempotencyKey") String idempotencyKey
    );
}
