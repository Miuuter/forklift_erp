package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.dto.CustomerDTO;
import com.example.forklift_erp.dto.MachineInventoryCreateDTO;
import com.example.forklift_erp.dto.VehicleOutboundOrderCreateDTO;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.dto.CustomerVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class DataImportVehicleRowMapperTests {
    private final DataImportVehicleRowMapper mapper = new DataImportVehicleRowMapper();

    @Test
    void salesMappingUsesTheColumnsDeclaredByTheTemplate() {
        WorkbookRow row = row(2, 29);
        set(row, 1, "2026-07-01");
        set(row, 2, "Forklift");
        set(row, 3, "Longgong");
        set(row, 4, "Standard configuration");
        set(row, 5, "V-001");
        set(row, 9, "80000.00");
        set(row, 11, "100000.00");
        set(row, 14, "Customer A");
        set(row, 15, "Address A");
        set(row, 16, "Contact A");
        set(row, 17, "13800000000");
        set(row, 18, "TAX-A");
        set(row, 20, "2026-07-02");
        set(row, 23, "REGISTERED");
        set(row, 25, "2026-07-03");

        CustomerDTO customer = mapper.buildCustomerFromSalesRow("Customer A", row);
        MachineInventoryCreateDTO machine = mapper.buildMachineFromSalesRow(row, "V-001", "Sales import");
        VehicleOutboundOrderCreateDTO outbound = mapper.buildVehicleOutboundPayload(
                machine(10L, 3L), customer(20L), row
        );

        assertThat(customer.getAddress()).isEqualTo("Address A");
        assertThat(customer.getContactName()).isEqualTo("Contact A");
        assertThat(customer.getContactPhone()).isEqualTo("13800000000");
        assertThat(customer.getTaxOrIdNumber()).isEqualTo("TAX-A");
        assertThat(machine.getSupplier()).isEqualTo("Longgong");
        assertThat(machine.getSalePrice()).isEqualByComparingTo("100000.00");
        assertThat(outbound.getInvoiceIssuedDate()).isEqualTo(LocalDate.of(2026, 7, 2));
        assertThat(outbound.getInvoiceApplicationDate()).isEqualTo(LocalDate.of(2026, 7, 3));
        assertThat(outbound.getRegistrationStatus()).isEqualTo("REGISTERED");
    }

    @Test
    void otherBrandCustomerMappingDoesNotShiftContactColumns() {
        WorkbookRow row = row(3, 21);
        set(row, 14, "Other address");
        set(row, 15, "Other contact");
        set(row, 16, "13900000000");
        set(row, 17, "TAX-OTHER");

        CustomerDTO customer = mapper.buildCustomerFromOtherBrandRow("Other customer", row);

        assertThat(customer.getAddress()).isEqualTo("Other address");
        assertThat(customer.getContactName()).isEqualTo("Other contact");
        assertThat(customer.getContactPhone()).isEqualTo("13900000000");
        assertThat(customer.getTaxOrIdNumber()).isEqualTo("TAX-OTHER");
    }

    @Test
    void oldSalesKeepsSalePriceSettlementPriceAndCustomerDataDistinct() {
        WorkbookRow row = row(4, 22);
        set(row, 1, "2026-06-01");
        set(row, 2, "Used forklift");
        set(row, 3, "CPC30");
        set(row, 5, "OLD-001");
        set(row, 9, "120000.00");
        set(row, 10, "1");
        set(row, 11, "90000.00");
        set(row, 12, "Bank transfer pending");
        set(row, 14, "Old address");
        set(row, 15, "Old contact");
        set(row, 16, "13700000000");
        set(row, 17, "TAX-OLD");

        CustomerDTO customer = mapper.buildCustomerFromOldSalesRow("Old customer", row);
        MachineInventoryCreateDTO machine = mapper.buildMachineFromOldSalesRow(row, "OLD-001");
        VehicleOutboundOrderCreateDTO outbound = mapper.buildOldSalesOutboundPayload(
                machine(11L, 4L), customer(21L), row
        );

        assertThat(customer.getAddress()).isEqualTo("Old address");
        assertThat(customer.getContactName()).isEqualTo("Old contact");
        assertThat(customer.getContactPhone()).isEqualTo("13700000000");
        assertThat(customer.getTaxOrIdNumber()).isEqualTo("TAX-OLD");
        assertThat(machine.getSalePrice()).isEqualByComparingTo("120000.00");
        assertThat(machine.getSettlementPrice()).isEqualByComparingTo("90000.00");
        assertThat(outbound.getSalePrice()).isEqualByComparingTo("120000.00");
        assertThat(outbound.getSettlementPrice()).isEqualByComparingTo("90000.00");
        assertThat(outbound.getPaymentRemark()).isEqualTo("Bank transfer pending");
    }

    private WorkbookRow row(int rowNumber, int size) {
        String[] values = new String[size];
        Arrays.fill(values, "");
        return new WorkbookRow(rowNumber, Arrays.asList(values));
    }

    private void set(WorkbookRow row, int index, String value) {
        row.values().set(index, value);
    }

    private MachineInventory machine(Long id, Long version) {
        MachineInventory machine = new MachineInventory();
        machine.setId(id);
        machine.setVersion(version);
        return machine;
    }

    private CustomerVO customer(Long id) {
        CustomerVO customer = new CustomerVO();
        customer.setId(id);
        return customer;
    }
}
