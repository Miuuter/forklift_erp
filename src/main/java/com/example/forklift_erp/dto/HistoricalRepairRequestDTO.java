package com.example.forklift_erp.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * Execution guard for historical corrections.  The repair endpoint is
 * intentionally separate from dry-run so production callers cannot mutate
 * history by accident.
 */
@Data
public class HistoricalRepairRequestDTO {
    /**
     * The repair service automatically creates and retains a complete JSON
     * backup immediately before mutation.  The operator must still provide
     * this explicit confirmation before the irreversible correction starts.
     */
    private Boolean backupConfirmed = false;

    /**
     * Used only when a historical record has no usable business date.
     */
    private LocalDate fallbackBusinessDate;
}
