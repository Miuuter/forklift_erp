package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.dto.CustomerDTO;
import com.example.forklift_erp.dto.CustomerVO;
import com.example.forklift_erp.dto.MachineInventoryCreateDTO;
import com.example.forklift_erp.dto.VehicleOutboundOrderCreateDTO;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class DataImportVehicleRowMapper {

    CustomerDTO buildCustomerFromSalesRow(String companyName, WorkbookRow row) {
        return buildCustomer(companyName, row, 15, 16, 17, 18,
                text(row, 10), text(row, 20), text(row, 26), text(row, 27));
    }

    CustomerDTO buildCustomerFromOtherBrandRow(String companyName, WorkbookRow row) {
        return buildCustomer(companyName, row, 14, 15, 16, 17, text(row, 20));
    }

    CustomerDTO buildCustomerFromOldSalesRow(String companyName, WorkbookRow row) {
        return buildCustomer(companyName, row, 14, 15, 16, 17, text(row, 20));
    }

    private CustomerDTO buildCustomer(
            String companyName,
            WorkbookRow row,
            int addressColumn,
            int contactNameColumn,
            int contactPhoneColumn,
            int taxIdColumn,
            String... remarks
    ) {
        CustomerDTO dto = new CustomerDTO();
        dto.setCompanyName(companyName);
        dto.setAddress(text(row, addressColumn));
        dto.setContactName(text(row, contactNameColumn));
        dto.setContactPhone(text(row, contactPhoneColumn));
        dto.setTaxOrIdNumber(text(row, taxIdColumn));
        String[] notes = new String[remarks.length + 1];
        notes[0] = "Workbook import";
        System.arraycopy(remarks, 0, notes, 1, remarks.length);
        dto.setRemarks(trimToLimit(joinNotes(notes), 255));
        return dto;
    }

    boolean customerChanged(CustomerVO existing, CustomerDTO dto) {
        return !Objects.equals(trimToNull(existing.getAddress()), trimToNull(dto.getAddress()))
                || !Objects.equals(trimToNull(existing.getContactName()), trimToNull(dto.getContactName()))
                || !Objects.equals(trimToNull(existing.getContactPhone()), trimToNull(dto.getContactPhone()))
                || !Objects.equals(trimToNull(existing.getTaxOrIdNumber()), trimToNull(dto.getTaxOrIdNumber()))
                || !Objects.equals(trimToNull(existing.getRemarks()), trimToNull(dto.getRemarks()));
    }

    VehicleOutboundOrderCreateDTO buildVehicleOutboundPayload(MachineInventory machine, CustomerVO customer, WorkbookRow row) {
        VehicleOutboundOrderCreateDTO dto = new VehicleOutboundOrderCreateDTO();
        dto.setMachineId(machine.getId());
        dto.setMachineVersion(machine.getVersion());
        dto.setCustomerId(customer.getId());
        dto.setSalesDate(date(row, 1));
        dto.setSettlementPrice(decimal(row, 9));
        dto.setSalePrice(decimal(row, 11));
        dto.setPaymentSettled(parseBool(text(row, 13)));
        dto.setPaymentRemark(text(row, 12));
        dto.setSalesReported(parseBool(text(row, 21)));
        dto.setSalesReportDate(date(row, 22));
        dto.setInvoiceApplied(parseBool(text(row, 24)));
        dto.setInvoiceApplicationDate(date(row, 25));
        dto.setInvoiceStatus(text(row, 19));
        dto.setInvoiceIssuedDate(date(row, 20));
        dto.setRegistrationStatus(text(row, 23));
        dto.setContractType(text(row, 28));
        dto.setOperator("import-workbook");
        dto.setOrderRemark(joinNotes(text(row, 10), text(row, 26), text(row, 27)));
        return dto;
    }

    VehicleOutboundOrderCreateDTO buildOtherBrandOutboundPayload(MachineInventory machine, CustomerVO customer, WorkbookRow row) {
        VehicleOutboundOrderCreateDTO dto = new VehicleOutboundOrderCreateDTO();
        dto.setMachineId(machine.getId());
        dto.setMachineVersion(machine.getVersion());
        dto.setCustomerId(customer.getId());
        dto.setSalesDate(date(row, 1));
        dto.setSettlementPrice(decimal(row, 10));
        dto.setSalePrice(decimal(row, 10));
        dto.setPaymentSettled(parseBool(text(row, 12)));
        dto.setPaymentRemark(text(row, 11));
        dto.setInvoiceStatus(text(row, 18));
        dto.setInvoiceIssuedDate(date(row, 19));
        dto.setOperator("import-workbook");
        dto.setOrderRemark(joinNotes("Other brand sales", text(row, 20)));
        return dto;
    }

    VehicleOutboundOrderCreateDTO buildOldSalesOutboundPayload(MachineInventory machine, CustomerVO customer, WorkbookRow row) {
        VehicleOutboundOrderCreateDTO dto = new VehicleOutboundOrderCreateDTO();
        dto.setMachineId(machine.getId());
        dto.setMachineVersion(machine.getVersion());
        dto.setCustomerId(customer.getId());
        dto.setSalesDate(date(row, 1));
        dto.setSettlementPrice(decimal(row, 11));
        dto.setSalePrice(decimal(row, 9));
        dto.setPaymentRemark(text(row, 12));
        dto.setInvoiceStatus(text(row, 18));
        dto.setInvoiceIssuedDate(date(row, 19));
        dto.setOperator("import-workbook");
        dto.setOrderRemark(joinNotes("Used vehicle sales", text(row, 20)));
        return dto;
    }

    MachineInventoryCreateDTO buildMachineFromInboundRow(WorkbookRow row, String vehicleNumber) {
        MachineInventoryCreateDTO dto = new MachineInventoryCreateDTO();
        dto.setVehicleProductNumber(vehicleNumber);
        dto.setName(firstNonBlank(text(row, 4), "Workbook vehicle"));
        dto.setSpecificationModel(firstNonBlank(text(row, 6), "Workbook model"));
        dto.setConfiguration(text(row, 7));
        dto.setMachineType(firstNonBlank(text(row, 6), text(row, 4), "Internal combustion forklift"));
        dto.setSupplier(text(row, 2));
        dto.setWarehouseName(firstNonBlank(text(row, 24), "Workbook warehouse"));
        dto.setApplicationNumber(text(row, 3));
        dto.setMaterialNumber(text(row, 5));
        dto.setEngineNumber(text(row, 9));
        dto.setFrameNumber(text(row, 10));
        dto.setWarrantyCardNumber(text(row, 11));
        dto.setManufacturingDate(date(row, 12));
        dto.setInboundDate(dateTime(row, 1));
        dto.setPurchasePrice(decimal(row, 13));
        dto.setSettlementPrice(decimal(row, 13));
        dto.setSalePrice(decimal(row, 13));
        dto.setInventoryCount(intValue(row, 18, 1));
        dto.setDestination1(text(row, 19));
        dto.setDestination2(text(row, 20));
        dto.setDestination3(text(row, 21));
        dto.setDestination4(text(row, 22));
        dto.setDestination5(text(row, 23));
        dto.setIsSalesReported(text(row, 15));
        dto.setSalesReportDate(date(row, 16));
        dto.setIsInvoiceApplied(firstNonBlank(text(row, 25), text(row, 17)));
        dto.setRemarks(joinNotes(text(row, 14), text(row, 26)));
        dto.setModelOnly(false);
        dto.setStockStatus("IN_STOCK");
        return normalizeMachinePayload(dto);
    }

    MachineInventoryCreateDTO buildMachineFromSalesRow(WorkbookRow row, String vehicleNumber, String source) {
        MachineInventoryCreateDTO dto = new MachineInventoryCreateDTO();
        dto.setVehicleProductNumber(vehicleNumber);
        dto.setName(firstNonBlank(text(row, 2), "Workbook vehicle"));
        dto.setSpecificationModel(firstNonBlank(text(row, 3), text(row, 4), "Workbook model"));
        dto.setConfiguration(text(row, 4));
        dto.setMachineType(firstNonBlank(text(row, 2), text(row, 3), text(row, 4), "Internal combustion forklift"));
        dto.setSupplier(firstNonBlank(text(row, 3), source));
        dto.setWarehouseName(source);
        dto.setEngineNumber(text(row, 6));
        dto.setFrameNumber(text(row, 7));
        dto.setWarrantyCardNumber(text(row, 8));
        dto.setInboundDate(dateTime(row, 1));
        dto.setPurchasePrice(decimal(row, 9));
        dto.setSettlementPrice(decimal(row, 9));
        dto.setSalePrice(decimal(row, 11));
        dto.setInventoryCount(1);
        dto.setIsSalesReported(text(row, 21));
        dto.setSalesReportDate(date(row, 22));
        dto.setIsInvoiceApplied(text(row, 24));
        dto.setRemarks(joinNotes(source, text(row, 10), text(row, 26)));
        dto.setStockStatus("IN_STOCK");
        dto.setModelOnly(false);
        return normalizeMachinePayload(dto);
    }

    MachineInventoryCreateDTO buildMachineFromOtherBrandRow(WorkbookRow row, String vehicleNumber) {
        MachineInventoryCreateDTO dto = new MachineInventoryCreateDTO();
        dto.setVehicleProductNumber(vehicleNumber);
        dto.setName(firstNonBlank(text(row, 2), "Other brand vehicle"));
        dto.setSpecificationModel(firstNonBlank(text(row, 4), "Other brand model"));
        dto.setConfiguration(text(row, 5));
        dto.setMachineType(firstNonBlank(text(row, 2), text(row, 4), "Other brand"));
        dto.setSupplier(firstNonBlank(text(row, 3), "Other brand"));
        dto.setWarehouseName("Other brand sales");
        dto.setEngineNumber(text(row, 7));
        dto.setFrameNumber(text(row, 8));
        dto.setWarrantyCardNumber(text(row, 9));
        dto.setInboundDate(dateTime(row, 1));
        dto.setSettlementPrice(decimal(row, 10));
        dto.setSalePrice(decimal(row, 10));
        dto.setInventoryCount(1);
        dto.setRemarks(joinNotes("Other brand sales", text(row, 20)));
        dto.setStockStatus("IN_STOCK");
        dto.setModelOnly(false);
        return normalizeMachinePayload(dto);
    }

    MachineInventoryCreateDTO buildMachineFromOldInboundRow(WorkbookRow row, String vehicleNumber) {
        MachineInventoryCreateDTO dto = new MachineInventoryCreateDTO();
        dto.setVehicleProductNumber(vehicleNumber);
        dto.setName(firstNonBlank(text(row, 3), "Old vehicle"));
        dto.setSpecificationModel(firstNonBlank(text(row, 4), "Old vehicle model"));
        dto.setConfiguration(text(row, 5));
        dto.setMachineType(firstNonBlank(text(row, 3), text(row, 4), "Old vehicle"));
        dto.setSupplier("Old vehicle recovery");
        dto.setWarehouseName("Old vehicle stock");
        dto.setEngineNumber(text(row, 7));
        dto.setFrameNumber(text(row, 9));
        dto.setManufacturingDate(date(row, 10));
        dto.setInboundDate(dateTime(row, 1));
        dto.setSalePrice(decimal(row, 8));
        dto.setInventoryCount(Math.max(1, intValue(row, 13, 1)));
        dto.setDestination1(text(row, 14));
        dto.setDestination2(text(row, 15));
        dto.setDestination3(text(row, 16));
        dto.setDestination4(text(row, 17));
        dto.setDestination5(text(row, 18));
        dto.setRemarks(joinNotes(text(row, 2), text(row, 11), text(row, 12), text(row, 19)));
        dto.setStockStatus("IN_STOCK");
        dto.setModelOnly(false);
        return normalizeMachinePayload(dto);
    }

    MachineInventoryCreateDTO buildMachineFromOldSalesRow(WorkbookRow row, String vehicleNumber) {
        MachineInventoryCreateDTO dto = new MachineInventoryCreateDTO();
        dto.setVehicleProductNumber(vehicleNumber);
        dto.setName(firstNonBlank(text(row, 2), "Used vehicle"));
        dto.setSpecificationModel(firstNonBlank(text(row, 3), "Used vehicle model"));
        dto.setConfiguration(text(row, 4));
        dto.setMachineType(firstNonBlank(text(row, 2), text(row, 3), "Used vehicle"));
        dto.setSupplier(firstNonBlank(text(row, 21), "Used vehicle"));
        dto.setWarehouseName("Used vehicle sales");
        dto.setEngineNumber(text(row, 6));
        dto.setFrameNumber(text(row, 7));
        dto.setWarrantyCardNumber(text(row, 8));
        dto.setInboundDate(dateTime(row, 1));
        dto.setSettlementPrice(decimal(row, 11));
        dto.setSalePrice(decimal(row, 9));
        dto.setInventoryCount(Math.max(1, intValue(row, 10, 1)));
        dto.setRemarks(joinNotes("Used vehicle sales", text(row, 20)));
        dto.setStockStatus("IN_STOCK");
        dto.setModelOnly(false);
        return normalizeMachinePayload(dto);
    }

    Map<String, WorkbookRow> indexByVehicle(List<WorkbookRow> rows, int vehicleColumn) {
        Map<String, WorkbookRow> map = new LinkedHashMap<>();
        for (WorkbookRow row : rows) {
            String vehicleNumber = cleanVehicleNumber(text(row, vehicleColumn));
            if (hasText(vehicleNumber) && !map.containsKey(vehicleNumber)) {
                map.put(vehicleNumber, row);
            }
        }
        return map;
    }

    String generatedVehicleNumber(String prefix, int rowNumber, WorkbookRow row) {
        String serial = text(row, 0);
        if (hasText(serial)) {
            String safeSerial = serial.replaceAll("[^A-Za-z0-9]", "");
            if (hasText(safeSerial)) {
                return prefix + "-" + safeSerial.substring(0, Math.min(20, safeSerial.length()));
            }
        }
        return prefix + "-ROW-" + rowNumber;
    }

    String cleanVehicleNumber(String value) {
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }
        String normalized = text.replace("<", "").replace(">", "").trim();
        if (Set.of("/", "\\", "-", "--", "0", "none", "null", "n/a", "N/A").contains(normalized)) {
            return null;
        }
        return normalized;
    }

    String text(WorkbookRow row, int index) {
        if (row == null || index < 0 || index >= row.values().size()) {
            return null;
        }
        return trimToNull(row.values().get(index));
    }

    String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private MachineInventoryCreateDTO normalizeMachinePayload(MachineInventoryCreateDTO dto) {
        dto.setVehicleProductNumber(trimToNull(dto.getVehicleProductNumber()));
        dto.setName(firstNonBlank(dto.getName(), "Workbook vehicle"));
        dto.setSpecificationModel(firstNonBlank(dto.getSpecificationModel(), "Workbook model"));
        dto.setMachineType(trimToNull(dto.getMachineType()));
        dto.setConfiguration(trimToNull(dto.getConfiguration()));
        dto.setSupplier(trimToNull(dto.getSupplier()));
        dto.setWarehouseName(trimToNull(dto.getWarehouseName()));
        dto.setApplicationNumber(trimToNull(dto.getApplicationNumber()));
        dto.setMaterialNumber(trimToNull(dto.getMaterialNumber()));
        dto.setEngineNumber(trimToNull(dto.getEngineNumber()));
        dto.setFrameNumber(trimToNull(dto.getFrameNumber()));
        dto.setWarrantyCardNumber(trimToNull(dto.getWarrantyCardNumber()));
        dto.setPurchasePrice(scaleMoney(dto.getPurchasePrice()));
        dto.setSalePrice(scaleMoney(dto.getSalePrice()));
        dto.setSettlementPrice(scaleMoney(dto.getSettlementPrice()));
        dto.setRemarks(trimToNull(dto.getRemarks()));
        if (dto.getInventoryCount() == null) {
            dto.setInventoryCount(1);
        }
        if (dto.getStockStatus() == null) {
            dto.setStockStatus("IN_STOCK");
        }
        return dto;
    }

    private String joinNotes(String... values) {
        return Arrays.stream(values)
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .collect(Collectors.collectingAndThen(Collectors.toList(), list -> list.isEmpty() ? null : String.join(" / ", list)));
    }

    private boolean parseBool(String value) {
        String text = trimToNull(value);
        if (text == null) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return !(normalized.startsWith("\u5426") || normalized.startsWith("\u65e0") || normalized.startsWith("\u6ca1")
                || normalized.startsWith("\u4e0d") || normalized.startsWith("0") || normalized.startsWith("/"));
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
                    throw invalidCell(row, index, "date");
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
        } catch (NumberFormatException | ArithmeticException ex) {
            throw invalidCell(row, index, "amount");
        }
    }

    private Integer intValue(WorkbookRow row, int index, int fallback) {
        String value = text(row, index);
        if (value == null) {
            return fallback;
        }
        try {
            return new BigDecimal(value.replace(",", "")).intValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            throw invalidCell(row, index, "whole number");
        }
    }

    private BusinessException invalidCell(WorkbookRow row, int index, String expectedType) {
        return new BusinessException(ResultCode.PARAM_ERROR,
                "Invalid " + expectedType + " at workbook row "
                        + (row == null ? "unknown" : row.rowNumber()) + ", column " + (index + 1));
    }

    private BigDecimal scaleMoney(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String trimToLimit(String value, int maxLength) {
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
