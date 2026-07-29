package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.dto.DataImportTemplateFile;
import com.example.forklift_erp.exception.BusinessException;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class DataImportTemplateBuilder {

    DataImportTemplateFile vehicleTemplate() {
        try (Workbook workbook = new XSSFWorkbook()) {
            createSheet(workbook, "Inbound", List.of(
                    "ID", "Inbound Date", "Supplier", "Application No", "Name", "Material No", "Model",
                    "Configuration", "Vehicle No", "Engine No", "Frame No", "Warranty Card", "Manufacturing Date",
                    "Purchase Price", "Remarks", "Sales Reported", "Sales Report Date", "Invoice Applied",
                    "Inventory Count", "Destination 1", "Destination 2", "Destination 3", "Destination 4", "Destination 5",
                    "Warehouse", "Invoice Applied Flag", "Extra Remarks"
            ));
            createSheet(workbook, "Sales", List.of(
                    "ID", "Sales Date", "Name", "Brand", "Configuration", "Vehicle No", "Engine No", "Frame No",
                    "Warranty Card", "Settlement Price", "Sales Note", "Sale Price", "Payment Note", "Payment Settled",
                    "Customer Name", "Customer Address", "Contact Name", "Contact Phone", "Tax ID", "Invoice Status",
                    "Invoice Issued Date", "Sales Reported", "Sales Report Date", "Registration Status",
                    "Invoice Applied", "Invoice Applied Date", "Order Remark", "Extra Remark", "Contract Type"
            ));
            createSheet(workbook, "AutoReport", List.of("Auto report sheet", "unused"));
            createSheet(workbook, "OtherBrandSales", List.of(
                    "ID", "Sales Date", "Name", "Brand", "Model", "Configuration", "Vehicle No", "Engine No",
                    "Frame No", "Warranty Card", "Settlement Price", "Payment Note", "Payment Settled",
                    "Customer Name", "Customer Address", "Contact Name", "Contact Phone", "Tax ID", "Invoice Status",
                    "Invoice Issued Date", "Remark"
            ));
            createSheet(workbook, "OldInbound", List.of(
                    "ID", "Inbound Date", "Note", "Name", "Model", "Configuration", "Vehicle No", "Engine No",
                    "Sale Price", "Frame No", "Manufacturing Date", "Remark", "Warehouse Remark", "Inventory Count",
                    "Destination 1", "Destination 2", "Destination 3", "Destination 4", "Destination 5", "Extra Remark"
            ));
            createSheet(workbook, "OldSales", List.of(
                    "ID", "Sales Date", "Name", "Model", "Configuration", "Vehicle No", "Engine No", "Frame No",
                    "Warranty Card", "Sale Price", "Quantity", "Settlement Price", "Payment Note", "Customer Name",
                    "Customer Address", "Contact Name", "Contact Phone", "Tax ID", "Invoice Status",
                    "Invoice Issued Date", "Remark", "Brand"
            ));
            return new DataImportTemplateFile("vehicle-workbook-template.xlsx", toBytes(workbook));
        } catch (IOException e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "Template generation failed");
        }
    }

    DataImportTemplateFile partTemplate() {
        try (Workbook workbook = new XSSFWorkbook()) {
            createSheet(workbook, "Parts", List.of(
                    "Inbound Date", "Part Code", "Document Type", "Document Name", "Part Name", "Specification",
                    "Unit", "Quantity", "Unit Price", "Warehouse", "Note", "Remark", "Source"
            ));
            return new DataImportTemplateFile("parts-purchase-template.xlsx", toBytes(workbook));
        } catch (IOException e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "Template generation failed");
        }
    }

    private byte[] toBytes(Workbook workbook) throws IOException {
        try (workbook; var out = new java.io.ByteArrayOutputStream()) {
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private void createSheet(Workbook workbook, String sheetName, List<String> headers) {
        Sheet sheet = workbook.createSheet(sheetName);
        var headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.size(); i++) {
            headerRow.createCell(i).setCellValue(headers.get(i));
            sheet.setColumnWidth(i, 18 * 256);
        }
    }
}
