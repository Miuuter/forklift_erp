package com.example.forklift_erp.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class HistoricalRepairReportVO {
    private boolean dryRun;
    private boolean backupConfirmed;
    private LocalDate fallbackBusinessDate;
    private LocalDateTime generatedAt;
    private String backupInstruction;
    private String backupFileName;
    private Long backupByteSize;
    private String backupSha256;
    private LocalDateTime backupCreatedAt;
    private List<Item> fixed = new ArrayList<>();
    private List<Item> defaulted = new ArrayList<>();
    private List<Item> exceptions = new ArrayList<>();

    public int getFixedCount() {
        return fixed.size();
    }

    public int getDefaultedCount() {
        return defaulted.size();
    }

    public int getExceptionCount() {
        return exceptions.size();
    }

    public void addFixed(String category, String sourceType, Long sourceId, String detail) {
        fixed.add(Item.of(category, sourceType, sourceId, detail));
    }

    public void addDefaulted(String category, String sourceType, Long sourceId, String detail) {
        defaulted.add(Item.of(category, sourceType, sourceId, detail));
    }

    public void addException(String category, String sourceType, Long sourceId, String detail) {
        exceptions.add(Item.of(category, sourceType, sourceId, detail));
    }

    @Data
    public static class Item {
        private String category;
        private String sourceType;
        private Long sourceId;
        private String detail;

        private static Item of(String category, String sourceType, Long sourceId, String detail) {
            Item item = new Item();
            item.setCategory(category);
            item.setSourceType(sourceType);
            item.setSourceId(sourceId);
            item.setDetail(detail);
            return item;
        }
    }
}
