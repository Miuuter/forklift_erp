ALTER TABLE data_import_job
    ADD COLUMN import_mode VARCHAR(30) NOT NULL DEFAULT 'BUSINESS_DOCUMENT' AFTER import_type,
    ADD COLUMN file_fingerprint VARCHAR(64) NULL AFTER staged_file_name;

CREATE TABLE data_import_row (
    id BIGINT NOT NULL AUTO_INCREMENT,
    import_job_id BIGINT NOT NULL,
    import_type VARCHAR(60) NOT NULL,
    import_mode VARCHAR(30) NOT NULL,
    file_fingerprint VARCHAR(64) NOT NULL,
    sheet_name VARCHAR(80) NOT NULL,
    row_no INT NOT NULL,
    business_key VARCHAR(240) NOT NULL,
    idempotency_key VARCHAR(320) NOT NULL,
    source_type VARCHAR(40) NULL,
    source_id BIGINT NULL,
    created_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_data_import_row_idempotency (idempotency_key),
    UNIQUE KEY uk_data_import_row_business (import_type, import_mode, business_key),
    KEY idx_data_import_row_job (import_job_id),
    KEY idx_data_import_row_source (source_type, source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
