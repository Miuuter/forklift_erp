package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.dto.PartInventoryCreateDTO;
import com.example.forklift_erp.dto.PartInventoryVO;
import com.example.forklift_erp.dto.PartStockAdjustRequestDTO;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.service.PartInventoryService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class DataImportPartsImporter {
    private static final String SOURCE_PURCHASE_DETAIL_IMPORT = "\u91c7\u8d2d\u660e\u7ec6\u5bfc\u5165";
    private static final String REMARK_SOURCE_PURCHASE_DETAIL_IMPORT = "\u6765\u6e90\uff1a\u91c7\u8d2d\u660e\u7ec6\u5bfc\u5165";
    private static final String CATEGORY_TIRE = "\u8f6e\u80ce";
    private static final String CATEGORY_BATTERY = "\u7535\u6c60";
    private static final String CATEGORY_CHARGER = "\u5145\u7535\u5668";
    private static final String CATEGORY_FORK = "\u8d27\u53c9";
    private static final String CATEGORY_MAST = "\u95e8\u67b6";
    private static final String CATEGORY_HYDRAULIC = "\u6db2\u538b\u4ef6";
    private static final String CATEGORY_ELECTRIC_CONTROL = "\u7535\u63a7\u4ef6";
    private static final String CATEGORY_PART = "\u914d\u4ef6";

    private final PartInventoryService partInventoryService;
    private final DataImportIdempotencyService idempotencyService;

    public DataImportPartsImporter(
            PartInventoryService partInventoryService,
            DataImportIdempotencyService idempotencyService
    ) {
        this.partInventoryService = partInventoryService;
        this.idempotencyService = idempotencyService;
    }

    ImportResult importWorkbook(WorkbookSnapshot snapshot, ImportContext context) {
        if (context.businessDocument()) {
            return new ImportResult(0, snapshot.sheetRows("Parts").size(),
                    "Parts workbook is a snapshot; BUSINESS_DOCUMENT mode requires purchase-order documents and makes no stock changes");
        }
        Map<String, List<WorkbookRow>> grouped = new LinkedHashMap<>();
        for (WorkbookRow row : snapshot.sheetRows("Parts")) {
            String code = text(row, 1);
            if (!hasText(code)) {
                continue;
            }
            grouped.computeIfAbsent(code, key -> new ArrayList<>()).add(row);
        }

        int created = 0;
        int updated = 0;
        int reused = 0;
        for (Map.Entry<String, List<WorkbookRow>> entry : grouped.entrySet()) {
            String code = entry.getKey();
            List<WorkbookRow> group = entry.getValue();
            WorkbookRow latestRow = group.stream()
                    .max(Comparator.comparingInt(WorkbookRow::rowNumber))
                    .orElse(group.get(0));
            if (!idempotencyService.reserve(context, "Parts", latestRow.rowNumber(), "PART:" + code)) {
                reused++;
                continue;
            }
            PartInventoryCreateDTO dto = buildPartDto(group, latestRow);
            int openingQuantity = group.stream().mapToInt(row -> intValue(row, 7, 0)).sum();
            Optional<PartInventory> existing = partInventoryService.findByPartCode(code);
            if (existing.isEmpty()) {
                if (context.masterData() || context.openingMigration()) {
                    dto.setQuantity(0);
                }
                PartInventoryVO createdPart = partInventoryService.create(dto);
                if (context.openingMigration() && openingQuantity > 0) {
                    postOpeningAdjustment(code, createdPart == null ? null : createdPart.getVersion(),
                            openingQuantity, latestRow);
                }
                created++;
                continue;
            }

            PartInventory current = existing.get();
            if (isOperational(current)) {
                // Never reset operated stock from an external snapshot.
                reused++;
                continue;
            }
            dto.setQuantity(current.getQuantity() == null ? 0 : current.getQuantity());
            dto.setSalePrice(current.getSalePrice());
            dto.setSettlementPrice(current.getSettlementPrice());
            dto.setVersion(current.getVersion());
            PartInventoryVO savedPart = null;
            if (partChanged(current, dto)) {
                savedPart = partInventoryService.update(current.getId(), dto);
                updated++;
            }
            if (context.openingMigration() && dto.getQuantity() == 0) {
                if (openingQuantity > 0) {
                    postOpeningAdjustment(
                            code,
                            savedPart == null ? current.getVersion() : savedPart.getVersion(),
                            openingQuantity,
                            latestRow
                    );
                }
            } else if (!partChanged(current, dto)) {
                reused++;
            }
        }
        return new ImportResult(created + updated + reused, 0,
                "Mode=" + context.importMode() + ", imported parts created=" + created
                        + ", updated=" + updated + ", reused=" + reused);
    }

    private void postOpeningAdjustment(
            String partCode,
            Long version,
            int openingQuantity,
            WorkbookRow latestRow
    ) {
        PartStockAdjustRequestDTO adjustment = new PartStockAdjustRequestDTO();
        adjustment.setPartCode(partCode);
        adjustment.setVersion(version);
        adjustment.setQuantity(openingQuantity);
        adjustment.setBusinessDate(date(latestRow, 0));
        adjustment.setOpeningBalance(true);
        adjustment.setReason("Opening migration from parts workbook");
        adjustment.setRemark(REMARK_SOURCE_PURCHASE_DETAIL_IMPORT);
        partInventoryService.inbound(adjustment);
    }

    PartInventoryCreateDTO buildPartDto(List<WorkbookRow> group, WorkbookRow latestRow) {
        int totalQuantity = group.stream().mapToInt(row -> intValue(row, 7, 0)).sum();
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal weightedQuantity = BigDecimal.ZERO;
        for (WorkbookRow row : group) {
            BigDecimal price = decimal(row, 8);
            int quantity = intValue(row, 7, 0);
            if (price != null && quantity > 0) {
                totalAmount = totalAmount.add(price.multiply(BigDecimal.valueOf(quantity)));
                weightedQuantity = weightedQuantity.add(BigDecimal.valueOf(quantity));
            }
        }
        BigDecimal averagePrice = weightedQuantity.signum() > 0
                ? totalAmount.divide(weightedQuantity, 2, RoundingMode.HALF_UP)
                : decimal(latestRow, 8);

        PartInventoryCreateDTO dto = new PartInventoryCreateDTO();
        dto.setPartCode(firstNonBlank(text(latestRow, 1), "PART"));
        dto.setPartBrand("Workbook import");
        dto.setPartName(firstNonBlank(text(latestRow, 4), dto.getPartCode()));
        dto.setSpecification(text(latestRow, 5));
        dto.setPartCategory(classifyPart(dto.getPartName(), dto.getSpecification(), text(latestRow, 2)));
        dto.setApplicableModels(text(latestRow, 5));
        dto.setSource(SOURCE_PURCHASE_DETAIL_IMPORT);
        dto.setQuantity(totalQuantity);
        dto.setUnit(text(latestRow, 6));
        dto.setPurchasePrice(averagePrice);
        dto.setLandedUnitCost(averagePrice);
        dto.setRemarks(buildPartRemark(group));
        dto.setInboundDate(dateTime(latestRow, 0));
        return dto;
    }

    boolean partChanged(PartInventory current, PartInventoryCreateDTO dto) {
        return !Objects.equals(trimToNull(current.getPartBrand()), trimToNull(dto.getPartBrand()))
                || !Objects.equals(trimToNull(current.getPartName()), trimToNull(dto.getPartName()))
                || !Objects.equals(trimToNull(current.getSpecification()), trimToNull(dto.getSpecification()))
                || !Objects.equals(trimToNull(current.getPartCategory()), trimToNull(dto.getPartCategory()))
                || !Objects.equals(trimToNull(current.getApplicableModels()), trimToNull(dto.getApplicableModels()))
                || !Objects.equals(trimToNull(current.getSource()), trimToNull(dto.getSource()))
                || !Objects.equals(current.getQuantity(), dto.getQuantity())
                || !Objects.equals(trimToNull(current.getUnit()), trimToNull(dto.getUnit()))
                || !Objects.equals(current.getPurchasePrice(), dto.getPurchasePrice())
                || !Objects.equals(current.getLandedUnitCost(), dto.getLandedUnitCost())
                || !Objects.equals(trimToNull(current.getRemarks()), trimToNull(dto.getRemarks()));
    }

    private boolean isOperational(PartInventory part) {
        return part != null
                && ((part.getQuantity() != null && part.getQuantity() > 0)
                || part.getSalesDate() != null
                || part.getInboundDate() != null);
    }

    private String buildPartRemark(List<WorkbookRow> rows) {
        List<String> notes = new ArrayList<>();
        notes.add(REMARK_SOURCE_PURCHASE_DETAIL_IMPORT);
        for (WorkbookRow row : rows.stream().limit(5).toList()) {
            addIfText(notes, text(row, 1));
            addIfText(notes, text(row, 2));
            addIfText(notes, text(row, 10));
            addIfText(notes, text(row, 11));
            addIfText(notes, text(row, 12));
        }
        return trimToLimit(joinNotes(notes.toArray(String[]::new)), 255);
    }

    private String classifyPart(String name, String specification, String documentType) {
        String joined = String.join(" ", Arrays.asList(
                firstNonBlank(name, ""),
                firstNonBlank(specification, ""),
                firstNonBlank(documentType, "")
        ));
        if (joined.contains(CATEGORY_TIRE)) return CATEGORY_TIRE;
        if (joined.contains(CATEGORY_BATTERY)) return CATEGORY_BATTERY;
        if (joined.contains("\u5145\u7535")) return CATEGORY_CHARGER;
        if (joined.contains(CATEGORY_FORK)) return CATEGORY_FORK;
        if (joined.contains(CATEGORY_MAST)) return CATEGORY_MAST;
        if (joined.contains("\u6db2\u538b")) return CATEGORY_HYDRAULIC;
        if (joined.contains("\u7535\u63a7")) return CATEGORY_ELECTRIC_CONTROL;
        return CATEGORY_PART;
    }

    private String text(WorkbookRow row, int index) {
        if (row == null || index < 0 || index >= row.values().size()) {
            return null;
        }
        return trimToNull(row.values().get(index));
    }

    private LocalDate date(WorkbookRow row, int index) {
        String value = text(row, index);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(value, DateTimeFormatter.ofPattern("yyyy/M/d"));
            } catch (DateTimeParseException ignoredAgain) {
                try {
                    return LocalDate.parse(value, DateTimeFormatter.ofPattern("yyyy-M-d"));
                } catch (DateTimeParseException ignoredThird) {
                    return null;
                }
            }
        }
    }

    private LocalDateTime dateTime(WorkbookRow row, int index) {
        String value = text(row, index);
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            LocalDate parsedDate = date(row, index);
            return parsedDate == null ? null : parsedDate.atStartOfDay();
        }
    }

    private BigDecimal decimal(WorkbookRow row, int index) {
        String value = text(row, index);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.replace(",", "").replace("\u5143", "")).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer intValue(WorkbookRow row, int index, int fallback) {
        String value = text(row, index);
        if (value == null) {
            return fallback;
        }
        try {
            return new BigDecimal(value.replace(",", "")).intValue();
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String joinNotes(String... values) {
        return Arrays.stream(values)
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .collect(Collectors.collectingAndThen(Collectors.toList(), list -> list.isEmpty() ? null : String.join(" / ", list)));
    }

    private void addIfText(List<String> list, String value) {
        if (hasText(value)) {
            list.add(value);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String trimToLimit(String value, int maxLength) {
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
