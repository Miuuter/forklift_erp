package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.exception.BusinessException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DataImportWorkbookReader {

    WorkbookSnapshot readVehicleWorkbook(Path file) {
        try (InputStream inputStream = Files.newInputStream(file);
             Workbook workbook = WorkbookFactory.create(inputStream)) {
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Map<String, List<WorkbookRow>> sheets = new LinkedHashMap<>();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                sheets.put(sheet.getSheetName(), readRows(sheet, formatter, evaluator));
            }
            return new WorkbookSnapshot(sheets);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "Failed to read workbook");
        }
    }

    WorkbookSnapshot readPartsWorkbook(Path file) {
        try (InputStream inputStream = Files.newInputStream(file);
             Workbook workbook = WorkbookFactory.create(inputStream)) {
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Map<String, List<WorkbookRow>> sheets = new LinkedHashMap<>();
            Sheet sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
            if (sheet != null) {
                sheets.put(sheet.getSheetName(), readRows(sheet, formatter, evaluator));
            }
            return new WorkbookSnapshot(sheets);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "Failed to read workbook");
        }
    }

    private List<WorkbookRow> readRows(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        List<WorkbookRow> rows = new ArrayList<>();
        if (sheet == null) {
            return rows;
        }
        int firstDataRow = Math.max(sheet.getFirstRowNum() + 1, 1);
        for (int rowIndex = firstDataRow; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            List<String> values = new ArrayList<>();
            boolean hasValue = false;
            for (int cellIndex = 0; cellIndex < Math.max(row.getLastCellNum(), 0); cellIndex++) {
                Cell cell = row.getCell(cellIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String value = cell == null ? null : formatter.formatCellValue(cell, evaluator).trim();
                if (value != null && !value.isBlank()) {
                    hasValue = true;
                }
                values.add(trimToNull(value));
            }
            if (hasValue) {
                rows.add(new WorkbookRow(rowIndex + 1, values));
            }
        }
        return rows;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}

record WorkbookSnapshot(Map<String, List<WorkbookRow>> sheets) {
    List<WorkbookRow> sheetRows(String name) {
        return sheets.getOrDefault(name, List.of());
    }

    int totalRows() {
        return sheets.values().stream().mapToInt(List::size).sum();
    }

    int totalRows(String... sheetNames) {
        if (sheetNames == null) {
            return 0;
        }
        int total = 0;
        for (String sheetName : sheetNames) {
            total += sheetRows(sheetName).size();
        }
        return total;
    }

    Map<String, Integer> sheetSizes() {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        for (Map.Entry<String, List<WorkbookRow>> entry : sheets.entrySet()) {
            sizes.put(entry.getKey(), entry.getValue().size());
        }
        return sizes;
    }
}

record WorkbookRow(int rowNumber, List<String> values) {
}
