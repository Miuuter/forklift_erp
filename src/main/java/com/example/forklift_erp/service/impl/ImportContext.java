package com.example.forklift_erp.service.impl;

record ImportContext(
        Long importJobId,
        String importType,
        String importMode,
        String fileFingerprint
) {
    static final String MODE_OPENING_MIGRATION = "OPENING_MIGRATION";
    static final String MODE_BUSINESS_DOCUMENT = "BUSINESS_DOCUMENT";
    static final String MODE_MASTER_DATA = "MASTER_DATA";

    boolean openingMigration() {
        return MODE_OPENING_MIGRATION.equals(importMode);
    }

    boolean businessDocument() {
        return MODE_BUSINESS_DOCUMENT.equals(importMode);
    }

    boolean masterData() {
        return MODE_MASTER_DATA.equals(importMode);
    }
}
