package com.example.forklift_erp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "data_import_row")
public class DataImportRow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "import_job_id", nullable = false)
    private Long importJobId;

    @Column(name = "import_type", nullable = false, length = 60)
    private String importType;

    @Column(name = "import_mode", nullable = false, length = 30)
    private String importMode;

    @Column(name = "file_fingerprint", nullable = false, length = 64)
    private String fileFingerprint;

    @Column(name = "sheet_name", nullable = false, length = 80)
    private String sheetName;

    @Column(name = "row_no", nullable = false)
    private Integer rowNumber;

    @Column(name = "business_key", nullable = false, length = 240)
    private String businessKey;

    @Column(name = "idempotency_key", nullable = false, length = 320, unique = true)
    private String idempotencyKey;

    @Column(name = "source_type", length = 40)
    private String sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
