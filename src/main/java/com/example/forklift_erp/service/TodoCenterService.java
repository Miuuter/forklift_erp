package com.example.forklift_erp.service;

import com.example.forklift_erp.constant.MachineStockStatus;
import com.example.forklift_erp.constant.RentalStatus;
import com.example.forklift_erp.dto.TodoCenterVO;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.RentalRecord;
import com.example.forklift_erp.entity.RepairRecord;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.OutboundOrderRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.RentalRecordRepository;
import com.example.forklift_erp.repository.RepairRecordRepository;
import com.example.forklift_erp.service.impl.OutboundUploadReadinessPolicy;
import com.example.forklift_erp.util.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TodoCenterService {
    private static final int ITEM_LIMIT = 30;
    private static final int QUEUE_ITEM_LIMIT = 8;
    private static final int LOW_PART_THRESHOLD = 5;
    private static final int RENTAL_DUE_SOON_DAYS = 7;
    private static final int LONG_IDLE_DAYS = 90;

    @Autowired
    private OutboundOrderRepository outboundOrderRepository;

    @Autowired
    private PartInventoryRepository partRepository;

    @Autowired
    private RepairRecordRepository repairRepository;

    @Autowired
    private RentalRecordRepository rentalRepository;

    @Autowired
    private MachineInventoryRepository machineRepository;

    @Autowired
    private OutboundUploadReadinessPolicy uploadReadinessPolicy;

    @Transactional(readOnly = true)
    public TodoCenterVO dashboard() {
        TodoCenterVO dashboard = new TodoCenterVO();
        LocalDateTime generatedAt = LocalDateTime.now();
        dashboard.setGeneratedAt(generatedAt);
        dashboard.setLowStockThreshold(LOW_PART_THRESHOLD);
        dashboard.setRentalDueSoonDays(RENTAL_DUE_SOON_DAYS);
        dashboard.setLongIdleDays(LONG_IDLE_DAYS);

        boolean includeLocked = SecurityUtils.isAdminOrSuperAdmin();
        LocalDate rentalDueCutoff = generatedAt.toLocalDate().plusDays(RENTAL_DUE_SOON_DAYS);
        LocalDateTime idleCutoff = generatedAt.minusDays(LONG_IDLE_DAYS);

        applyOrderSummary(dashboard, outboundOrderRepository.summarizeTodos(includeLocked));

        List<OutboundOrder> overduePaymentOrders = outboundOrderRepository.findOverduePaymentTodos(includeLocked, queuePage());
        List<OutboundOrder> pendingPaymentOrders = outboundOrderRepository.findPendingPaymentTodos(includeLocked, queuePage());
        List<OutboundOrder> salesReportOrders = outboundOrderRepository.findSalesReportTodos(includeLocked, queuePage());
        List<OutboundOrder> invoiceApplicationOrders = outboundOrderRepository.findInvoiceApplicationTodos(includeLocked, queuePage());
        List<OutboundOrder> invoiceFileOrders = outboundOrderRepository.findInvoiceFileTodos(includeLocked, queuePage());
        List<OutboundOrder> contractFileOrders = outboundOrderRepository.findContractFileTodos(includeLocked, queuePage());

        dashboard.setPendingRepairCount(toSafeInt(repairRepository.countPendingTodos(includeLocked)));
        List<RepairRecord> pendingRepairs = repairRepository.findPendingTodos(includeLocked, queuePage());

        dashboard.setActiveRentalCount(toSafeInt(rentalRepository.countByStatus(RentalStatus.ACTIVE.code())));
        dashboard.setRentalDueCount(toSafeInt(rentalRepository.countDueSoonTodos(RentalStatus.ACTIVE.code(), rentalDueCutoff)));
        List<RentalRecord> dueRentals = rentalRepository.findDueSoonTodos(RentalStatus.ACTIVE.code(), rentalDueCutoff, queuePage());

        dashboard.setLowStockCount(toSafeInt(partRepository.countLowStockTodos(includeLocked)));
        List<PartInventory> lowStockParts = partRepository.findLowStockTodos(includeLocked, queuePage());

        dashboard.setInStockVehicleCount(toSafeInt(machineRepository.countInStockVehicleTodos(includeLocked)));
        dashboard.setLongIdleVehicleCount(toSafeInt(machineRepository.countLongIdleVehicleTodos(
                idleCutoff,
                MachineStockStatus.IN_STOCK.code(),
                RentalStatus.ACTIVE.code(),
                includeLocked
        )));
        List<MachineInventory> longIdleVehicles = machineRepository.findLongIdleVehicleTodos(
                idleCutoff,
                MachineStockStatus.IN_STOCK.code(),
                RentalStatus.ACTIVE.code(),
                includeLocked,
                queuePage()
        );

        addQueue(dashboard, "overduePayment", "逾期收款", "已过收款日期且仍有未收金额", "danger",
                dashboard.getOverduePaymentCount(), dashboard.getOverdueAmount(),
                "outboundOrders", filter("stage", "overdue"),
                overduePaymentOrders.stream().map(this::orderPaymentTodo).toList());

        int duePaymentCount = Math.max(0, dashboard.getPendingPaymentCount() - dashboard.getOverduePaymentCount());
        addQueue(dashboard, "pendingPayment", "待收款", "未到期或未设置到期日的应收款", "warn",
                duePaymentCount, dashboard.getOutstandingAmount().subtract(dashboard.getOverdueAmount()).max(BigDecimal.ZERO),
                "outboundOrders", filter("stage", "payment"),
                pendingPaymentOrders.stream().map(this::orderPaymentTodo).toList());

        addQueue(dashboard, "invoiceFile", "待上传发票", "已具备发票上传条件但缺少文件", "primary",
                dashboard.getPendingInvoiceFileCount(), BigDecimal.ZERO,
                "outboundOrders", filter("stage", "invoiceFile"),
                invoiceFileOrders.stream().map(order -> orderSimpleTodo(order, "ORDER_INVOICE_FILE", "待上传发票", "primary")).toList());

        addQueue(dashboard, "contractFile", "待上传合同", "已标记合同但缺少合同文件", "teal",
                dashboard.getPendingContractFileCount(), BigDecimal.ZERO,
                "outboundOrders", filter("stage", "contractFile"),
                contractFileOrders.stream().map(order -> orderSimpleTodo(order, "ORDER_CONTRACT_FILE", "待上传合同", "teal")).toList());

        addQueue(dashboard, "repairPending", "待维修", "未完成维修工单需要跟进", "warn",
                dashboard.getPendingRepairCount(), BigDecimal.ZERO,
                "repairs", filter("status", "pending"),
                pendingRepairs.stream().map(this::repairTodo).toList());

        addQueue(dashboard, "rentalDue", "租赁到期", "进行中租赁将在 7 天内到期或已逾期", "warn",
                dashboard.getRentalDueCount(), BigDecimal.ZERO,
                "rentals", filter("status", "dueSoon"),
                dueRentals.stream().map(this::rentalDueTodo).toList());

        addQueue(dashboard, "lowStock", "低库存", "配件库存小于等于预警阈值", "danger",
                dashboard.getLowStockCount(), BigDecimal.ZERO,
                "parts", filter("stock", "low"),
                lowStockParts.stream().map(this::partLowStockTodo).toList());

        addQueue(dashboard, "longIdleVehicles", "长期未动库存", "在库整机超过 90 天未出库且未出租", "warn",
                dashboard.getLongIdleVehicleCount(), BigDecimal.ZERO,
                "vehicles", filter("stock", "longIdle"),
                longIdleVehicles.stream().map(this::longIdleVehicleTodo).toList());

        addQueue(dashboard, "salesReport", "待报销售", "已收款但尚未完成销售报备", "primary",
                dashboard.getPendingSalesReportCount(), BigDecimal.ZERO,
                "outboundOrders", filter("stage", "salesReport"),
                salesReportOrders.stream().map(order -> orderSimpleTodo(order, "ORDER_SALES_REPORT", "待报销售", "primary")).toList());

        addQueue(dashboard, "invoiceApplication", "待申请发票", "已报销售但尚未申请发票", "warn",
                dashboard.getPendingInvoiceApplicationCount(), BigDecimal.ZERO,
                "outboundOrders", filter("stage", "invoiceApplication"),
                invoiceApplicationOrders.stream().map(order -> orderSimpleTodo(order, "ORDER_INVOICE_APPLICATION", "待申请发票", "warn")).toList());

        dashboard.getItems().sort(
                Comparator.comparing((TodoCenterVO.TodoItem item) -> priorityRank(item.getPriority()))
                        .thenComparing(TodoCenterVO.TodoItem::getSortTime, Comparator.nullsLast(Comparator.reverseOrder()))
        );
        if (dashboard.getItems().size() > ITEM_LIMIT) {
            dashboard.setItems(dashboard.getItems().subList(0, ITEM_LIMIT));
        }

        dashboard.setTotalTodoCount(dashboard.getQueues().stream()
                .mapToInt(queue -> queue.getCount() == null ? 0 : queue.getCount())
                .sum());
        dashboard.setCriticalTodoCount(dashboard.getQueues().stream()
                .filter(queue -> "danger".equals(queue.getPriority()))
                .mapToInt(queue -> queue.getCount() == null ? 0 : queue.getCount())
                .sum());

        return dashboard;
    }

    private void applyOrderSummary(TodoCenterVO dashboard, OutboundOrderRepository.OutboundTodoSummaryProjection summary) {
        if (summary == null) {
            return;
        }
        dashboard.setReceivableAmount(amount(summary.getReceivableAmount()));
        dashboard.setReceivedAmount(amount(summary.getReceivedAmount()));
        dashboard.setOutstandingAmount(amount(summary.getOutstandingAmount()));
        dashboard.setOverdueAmount(amount(summary.getOverdueAmount()));
        dashboard.setPendingPaymentCount(toSafeInt(summary.getPendingPaymentCount()));
        dashboard.setOverduePaymentCount(toSafeInt(summary.getOverduePaymentCount()));
        dashboard.setPendingSalesReportCount(toSafeInt(summary.getPendingSalesReportCount()));
        dashboard.setPendingInvoiceApplicationCount(toSafeInt(summary.getPendingInvoiceApplicationCount()));
        dashboard.setPendingInvoiceFileCount(toSafeInt(summary.getPendingInvoiceFileCount()));
        dashboard.setPendingContractFileCount(toSafeInt(summary.getPendingContractFileCount()));
    }

    private void addQueue(
            TodoCenterVO dashboard,
            String key,
            String label,
            String description,
            String priority,
            Integer count,
            BigDecimal amount,
            String targetTab,
            Map<String, String> targetFilter,
            List<TodoCenterVO.TodoItem> items
    ) {
        TodoCenterVO.TodoQueue queue = new TodoCenterVO.TodoQueue();
        queue.setKey(key);
        queue.setLabel(label);
        queue.setDescription(description);
        queue.setPriority(priority);
        queue.setCount(count == null ? 0 : count);
        queue.setAmount(amount(amount));
        queue.setTargetTab(targetTab);
        queue.setTargetFilter(new LinkedHashMap<>(targetFilter));
        queue.setItems(items);
        dashboard.getQueues().add(queue);
        dashboard.getItems().addAll(items);
    }

    private TodoCenterVO.TodoItem orderPaymentTodo(OutboundOrder order) {
        BigDecimal receivable = amount(firstAmount(order.getReceivableAmount(), order.getSettlementPrice()));
        BigDecimal received = amount(order.getReceivedAmount());
        BigDecimal outstanding = receivable.subtract(received).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        int overdueDays = overdueDays(outstanding, order.getPaymentDueDate());
        TodoCenterVO.TodoItem item = orderSimpleTodo(
                order,
                "ORDER_PAYMENT",
                overdueDays > 0 ? "逾期收款" : "待收款",
                overdueDays > 0 ? "danger" : "warn"
        );
        item.setAmount(outstanding);
        item.setDueDate(order.getPaymentDueDate());
        item.setOverdueDays(overdueDays);
        return target(item, "outboundOrders", filter("stage", overdueDays > 0 ? "overdue" : "payment"));
    }

    private TodoCenterVO.TodoItem orderSimpleTodo(OutboundOrder order, String type, String label, String priority) {
        String title = safe(order.getResourceCode(), order.getOrderNo(), "订单 " + order.getId());
        String detail = safe(order.getCustomerName(), order.getPaymentRemark(), order.getOrderNo());
        if ("ORDER_INVOICE_FILE".equals(type)) {
            detail = safe(order.getInvoiceStatus(), detail);
        }
        if ("ORDER_CONTRACT_FILE".equals(type)) {
            detail = safe(order.getContractType(), detail);
        }
        return target(item(type, label, title, detail, priority, "OUTBOUND_ORDER", order.getId(),
                null, null, order.getUpdatedAt()), "outboundOrders", filter("stage", orderStage(type)));
    }

    private TodoCenterVO.TodoItem repairTodo(RepairRecord repair) {
        return target(item(
                "REPAIR_PENDING",
                "待维修",
                safe(repair.getCustomerName(), repair.getVehicleNumber(), "维修记录 " + repair.getId()),
                safe(repair.getFaultDescription(), repair.getStatus(), "待处理"),
                "warn",
                "REPAIR",
                repair.getId(),
                amount(repair.getTotalFee()),
                null,
                repair.getUpdatedAt()
        ), "repairs", filter("status", "pending"));
    }

    private TodoCenterVO.TodoItem rentalDueTodo(RentalRecord rental) {
        TodoCenterVO.TodoItem item = item(
                "RENTAL_DUE",
                "租赁到期",
                safe(rental.getVehicleNumber(), rental.getRentalNo(), "租赁记录 " + rental.getId()),
                joinNonBlank(" / ", rental.getCustomerName(), rental.getDestination(), rental.getRentalNo()),
                rentalOverdueDays(rental.getEndDate()) > 0 ? "danger" : "warn",
                "RENTAL",
                rental.getId(),
                amount(firstAmount(rental.getMonthlyRentalPrice(), rental.getRentalPrice())),
                rental.getEndDate(),
                rental.getUpdatedAt()
        );
        item.setOverdueDays(rentalOverdueDays(rental.getEndDate()));
        return target(item, "rentals", filter("status", "dueSoon"));
    }

    private TodoCenterVO.TodoItem partLowStockTodo(PartInventory part) {
        return target(item(
                "PART_LOW_STOCK",
                "低库存",
                safe(part.getPartCode(), "配件 " + part.getId()),
                joinNonBlank(" / ", part.getPartName(), "库存 " + safeNumber(part.getQuantity()) + unit(part.getUnit()),
                        "阈值 " + (part.getReorderPoint() == null ? LOW_PART_THRESHOLD : part.getReorderPoint())),
                "danger",
                "PART",
                part.getId(),
                null,
                null,
                part.getUpdatedAt()
        ), "parts", filter("stock", "low"));
    }

    private TodoCenterVO.TodoItem longIdleVehicleTodo(MachineInventory machine) {
        LocalDateTime stockSince = firstDateTime(machine.getInboundDate(), machine.getCreatedAt());
        TodoCenterVO.TodoItem item = item(
                "VEHICLE_LONG_IDLE",
                "长期未动库存",
                safe(machine.getVehicleProductNumber(), "整机 " + machine.getId()),
                joinNonBlank(" / ", machine.getName(), machine.getSpecificationModel(), machine.getWarehouseName()),
                "warn",
                "MACHINE",
                machine.getId(),
                amount(firstAmount(machine.getSettlementPrice(), machine.getPurchasePrice())),
                null,
                stockSince
        );
        item.setAgeDays(ageDays(stockSince));
        return target(item, "vehicles", filter("stock", "longIdle"));
    }

    private TodoCenterVO.TodoItem item(
            String type,
            String label,
            String title,
            String detail,
            String priority,
            String resourceType,
            Long resourceId,
            BigDecimal amount,
            LocalDate dueDate,
            LocalDateTime sortTime
    ) {
        TodoCenterVO.TodoItem item = new TodoCenterVO.TodoItem();
        item.setType(type);
        item.setLabel(label);
        item.setTitle(title);
        item.setDetail(detail);
        item.setPriority(priority);
        item.setResourceType(resourceType);
        item.setResourceId(resourceId);
        item.setAmount(amount(amount));
        item.setDueDate(dueDate);
        item.setSortTime(sortTime);
        item.setOverdueDays(overdueDays(amount, dueDate));
        return item;
    }

    private TodoCenterVO.TodoItem target(TodoCenterVO.TodoItem item, String tab, Map<String, String> filter) {
        item.setTargetTab(tab);
        item.setTargetFilter(new LinkedHashMap<>(filter));
        return item;
    }

    private PageRequest queuePage() {
        return PageRequest.of(0, QUEUE_ITEM_LIMIT);
    }

    private Map<String, String> filter(String... pairs) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (!isBlank(pairs[i]) && pairs[i + 1] != null) {
                result.put(pairs[i], pairs[i + 1]);
            }
        }
        return result;
    }

    private String orderStage(String type) {
        return switch (type) {
            case "ORDER_PAYMENT" -> "payment";
            case "ORDER_SALES_REPORT" -> "salesReport";
            case "ORDER_INVOICE_APPLICATION" -> "invoiceApplication";
            case "ORDER_INVOICE_FILE" -> "invoiceFile";
            case "ORDER_CONTRACT_FILE" -> "contractFile";
            default -> "";
        };
    }

    private BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal firstAmount(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null) {
                return value;
            }
        }
        return BigDecimal.ZERO;
    }

    private LocalDateTime firstDateTime(LocalDateTime... values) {
        for (LocalDateTime value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private int overdueDays(BigDecimal outstanding, LocalDate dueDate) {
        if (dueDate == null || outstanding == null || outstanding.signum() <= 0) {
            return 0;
        }
        return positiveDaysBetween(dueDate, LocalDate.now());
    }

    private int rentalOverdueDays(LocalDate dueDate) {
        if (dueDate == null) {
            return 0;
        }
        return positiveDaysBetween(dueDate, LocalDate.now());
    }

    private int ageDays(LocalDateTime value) {
        if (value == null) {
            return 0;
        }
        return positiveDaysBetween(value.toLocalDate(), LocalDate.now());
    }

    private int positiveDaysBetween(LocalDate start, LocalDate end) {
        long days = ChronoUnit.DAYS.between(start, end);
        if (days <= 0) {
            return 0;
        }
        return days > Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.toIntExact(days);
    }

    private int priorityRank(String priority) {
        if ("danger".equals(priority)) return 0;
        if ("warn".equals(priority)) return 1;
        return 2;
    }

    private String joinNonBlank(String delimiter, String... values) {
        String joined = String.join(delimiter, java.util.Arrays.stream(values)
                .filter(value -> !isBlank(value))
                .map(String::trim)
                .toList());
        return joined.isBlank() ? "-" : joined;
    }

    private String safe(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "-";
    }

    private String safeNumber(Integer value) {
        return value == null ? "0" : String.valueOf(value);
    }

    private String unit(String value) {
        return isBlank(value) ? "" : value.trim();
    }

    private int toSafeInt(Long value) {
        return value == null ? 0 : toSafeInt(value.longValue());
    }

    private int toSafeInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.toIntExact(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
