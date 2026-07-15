package com.example.forklift_erp;

import com.example.forklift_erp.constant.ModificationWorkOrderStatus;
import com.example.forklift_erp.constant.MachineStockStatus;
import com.example.forklift_erp.constant.PartChangeAction;
import com.example.forklift_erp.constant.RentalStatus;
import com.example.forklift_erp.constant.RepairStatus;
import com.example.forklift_erp.dto.StatisticsDashboardVO;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.ModificationWorkOrder;
import com.example.forklift_erp.entity.ModificationWorkOrderLine;
import com.example.forklift_erp.entity.RentalRecord;
import com.example.forklift_erp.entity.RepairRecord;
import com.example.forklift_erp.repository.ModificationWorkOrderLineRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderRepository;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.RentalRecordRepository;
import com.example.forklift_erp.repository.RepairRecordRepository;
import com.example.forklift_erp.service.StatisticsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

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
    private JdbcTemplate jdbcTemplate;

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

    private final List<Long> repairIds = new ArrayList<>();
    private final List<Long> rentalIds = new ArrayList<>();
    private final List<Long> workOrderIds = new ArrayList<>();
    private final List<Long> machineIds = new ArrayList<>();

    @AfterEach
    void cleanFinanceRows() {
        jdbcTemplate.update("delete from stock_operation_log where resource_code like ?", STOCK_CODE_PREFIX + "%");

        for (Long workOrderId : workOrderIds.reversed()) {
            modificationWorkOrderLineRepository.findByWorkOrderIdOrderByIdAsc(workOrderId)
                    .forEach(modificationWorkOrderLineRepository::delete);
            modificationWorkOrderRepository.findById(workOrderId).ifPresent(modificationWorkOrderRepository::delete);
        }
        workOrderIds.clear();

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
        createStockRows();
        createCompletedRepair();
        createReturnedRentalForFullMonth(machineId);
        createCompletedModificationWorkOrder(machineId);

        StatisticsDashboardVO dashboard = statisticsService.financeDashboard(FINANCE_YEAR);

        assertFinancialTotals(dashboard.getAnnualSummary());
        assertFinancialTotals(findPeriod(dashboard.getMonthlyFinance(), FINANCE_YEAR + "-01"));
        assertFinancialTotals(findPeriod(dashboard.getYearlyFinance(), String.valueOf(FINANCE_YEAR)));
    }

    private void createStockRows() {
        insertStockLog("MACHINE", "INBOUND", STOCK_CODE_PREFIX + "M-IN", 2, null, null,
                "10000.00", "0.00", LocalDateTime.of(FINANCE_YEAR, 1, 5, 9, 0));
        insertStockLog("MACHINE", "OUTBOUND", STOCK_CODE_PREFIX + "M-OUT", 1, null, null,
                "10000.00", "16000.00", LocalDateTime.of(FINANCE_YEAR, 1, 10, 10, 0));
        insertStockLog("PART", "ADJUST", STOCK_CODE_PREFIX + "P-ADJ-IN", 3, 10, 13,
                "50.00", "80.00", LocalDateTime.of(FINANCE_YEAR, 1, 12, 11, 0));
        insertStockLog("PART", "ADJUST", STOCK_CODE_PREFIX + "P-ADJ-OUT", 4, 13, 9,
                "50.00", "80.00", LocalDateTime.of(FINANCE_YEAR, 1, 13, 11, 0));
    }

    private void insertStockLog(
            String resourceType,
            String operationType,
            String resourceCode,
            Integer quantity,
            Integer beforeQuantity,
            Integer afterQuantity,
            String unitCost,
            String unitRevenue,
            LocalDateTime createdAt
    ) {
        jdbcTemplate.update("""
                        insert into stock_operation_log
                        (resource_type, operation_type, resource_code, resource_name, quantity,
                         before_quantity, after_quantity, unit_cost, unit_revenue, operator, remark, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                resourceType,
                operationType,
                resourceCode,
                resourceCode,
                quantity,
                beforeQuantity,
                afterQuantity,
                new BigDecimal(unitCost),
                new BigDecimal(unitRevenue),
                "finance-test",
                "finance integration test",
                createdAt
        );
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
        repairIds.add(repairRecordRepository.save(repair).getId());
    }

    private Long createMachine() {
        MachineInventory machine = new MachineInventory();
        machine.setVehicleProductNumber("FIN-MACHINE-" + unique("machine"));
        machine.setName("finance machine");
        machine.setSpecificationModel("finance-spec");
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
        rentalIds.add(rentalRecordRepository.save(rental).getId());
    }

    private void createCompletedModificationWorkOrder(Long machineId) {
        ModificationWorkOrder order = new ModificationWorkOrder();
        order.setWorkOrderNo("FIN-MOD-" + unique("mod"));
        order.setMachineId(machineId);
        order.setStatus(ModificationWorkOrderStatus.COMPLETED.code());
        order.setCompletedAt(LocalDateTime.of(FINANCE_YEAR, 1, 20, 16, 0));
        ModificationWorkOrder savedOrder = modificationWorkOrderRepository.save(order);
        workOrderIds.add(savedOrder.getId());

        saveModificationLine(savedOrder.getId(), PartChangeAction.DISCOUNT.code(), "-120.00");
        saveModificationLine(savedOrder.getId(), PartChangeAction.DISCOUNT.code(), "45.00");
        saveModificationLine(savedOrder.getId(), PartChangeAction.STOCK_IN.code(), "-999.00");
    }

    private void saveModificationLine(Long workOrderId, String oldPartAction, String priceDifference) {
        ModificationWorkOrderLine line = new ModificationWorkOrderLine();
        line.setWorkOrderId(workOrderId);
        line.setMachineConfigId(990003L);
        line.setItemName("finance modification line");
        line.setOldPartAction(oldPartAction);
        line.setPriceDifference(new BigDecimal(priceDifference));
        modificationWorkOrderLineRepository.save(line);
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
