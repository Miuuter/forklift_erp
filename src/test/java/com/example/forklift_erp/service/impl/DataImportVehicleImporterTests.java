package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.dto.CustomerDTO;
import com.example.forklift_erp.dto.CustomerVO;
import com.example.forklift_erp.dto.MachineInventoryCreateDTO;
import com.example.forklift_erp.dto.MachineInventoryVO;
import com.example.forklift_erp.dto.VehicleOutboundOrderCreateDTO;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.service.CustomerService;
import com.example.forklift_erp.service.MachineInventoryService;
import com.example.forklift_erp.service.OutboundOrderService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.eq;

class DataImportVehicleImporterTests {

    @Test
    void importWorkbookCreatesInboundMachineFromInboundSheet() {
        CustomerService customerService = mock(CustomerService.class);
        MachineInventoryService machineService = mock(MachineInventoryService.class);
        OutboundOrderService outboundOrderService = mock(OutboundOrderService.class);
        stubEmptyLookups(customerService, machineService, outboundOrderService);
        MachineInventoryVO created = new MachineInventoryVO();
        created.setId(99L);
        MachineInventory persisted = machine(99L, "V-100", 2);
        when(machineService.create(any(MachineInventoryCreateDTO.class))).thenReturn(created);
        when(machineService.findById(99L)).thenReturn(Optional.of(persisted));
        DataImportVehicleImporter importer = newImporter(customerService, machineService, outboundOrderService);

        ImportResult result = importer.importWorkbook(snapshot(Map.of(
                "Inbound",
                List.of(new WorkbookRow(2, rowWith(25, Map.of(
                        1, "2024-01-02",
                        2, "Supplier A",
                        4, "Forklift A",
                        6, "CPCD30",
                        8, "V-100",
                        13, "1000",
                        18, "2",
                        24, "WH-A"
                )))
        ))), businessContext());

        ArgumentCaptor<MachineInventoryCreateDTO> captor = ArgumentCaptor.forClass(MachineInventoryCreateDTO.class);
        verify(machineService).create(captor.capture());
        verify(outboundOrderService, never()).createVehicleOutbound(any());
        assertThat(result.importedRows()).isEqualTo(1);
        assertThat(result.skippedRows()).isZero();
        assertThat(result.summary()).isEqualTo("Mode=BUSINESS_DOCUMENT, imported customers=0, machines=1, orders=0");
        MachineInventoryCreateDTO dto = captor.getValue();
        assertThat(dto.getVehicleProductNumber()).isEqualTo("V-100");
        assertThat(dto.getName()).isEqualTo("Forklift A");
        assertThat(dto.getSpecificationModel()).isEqualTo("CPCD30");
        assertThat(dto.getInventoryCount()).isEqualTo(2);
        assertThat(dto.getPurchasePrice()).isEqualByComparingTo("1000.00");
        assertThat(dto.getWarehouseName()).isEqualTo("WH-A");
        assertThat(dto.getInboundDate()).isEqualTo(LocalDateTime.of(2024, 1, 2, 0, 0));
    }

    @Test
    void importWorkbookCreatesSalesMachineCustomerAndOutboundOrder() {
        CustomerService customerService = mock(CustomerService.class);
        MachineInventoryService machineService = mock(MachineInventoryService.class);
        OutboundOrderService outboundOrderService = mock(OutboundOrderService.class);
        stubEmptyLookups(customerService, machineService, outboundOrderService);
        MachineInventoryVO createdMachine = new MachineInventoryVO();
        createdMachine.setId(20L);
        when(machineService.create(any(MachineInventoryCreateDTO.class))).thenReturn(createdMachine);
        when(machineService.findById(20L)).thenReturn(Optional.of(machine(20L, "V-200", 1)));
        CustomerVO createdCustomer = new CustomerVO();
        createdCustomer.setId(5L);
        createdCustomer.setCompanyName("Acme");
        when(customerService.create(any(CustomerDTO.class))).thenReturn(createdCustomer);
        DataImportVehicleImporter importer = newImporter(customerService, machineService, outboundOrderService);

        ImportResult result = importer.importWorkbook(snapshot(Map.of(
                "Sales",
                List.of(new WorkbookRow(2, rowWith(29, Map.of(
                        1, "2024-02-03",
                        2, "Forklift B",
                        3, "CPD20",
                        5, "V-200",
                        9, "1200",
                        11, "1500",
                        13, "yes",
                        14, "Acme",
                        20, "2024-02-04"
                )))
        ))), businessContext());

        ArgumentCaptor<VehicleOutboundOrderCreateDTO> orderCaptor = ArgumentCaptor.forClass(VehicleOutboundOrderCreateDTO.class);
        verify(outboundOrderService).createVehicleOutbound(orderCaptor.capture());
        assertThat(result.importedRows()).isEqualTo(3);
        assertThat(result.summary()).isEqualTo("Mode=BUSINESS_DOCUMENT, imported customers=1, machines=1, orders=1");
        VehicleOutboundOrderCreateDTO order = orderCaptor.getValue();
        assertThat(order.getMachineId()).isEqualTo(20L);
        assertThat(order.getCustomerId()).isEqualTo(5L);
        assertThat(order.getSalesDate()).isEqualTo(LocalDate.of(2024, 2, 3));
        assertThat(order.getSettlementPrice()).isEqualByComparingTo("1200.00");
        assertThat(order.getSalePrice()).isEqualByComparingTo("1500.00");
        assertThat(order.getPaymentSettled()).isTrue();
    }

    @Test
    void importWorkbookSkipsSalesRowsMissingCustomer() {
        CustomerService customerService = mock(CustomerService.class);
        MachineInventoryService machineService = mock(MachineInventoryService.class);
        OutboundOrderService outboundOrderService = mock(OutboundOrderService.class);
        stubEmptyLookups(customerService, machineService, outboundOrderService);
        DataImportVehicleImporter importer = newImporter(customerService, machineService, outboundOrderService);

        ImportResult result = importer.importWorkbook(snapshot(Map.of(
                "Sales",
                List.of(new WorkbookRow(2, rowWith(15, Map.of(5, "V-300"))))
        )), businessContext());

        verify(machineService, never()).create(any());
        verify(customerService, never()).create(any());
        verify(outboundOrderService, never()).createVehicleOutbound(any());
        assertThat(result.importedRows()).isZero();
        assertThat(result.skippedRows()).isEqualTo(1);
    }

    @Test
    void importWorkbookDoesNotRewriteExistingVehicleFromInboundSnapshot() {
        CustomerService customerService = mock(CustomerService.class);
        MachineInventoryService machineService = mock(MachineInventoryService.class);
        OutboundOrderService outboundOrderService = mock(OutboundOrderService.class);
        MachineInventory existing = machine(11L, "V-500", 1);
        existing.setVersion(8L);
        when(customerService.findAll()).thenReturn(List.of());
        when(machineService.findAll()).thenReturn(List.of(existing));
        when(outboundOrderService.findAll()).thenReturn(List.of());
        when(machineService.findById(11L)).thenReturn(Optional.of(existing));
        CustomerVO createdCustomer = new CustomerVO();
        createdCustomer.setId(12L);
        createdCustomer.setCompanyName("Buyer");
        when(customerService.create(any(CustomerDTO.class))).thenReturn(createdCustomer);
        DataImportVehicleImporter importer = newImporter(customerService, machineService, outboundOrderService);

        importer.importWorkbook(snapshot(Map.of(
                "Sales",
                List.of(new WorkbookRow(2, rowWith(29, Map.of(
                        1, "2024-03-01",
                        5, "V-500",
                        14, "Buyer"
                )))),
                "Inbound",
                List.of(new WorkbookRow(2, rowWith(25, Map.of(
                        1, "2024-02-28",
                        4, "Updated Forklift",
                        6, "CPCD35",
                        8, "V-500",
                        13, "2000",
                        18, "1"
                ))))
        )), businessContext());

        verify(machineService, never()).update(any(), any());
        verify(machineService, never()).create(any());
        verify(outboundOrderService).createVehicleOutbound(any());
    }

    @Test
    void importWorkbookUpdatesExistingCustomerBeforeOutbound() {
        CustomerService customerService = mock(CustomerService.class);
        MachineInventoryService machineService = mock(MachineInventoryService.class);
        OutboundOrderService outboundOrderService = mock(OutboundOrderService.class);
        when(machineService.findAll()).thenReturn(List.of());
        when(outboundOrderService.findAll()).thenReturn(List.of());
        MachineInventoryVO createdMachine = new MachineInventoryVO();
        createdMachine.setId(30L);
        when(machineService.create(any(MachineInventoryCreateDTO.class))).thenReturn(createdMachine);
        when(machineService.findById(30L)).thenReturn(Optional.of(machine(30L, "V-600", 1)));
        CustomerVO existingCustomer = new CustomerVO();
        existingCustomer.setId(44L);
        existingCustomer.setVersion(3L);
        existingCustomer.setCompanyName("Existing Buyer");
        when(customerService.findAll()).thenReturn(List.of(existingCustomer));
        CustomerVO updatedCustomer = new CustomerVO();
        updatedCustomer.setId(44L);
        updatedCustomer.setCompanyName("Existing Buyer");
        when(customerService.update(eq(44L), any(CustomerDTO.class))).thenReturn(updatedCustomer);
        DataImportVehicleImporter importer = newImporter(customerService, machineService, outboundOrderService);

        importer.importWorkbook(snapshot(Map.of(
                "Sales",
                List.of(new WorkbookRow(2, rowWith(29, Map.of(
                        1, "2024-04-01",
                        2, "Forklift C",
                        3, "CPD25",
                        5, "V-600",
                        14, "Existing Buyer",
                        15, "New Address",
                        16, "New Contact",
                        17, "13900000000"
                ))))
        )), businessContext());

        ArgumentCaptor<CustomerDTO> customerCaptor = ArgumentCaptor.forClass(CustomerDTO.class);
        verify(customerService).update(eq(44L), customerCaptor.capture());
        assertThat(customerCaptor.getValue().getVersion()).isEqualTo(3L);
        assertThat(customerCaptor.getValue().getAddress()).isEqualTo("New Address");
        assertThat(customerCaptor.getValue().getContactName()).isEqualTo("New Contact");
        assertThat(customerCaptor.getValue().getContactPhone()).isEqualTo("13900000000");
    }

    @Test
    void importWorkbookGeneratesVehicleNumberForOtherBrandSalesWithoutNumber() {
        CustomerService customerService = mock(CustomerService.class);
        MachineInventoryService machineService = mock(MachineInventoryService.class);
        OutboundOrderService outboundOrderService = mock(OutboundOrderService.class);
        stubEmptyLookups(customerService, machineService, outboundOrderService);
        MachineInventoryVO createdMachine = new MachineInventoryVO();
        createdMachine.setId(60L);
        when(machineService.create(any(MachineInventoryCreateDTO.class))).thenReturn(createdMachine);
        when(machineService.findById(60L)).thenReturn(Optional.of(machine(60L, "OTHER-SALE-A001", 1)));
        CustomerVO createdCustomer = new CustomerVO();
        createdCustomer.setId(61L);
        createdCustomer.setCompanyName("Other Buyer");
        when(customerService.create(any(CustomerDTO.class))).thenReturn(createdCustomer);
        DataImportVehicleImporter importer = newImporter(customerService, machineService, outboundOrderService);

        importer.importWorkbook(snapshot(Map.of(
                "OtherBrandSales",
                List.of(new WorkbookRow(7, rowWith(21, Map.of(
                        0, "A-001",
                        1, "2024-05-01",
                        2, "Other Forklift",
                        4, "OB-30",
                        13, "Other Buyer"
                ))))
        )), businessContext());

        ArgumentCaptor<MachineInventoryCreateDTO> machineCaptor = ArgumentCaptor.forClass(MachineInventoryCreateDTO.class);
        verify(machineService).create(machineCaptor.capture());
        assertThat(machineCaptor.getValue().getVehicleProductNumber()).isEqualTo("OTHER-SALE-A001");
        verify(outboundOrderService).createVehicleOutbound(any());
    }

    private void stubEmptyLookups(
            CustomerService customerService,
            MachineInventoryService machineService,
            OutboundOrderService outboundOrderService
    ) {
        when(customerService.findAll()).thenReturn(List.of());
        when(machineService.findAll()).thenReturn(List.of());
        when(outboundOrderService.findAll()).thenReturn(List.of());
    }

    private DataImportVehicleImporter newImporter(
            CustomerService customerService,
            MachineInventoryService machineService,
            OutboundOrderService outboundOrderService
    ) {
        DataImportIdempotencyService idempotencyService = mock(DataImportIdempotencyService.class);
        when(idempotencyService.reserve(any(), any(), anyInt(), any())).thenReturn(true);
        return new DataImportVehicleImporter(
                customerService,
                machineService,
                outboundOrderService,
                new DataImportVehicleRowMapper(),
                idempotencyService
        );
    }

    private ImportContext businessContext() {
        return new ImportContext(1L, "vehicle-workbook", ImportContext.MODE_BUSINESS_DOCUMENT, "vehicle-test-file");
    }

    private WorkbookSnapshot snapshot(Map<String, List<WorkbookRow>> sheets) {
        return new WorkbookSnapshot(sheets);
    }

    private List<String> rowWith(int size, Map<Integer, String> values) {
        String[] cells = new String[size];
        Arrays.fill(cells, "");
        values.forEach((index, value) -> cells[index] = value);
        return Arrays.asList(cells);
    }

    private MachineInventory machine(Long id, String vehicleNumber, int inventoryCount) {
        MachineInventory machine = new MachineInventory();
        machine.setId(id);
        machine.setVersion(1L);
        machine.setVehicleProductNumber(vehicleNumber);
        machine.setInventoryCount(inventoryCount);
        machine.setPurchasePrice(new BigDecimal("1000.00"));
        return machine;
    }
}
