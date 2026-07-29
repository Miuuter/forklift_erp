package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.dto.DataImportTemplateFile;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DataImportTemplateBuilderTests {

    private final DataImportTemplateBuilder builder = new DataImportTemplateBuilder();

    @Test
    void vehicleTemplateContainsExpectedWorkbookSheetsAndHeaders() throws Exception {
        DataImportTemplateFile template = builder.vehicleTemplate();

        assertThat(template.fileName()).isEqualTo("vehicle-workbook-template.xlsx");
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(template.content()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(6);
            assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("Inbound");
            assertThat(workbook.getSheet("Inbound").getRow(0).getCell(8).getStringCellValue()).isEqualTo("Vehicle No");
            assertThat(workbook.getSheet("Sales").getRow(0).getCell(14).getStringCellValue()).isEqualTo("Customer Name");
            assertThat(workbook.getSheet("OldSales").getRow(0).getCell(21).getStringCellValue()).isEqualTo("Brand");
        }
    }

    @Test
    void partTemplateContainsPartsSheet() throws Exception {
        DataImportTemplateFile template = builder.partTemplate();

        assertThat(template.fileName()).isEqualTo("parts-purchase-template.xlsx");
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(template.content()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("Parts");
            assertThat(workbook.getSheet("Parts").getRow(0).getCell(1).getStringCellValue()).isEqualTo("Part Code");
            assertThat(workbook.getSheet("Parts").getRow(0).getCell(4).getStringCellValue()).isEqualTo("Part Name");
            assertThat(workbook.getSheet("Parts").getRow(0).getCell(12).getStringCellValue()).isEqualTo("Source");
        }
    }
}
