package com.example.forklift_erp.service;

import com.example.forklift_erp.dto.HistoricalRepairReportVO;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.PurchaseOrder;
import com.example.forklift_erp.entity.StockBalance;
import com.example.forklift_erp.entity.StockMovementLine;
import com.example.forklift_erp.entity.Supplier;
import com.example.forklift_erp.entity.Warehouse;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.OutboundOrderRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.PurchaseOrderRepository;
import com.example.forklift_erp.repository.StockBalanceRepository;
import com.example.forklift_erp.repository.StockMovementLineRepository;
import com.example.forklift_erp.repository.StockMovementRepository;
import com.example.forklift_erp.repository.SupplierRepository;
import com.example.forklift_erp.repository.WarehouseRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reconstructs direct supplier and warehouse references without inventing
 * ambiguous history.
 */
@Service
public class HistoricalReferenceRepairService {
    private static final String RESOURCE_MACHINE = StockLedgerService.RESOURCE_MACHINE;
    private static final String RESOURCE_PART = StockLedgerService.RESOURCE_PART;

    private final SupplierRepository supplierRepository;
    private final WarehouseRepository warehouseRepository;
    private final MachineInventoryRepository machineInventoryRepository;
    private final PartInventoryRepository partInventoryRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final OutboundOrderRepository outboundOrderRepository;
    private final StockBalanceRepository stockBalanceRepository;
    private final StockMovementRepository stockMovementRepository;
    private final StockMovementLineRepository stockMovementLineRepository;
    private final MigrationExceptionService migrationExceptionService;

    public HistoricalReferenceRepairService(
            SupplierRepository supplierRepository,
            WarehouseRepository warehouseRepository,
            MachineInventoryRepository machineInventoryRepository,
            PartInventoryRepository partInventoryRepository,
            PurchaseOrderRepository purchaseOrderRepository,
            OutboundOrderRepository outboundOrderRepository,
            StockBalanceRepository stockBalanceRepository,
            StockMovementRepository stockMovementRepository,
            StockMovementLineRepository stockMovementLineRepository,
            MigrationExceptionService migrationExceptionService
    ) {
        this.supplierRepository = supplierRepository;
        this.warehouseRepository = warehouseRepository;
        this.machineInventoryRepository = machineInventoryRepository;
        this.partInventoryRepository = partInventoryRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.outboundOrderRepository = outboundOrderRepository;
        this.stockBalanceRepository = stockBalanceRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.stockMovementLineRepository = stockMovementLineRepository;
        this.migrationExceptionService = migrationExceptionService;
    }

    public void repair(
            HistoricalRepairReportVO report,
            boolean dryRun,
            Map<Long, MachineInventory> machines,
            Map<Long, PartInventory> parts
    ) {
        Map<ResourceKey, Set<Long>> balanceWarehouses = balanceWarehouses();
        backfillSupplierReferences(report, dryRun, machines);
        backfillWarehouseReferences(report, dryRun, machines, parts, balanceWarehouses);
    }

    private void backfillSupplierReferences(
            HistoricalRepairReportVO report,
            boolean dryRun,
            Map<Long, MachineInventory> machines
    ) {
        List<Supplier> suppliers = supplierRepository.findAll();
        Map<Long, Supplier> supplierById = suppliers.stream()
                .collect(Collectors.toMap(Supplier::getId, Function.identity(), (left, right) -> left));
        Map<String, List<Supplier>> byName = indexByNormalized(suppliers, Supplier::getSupplierName);

        for (MachineInventory machine : machines.values()) {
            String name = firstText(machine.getSupplierNameSnapshot(), machine.getSupplier());
            if (machine.getSupplierId() != null) {
                Supplier supplier = supplierById.get(machine.getSupplierId());
                if (supplier == null) {
                    exception(report, dryRun, "UNRESOLVED_SUPPLIER_ID", "MACHINE_INVENTORY", machine.getId(),
                            "Machine references a supplier id that does not exist: " + machine.getSupplierId());
                    continue;
                }
                if (!sameText(machine.getSupplierNameSnapshot(), supplier.getSupplierName())) {
                    if (!dryRun) {
                        machine.setSupplierNameSnapshot(supplier.getSupplierName());
                        machineInventoryRepository.save(machine);
                    }
                    report.addDefaulted("SUPPLIER_SNAPSHOT", "MACHINE_INVENTORY", machine.getId(),
                            "Filled supplier name snapshot from supplier id " + supplier.getId());
                }
                continue;
            }
            if (!hasText(name)) {
                continue;
            }
            Supplier supplier = uniqueCandidate(byName.get(normalized(name)));
            if (supplier == null) {
                exception(report, dryRun, byName.containsKey(normalized(name))
                                ? "AMBIGUOUS_SUPPLIER_MATCH" : "UNRESOLVED_SUPPLIER_MATCH",
                        "MACHINE_INVENTORY", machine.getId(),
                        "Cannot uniquely match supplier name: " + name);
                continue;
            }
            if (!dryRun) {
                machine.setSupplierId(supplier.getId());
                machine.setSupplierNameSnapshot(supplier.getSupplierName());
                machineInventoryRepository.save(machine);
            }
            report.addFixed("SUPPLIER_REFERENCE", "MACHINE_INVENTORY", machine.getId(),
                    "Matched supplier \"" + supplier.getSupplierName() + "\"");
        }

        for (PurchaseOrder purchase : purchaseOrderRepository.findAll()) {
            if (purchase.getSupplierId() != null) {
                if (!supplierById.containsKey(purchase.getSupplierId())) {
                    exception(report, dryRun, "UNRESOLVED_SUPPLIER_ID", "PURCHASE_ORDER", purchase.getId(),
                            "Purchase order references a supplier id that does not exist: " + purchase.getSupplierId());
                }
                continue;
            }
            if (!hasText(purchase.getSupplierName())) {
                continue;
            }
            Supplier supplier = uniqueCandidate(byName.get(normalized(purchase.getSupplierName())));
            if (supplier == null) {
                exception(report, dryRun, byName.containsKey(normalized(purchase.getSupplierName()))
                                ? "AMBIGUOUS_SUPPLIER_MATCH" : "UNRESOLVED_SUPPLIER_MATCH",
                        "PURCHASE_ORDER", purchase.getId(),
                        "Cannot uniquely match supplier name: " + purchase.getSupplierName());
                continue;
            }
            if (!dryRun) {
                purchase.setSupplierId(supplier.getId());
                purchase.setSupplierName(supplier.getSupplierName());
                purchaseOrderRepository.save(purchase);
            }
            report.addFixed("SUPPLIER_REFERENCE", "PURCHASE_ORDER", purchase.getId(),
                    "Matched supplier \"" + supplier.getSupplierName() + "\"");
        }
    }

    private void backfillWarehouseReferences(
            HistoricalRepairReportVO report,
            boolean dryRun,
            Map<Long, MachineInventory> machines,
            Map<Long, PartInventory> parts,
            Map<ResourceKey, Set<Long>> balanceWarehouses
    ) {
        List<Warehouse> warehouses = warehouseRepository.findAll();
        Map<Long, Warehouse> warehouseById = warehouses.stream()
                .collect(Collectors.toMap(Warehouse::getId, Function.identity(), (left, right) -> left));
        Map<String, List<Warehouse>> byText = warehouseIndex(warehouses);
        Warehouse singleWarehouse = warehouses.size() == 1 ? warehouses.get(0) : null;

        for (MachineInventory machine : machines.values()) {
            ResourceKey key = new ResourceKey(RESOURCE_MACHINE, machine.getId());
            Warehouse warehouse = warehouseById.get(machine.getWarehouseId());
            if (machine.getWarehouseId() != null && warehouse == null) {
                exception(report, dryRun, "UNRESOLVED_WAREHOUSE_ID", "MACHINE_INVENTORY", machine.getId(),
                        "Machine references a warehouse id that does not exist: " + machine.getWarehouseId());
                continue;
            }
            if (warehouse == null) {
                warehouse = uniqueCandidate(byText.get(normalized(machine.getWarehouseName())));
                if (warehouse == null) {
                    warehouse = uniqueWarehouse(balanceWarehouses.get(key), warehouseById);
                }
                if (warehouse == null && singleWarehouse != null) {
                    warehouse = singleWarehouse;
                    report.addDefaulted("WAREHOUSE_REFERENCE", "MACHINE_INVENTORY", machine.getId(),
                            "Used the only configured warehouse \"" + warehouse.getWarehouseName() + "\"");
                } else if (warehouse == null && value(machine.getInventoryCount()) > 0) {
                    exception(report, dryRun,
                            hasText(machine.getWarehouseName()) ? "UNRESOLVED_WAREHOUSE_MATCH" : "UNRESOLVED_WAREHOUSE",
                            "MACHINE_INVENTORY", machine.getId(),
                            "Cannot determine the actual warehouse for in-stock serialized vehicle");
                    continue;
                }
                if (warehouse != null && !hasWarehouseReport(report, "MACHINE_INVENTORY", machine.getId())) {
                    report.addFixed("WAREHOUSE_REFERENCE", "MACHINE_INVENTORY", machine.getId(),
                            "Matched warehouse \"" + warehouse.getWarehouseName() + "\"");
                }
            }
            if (warehouse != null && (!Objects.equals(machine.getWarehouseId(), warehouse.getId())
                    || !sameText(machine.getWarehouseName(), warehouse.getWarehouseName()))) {
                if (!dryRun) {
                    machine.setWarehouseId(warehouse.getId());
                    machine.setWarehouseName(warehouse.getWarehouseName());
                    machineInventoryRepository.save(machine);
                }
            }
        }

        for (PartInventory part : parts.values()) {
            if (part.getWarehouseId() != null) {
                if (!warehouseById.containsKey(part.getWarehouseId())) {
                    exception(report, dryRun, "UNRESOLVED_WAREHOUSE_ID", "PART_INVENTORY", part.getId(),
                            "Part references a warehouse id that does not exist: " + part.getWarehouseId());
                }
                continue;
            }
            if (value(part.getQuantity()) <= 0) {
                continue;
            }
            Warehouse warehouse = uniqueWarehouse(
                    balanceWarehouses.get(new ResourceKey(RESOURCE_PART, part.getId())),
                    warehouseById
            );
            boolean defaulted = false;
            if (warehouse == null && singleWarehouse != null) {
                warehouse = singleWarehouse;
                defaulted = true;
            }
            if (warehouse == null) {
                exception(report, dryRun, "UNRESOLVED_WAREHOUSE", "PART_INVENTORY", part.getId(),
                        "Cannot determine the actual warehouse for an in-stock part in a multi-warehouse system");
                continue;
            }
            if (!dryRun) {
                part.setWarehouseId(warehouse.getId());
                partInventoryRepository.save(part);
            }
            if (defaulted) {
                report.addDefaulted("WAREHOUSE_REFERENCE", "PART_INVENTORY", part.getId(),
                        "Used the only configured warehouse \"" + warehouse.getWarehouseName() + "\"");
            } else {
                report.addFixed("WAREHOUSE_REFERENCE", "PART_INVENTORY", part.getId(),
                        "Matched warehouse from the sole stock-balance location \"" + warehouse.getWarehouseName() + "\"");
            }
        }

        backfillPurchaseWarehouses(report, dryRun, balanceWarehouses, warehouseById, singleWarehouse);
        backfillOutboundWarehouses(report, dryRun, warehouseById, singleWarehouse);
    }

    private void backfillPurchaseWarehouses(
            HistoricalRepairReportVO report,
            boolean dryRun,
            Map<ResourceKey, Set<Long>> balanceWarehouses,
            Map<Long, Warehouse> warehouseById,
            Warehouse singleWarehouse
    ) {
        for (PurchaseOrder purchase : purchaseOrderRepository.findAll()) {
            if (purchase.getWarehouseId() != null || !"RECEIVED".equals(purchase.getStatus())) {
                continue;
            }
            Warehouse warehouse = purchase.getResourceId() == null ? null
                    : uniqueWarehouse(
                            balanceWarehouses.get(new ResourceKey(purchase.getResourceType(), purchase.getResourceId())),
                            warehouseById
                    );
            boolean defaulted = false;
            if (warehouse == null && singleWarehouse != null) {
                warehouse = singleWarehouse;
                defaulted = true;
            }
            if (warehouse == null) {
                exception(report, dryRun, "UNRESOLVED_WAREHOUSE", "PURCHASE_ORDER", purchase.getId(),
                        "Received purchase order has no uniquely determinable warehouse");
                continue;
            }
            if (!dryRun) {
                purchase.setWarehouseId(warehouse.getId());
                purchaseOrderRepository.save(purchase);
            }
            if (defaulted) {
                report.addDefaulted("WAREHOUSE_REFERENCE", "PURCHASE_ORDER", purchase.getId(),
                        "Used the only configured warehouse \"" + warehouse.getWarehouseName() + "\"");
            } else {
                report.addFixed("WAREHOUSE_REFERENCE", "PURCHASE_ORDER", purchase.getId(),
                        "Matched warehouse from the resource stock balance");
            }
        }
    }

    private void backfillOutboundWarehouses(
            HistoricalRepairReportVO report,
            boolean dryRun,
            Map<Long, Warehouse> warehouseById,
            Warehouse singleWarehouse
    ) {
        for (OutboundOrder order : outboundOrderRepository.findAll()) {
            if (order.getSourceWarehouseId() != null) {
                if (!warehouseById.containsKey(order.getSourceWarehouseId())) {
                    exception(report, dryRun, "UNRESOLVED_WAREHOUSE_ID", "OUTBOUND_ORDER", order.getId(),
                            "Outbound order references a source warehouse id that does not exist: "
                                    + order.getSourceWarehouseId());
                }
                continue;
            }
            Set<Long> movementWarehouses = stockMovementRepository
                    .findBySourceTypeAndSourceId(FinancialEventService.SOURCE_OUTBOUND_ORDER, order.getId())
                    .stream()
                    .flatMap(movement -> stockMovementLineRepository
                            .findByMovementIdOrderByIdAsc(movement.getId()).stream())
                    .filter(line -> value(line.getQuantityDelta()) < 0)
                    .map(StockMovementLine::getWarehouseId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Warehouse warehouse = uniqueWarehouse(movementWarehouses, warehouseById);
            boolean defaulted = false;
            if (warehouse == null && singleWarehouse != null) {
                warehouse = singleWarehouse;
                defaulted = true;
            }
            if (warehouse == null) {
                exception(report, dryRun, "UNRESOLVED_OUTBOUND_WAREHOUSE", "OUTBOUND_ORDER", order.getId(),
                        "Cannot determine the source warehouse from historic outbound movement lines");
                continue;
            }
            if (!dryRun) {
                order.setSourceWarehouseId(warehouse.getId());
                outboundOrderRepository.save(order);
            }
            if (defaulted) {
                report.addDefaulted("OUTBOUND_WAREHOUSE", "OUTBOUND_ORDER", order.getId(),
                        "Used the only configured warehouse \"" + warehouse.getWarehouseName() + "\"");
            } else {
                report.addFixed("OUTBOUND_WAREHOUSE", "OUTBOUND_ORDER", order.getId(),
                        "Matched source warehouse from historic stock movement");
            }
        }
    }

    private Map<ResourceKey, Set<Long>> balanceWarehouses() {
        Map<ResourceKey, Set<Long>> result = new HashMap<>();
        for (StockBalance balance : stockBalanceRepository.findAll()) {
            if (balance.getWarehouseId() == null) {
                continue;
            }
            if (value(balance.getAvailableQuantity())
                    + value(balance.getReservedQuantity())
                    + value(balance.getLockedQuantity()) <= 0) {
                continue;
            }
            result.computeIfAbsent(
                    new ResourceKey(balance.getResourceType(), balance.getResourceId()),
                    ignored -> new LinkedHashSet<>()
            ).add(balance.getWarehouseId());
        }
        return result;
    }

    private Map<String, List<Supplier>> indexByNormalized(
            List<Supplier> suppliers,
            Function<Supplier, String> text
    ) {
        Map<String, List<Supplier>> result = new LinkedHashMap<>();
        for (Supplier supplier : suppliers) {
            if (!hasText(text.apply(supplier))) {
                continue;
            }
            result.computeIfAbsent(normalized(text.apply(supplier)), ignored -> new ArrayList<>()).add(supplier);
        }
        return result;
    }

    private Map<String, List<Warehouse>> warehouseIndex(List<Warehouse> warehouses) {
        Map<String, List<Warehouse>> result = new LinkedHashMap<>();
        for (Warehouse warehouse : warehouses) {
            addCandidate(result, warehouse.getWarehouseName(), warehouse);
            addCandidate(result, warehouse.getWarehouseCode(), warehouse);
        }
        return result;
    }

    private void addCandidate(Map<String, List<Warehouse>> index, String key, Warehouse warehouse) {
        if (!hasText(key)) {
            return;
        }
        List<Warehouse> candidates = index.computeIfAbsent(normalized(key), ignored -> new ArrayList<>());
        if (candidates.stream().noneMatch(existing -> Objects.equals(existing.getId(), warehouse.getId()))) {
            candidates.add(warehouse);
        }
    }

    private Warehouse uniqueWarehouse(Set<Long> ids, Map<Long, Warehouse> warehouseById) {
        if (ids == null || ids.size() != 1) {
            return null;
        }
        return warehouseById.get(ids.iterator().next());
    }

    private <T> T uniqueCandidate(Collection<T> candidates) {
        return candidates != null && candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    private void exception(
            HistoricalRepairReportVO report,
            boolean dryRun,
            String exceptionType,
            String sourceType,
            Long sourceId,
            String detail
    ) {
        report.addException(exceptionType, sourceType, sourceId, detail);
        if (!dryRun) {
            migrationExceptionService.openOnce(exceptionType, sourceType, sourceId, detail);
        }
    }

    private boolean hasWarehouseReport(HistoricalRepairReportVO report, String sourceType, Long sourceId) {
        return report.getFixed().stream().anyMatch(item -> sourceType.equals(item.getSourceType())
                && Objects.equals(sourceId, item.getSourceId())
                && "WAREHOUSE_REFERENCE".equals(item.getCategory()))
                || report.getDefaulted().stream().anyMatch(item -> sourceType.equals(item.getSourceType())
                && Objects.equals(sourceId, item.getSourceId())
                && "WAREHOUSE_REFERENCE".equals(item.getCategory()));
    }

    private int value(Integer input) {
        return input == null ? 0 : input;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean sameText(String left, String right) {
        return Objects.equals(normalized(left), normalized(right));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record ResourceKey(String resourceType, Long resourceId) {
    }
}
