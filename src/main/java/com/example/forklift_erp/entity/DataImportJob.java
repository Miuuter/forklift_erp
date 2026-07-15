package com.example.forklift_erp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "data_import_job",
        indexes = {
                @Index(name = "idx_data_import_job_type_created_at", columnList = "import_type, created_at"),
                @Index(name = "idx_data_import_job_status", columnList = "status"),
                @Index(name = "idx_data_import_job_imported_at", columnList = "finished_at")
        }
)
public class DataImportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "import_type", nullable = false, length = 60)
    private String importType;

    @Column(name = "import_mode", nullable = false, length = 30)
    private String importMode = "BUSINESS_DOCUMENT";

    @Column(name = "template_name", length = 120)
    private String templateName;

    @Column(name = "original_file_name", length = 255)
    private String originalFileName;

    @Column(name = "staged_file_name", length = 255)
    private String stagedFileName;

    @Column(name = "file_fingerprint", length = 64)
    private String fileFingerprint;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "total_rows")
    private Integer totalRows = 0;

    @Column(name = "valid_rows")
    private Integer validRows = 0;

    @Column(name = "error_rows")
    private Integer errorRows = 0;

    @Column(name = "error_details")
    private Integer errorDetails = 0;

    @Column(name = "imported_rows")
    private Integer importedRows = 0;

    @Column(name = "skipped_rows")
    private Integer skippedRows = 0;

    @Column(name = "summary", length = 1000)
    private String summary;

    @Column(name = "error_rows_json", columnDefinition = "LONGTEXT")
    private String errorRowsJson;

    @Column(name = "validation_snapshot_json", columnDefinition = "LONGTEXT")
    private String validationSnapshotJson;

    @Column(name = "created_by", length = 50)
    private String createdBy;

    @Column(name = "imported_by", length = 50)
    private String importedBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null || this.status.isBlank()) {
            this.status = "DRAFT";
        }
        if (this.totalRows == null) {
            this.totalRows = 0;
        }
        if (this.validRows == null) {
            this.validRows = 0;
        }
        if (this.errorRows == null) {
            this.errorRows = 0;
        }
        if (this.errorDetails == null) {
            this.errorDetails = 0;
        }
        if (this.importedRows == null) {
            this.importedRows = 0;
        }
        if (this.skippedRows == null) {
            this.skippedRows = 0;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
