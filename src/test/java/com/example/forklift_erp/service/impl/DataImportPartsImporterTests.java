package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.dto.PartInventoryCreateDTO;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.service.PartInventoryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataImportPartsImporterTests {

    @Test
    void importWorkbookCreatesMissingPartWithGroupedQuantityAndWeightedPrice() {
        PartInventoryService partInventoryService = mock(PartInventoryService.class);
        when(partInventoryService.findByPartCode("P-001")).thenReturn(Optional.empty());
        DataImportPartsImporter importer = newImporter(partInventoryService);

        ImportResult result = importer.importWorkbook(snapshot(
                new WorkbookRow(2, row("2024-01-01", "P-001", "\u91c7\u8d2d\u5355", "", "\u8f6e\u80ce", "23x9-10", "\u4ef6", "2", "10.00", "", "A")),
                new WorkbookRow(3, row("2024-01-02", "P-001", "\u91c7\u8d2d\u5355", "", "\u8f6e\u80ce", "23x9-10", "\u4ef6", "3", "20.00", "", "B"))
        ), openingContext());

        ArgumentCaptor<PartInventoryCreateDTO> captor = ArgumentCaptor.forClass(PartInventoryCreateDTO.class);
        verify(partInventoryService).create(captor.capture());
        PartInventoryCreateDTO dto = captor.getValue();
        assertThat(result.importedRows()).isEqualTo(1);
        assertThat(result.summary()).isEqualTo("Mode=OPENING_MIGRATION, imported parts created=1, updated=0, reused=0");
        assertThat(dto.getPartCode()).isEqualTo("P-001");
        assertThat(dto.getQuantity()).isZero();
        assertThat(dto.getPurchasePrice()).isEqualByComparingTo("16.00");
        assertThat(dto.getLandedUnitCost()).isEqualByComparingTo("16.00");
        assertThat(dto.getPartCategory()).isEqualTo("\u8f6e\u80ce");
        assertThat(dto.getSource()).isEqualTo("\u91c7\u8d2d\u660e\u7ec6\u5bfc\u5165");
        assertThat(dto.getInboundDate()).isEqualTo(LocalDateTime.of(2024, 1, 2, 0, 0));
    }

    @Test
    void importWorkbookUpdatesChangedExistingPartWithExpectedVersion() {
        PartInventoryService partInventoryService = mock(PartInventoryService.class);
        PartInventory existing = new PartInventory();
        existing.setId(7L);
        existing.setVersion(3L);
        existing.setPartCode("P-002");
        existing.setPartBrand("Old brand");
        existing.setPartName("Old name");
        existing.setQuantity(0);
        when(partInventoryService.findByPartCode("P-002")).thenReturn(Optional.of(existing));
        DataImportPartsImporter importer = newImporter(partInventoryService);

        ImportResult result = importer.importWorkbook(snapshot(
                new WorkbookRow(2, row("2024-02-01", "P-002", "\u91c7\u8d2d\u5355", "", "Filter", "F-10", "pcs", "4", "12.50"))
        ), openingContext());

        ArgumentCaptor<PartInventoryCreateDTO> captor = ArgumentCaptor.forClass(PartInventoryCreateDTO.class);
        verify(partInventoryService).update(eq(7L), captor.capture());
        verify(partInventoryService, never()).create(any());
        assertThat(result.summary()).isEqualTo("Mode=OPENING_MIGRATION, imported parts created=0, updated=1, reused=0");
        assertThat(captor.getValue().getVersion()).isEqualTo(3L);
        assertThat(captor.getValue().getPartName()).isEqualTo("Filter");
    }

    @Test
    void importWorkbookReusesUnchangedExistingPart() {
        PartInventoryService partInventoryService = mock(PartInventoryService.class);
        DataImportPartsImporter importer = newImporter(partInventoryService);
        WorkbookRow row = new WorkbookRow(2, row("2024-03-01", "P-003", "\u91c7\u8d2d\u5355", "", "Bearing", "B-20", "pcs", "2", "8.00", "", "N1"));
        PartInventoryCreateDTO dto = importer.buildPartDto(List.of(row), row);
        when(partInventoryService.findByPartCode("P-003")).thenReturn(Optional.of(partFromDto(8L, 5L, dto)));

        ImportResult result = importer.importWorkbook(snapshot(row), openingContext());

        verify(partInventoryService).findByPartCode("P-003");
        verify(partInventoryService, never()).create(any());
        verify(partInventoryService, never()).update(any(), any());
        assertThat(result.summary()).isEqualTo("Mode=OPENING_MIGRATION, imported parts created=0, updated=0, reused=1");
    }

    private WorkbookSnapshot snapshot(WorkbookRow... rows) {
        return new WorkbookSnapshot(Map.of("Parts", List.of(rows)));
    }

    private DataImportPartsImporter newImporter(PartInventoryService partInventoryService) {
        DataImportIdempotencyService idempotencyService = mock(DataImportIdempotencyService.class);
        when(idempotencyService.reserve(any(), any(), anyInt(), any())).thenReturn(true);
        return new DataImportPartsImporter(partInventoryService, idempotencyService);
    }

    private ImportContext openingContext() {
        return new ImportContext(1L, "parts-purchase", ImportContext.MODE_OPENING_MIGRATION, "parts-test-file");
    }

    private List<String> row(String... values) {
        return List.of(values);
    }

    private PartInventory partFromDto(Long id, Long version, PartInventoryCreateDTO dto) {
        PartInventory part = new PartInventory();
        part.setId(id);
        part.setVersion(version);
        part.setPartCode(dto.getPartCode());
        part.setPartBrand(dto.getPartBrand());
        part.setPartName(dto.getPartName());
        part.setSpecification(dto.getSpecification());
        part.setPartCategory(dto.getPartCategory());
        part.setApplicableModels(dto.getApplicableModels());
        part.setSource(dto.getSource());
        part.setQuantity(dto.getQuantity());
        part.setUnit(dto.getUnit());
        part.setPurchasePrice(new BigDecimal("8.00"));
        part.setSettlementPrice(new BigDecimal("8.00"));
        part.setRemarks(dto.getRemarks());
        return part;
    }
}
