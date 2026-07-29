package com.example.forklift_erp;

import com.example.forklift_erp.constant.FinancialEventType;
import com.example.forklift_erp.constant.MachineStockStatus;
import com.example.forklift_erp.constant.ModificationWorkOrderStatus;
import com.example.forklift_erp.constant.PartChangeAction;
import com.example.forklift_erp.constant.RentalStatus;
import com.example.forklift_erp.constant.RepairStatus;
import com.example.forklift_erp.dto.StatisticsDashboardVO;
import com.example.forklift_erp.entity.ConfigItem;
import com.example.forklift_erp.entity.ConfigValue;
import com.example.forklift_erp.entity.FinancialEvent;
import com.example.forklift_erp.entity.MachineConfig;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.ModificationWorkOrder;
import com.example.forklift_erp.entity.ModificationWorkOrderLine;
import com.example.forklift_erp.entity.RentalBill;
import com.example.forklift_erp.entity.RentalRecord;
import com.example.forklift_erp.entity.RepairRecord;
import com.example.forklift_erp.entity.StockMovement;
import com.example.forklift_erp.entity.StockMovementLine;
import com.example.forklift_erp.repository.ConfigItemRepository;
import com.example.forklift_erp.repository.ConfigValueRepository;
import com.example.forklift_erp.repository.FinancialEventRepository;
import com.example.forklift_erp.repository.MachineConfigRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderLineRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderRepository;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.RentalBillRepository;
import com.example.forklift_erp.repository.RentalRecordRepository;
import com.example.forklift_erp.repository.RepairRecordRepository;
import com.example.forklift_erp.repository.StockMovementLineRepository;
import com.example.forklift_erp.repository.StockMovementRepository;
import com.example.forklift_erp.service.FinancialEventService;
import com.example.forklift_erp.service.StatisticsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class StatisticsFinanceIntegrationTests extends TestcontainersDatabaseSupport {

    private static final int FINANCE_YEAR = 2091;
    private static final String STOCK_CODE_PREFIX = "FIN-IT-";

    @Autowired
    private StatisticsService statisticsService;

    @Autowired
    private RepairRecordRepository repairRecordRepository;

    @Autowired
    private RentalRecordRepository rentalRecordRepository;

    @Autowired
    private ModificationWorkOrderRepository modificationWorkOrderRepository;

    @Autowired
    private ModificationWorkOrderLineRepository modificationWorkOrderLineRepository;

    @Autowired
    private MachineInventoryRepository machineInventoryRepository;

    @Autowired
    private MachineConfigRepository machineConfigRepository;

    @Autowired
    private ConfigItemRepository configItemRepository;

    @Autowired
    private ConfigValueRepository configValueRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private StockMovementLineRepository stockMovementLineRepository;

    @Autowired
    private FinancialEventRepository financialEventRepository;

    @Autowired
    private RentalBillRepository rentalBillRepository;

    private final List<Long> repairIds = new ArrayList<>();
    private final List<Long> rentalIds = new ArrayList<>();
    private final List<Long> rentalBillIds = new ArrayList<>();
    private final List<Long> workOrderIds = new ArrayList<>();
    private final List<Long> machineConfigIds = new ArrayList<>();
    private final List<Long> configValueIds = new ArrayList<>();
    private final List<Long> configItemIds = new ArrayList<>();
    private final List<Long> machineIds = new ArrayList<>();
    private final List<Long> stockMovementIds = new ArrayList<>();
    private final List<Long> financialEventIds = new ArrayList<>();

    @AfterEach
    void cleanFinanceRows() {
        rentalBillRepository.deleteAllByIdInBatch(rentalBillIds);
        rentalBillIds.clear();

        financialEventRepository.deleteAllByIdInBatch(financialEventIds);
        financialEventIds.clear();

        stockMovementRepository.deleteAllByIdInBatch(stockMovementIds);
        stockMovementIds.clear();

        for (Long workOrderId : workOrderIds.reversed()) {
            modificationWorkOrderLineRepository.findByWorkOrderIdOrderByIdAsc(workOrderId)
                    .forEach(modificationWorkOrderLineRepository::delete);
            modificationWorkOrderRepository.findById(workOrderId).ifPresent(modificationWorkOrderRepository::delete);
        }
        workOrderIds.clear();

        machineConfigRepository.deleteAllByIdInBatch(machineConfigIds.reversed());
        machineConfigIds.clear();

        configValueRepository.deleteAllByIdInBatch(configValueIds.reversed());
        configValueIds.clear();

        configItemRepository.deleteAllByIdInBatch(configItemIds.reversed());
        configItemIds.clear();

        for (Long rentalId : rentalIds.reversed()) {
            rentalRecordRepository.findById(rentalId).ifPresent(rentalRecordRepository::delete);
        }
        rentalIds.clear();

        for (Long repairId : repairIds.reversed()) {
            repairRecordRepository.findById(repairId).ifPresent(repairRecordRepository::delete);
        }
        repairIds.clear();

        for (Long machineId : machineIds.reversed()) {
            machineInventoryRepository.findById(machineId).ifPresent(machineInventoryRepository::delete);
        }
        machineIds.clear();
    }

    @Test
    void financeDashboardCombinesStockRepairRentalAndModificationWithExpectedTotals() {
        Long machineId = createMachine();
        createStockRows(machineId);
        createCompletedRepair();
        createReturnedRentalForFullMonth(machineId);
        createCompletedModificationWorkOrder(machineId);

        StatisticsDashboardVO dashboard = statisticsService.financeDashboard(FINANCE_YEAR);

        assertFinancialTotals(dashboard.getAnnualSummary());
        assertFinancialTotals(findPeriod(dashboard.getMonthlyFinance(), FINANCE_YEAR + "-01"));
        assertFinancialTotals(findPeriod(dashboard.getYearlyFinance(), String.valueOf(FINANCE_YEAR)));
    }

    private void createStockRows(Long machineId) {
        saveStockMovement("MACHINE", machineId, "INBOUND", STOCK_CODE_PREFIX + "M-IN",
                2, 0, 2, "10000.00", "0.00", "20000.00", "0.00",
                LocalDate.of(FINANCE_YEAR, 1, 5));
        Long outboundMovementId = saveStockMovement(
                "MACHINE", machineId, "OUTBOUND", STOCK_CODE_PREFIX + "M-OUT",
                -1, 2, 1, "10000.00", "16000.00", "10000.00", "16000.00",
                LocalDate.of(FINANCE_YEAR, 1, 10)
        );
        long partResourceId = 90_000_000L + machineId;
        saveStockMovement("PART", partResourceId, "INBOUND", STOCK_CODE_PREFIX + "P-IN",
                3, 10, 13, "50.00", "80.00", "150.00", "240.00",
                LocalDate.of(FINANCE_YEAR, 1, 12));
        saveStockMovement("PART", partResourceId, "OUTBOUND", STOCK_CODE_PREFIX + "P-OUT",
                -4, 13, 9, "50.00", "80.00", "200.00", "320.00",
                LocalDate.of(FINANCE_YEAR, 1, 13));

        postFinancialEvent(FinancialEventType.REVENUE, "16320.00",
                FinancialEventService.SOURCE_OUTBOUND_ORDER, outboundMovementId);
        postFinancialEvent(FinancialEventType.COST_OF_GOODS_SOLD, "10200.00",
                FinancialEventService.SOURCE_OUTBOUND_ORDER, outboundMovementId);
    }

    private Long saveStockMovement(
            String resourceType,
            Long resourceId,
            String movementType,
            String resourceCode,
            Integer quantityDelta,
            Integer beforeQuantity,
            Integer afterQuantity,
            String unitCost,
            String unitRevenue,
            String costAmount,
            String lineAmount,
            LocalDate businessDate
    ) {
        StockMovement movement = new StockMovement();
        movement.setMovementNo(STOCK_CODE_PREFIX + unique("movement"));
        movement.setMovementType(movementType);
        movement.setResourceType(resourceType);
        movement.setSourceType("STATISTICS_TEST");
        movement.setSourceId(resourceId);
        movement.setBusinessDate(businessDate);
        movement.setBusinessType("STATISTICS_TEST");
        movement.setOperator("finance-test");
        StockMovement savedMovement = stockMovementRepository.save(movement);
        stockMovementIds.add(savedMovement.getId());

        StockMovementLine line = new StockMovementLine();
        line.setMovementId(savedMovement.getId());
        line.setResourceType(resourceType);
        line.setResourceId(resourceId);
        line.setResourceCode(resourceCode);
        line.setResourceName(resourceCode);
        line.setWarehouseId(defaultWarehouseId());
        line.setQuantityDelta(quantityDelta);
        line.setBeforeQuantity(beforeQuantity);
        line.setAfterQuantity(afterQuantity);
        line.setUnitCost(new BigDecimal(unitCost));
        line.setUnitRevenue(new BigDecimal(unitRevenue));
        line.setCostAmount(new BigDecimal(costAmount));
        line.setLineAmount(new BigDecimal(lineAmount));
        stockMovementLineRepository.save(line);
        return savedMovement.getId();
    }

    private void createCompletedRepair() {
        RepairRecord repair = new RepairRecord();
        repair.setRepairDate(LocalDateTime.of(FINANCE_YEAR, 1, 15, 14, 0));
        repair.setCustomerName("finance repair customer");
        repair.setFaultDescription("finance repair fault");
        repair.setRepairContent("finance repair content");
        repair.setRepairExternal(true);
        repair.setRepairFee(new BigDecimal("500.00"));
        repair.setPartsFee(new BigDecimal("150.00"));
        repair.setRepairExpense(new BigDecimal("200.00"));
        repair.setPartsCost(new BigDecimal("80.00"));
        repair.setTotalFee(new BigDecimal("850.00"));
        repair.setStatus(RepairStatus.COMPLETED.code());
        RepairRecord savedRepair = repairRecordRepository.save(repair);
        repairIds.add(savedRepair.getId());
        postFinancialEvent(FinancialEventType.ACCOUNTS_RECEIVABLE, "850.00",
                FinancialEventService.SOURCE_REPAIR, savedRepair.getId());
        postFinancialEvent(FinancialEventType.REVENUE, "650.00",
                FinancialEventService.SOURCE_REPAIR, savedRepair.getId());
        postFinancialEvent(FinancialEventType.OPERATING_COST, "200.00",
                FinancialEventService.SOURCE_REPAIR, savedRepair.getId());
        postFinancialEvent(FinancialEventType.COST_OF_GOODS_SOLD, "80.00",
                FinancialEventService.SOURCE_REPAIR, savedRepair.getId());
    }

    private Long createMachine() {
        MachineInventory machine = new MachineInventory();
        machine.setVehicleProductNumber("FIN-MACHINE-" + unique("machine"));
        machine.setName("finance machine");
        machine.setSpecificationModel("finance-spec");
        machine.setWarehouseId(defaultWarehouseId());
        machine.setStockStatus(MachineStockStatus.IN_STOCK.code());
        machine.setInventoryCount(1);
        machine.setModelOnly(false);
        Long id = machineInventoryRepository.save(machine).getId();
        machineIds.add(id);
        return id;
    }

    private void createReturnedRentalForFullMonth(Long machineId) {
        RentalRecord rental = new RentalRecord();
        rental.setRentalNo("FIN-RENT-" + unique("rent"));
        rental.setMachineId(machineId);
        rental.setDestination("finance rental destination");
        rental.setRentalPrice(new BigDecimal("3000.00"));
        rental.setMonthlyRentalPrice(new BigDecimal("3100.00"));
        rental.setStartDate(LocalDate.of(FINANCE_YEAR, 1, 1));
        rental.setEndDate(LocalDate.of(FINANCE_YEAR, 1, 31));
        rental.setStatus(RentalStatus.RETURNED.code());
        RentalRecord savedRental = rentalRecordRepository.save(rental);
        rentalIds.add(savedRental.getId());

        RentalBill bill = new RentalBill();
        bill.setRentalId(savedRental.getId());
        bill.setBillPeriod(LocalDate.of(FINANCE_YEAR, 1, 1));
        bill.setBusinessDate(LocalDate.of(FINANCE_YEAR, 1, 31));
        bill.setAmount(new BigDecimal("3100.00"));
        RentalBill savedBill = rentalBillRepository.save(bill);
        rentalBillIds.add(savedBill.getId());

        FinancialEvent receivable = postFinancialEvent(
                FinancialEventType.ACCOUNTS_RECEIVABLE,
                "3100.00",
                FinancialEventService.SOURCE_RENTAL_BILL,
                savedBill.getId()
        );
        postFinancialEvent(FinancialEventType.REVENUE, "3100.00",
                FinancialEventService.SOURCE_RENTAL_BILL, savedBill.getId());
        savedBill.setFinancialEventId(receivable.getId());
        rentalBillRepository.save(savedBill);
    }

    private void createCompletedModificationWorkOrder(Long machineId) {
        MachineConfig machineConfig = createMachineConfig(machineId);

        ModificationWorkOrder order = new ModificationWorkOrder();
        order.setWorkOrderNo("FIN-MOD-" + unique("mod"));
        order.setMachineId(machineId);
        order.setStatus(ModificationWorkOrderStatus.COMPLETED.code());
        order.setCompletedAt(LocalDateTime.of(FINANCE_YEAR, 1, 20, 16, 0));
        ModificationWorkOrder savedOrder = modificationWorkOrderRepository.save(order);
        workOrderIds.add(savedOrder.getId());

        saveModificationLine(savedOrder.getId(), machineConfig, PartChangeAction.DISCOUNT.code(), "-120.00");
        saveModificationLine(savedOrder.getId(), machineConfig, PartChangeAction.DISCOUNT.code(), "45.00");
        saveModificationLine(savedOrder.getId(), machineConfig, PartChangeAction.STOCK_IN.code(), "-999.00");
        postFinancialEvent(FinancialEventType.REVENUE, "120.00",
                "MODIFICATION_WORK_ORDER", savedOrder.getId());
        postFinancialEvent(FinancialEventType.OPERATING_COST, "45.00",
                "MODIFICATION_WORK_ORDER", savedOrder.getId());
    }

    private MachineConfig createMachineConfig(Long machineId) {
        ConfigItem item = new ConfigItem();
        item.setCategory("FINANCE_TEST");
        item.setItemName("finance modification config");
        item.setItemCode("FIN-CFG-" + unique("config"));
        item.setInputType("SELECT");
        ConfigItem savedItem = configItemRepository.saveAndFlush(item);
        configItemIds.add(savedItem.getId());

        ConfigValue value = new ConfigValue();
        value.setConfigItemId(savedItem.getId());
        value.setValueLabel("finance modification value");
        value.setValueCode("FINANCE_VALUE");
        value.setIsDefault(false);
        ConfigValue savedValue = configValueRepository.saveAndFlush(value);
        configValueIds.add(savedValue.getId());

        MachineConfig config = new MachineConfig();
        config.setMachineId(machineId);
        config.setConfigItemId(savedItem.getId());
        config.setConfigValueId(savedValue.getId());
        config.setItemName(savedItem.getItemName());
        config.setSelectedValue(savedValue.getValueLabel());
        MachineConfig savedConfig = machineConfigRepository.saveAndFlush(config);
        machineConfigIds.add(savedConfig.getId());
        return savedConfig;
    }

    private void saveModificationLine(
            Long workOrderId,
            MachineConfig machineConfig,
            String oldPartAction,
            String priceDifference
    ) {
        ModificationWorkOrderLine line = new ModificationWorkOrderLine();
        line.setWorkOrderId(workOrderId);
        line.setMachineId(machineConfig.getMachineId());
        line.setMachineConfigId(machineConfig.getId());
        line.setConfigItemId(machineConfig.getConfigItemId());
        line.setItemName("finance modification line");
        line.setOldPartAction(oldPartAction);
        line.setPriceDifference(new BigDecimal(priceDifference));
        modificationWorkOrderLineRepository.save(line);
    }

    private FinancialEvent postFinancialEvent(
            String eventType,
            String amount,
            String sourceType,
            Long sourceId
    ) {
        FinancialEvent event = new FinancialEvent();
        event.setEventNo(STOCK_CODE_PREFIX + unique("event"));
        event.setEventType(eventType);
        event.setAmount(new BigDecimal(amount));
        event.setBusinessDate(LocalDate.of(FINANCE_YEAR, 1, 31));
        event.setSourceType(sourceType);
        event.setSourceId(sourceId);
        event.setIdempotencyKey(STOCK_CODE_PREFIX + unique("event-key"));
        event.setCreatedBy("finance-test");
        FinancialEvent savedEvent = financialEventRepository.save(event);
        financialEventIds.add(savedEvent.getId());
        return savedEvent;
    }

    private void assertFinancialTotals(StatisticsDashboardVO.FinancialRow row) {
        assertThat(row).isNotNull();
        assertThat(row.getInboundQuantity()).isEqualTo(5);
        assertThat(row.getOutboundQuantity()).isEqualTo(5);
        assertThat(row.getRepairOrders()).isEqualTo(1);
        assertThat(row.getRentalOrders()).isEqualTo(1);
        assertThat(row.getModificationOrders()).isEqualTo(1);

        assertMoney(row.getInboundCost(), "20150.00");
        assertMoney(row.getOutboundRevenue(), "16320.00");
        assertMoney(row.getOutboundCost(), "10200.00");
        assertMoney(row.getRepairIncome(), "650.00");
        assertMoney(row.getRepairReceivable(), "850.00");
        assertMoney(row.getRepairExpense(), "200.00");
        assertMoney(row.getRepairPartsCost(), "80.00");
        assertMoney(row.getRentalIncome(), "3100.00");
        assertMoney(row.getModificationIncome(), "120.00");
        assertMoney(row.getModificationExpense(), "45.00");
        assertMoney(row.getTotalIncome(), "20190.00");
        assertMoney(row.getTotalExpense(), "10525.00");
        assertMoney(row.getGrossProfit(), "9665.00");
        assertMoney(row.getNetProfit(), "9665.00");
        assertMoney(row.getNetCashflow(), "0.00");
    }

    private StatisticsDashboardVO.FinancialRow findPeriod(List<StatisticsDashboardVO.FinancialRow> rows, String period) {
        return rows.stream()
                .filter(row -> period.equals(row.getPeriod()))
                .findFirst()
                .orElseThrow();
    }

    private void assertMoney(BigDecimal actual, String expected) {
        assertThat(actual).isEqualByComparingTo(new BigDecimal(expected));
    }
}
