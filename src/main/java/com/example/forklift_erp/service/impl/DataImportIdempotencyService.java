package com.example.forklift_erp.service.impl;

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
        return dataImportRowRepository.reserve(
                context.importJobId(),
                context.importType(),
                context.importMode(),
                context.fileFingerprint(),
                normalize(sheetName),
                rowNumber,
                globalBusinessKey,
                idempotencyKey
        ) == 1;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }
}
