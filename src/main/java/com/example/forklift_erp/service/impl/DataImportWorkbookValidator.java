package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.dto.DataImportErrorVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class DataImportWorkbookValidator {
    private static final List<String> VEHICLE_DATA_SHEETS = List.of(
            "Inbound", "Sales", "OtherBrandSales", "OldInbound", "OldSales"
    );
    private static final int MONEY_INTEGER_DIGITS = 10;
    private static final int MONEY_FRACTION_DIGITS = 2;

    List<DataImportErrorVO> validateVehicleRows(WorkbookSnapshot snapshot) {
        List<DataImportErrorVO> errors = new ArrayList<>();
        if (snapshot == null || VEHICLE_DATA_SHEETS.stream().noneMatch(snapshot.sheets()::containsKey)) {
            errors.add(error(null, null, "sheets",
                    "Workbook must contain at least one supported vehicle data sheet", null));
            return errors;
        }
        if (snapshot.totalRows(VEHICLE_DATA_SHEETS.toArray(String[]::new)) == 0) {
            errors.add(error(null, null, "rows", "Workbook contains no vehicle data rows", null));
            return errors;
        }

        validateVehicleSheet(snapshot.sheetRows("Inbound"), "Inbound", 8, -1, errors);
        validateVehicleSheet(snapshot.sheetRows("Sales"), "Sales", 5, 14, errors);
        validateVehicleSheet(snapshot.sheetRows("OtherBrandSales"), "OtherBrandSales", 6, 13, errors);
        validateVehicleSheet(snapshot.sheetRows("OldInbound"), "OldInbound", 6, -1, errors);
        validateVehicleSheet(snapshot.sheetRows("OldSales"), "OldSales", 5, 13, errors);

        validateDates(snapshot.sheetRows("Inbound"), "Inbound", errors,
                field(1, "inboundDate"), field(12, "manufacturingDate"), field(16, "salesReportDate"));
        validateDates(snapshot.sheetRows("Sales"), "Sales", errors,
                field(1, "salesDate"), field(20, "invoiceIssuedDate"),
                field(22, "salesReportDate"), field(25, "invoiceApplicationDate"));
        validateDates(snapshot.sheetRows("OtherBrandSales"), "OtherBrandSales", errors,
                field(1, "salesDate"), field(19, "invoiceIssuedDate"));
        validateDates(snapshot.sheetRows("OldInbound"), "OldInbound", errors,
                field(1, "inboundDate"), field(10, "manufacturingDate"));
        validateDates(snapshot.sheetRows("OldSales"), "OldSales", errors,
                field(1, "salesDate"), field(19, "invoiceIssuedDate"));

        validateMoney(snapshot.sheetRows("Inbound"), "Inbound", errors,
                field(13, "purchasePrice"));
        validateMoney(snapshot.sheetRows("Sales"), "Sales", errors,
                field(9, "settlementPrice"), field(11, "salePrice"));
        validateMoney(snapshot.sheetRows("OtherBrandSales"), "OtherBrandSales", errors,
                field(10, "settlementPrice"));
        validateMoney(snapshot.sheetRows("OldInbound"), "OldInbound", errors,
                field(8, "salePrice"));
        validateMoney(snapshot.sheetRows("OldSales"), "OldSales", errors,
                field(9, "salePrice"), field(11, "settlementPrice"));

        validateInteger(snapshot.sheetRows("Inbound"), "Inbound", 18, "inventoryCount", 0, errors);
        validateInteger(snapshot.sheetRows("OldInbound"), "OldInbound", 13, "inventoryCount", 1, errors);
        validateInteger(snapshot.sheetRows("OldSales"), "OldSales", 10, "quantity", 1, errors);
        return errors;
    }

    List<DataImportErrorVO> validatePartRows(WorkbookSnapshot snapshot) {
        List<DataImportErrorVO> errors = new ArrayList<>();
        if (snapshot == null || !snapshot.sheets().containsKey("Parts")) {
            errors.add(error("Parts", null, "sheet", "Workbook must contain a Parts sheet", null));
            return errors;
        }
        List<WorkbookRow> rows = snapshot.sheetRows("Parts");
        if (rows.isEmpty()) {
            errors.add(error("Parts", null, "rows", "Parts sheet contains no data rows", null));
            return errors;
        }
        for (WorkbookRow row : rows) {
            requireText(row, "Parts", 1, "partCode", "Part code is required", errors);
            requireText(row, "Parts", 4, "partName", "Part name is required", errors);
            validateLength(row, "Parts", 1, "partCode", 100, errors);
            validateLength(row, "Parts", 4, "partName", 100, errors);
            validateLength(row, "Parts", 5, "specification", 100, errors);
            validateLength(row, "Parts", 6, "unit", 20, errors);
            validateRequiredInteger(row, "Parts", 7, "quantity", 1, errors);
            validateMoney(row, "Parts", 8, "unitPrice", errors);
            validateDate(row, "Parts", 0, "inboundDate", errors);
        }
        return errors;
    }

    private void validateVehicleSheet(
            List<WorkbookRow> rows,
            String sheetName,
            int vehicleColumn,
            int customerColumn,
            List<DataImportErrorVO> errors
    ) {
        Set<String> seenVehicles = new HashSet<>();
        for (WorkbookRow row : rows) {
            String vehicleNumber = text(row, vehicleColumn);
            if (vehicleNumber == null || isPlaceholderVehicle(vehicleNumber)) {
                errors.add(error(sheetName, row.rowNumber(), "vehicleNumber",
                        "Vehicle number is required", vehicleNumber));
            } else if (!seenVehicles.add(vehicleNumber.toUpperCase(Locale.ROOT))) {
                errors.add(error(sheetName, row.rowNumber(), "vehicleNumber",
                        "Duplicate vehicle number in sheet", vehicleNumber));
            }
            if (customerColumn >= 0 && text(row, customerColumn) == null) {
                errors.add(error(sheetName, row.rowNumber(), "customerName",
                        "Customer name is required", null));
            }
        }
    }

    private void validateDates(
            List<WorkbookRow> rows,
            String sheetName,
            List<DataImportErrorVO> errors,
            Field... fields
    ) {
        for (WorkbookRow row : rows) {
            for (Field field : fields) {
                validateDate(row, sheetName, field.column(), field.name(), errors);
            }
        }
    }

    private void validateDate(
            WorkbookRow row,
            String sheetName,
            int column,
            String fieldName,
            List<DataImportErrorVO> errors
    ) {
        String value = text(row, column);
        if (value != null && !isDate(value)) {
            errors.add(error(sheetName, row.rowNumber(), fieldName,
                    "Date must use a valid ISO or yyyy/M/d format", value));
        }
    }

    private void validateMoney(
            List<WorkbookRow> rows,
            String sheetName,
            List<DataImportErrorVO> errors,
            Field... fields
    ) {
        for (WorkbookRow row : rows) {
            for (Field field : fields) {
                validateMoney(row, sheetName, field.column(), field.name(), errors);
            }
        }
    }

    private void validateMoney(
            WorkbookRow row,
            String sheetName,
            int column,
            String fieldName,
            List<DataImportErrorVO> errors
    ) {
        String value = text(row, column);
        if (value == null) {
            return;
        }
        BigDecimal amount = decimal(value);
        if (amount == null || amount.signum() < 0 || !fitsMoneyColumn(amount)) {
            errors.add(error(sheetName, row.rowNumber(), fieldName,
                    "Amount must be non-negative with at most 10 integer and 2 decimal digits", value));
        }
    }

    private void validateInteger(
            List<WorkbookRow> rows,
            String sheetName,
            int column,
            String fieldName,
            int minimum,
            List<DataImportErrorVO> errors
    ) {
        for (WorkbookRow row : rows) {
            String value = text(row, column);
            if (value != null && exactInteger(value, minimum) == null) {
                errors.add(error(sheetName, row.rowNumber(), fieldName,
                        integerMessage(minimum), value));
            }
        }
    }

    private void validateRequiredInteger(
            WorkbookRow row,
            String sheetName,
            int column,
            String fieldName,
            int minimum,
            List<DataImportErrorVO> errors
    ) {
        String value = text(row, column);
        if (value == null || exactInteger(value, minimum) == null) {
            errors.add(error(sheetName, row.rowNumber(), fieldName,
                    integerMessage(minimum), value));
        }
    }

    private Integer exactInteger(String value, int minimum) {
        try {
            int number = new BigDecimal(value.replace(",", "")).intValueExact();
            return number < minimum ? null : number;
        } catch (ArithmeticException | NumberFormatException ex) {
            return null;
        }
    }

    private String integerMessage(int minimum) {
        return minimum > 0
                ? "Value must be a positive whole number within the supported range"
                : "Value must be a non-negative whole number within the supported range";
    }

    private void requireText(
            WorkbookRow row,
            String sheetName,
            int column,
            String fieldName,
            String message,
            List<DataImportErrorVO> errors
    ) {
        if (text(row, column) == null) {
            errors.add(error(sheetName, row.rowNumber(), fieldName, message, null));
        }
    }

    private void validateLength(
            WorkbookRow row,
            String sheetName,
            int column,
            String fieldName,
            int maximum,
            List<DataImportErrorVO> errors
    ) {
        String value = text(row, column);
        if (value != null && value.length() > maximum) {
            errors.add(error(sheetName, row.rowNumber(), fieldName,
                    "Value cannot exceed " + maximum + " characters", value));
        }
    }

    private boolean isDate(String value) {
        try {
            LocalDateTime.parse(value);
            return true;
        } catch (DateTimeParseException ignored) {
            // Continue with date-only formats supported by the row mappers.
        }
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("yyyy/M/d"),
                DateTimeFormatter.ofPattern("yyyy-M-d")
        )) {
            try {
                LocalDate.parse(value, formatter);
                return true;
            } catch (DateTimeParseException ignored) {
                // Try the next supported format.
            }
        }
        return false;
    }

    private BigDecimal decimal(String value) {
        try {
            return new BigDecimal(value.replace(",", "").replace("\u5143", "").trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean fitsMoneyColumn(BigDecimal amount) {
        BigDecimal normalized = amount.stripTrailingZeros();
        int fractionDigits = Math.max(normalized.scale(), 0);
        int integerDigits = Math.max(normalized.precision() - normalized.scale(), 1);
        return fractionDigits <= MONEY_FRACTION_DIGITS && integerDigits <= MONEY_INTEGER_DIGITS;
    }

    private boolean isPlaceholderVehicle(String value) {
        String normalized = value.replace("<", "").replace(">", "").trim();
        return Set.of("/", "\\", "-", "--", "0", "none", "null", "n/a")
                .contains(normalized.toLowerCase(Locale.ROOT));
    }

    private String text(WorkbookRow row, int index) {
        if (row == null || index < 0 || index >= row.values().size()) {
            return null;
        }
        String value = row.values().get(index);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private DataImportErrorVO error(
            String sheetName,
            Integer rowNumber,
            String fieldName,
            String message,
            String value
    ) {
        return new DataImportErrorVO(sheetName, rowNumber, fieldName, message, value);
    }

    private Field field(int column, String name) {
        return new Field(column, name);
    }

    private record Field(int column, String name) {
    }
}
