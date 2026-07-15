package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.dto.CustomerDTO;
import com.example.forklift_erp.dto.CustomerVO;
import com.example.forklift_erp.dto.MachineInventoryCreateDTO;
import com.example.forklift_erp.dto.MachineInventoryVO;
import com.example.forklift_erp.dto.OutboundOrderVO;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.service.CustomerService;
import com.example.forklift_erp.service.MachineInventoryService;
import com.example.forklift_erp.service.OutboundOrderService;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DataImportVehicleImporter {
    private final CustomerService customerService;
    private final MachineInventoryService machineInventoryService;
    private final OutboundOrderService outboundOrderService;
    private final DataImportVehicleRowMapper rowMapper;
    private final DataImportIdempotencyService idempotencyService;

    public DataImportVehicleImporter(
            CustomerService customerService,
            MachineInventoryService machineInventoryService,
            OutboundOrderService outboundOrderService,
            DataImportVehicleRowMapper rowMapper,
            DataImportIdempotencyService idempotencyService
    ) {
        this.customerService = customerService;
        this.machineInventoryService = machineInventoryService;
        this.outboundOrderService = outboundOrderService;
        this.rowMapper = rowMapper;
        this.idempotencyService = idempotencyService;
    }

    ImportResult importWorkbook(WorkbookSnapshot snapshot, ImportContext context) {
        if (context.masterData()) {
            return new ImportResult(0, snapshot.totalRows(),
                    "Vehicle workbook contains inventory/business sheets; MASTER_DATA mode intentionally makes no stock changes");
        }
        Map<String, MachineInventory> machinesByNumber = machineInventoryService.findAll().stream()
                .filter(machine -> rowMapper.hasText(machine.getVehicleProductNumber()))
                .collect(Collectors.toMap(MachineInventory::getVehicleProductNumber, machine -> machine, (left, right) -> left, LinkedHashMap::new));
        Map<String, CustomerVO> customersByName = customerService.findAll().stream()
                .filter(customer -> rowMapper.hasText(customer.getCompanyName()))
                .collect(Collectors.toMap(CustomerVO::getCompanyName, customer -> customer, (left, right) -> left, LinkedHashMap::new));
        Set<String> orderVehicleNumbers = outboundOrderService.findAll().stream()
                .filter(order -> "MACHINE".equals(order.getResourceType()) && rowMapper.hasText(order.getResourceCode()))
                .map(OutboundOrderVO::getResourceCode)
                .collect(Collectors.toCollection(HashSet::new));

        Map<String, WorkbookRow> inboundByVehicle = rowMapper.indexByVehicle(snapshot.sheetRows("Inbound"), 8);
        Map<String, WorkbookRow> oldInboundByVehicle = rowMapper.indexByVehicle(snapshot.sheetRows("OldInbound"), 6);

        int importedCustomers = 0;
        int importedMachines = 0;
        int importedOrders = 0;
        int skippedRows = 0;

        if (context.businessDocument()) {
        for (WorkbookRow salesRow : snapshot.sheetRows("Sales")) {
            String vehicleNumber = rowMapper.cleanVehicleNumber(rowMapper.text(salesRow, 5));
            String customerName = rowMapper.text(salesRow, 14);
            if (!rowMapper.hasText(vehicleNumber) || !rowMapper.hasText(customerName)) {
                skippedRows++;
                continue;
            }
            if (!idempotencyService.reserve(context, "Sales", salesRow.rowNumber(), "SALES:" + vehicleNumber)) {
                skippedRows++;
                continue;
            }
            WorkbookRow inboundRow = inboundByVehicle.get(vehicleNumber);
            MachineInventory machine = inboundRow != null
                    ? upsertMachineFromInboundRow(inboundRow, vehicleNumber, machinesByNumber)
                    : upsertMachineFromSalesRow(salesRow, vehicleNumber, machinesByNumber);
            CustomerVO customer = upsertCustomer(customerName, salesRow, customersByName);
            if (!orderVehicleNumbers.contains(vehicleNumber) && machine.getInventoryCount() != null && machine.getInventoryCount() > 0) {
                outboundOrderService.createVehicleOutbound(rowMapper.buildVehicleOutboundPayload(machine, customer, salesRow));
                orderVehicleNumbers.add(vehicleNumber);
                importedOrders++;
            }
            importedMachines++;
            if (customer.getId() != null) {
                importedCustomers++;
            }
        }
        }

        for (WorkbookRow inboundRow : snapshot.sheetRows("Inbound")) {
            String vehicleNumber = rowMapper.cleanVehicleNumber(rowMapper.text(inboundRow, 8));
            if (!rowMapper.hasText(vehicleNumber) || machinesByNumber.containsKey(vehicleNumber)) {
                continue;
            }
            if (!idempotencyService.reserve(context, "Inbound", inboundRow.rowNumber(), "INBOUND:" + vehicleNumber)) {
                skippedRows++;
                continue;
            }
            upsertMachineFromInboundRow(inboundRow, vehicleNumber, machinesByNumber);
            importedMachines++;
        }

        if (context.businessDocument()) {
        for (WorkbookRow otherBrandRow : snapshot.sheetRows("OtherBrandSales")) {
            String vehicleNumber = rowMapper.cleanVehicleNumber(rowMapper.text(otherBrandRow, 6));
            if (!rowMapper.hasText(vehicleNumber)) {
                vehicleNumber = rowMapper.generatedVehicleNumber("OTHER-SALE", otherBrandRow.rowNumber(), otherBrandRow);
            }
            if (machinesByNumber.containsKey(vehicleNumber)) {
                continue;
            }
            if (!idempotencyService.reserve(context, "OtherBrandSales", otherBrandRow.rowNumber(),
                    "OTHER_BRAND_SALE:" + vehicleNumber)) {
                skippedRows++;
                continue;
            }
            MachineInventory machine = upsertMachineFromOtherBrandRow(otherBrandRow, vehicleNumber, machinesByNumber);
            CustomerVO customer = upsertCustomer(rowMapper.firstNonBlank(rowMapper.text(otherBrandRow, 13), "Other-brand customer"), otherBrandRow, customersByName);
            if (!orderVehicleNumbers.contains(vehicleNumber)) {
                outboundOrderService.createVehicleOutbound(rowMapper.buildOtherBrandOutboundPayload(machine, customer, otherBrandRow));
                orderVehicleNumbers.add(vehicleNumber);
                importedOrders++;
            }
            importedMachines++;
            importedCustomers++;
        }

        for (WorkbookRow oldSalesRow : snapshot.sheetRows("OldSales")) {
            String vehicleNumber = rowMapper.cleanVehicleNumber(rowMapper.text(oldSalesRow, 5));
            if (!rowMapper.hasText(vehicleNumber)) {
                vehicleNumber = rowMapper.generatedVehicleNumber("OLD-SALE", oldSalesRow.rowNumber(), oldSalesRow);
            }
            if (machinesByNumber.containsKey(vehicleNumber)) {
                continue;
            }
            if (!idempotencyService.reserve(context, "OldSales", oldSalesRow.rowNumber(), "OLD_SALE:" + vehicleNumber)) {
                skippedRows++;
                continue;
            }
            WorkbookRow oldInboundRow = oldInboundByVehicle.get(vehicleNumber);
            MachineInventory machine = oldInboundRow != null
                    ? upsertMachineFromOldInboundRow(oldInboundRow, vehicleNumber, machinesByNumber)
                    : upsertMachineFromOldSalesRow(oldSalesRow, vehicleNumber, machinesByNumber);
            CustomerVO customer = upsertCustomer(rowMapper.firstNonBlank(rowMapper.text(oldSalesRow, 13), "Used vehicle customer"), oldSalesRow, customersByName);
            if (!orderVehicleNumbers.contains(vehicleNumber)) {
                outboundOrderService.createVehicleOutbound(rowMapper.buildOldSalesOutboundPayload(machine, customer, oldSalesRow));
                orderVehicleNumbers.add(vehicleNumber);
                importedOrders++;
            }
            importedMachines++;
            importedCustomers++;
        }
        }

        for (WorkbookRow oldInboundRow : snapshot.sheetRows("OldInbound")) {
            String vehicleNumber = rowMapper.cleanVehicleNumber(rowMapper.text(oldInboundRow, 6));
            if (!rowMapper.hasText(vehicleNumber) || machinesByNumber.containsKey(vehicleNumber)) {
                continue;
            }
            if (!idempotencyService.reserve(context, "OldInbound", oldInboundRow.rowNumber(), "OLD_INBOUND:" + vehicleNumber)) {
                skippedRows++;
                continue;
            }
            upsertMachineFromOldInboundRow(oldInboundRow, vehicleNumber, machinesByNumber);
            importedMachines++;
        }

        return new ImportResult(
                importedCustomers + importedMachines + importedOrders,
                skippedRows,
                "Mode=" + context.importMode() + ", imported customers=" + importedCustomers
                        + ", machines=" + importedMachines + ", orders=" + importedOrders
        );
    }

    private MachineInventory upsertMachineFromInboundRow(WorkbookRow row, String vehicleNumber, Map<String, MachineInventory> machinesByNumber) {
        MachineInventoryCreateDTO dto = rowMapper.buildMachineFromInboundRow(row, vehicleNumber);
        MachineInventory existing = machinesByNumber.get(vehicleNumber);
        if (existing == null) {
            MachineInventoryVO created = machineInventoryService.create(dto);
            MachineInventory machine = machineInventoryService.findById(created.getId()).orElseThrow();
            machinesByNumber.put(vehicleNumber, machine);
            return machine;
        }
        // A spreadsheet snapshot must never rewrite an existing serial asset's
        // stock, warehouse or posted cost. Reuse the authoritative profile.
        machinesByNumber.put(vehicleNumber, existing);
        return existing;
    }

    private MachineInventory upsertMachineFromSalesRow(WorkbookRow row, String vehicleNumber, Map<String, MachineInventory> machinesByNumber) {
        MachineInventoryCreateDTO dto = rowMapper.buildMachineFromSalesRow(row, vehicleNumber, "Workbook sales");
        MachineInventory existing = machinesByNumber.get(vehicleNumber);
        if (existing == null) {
            MachineInventoryVO created = machineInventoryService.create(dto);
            MachineInventory machine = machineInventoryService.findById(created.getId()).orElseThrow();
            machinesByNumber.put(vehicleNumber, machine);
            return machine;
        }
        machinesByNumber.put(vehicleNumber, existing);
        return existing;
    }

    private boolean isOperational(MachineInventory machine) {
        return machine != null
                && (machine.getInventoryCount() == null || machine.getInventoryCount() <= 0
                || com.example.forklift_erp.constant.MachineStockStatus.OUTBOUND.code().equals(machine.getStockStatus())
                || com.example.forklift_erp.constant.MachineStockStatus.RENTED.code().equals(machine.getStockStatus())
                || machine.getSalesDate() != null);
    }

    private MachineInventory upsertMachineFromOtherBrandRow(WorkbookRow row, String vehicleNumber, Map<String, MachineInventory> machinesByNumber) {
        MachineInventoryCreateDTO dto = rowMapper.buildMachineFromOtherBrandRow(row, vehicleNumber);
        MachineInventory existing = machinesByNumber.get(vehicleNumber);
        if (existing == null) {
            MachineInventoryVO created = machineInventoryService.create(dto);
            MachineInventory machine = machineInventoryService.findById(created.getId()).orElseThrow();
            machinesByNumber.put(vehicleNumber, machine);
            return machine;
        }
        machinesByNumber.put(vehicleNumber, existing);
        return existing;
    }

    private MachineInventory upsertMachineFromOldInboundRow(WorkbookRow row, String vehicleNumber, Map<String, MachineInventory> machinesByNumber) {
        MachineInventoryCreateDTO dto = rowMapper.buildMachineFromOldInboundRow(row, vehicleNumber);
        MachineInventory existing = machinesByNumber.get(vehicleNumber);
        if (existing == null) {
            MachineInventoryVO created = machineInventoryService.create(dto);
            MachineInventory machine = machineInventoryService.findById(created.getId()).orElseThrow();
            machinesByNumber.put(vehicleNumber, machine);
            return machine;
        }
        machinesByNumber.put(vehicleNumber, existing);
        return existing;
    }

    private MachineInventory upsertMachineFromOldSalesRow(WorkbookRow row, String vehicleNumber, Map<String, MachineInventory> machinesByNumber) {
        MachineInventoryCreateDTO dto = rowMapper.buildMachineFromOldSalesRow(row, vehicleNumber);
        MachineInventory existing = machinesByNumber.get(vehicleNumber);
        if (existing == null) {
            MachineInventoryVO created = machineInventoryService.create(dto);
            MachineInventory machine = machineInventoryService.findById(created.getId()).orElseThrow();
            machinesByNumber.put(vehicleNumber, machine);
            return machine;
        }
        machinesByNumber.put(vehicleNumber, existing);
        return existing;
    }

    private CustomerVO upsertCustomer(String companyName, WorkbookRow row, Map<String, CustomerVO> customersByName) {
        String name = rowMapper.trimToNull(companyName);
        if (name == null) {
            name = "Workbook customer";
        }
        CustomerVO existing = customersByName.get(name);
        CustomerDTO dto = rowMapper.buildCustomerFromRow(name, row);
        if (existing == null) {
            CustomerVO created = customerService.create(dto);
            customersByName.put(name, created);
            return created;
        }
        dto.setVersion(existing.getVersion());
        if (rowMapper.customerChanged(existing, dto)) {
            CustomerVO updated = customerService.update(existing.getId(), dto);
            customersByName.put(name, updated);
            return updated;
        }
        return existing;
    }

}
