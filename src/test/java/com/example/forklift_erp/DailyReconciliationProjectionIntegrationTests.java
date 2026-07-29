package com.example.forklift_erp;

import com.example.forklift_erp.constant.FinancialEventType;
import com.example.forklift_erp.constant.MachineStockStatus;
import com.example.forklift_erp.entity.FinancialEvent;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.entity.RentalRecord;
import com.example.forklift_erp.entity.StockBalance;
import com.example.forklift_erp.entity.Warehouse;
import com.example.forklift_erp.repository.FinancialEventRepository;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.OutboundOrderRepository;
import com.example.forklift_erp.repository.RentalRecordRepository;
import com.example.forklift_erp.repository.StockBalanceRepository;
import com.example.forklift_erp.repository.WarehouseRepository;
import com.example.forklift_erp.service.DailyReconciliationService;
import com.example.forklift_erp.service.FinancialEventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DailyReconciliationProjectionIntegrationTests extends TestcontainersDatabaseSupport {

    @Autowired
    private DailyReconciliationService reconciliationService;

    @Autowired
    private OutboundOrderRepository outboundOrderRepository;

    @Autowired
    private FinancialEventRepository financialEventRepository;

    @Autowired
    private MachineInventoryRepository machineInventoryRepository;

    @Autowired
    private RentalRecordRepository rentalRecordRepository;

    @Autowired
    private StockBalanceRepository stockBalanceRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    private final List<Long> financialEventIds = new ArrayList<>();
    private Long orderId;
    private Long rentalId;
    private Long machineId;
    private Long extraWarehouseId;

    @AfterEach
    void cleanFacts() {
        if (rentalId != null) {
            rentalRecordRepository.deleteById(rentalId);
            rentalId = null;
        }
        if (machineId != null) {
            stockBalanceRepository.findByResourceTypeAndResourceId("MACHINE", machineId)
                    .forEach(stockBalanceRepository::delete);
            machineInventoryRepository.deleteById(machineId);
            machineId = null;
        }
        if (extraWarehouseId != null) {
            warehouseRepository.deleteById(extraWarehouseId);
            extraWarehouseId = null;
        }
        financialEventRepository.deleteAllByIdInBatch(financialEventIds);
        financialEventIds.clear();
        if (orderId != null) {
            outboundOrderRepository.deleteById(orderId);
            orderId = null;
        }
    }

    @Test
    void aggregatesSalesFactsWithoutLoadingAllEntities() {
        LocalDate activityDate = LocalDate.of(2092, 7, 15);
        OutboundOrder order = new OutboundOrder();
        order.setOrderNo("RECON-" + UUID.randomUUID());
        order.setResourceType(OutboundOrder.RESOURCE_PART);
        order.setResourceCode("RECON-PART");
        order.setResourceName("Reconciliation part");
        order.setQuantity(1);
        order.setCustomerName("Reconciliation customer");
        orderId = outboundOrderRepository.saveAndFlush(order).getId();

        saveEvent(FinancialEventType.ACCOUNTS_RECEIVABLE, "100.00", activityDate, "AR");
        saveEvent(FinancialEventType.CASH_RECEIPT, "40.00", activityDate, "RECEIPT");

        var result = reconciliationService.reconcile(activityDate);

        assertThat(result.getSales()).filteredOn(row -> orderId.equals(row.getOutboundOrderId()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getReceivable()).isEqualByComparingTo("100.00");
                    assertThat(row.getReceipts()).isEqualByComparingTo("40.00");
                    assertThat(row.getUnpaid()).isEqualByComparingTo("60.00");
                    assertThat(row.getStatus()).isEqualTo("OUTSTANDING");
                });
    }

    @Test
    void reportsRentalLockInAnotherWarehouseEvenWhenMachineHasAnActiveRental() {
        Warehouse rentalWarehouse = warehouseRepository
                .findFirstByDefaultWarehouseTrueOrderByIdAsc()
                .orElseThrow();
        Warehouse strayLockWarehouse = new Warehouse();
        strayLockWarehouse.setWarehouseCode("RECON-WH-" + UUID.randomUUID());
        strayLockWarehouse.setWarehouseName("Reconciliation stray lock warehouse");
        strayLockWarehouse.setWarehouseType("MAIN");
        strayLockWarehouse.setDefaultWarehouse(false);
        extraWarehouseId = warehouseRepository.saveAndFlush(strayLockWarehouse).getId();

        MachineInventory machine = new MachineInventory();
        machine.setVehicleProductNumber("RECON-MACHINE-" + UUID.randomUUID());
        machine.setName("Reconciliation rental machine");
        machine.setSpecificationModel("RECON-MODEL");
        machine.setWarehouseId(rentalWarehouse.getId());
        machine.setWarehouseName(rentalWarehouse.getWarehouseName());
        machine.setStockStatus(MachineStockStatus.RENTED.code());
        machine.setInventoryCount(0);
        machine.setModelOnly(false);
        machineId = machineInventoryRepository.saveAndFlush(machine).getId();

        stockBalanceRepository.saveAndFlush(balance(machineId, rentalWarehouse.getId()));
        stockBalanceRepository.saveAndFlush(balance(machineId, extraWarehouseId));

        RentalRecord rental = new RentalRecord();
        rental.setRentalNo("RECON-RENTAL-" + UUID.randomUUID());
        rental.setMachineId(machineId);
        rental.setWarehouseId(rentalWarehouse.getId());
        rental.setVehicleNumber(machine.getVehicleProductNumber());
        rental.setMachineName(machine.getName());
        rental.setDestination("Reconciliation test destination");
        rental.setRentalPrice(new BigDecimal("100.00"));
        rental.setStatus(RentalRecord.STATUS_ACTIVE);
        rentalId = rentalRecordRepository.saveAndFlush(rental).getId();

        var result = reconciliationService.reconcile(LocalDate.now());

        assertThat(result.getRentalIssues())
                .anySatisfy(issue -> {
                    assertThat(issue.getCode()).isEqualTo("UNMATCHED_RENTAL_LOCK");
                    assertThat(issue.getMachineId()).isEqualTo(machineId);
                    assertThat(issue.getWarehouseId()).isEqualTo(extraWarehouseId);
                });
    }

    private StockBalance balance(Long resourceId, Long warehouseId) {
        StockBalance balance = new StockBalance();
        balance.setResourceType("MACHINE");
        balance.setResourceId(resourceId);
        balance.setWarehouseId(warehouseId);
        balance.setAvailableQuantity(0);
        balance.setReservedQuantity(0);
        balance.setLockedQuantity(1);
        return balance;
    }

    private void saveEvent(String eventType, String amount, LocalDate date, String suffix) {
        FinancialEvent event = new FinancialEvent();
        event.setEventNo("RECON-FE-" + UUID.randomUUID());
        event.setEventType(eventType);
        event.setAmount(new BigDecimal(amount));
        event.setBusinessDate(date);
        event.setSourceType(FinancialEventService.SOURCE_OUTBOUND_ORDER);
        event.setSourceId(orderId);
        event.setIdempotencyKey("RECON-" + orderId + "-" + suffix);
        financialEventIds.add(financialEventRepository.saveAndFlush(event).getId());
    }
}
