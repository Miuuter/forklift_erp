package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.entity.DataImportRow;
import com.example.forklift_erp.repository.DataImportRowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DataImportIdempotencyService {
    private final DataImportRowRepository dataImportRowRepository;

    DataImportIdempotencyService(DataImportRowRepository dataImportRowRepository) {
        this.dataImportRowRepository = dataImportRowRepository;
    }

    /**
     * Reserves an input row in the surrounding import transaction. A retry of
     * the same file/row or the same business document will safely skip it.
     */
    @Transactional
    boolean reserve(ImportContext context, String sheetName, int rowNumber, String businessKey) {
        String normalizedKey = normalize(businessKey);
        String globalBusinessKey = context.masterData()
                ? context.fileFingerprint() + ":" + normalizedKey
                : normalizedKey;
        String idempotencyKey = context.fileFingerprint() + ":" + normalize(sheetName) + ":" + rowNumber;
        if (dataImportRowRepository.findByIdempotencyKey(idempotencyKey).isPresent()
                || dataImportRowRepository.existsByImportTypeAndImportModeAndBusinessKey(
                context.importType(), context.importMode(), globalBusinessKey)) {
            return false;
        }
        DataImportRow row = new DataImportRow();
        row.setImportJobId(context.importJobId());
        row.setImportType(context.importType());
        row.setImportMode(context.importMode());
        row.setFileFingerprint(context.fileFingerprint());
        row.setSheetName(normalize(sheetName));
        row.setRowNumber(rowNumber);
        row.setBusinessKey(globalBusinessKey);
        row.setIdempotencyKey(idempotencyKey);
        dataImportRowRepository.save(row);
        return true;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }
}
