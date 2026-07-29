package com.example.forklift_erp.config;

import com.example.forklift_erp.constant.MachineStockStatus;
import com.example.forklift_erp.constant.ModificationWorkOrderStatus;
import com.example.forklift_erp.constant.PartChangeAction;
import com.example.forklift_erp.constant.RentalStatus;
import com.example.forklift_erp.constant.RepairStatus;
import com.example.forklift_erp.entity.ConfigItem;
import com.example.forklift_erp.entity.ConfigValue;
import com.example.forklift_erp.entity.Customer;
import com.example.forklift_erp.entity.MachineConfig;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.ModificationWorkOrder;
import com.example.forklift_erp.entity.ModificationWorkOrderLine;
import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.PurchaseOrder;
import com.example.forklift_erp.entity.RentalRecord;
import com.example.forklift_erp.entity.RepairRecord;
import com.example.forklift_erp.entity.StockMovement;
import com.example.forklift_erp.entity.StockMovementLine;
import com.example.forklift_erp.entity.StocktakingRecord;
import com.example.forklift_erp.entity.Supplier;
import com.example.forklift_erp.entity.Warehouse;
import com.example.forklift_erp.repository.ConfigItemRepository;
import com.example.forklift_erp.repository.ConfigValueRepository;
import com.example.forklift_erp.repository.CustomerRepository;
import com.example.forklift_erp.repository.MachineConfigRepository;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderLineRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderRepository;
import com.example.forklift_erp.repository.OutboundOrderRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.PurchaseOrderRepository;
import com.example.forklift_erp.repository.RentalRecordRepository;
import com.example.forklift_erp.repository.RepairRecordRepository;
import com.example.forklift_erp.repository.StockMovementLineRepository;
import com.example.forklift_erp.repository.StockMovementRepository;
import com.example.forklift_erp.repository.StocktakingRecordRepository;
import com.example.forklift_erp.repository.SupplierRepository;
import com.example.forklift_erp.repository.WarehouseRepository;
import com.example.forklift_erp.service.StockLedgerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@Order(20)
@ConditionalOnProperty(prefix = "forklift.seed-demo-data", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DemoDataInitializer implements CommandLineRunner {
    private static final String OPERATOR = "demo-seed";
    private static final String MARKER = "[seed-demo-data]";

    @Autowired
    private ConfigItemRepository configItemRepository;

    @Autowired
    private ConfigValueRepository configValueRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private MachineInventoryRepository machineRepository;

    @Autowired
    private MachineConfigRepository machineConfigRepository;

    @Autowired
    private PartInventoryRepository partRepository;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private OutboundOrderRepository outboundOrderRepository;

    @Autowired
    private RentalRecordRepository rentalRecordRepository;

    @Autowired
    private RepairRecordRepository repairRecordRepository;

    @Autowired
    private StocktakingRecordRepository stocktakingRecordRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private StockMovementLineRepository stockMovementLineRepository;

    @Autowired
    private ModificationWorkOrderRepository workOrderRepository;

    @Autowired
    private ModificationWorkOrderLineRepository workOrderLineRepository;

    @Autowired
    private StockLedgerService stockLedgerService;

    @Override
    @Transactional
    public void run(String... args) {
        Map<String, ConfigItem> configItems = ensureConfigCatalog();
        Map<String, Warehouse> warehouses = ensureWarehouses();
        Map<String, Customer> customers = ensureCustomers();
        Map<String, Supplier> suppliers = ensureSuppliers();
        Map<String, MachineInventory> machines = ensureMachines(warehouses);
        ensureMachineConfigs(machines, configItems);
        Map<String, PartInventory> parts = ensureParts(warehouses, machines);
        ensurePurchaseOrders(suppliers, parts, configItems);
        ensureOutboundOrders(customers, machines, parts);
        ensureRentalRecords(customers, machines);
        ensureRepairRecords(customers, machines, parts);
        ensureStocktakingRecords(machines, parts);
        ensureStockMovements(warehouses, machines, parts);
        ensureModificationWorkOrders(customers, machines, parts);
    }

    private Map<String, ConfigItem> ensureConfigCatalog() {
        Map<String, ConfigItem> result = new LinkedHashMap<>();
        List<ConfigItemSpec> specs = List.of(
                item("TIRE_TYPE", "Chassis", "Tire", "Tire type", "SET", true, 10,
                        value("SOLID_STANDARD", "Solid standard tire", true, 10),
                        value("PNEUMATIC_ROUGH", "Pneumatic rough terrain tire", false, 20),
                        value("NON_MARKING", "White non-marking tire", false, 30),
                        value("DUAL_FRONT", "Dual front tire", false, 40)),
                item("FORK_LENGTH", "Attachment", "Fork", "Fork length", "mm", true, 20,
                        value("FORK_1070", "1070 mm short fork", false, 10),
                        value("FORK_1220", "1220 mm standard fork", true, 20),
                        value("FORK_1520", "1520 mm extended fork", false, 30),
                        value("FORK_1800", "1800 mm heavy cargo fork", false, 40)),
                item("MAST_STAGE", "Mast", "Lift", "Mast stage and height", "mm", true, 30,
                        value("MAST_2_3000", "2-stage 3000 mm mast", true, 10),
                        value("MAST_2_4000", "2-stage 4000 mm mast", false, 20),
                        value("MAST_3_4500", "3-stage full free 4500 mm mast", false, 30),
                        value("MAST_3_4800", "3-stage full free 4800 mm mast", false, 40)),
                item("BATTERY_PACK", "Power", "Battery", "Battery or starter pack", "SET", true, 40,
                        value("BATTERY_STANDARD", "Standard maintenance-free starter or lithium pack", true, 10),
                        value("LITHIUM_80V300", "80V300Ah lithium battery", false, 20),
                        value("LEAD_ACID_80V500", "80V500Ah lead-acid battery", false, 30),
                        value("FAST_SWAP", "Fast-swap battery bracket", false, 40)),
                item("CHARGER_SPEC", "Power", "Charger", "Charger spec", "SET", false, 50,
                        value("CHARGER_STANDARD", "Standard matching charger", true, 10),
                        value("CHARGER_FAST_100A", "External fast charger 80V100A", false, 20),
                        value("CHARGER_DUAL", "Dual-gun intelligent charger", false, 30)),
                item("ATTACHMENT", "Attachment", "Hydraulic", "Hydraulic attachment", "SET", false, 60,
                        value("NO_ATTACHMENT", "No attachment", true, 10),
                        value("SIDE_SHIFTER", "Hydraulic side shifter", false, 20),
                        value("FORK_POSITIONER", "Fork positioner", false, 30),
                        value("CLAMP_RESERVED", "Clamp oil-way reserved", false, 40)),
                item("SAFETY_KIT", "Body", "Safety", "Safety accessories", "SET", true, 70,
                        value("SAFETY_STANDARD", "Beacon and backup buzzer", true, 10),
                        value("BLUE_LIGHT", "Blue warning light and rear handle horn", false, 20),
                        value("CAMERA", "Rear camera and driving recorder", false, 30)),
                item("CABIN_TYPE", "Body", "Cabin", "Cabin or overhead guard", "SET", true, 80,
                        value("OPEN_GUARD", "Standard overhead guard", true, 10),
                        value("RAIN_COVER", "Rain cover and windshield", false, 20),
                        value("FULL_CABIN", "Full closed cabin", false, 30)),
                item("PAINT_COLOR", "Body", "Paint", "Paint color", "SET", false, 90,
                        value("COLOR_YELLOW", "Industrial yellow", true, 10),
                        value("COLOR_RED", "Fleet red", false, 20),
                        value("COLOR_BLUE", "Cold-chain blue", false, 30),
                        value("COLOR_GREEN", "Eco green", false, 40)),
                item("TELEMATICS", "Electronics", "IoT", "Telematics device", "SET", false, 100,
                        value("NO_TELEMATICS", "No telematics", true, 10),
                        value("GPS_BASIC", "GPS basic tracker", false, 20),
                        value("IOT_ADVANCED", "IoT operation monitor", false, 30)),
                item("WARRANTY_LEVEL", "Service", "Warranty", "Warranty level", "YEAR", true, 110,
                        value("WARRANTY_1Y", "1-year standard warranty", true, 10),
                        value("WARRANTY_2Y", "2-year extended warranty", false, 20),
                        value("WARRANTY_3Y", "3-year premium warranty", false, 30)),
                item("WORKING_SCENE", "Scenario", "Usage", "Working scene", "SET", false, 120,
                        value("GENERAL_WAREHOUSE", "General warehouse", true, 10),
                        value("COLD_STORAGE", "Cold storage", false, 20),
                        value("OUTDOOR_ROUGH", "Outdoor rough yard", false, 30),
                        value("FOOD_CLEAN", "Food clean workshop", false, 40))
        );

        for (ConfigItemSpec spec : specs) {
            ConfigItem item = configItemRepository.findByItemCode(spec.code())
                    .orElseGet(() -> {
                        ConfigItem created = new ConfigItem();
                        created.setItemCode(spec.code());
                        return created;
                    });
            item.setCategory(spec.category());
            item.setSubCategory(spec.subCategory());
            item.setItemName(spec.itemName());
            item.setInputType("SELECT");
            item.setUnit(spec.unit());
            item.setIsRequired(spec.required());
            item.setSortOrder(spec.sortOrder());
            ConfigItem savedItem = configItemRepository.saveAndFlush(item);
            result.put(spec.code(), savedItem);

            List<String> desiredDefaults = spec.values().stream()
                    .filter(ConfigValueSpec::defaultValue)
                    .map(ConfigValueSpec::code)
                    .toList();
            List<ConfigValue> existingValues = DemoDefaultIntegrity.lockConfigValues(
                    configItemRepository, configValueRepository, savedItem,
                    spec.code(), desiredDefaults);
            for (ConfigValueSpec valueSpec : spec.values()) {
                ConfigValue value = existingValues.stream()
                        .filter(row -> valueSpec.code().equals(row.getValueCode()))
                        .findFirst()
                        .orElseGet(ConfigValue::new);
                value.setConfigItemId(savedItem.getId());
                value.setValueCode(valueSpec.code());
                value.setValueLabel(valueSpec.label());
                value.setIsDefault(valueSpec.defaultValue());
                value.setSortOrder(valueSpec.sortOrder());
                value.setRemark(MARKER + " configuration option");
                configValueRepository.saveAndFlush(value);
            }
        }
        return result;
    }

    private Map<String, Warehouse> ensureWarehouses() {
        Map<String, Warehouse> result = new LinkedHashMap<>();
        List<WarehouseSpec> specs = List.of(
                new WarehouseSpec("DEFAULT", "Main warehouse", "MAIN", "HQ inbound and outbound dock", true),
                new WarehouseSpec("WH-SH", "Shanghai finished vehicle warehouse", "BRANCH", "Shanghai pilot free-trade zone", false),
                new WarehouseSpec("WH-SZ", "Shenzhen parts warehouse", "PARTS", "Shenzhen Baoan district", false),
                new WarehouseSpec("WH-CD", "Chengdu rental warehouse", "RENTAL", "Chengdu Longquanyi logistics park", false),
                new WarehouseSpec("WH-QC", "Quality hold warehouse", "HOLD", "Incoming inspection and frozen stock area", false),
                new WarehouseSpec("WH-BJ", "Beijing north service warehouse", "SERVICE", "Beijing Shunyi service center", false),
                new WarehouseSpec("WH-WH", "Wuhan transit warehouse", "TRANSIT", "Wuhan Yangluo transfer hub", false),
                new WarehouseSpec("WH-XA", "Xian project warehouse", "PROJECT", "Xian bonded project yard", false)
        );
        Map<String, Warehouse> existingByCode = DemoDefaultIntegrity.lockWarehouses(
                warehouseRepository, "DEFAULT");
        for (WarehouseSpec spec : specs) {
            Warehouse warehouse = existingByCode.getOrDefault(spec.code(), new Warehouse());
            warehouse.setWarehouseCode(spec.code());
            warehouse.setWarehouseName(spec.name());
            warehouse.setWarehouseType(spec.type());
            warehouse.setAddress(spec.address());
            warehouse.setDefaultWarehouse(spec.defaultWarehouse());
            result.put(spec.code(), warehouseRepository.saveAndFlush(warehouse));
        }
        return result;
    }

    private Map<String, Customer> ensureCustomers() {
        Map<String, Customer> result = new LinkedHashMap<>();
        List<CustomerSpec> specs = List.of(
                customer("TEST-CUST-ALPHA", "Alpha Logistics Co.", "Shanghai Pudong warehouse", "Alice Chen", "13800001001", "TAX-ALPHA-001"),
                customer("TEST-CUST-BETA", "Beta Cold Chain Ltd.", "Suzhou cold storage park", "Bob Li", "13800001002", "TAX-BETA-002"),
                customer("TEST-CUST-GAMMA", "Gamma Paper Mill", "Hangzhou Xiaoshan factory", "Grace Wang", "13800001003", "TAX-GAMMA-003"),
                customer("TEST-CUST-DELTA", "Delta Food Workshop", "Ningbo clean workshop", "Derek Zhao", "13800001004", "TAX-DELTA-004"),
                customer("TEST-CUST-EPSILON", "Epsilon Port Yard", "Qingdao container yard", "Emma Sun", "13800001005", "TAX-EPS-005"),
                customer("TEST-CUST-ZETA", "Zeta Rental Client", "Chengdu temporary project site", "Zack He", "13800001006", "TAX-ZETA-006"),
                customer("TEST-CUST-ETA", "Eta Maintenance Client", "Wuxi equipment center", "Evan Zhou", "13800001007", "TAX-ETA-007"),
                customer("TEST-CUST-THETA", "Theta Key Account", "Beijing north distribution center", "Tina Hu", "13800001008", "TAX-THETA-008"),
                customer("TEST-CUST-IOTA", "Iota E-commerce Fulfillment", "Guangzhou Nansha fulfillment center", "Ivy Gao", "13800001009", "TAX-IOTA-009"),
                customer("TEST-CUST-KAPPA", "Kappa Chemical Plant", "Nanjing Jiangbei hazardous goods area", "Kevin Ma", "13800001010", "TAX-KAPPA-010"),
                customer("TEST-CUST-LAMBDA", "Lambda Pharma Cleanroom", "Taizhou pharma clean workshop", "Lena Wu", "13800001011", "TAX-LAMBDA-011"),
                customer("TEST-CUST-MU", "Mu Seasonal Project", "Xian airport expansion project", "Mason Yu", "13800001012", "TAX-MU-012")
        );
        for (CustomerSpec spec : specs) {
            Customer customer = customerRepository.findByCompanyName(spec.companyName())
                    .orElseGet(Customer::new);
            customer.setCompanyName(spec.companyName());
            customer.setAddress(spec.address());
            customer.setContactName(spec.contactName());
            customer.setContactPhone(spec.contactPhone());
            customer.setTaxOrIdNumber(spec.taxNumber());
            customer.setRemarks(MARKER + " " + spec.code());
            result.put(spec.code(), customerRepository.saveAndFlush(customer));
        }
        return result;
    }

    private Map<String, Supplier> ensureSuppliers() {
        Map<String, Supplier> result = new LinkedHashMap<>();
        List<SupplierSpec> specs = List.of(
                supplier("TEST-SUP-LG", "Longgong Demo Supplier", "Vehicle OEM", "Liam Xu", "13900002001"),
                supplier("TEST-SUP-TL", "Tianli Fork Demo Parts", "Parts", "Tony Fang", "13900002002"),
                supplier("TEST-SUP-BAT", "Fanji Battery Demo", "Battery", "Fiona Lu", "13900002003"),
                supplier("TEST-SUP-ATT", "United Attachment Demo", "Attachment", "Una Lin", "13900002004"),
                supplier("TEST-SUP-SVC", "Onsite Service Partner", "Service", "Oscar Qian", "13900002005"),
                supplier("TEST-SUP-COLD", "Coldroom Retrofit Partner", "Service", "Cora Shen", "13900002006"),
                supplier("TEST-SUP-USED", "Used Equipment Demo Broker", "Used vehicle", "Ulysses Wei", "13900002007"),
                supplier("TEST-SUP-LOG", "Demo Logistics Carrier", "Freight", "Luca Tan", "13900002008")
        );
        for (SupplierSpec spec : specs) {
            Supplier supplier = supplierRepository.findAllByOrderBySupplierNameAsc().stream()
                    .filter(row -> spec.name().equals(row.getSupplierName()))
                    .findFirst()
                    .orElseGet(Supplier::new);
            supplier.setSupplierName(spec.name());
            supplier.setSupplierType(spec.type());
            supplier.setContactName(spec.contactName());
            supplier.setContactPhone(spec.contactPhone());
            supplier.setAddress("Demo supplier address - " + spec.code());
            supplier.setTaxNumber("SUP-TAX-" + spec.code());
            supplier.setBankAccount("6222-0000-" + spec.code());
            supplier.setRemarks(MARKER + " supplier");
            result.put(spec.code(), supplierRepository.saveAndFlush(supplier));
        }
        return result;
    }

    private Map<String, MachineInventory> ensureMachines(Map<String, Warehouse> warehouses) {
        Map<String, MachineInventory> result = new LinkedHashMap<>();
        List<MachineSpec> specs = List.of(
                machine("TEST-MODEL-FD30", "3T diesel forklift model", "CPCD30", "Diesel Forklift", "standard template", "DEFAULT", 0, true, MachineStockStatus.PENDING_INBOUND.code(), false, "OEM-A", "68000", "86000", "82000"),
                machine("TEST-FD30-001", "3T diesel forklift", "CPCD30", "Diesel Forklift", "standard stock", "DEFAULT", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-A", "68000", "86000", "82000"),
                machine("TEST-FD30-002", "3T diesel forklift", "CPCD30", "Diesel Forklift", "locked high-value unit", "WH-SH", 1, false, MachineStockStatus.IN_STOCK.code(), true, "OEM-A", "69000", "88000", "83500"),
                machine("TEST-FD35-001", "3.5T diesel forklift", "CPCD35", "Diesel Forklift", "pending modification", "WH-SH", 1, false, MachineStockStatus.PENDING_MODIFICATION.code(), false, "OEM-A", "78000", "98000", "93000"),
                machine("TEST-FD35-002", "3.5T diesel forklift", "CPCD35", "Diesel Forklift", "modifying with work order", "WH-QC", 1, false, MachineStockStatus.MODIFYING.code(), false, "OEM-A", "78500", "99500", "94000"),
                machine("TEST-CPD20-001", "2T electric forklift", "CPD20", "Electric Forklift", "lithium standard", "WH-SH", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-E", "62000", "83000", "79000"),
                machine("TEST-CPD25-001", "2.5T electric forklift", "CPD25", "Electric Forklift", "pending outbound", "DEFAULT", 1, false, MachineStockStatus.PENDING_OUTBOUND.code(), false, "OEM-E", "72000", "96000", "91000"),
                machine("TEST-CPD30-001", "3T electric forklift", "CPD30", "Electric Forklift", "rental active unit", "WH-CD", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-E", "82000", "109000", "102000"),
                machine("TEST-CPD30-002", "3T electric forklift", "CPD30", "Electric Forklift", "repair pending unit", "WH-CD", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-E", "83000", "111000", "104000"),
                machine("TEST-PALLET-001", "Electric pallet truck", "CBD20", "Pallet Truck", "compact warehouse unit", "WH-SZ", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-P", "12000", "18000", "16000"),
                machine("TEST-PALLET-001-B", "Electric pallet truck", "CBD20", "Pallet Truck", "compact warehouse spare unit", "WH-SZ", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-P", "12000", "18000", "16000"),
                machine("TEST-STACKER-001", "Electric stacker", "CDD15", "Stacker", "high-rack sample", "WH-SZ", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-P", "18000", "26000", "23000"),
                machine("TEST-MAN-001", "Manual pallet truck", "CBY2.0", "Manual Pallet Truck", "manual low-price sample", "WH-SZ", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-M", "1100", "1800", "1500"),
                machine("TEST-MAN-001-B", "Manual pallet truck", "CBY2.0", "Manual Pallet Truck", "manual low-price spare unit", "WH-SZ", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-M", "1100", "1800", "1500"),
                machine("TEST-MAN-001-C", "Manual pallet truck", "CBY2.0", "Manual Pallet Truck", "manual low-price spare unit", "WH-SZ", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-M", "1100", "1800", "1500"),
                machine("TEST-MAN-001-D", "Manual pallet truck", "CBY2.0", "Manual Pallet Truck", "manual low-price spare unit", "WH-SZ", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-M", "1100", "1800", "1500"),
                machine("TEST-FD25-OUT", "2.5T diesel forklift", "CPCD25", "Diesel Forklift", "sold outbound history", "DEFAULT", 0, false, MachineStockStatus.OUTBOUND.code(), false, "OEM-A", "61000", "79000", "76000"),
                machine("TEST-FD25-PEND", "2.5T diesel forklift", "CPCD25", "Diesel Forklift", "pending inbound without stock", "WH-QC", 0, false, MachineStockStatus.PENDING_INBOUND.code(), false, "OEM-A", "60000", "78000", "74000"),
                machine("TEST-FD20-LOW", "2T diesel forklift", "CPCD20", "Diesel Forklift", "low-price small site stock", "WH-XA", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-A", "52000", "69000", "65000"),
                machine("TEST-FD50-HEAVY", "5T diesel forklift", "CPCD50", "Diesel Forklift", "heavy yard high margin unit", "WH-WH", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-A", "118000", "156000", "148000"),
                machine("TEST-FD70-PORT", "7T diesel forklift", "CPCD70", "Diesel Forklift", "port yard oversized unit", "WH-WH", 1, false, MachineStockStatus.PENDING_OUTBOUND.code(), true, "OEM-A", "168000", "225000", "210000"),
                machine("TEST-CPD15-COLD", "1.5T electric forklift", "CPD15", "Electric Forklift", "cold storage non-marking setup", "WH-SH", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-E", "54000", "72000", "68000"),
                machine("TEST-CPD15-COLD-002", "1.5T electric forklift", "CPD15", "Electric Forklift", "cold storage non-marking spare unit", "WH-SH", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-E", "54000", "72000", "68000"),
                machine("TEST-CPD35-LITH", "3.5T electric forklift", "CPD35", "Electric Forklift", "large lithium battery stock", "WH-BJ", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-E", "98000", "132000", "124000"),
                machine("TEST-CPD35-MOD", "3.5T electric forklift", "CPD35", "Electric Forklift", "fork positioner retrofit planned", "WH-QC", 1, false, MachineStockStatus.PENDING_MODIFICATION.code(), false, "OEM-E", "98500", "135000", "126000"),
                machine("TEST-CPD45-RENT", "4.5T electric forklift", "CPD45", "Electric Forklift", "long-term rental reserve", "WH-CD", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-E", "132000", "178000", "166000"),
                machine("TEST-STACKER-002", "Electric stacker", "CDD20", "Stacker", "returned rental inspection", "WH-CD", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-P", "22000", "32000", "28600"),
                machine("TEST-STACKER-003", "Electric reach stacker", "CQD16", "Reach Stacker", "narrow aisle warehouse sample", "WH-BJ", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-P", "46000", "64000", "58500"),
                machine("TEST-PALLET-002", "Electric pallet truck", "CBD15", "Pallet Truck", "zero-stock outbound history", "WH-SZ", 0, false, MachineStockStatus.OUTBOUND.code(), false, "OEM-P", "9800", "15000", "13600"),
                machine("TEST-PALLET-003", "Electric pallet truck", "CBD25", "Pallet Truck", "bulk stock unit 01", "DEFAULT", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-P", "14800", "21800", "19800"),
                machine("TEST-PALLET-004", "Electric pallet truck", "CBD25", "Pallet Truck", "bulk stock unit 02", "DEFAULT", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-P", "14800", "21800", "19800"),
                machine("TEST-PALLET-005", "Electric pallet truck", "CBD25", "Pallet Truck", "bulk stock unit 03", "DEFAULT", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-P", "14800", "21800", "19800"),
                machine("TEST-PALLET-006", "Electric pallet truck", "CBD25", "Pallet Truck", "bulk stock unit 04", "DEFAULT", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-P", "14800", "21800", "19800"),
                machine("TEST-PALLET-007", "Electric pallet truck", "CBD25", "Pallet Truck", "bulk stock unit 05", "DEFAULT", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-P", "14800", "21800", "19800"),
                machine("TEST-PALLET-008", "Electric pallet truck", "CBD25", "Pallet Truck", "bulk stock unit 06", "DEFAULT", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-P", "14800", "21800", "19800"),
                machine("TEST-PALLET-009", "Electric pallet truck", "CBD25", "Pallet Truck", "bulk stock unit 07", "DEFAULT", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-P", "14800", "21800", "19800"),
                machine("TEST-PALLET-010", "Electric pallet truck", "CBD25", "Pallet Truck", "bulk stock unit 08", "DEFAULT", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-P", "14800", "21800", "19800"),
                machine("TEST-MAN-002", "Manual pallet truck", "CBY3.0", "Manual Pallet Truck", "manual project unit 01", "WH-XA", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-M", "1300", "2100", "1750"),
                machine("TEST-MAN-003", "Manual pallet truck", "CBY3.0", "Manual Pallet Truck", "manual project unit 02", "WH-XA", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-M", "1300", "2100", "1750"),
                machine("TEST-MAN-004", "Manual pallet truck", "CBY3.0", "Manual Pallet Truck", "manual project unit 03", "WH-XA", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-M", "1300", "2100", "1750"),
                machine("TEST-MAN-005", "Manual pallet truck", "CBY3.0", "Manual Pallet Truck", "manual project unit 04", "WH-XA", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-M", "1300", "2100", "1750"),
                machine("TEST-MAN-006", "Manual pallet truck", "CBY3.0", "Manual Pallet Truck", "manual project unit 05", "WH-XA", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-M", "1300", "2100", "1750"),
                machine("TEST-MAN-007", "Manual pallet truck", "CBY3.0", "Manual Pallet Truck", "manual project unit 06", "WH-XA", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-M", "1300", "2100", "1750"),
                machine("TEST-MAN-008", "Manual pallet truck", "CBY3.0", "Manual Pallet Truck", "manual project unit 07", "WH-XA", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-M", "1300", "2100", "1750"),
                machine("TEST-MAN-009", "Manual pallet truck", "CBY3.0", "Manual Pallet Truck", "manual project unit 08", "WH-XA", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-M", "1300", "2100", "1750"),
                machine("TEST-MAN-010", "Manual pallet truck", "CBY3.0", "Manual Pallet Truck", "manual project unit 09", "WH-XA", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-M", "1300", "2100", "1750"),
                machine("TEST-MAN-011", "Manual pallet truck", "CBY3.0", "Manual Pallet Truck", "manual project unit 10", "WH-XA", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-M", "1300", "2100", "1750"),
                machine("TEST-MAN-012", "Manual pallet truck", "CBY3.0", "Manual Pallet Truck", "manual project unit 11", "WH-XA", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-M", "1300", "2100", "1750"),
                machine("TEST-MAN-013", "Manual pallet truck", "CBY3.0", "Manual Pallet Truck", "manual project unit 12", "WH-XA", 1, false, MachineStockStatus.IN_STOCK.code(), false, "OEM-M", "1300", "2100", "1750"),
                machine("TEST-USED-FD30", "Used 3T diesel forklift", "CPCD30-USED", "Used Forklift", "trade-in refurbishment stock", "WH-QC", 1, false, MachineStockStatus.IN_STOCK.code(), false, "Used Broker", "32000", "52000", "45000"),
                machine("TEST-USED-CPD25", "Used 2.5T electric forklift", "CPD25-USED", "Used Forklift", "battery health pending inbound", "WH-QC", 0, false, MachineStockStatus.PENDING_INBOUND.code(), false, "Used Broker", "28000", "46000", "39000"),
                machine("TEST-DEMO-LOCKED", "Demo showroom forklift", "CPCD30-SHOW", "Diesel Forklift", "locked showroom loaner", "DEFAULT", 1, false, MachineStockStatus.IN_STOCK.code(), true, "OEM-A", "70000", "92000", "86000"),
                machine("TEST-MODEL-CPD25", "2.5T electric forklift model", "CPD25", "Electric Forklift", "electric template without stock", "DEFAULT", 0, true, MachineStockStatus.PENDING_INBOUND.code(), false, "OEM-E", "72000", "96000", "91000")
        );
        for (MachineSpec spec : specs) {
            Warehouse warehouse = warehouses.get(spec.warehouseCode());
            MachineInventory machine = machineRepository.findByVehicleProductNumber(spec.vehicleNumber())
                    .orElseGet(MachineInventory::new);
            machine.setVehicleProductNumber(spec.vehicleNumber());
            machine.setName(spec.name());
            machine.setSpecificationModel(spec.model());
            machine.setMachineType(spec.type());
            machine.setConfiguration(spec.configuration());
            machine.setSupplier(spec.supplier());
            machine.setWarehouseId(warehouse.getId());
            machine.setWarehouseName(warehouse.getWarehouseName());
            machine.setApplicationNumber("APP-" + spec.vehicleNumber());
            machine.setMaterialNumber("MAT-" + spec.model());
            machine.setEngineNumber(spec.modelOnly() ? null : "ENG-" + spec.vehicleNumber());
            machine.setFrameNumber(spec.modelOnly() ? null : "FRM-" + spec.vehicleNumber());
            machine.setWarrantyCardNumber(spec.modelOnly() ? null : "WAR-" + spec.vehicleNumber());
            machine.setManufacturingDate(LocalDate.now().minusMonths(18).plusDays(Math.abs(spec.vehicleNumber().hashCode()) % 240));
            machine.setAnnualInspectionDate(LocalDate.now().plusMonths(8));
            machine.setInboundDate(spec.quantity() > 0 ? LocalDateTime.now().minusDays(90) : null);
            machine.setPurchasePrice(money(spec.purchasePrice()));
            machine.setSalePrice(money(spec.salePrice()));
            machine.setSettlementPrice(money(spec.settlementPrice()));
            machine.setInventoryCount(spec.quantity());
            machine.setModelOnly(spec.modelOnly());
            machine.setStockStatus(spec.status());
            machine.setIsLocked(spec.locked());
            machine.setRemarks(MARKER + " " + spec.configuration());
            MachineInventory saved = machineRepository.saveAndFlush(machine);
            if (!Boolean.TRUE.equals(saved.getModelOnly())) {
                stockLedgerService.syncBalance(StockLedgerService.RESOURCE_MACHINE, saved.getId(), saved.getWarehouseId(), saved.getInventoryCount());
            }
            result.put(spec.vehicleNumber(), saved);
        }
        return result;
    }

    private void ensureMachineConfigs(Map<String, MachineInventory> machines, Map<String, ConfigItem> items) {
        int index = 0;
        for (MachineInventory machine : machines.values()) {
            if (Boolean.TRUE.equals(machine.getModelOnly())) {
                continue;
            }
            if (!machineConfigRepository.findByMachineId(machine.getId()).isEmpty()) {
                continue;
            }
            for (ConfigItem item : items.values()) {
                List<ConfigValue> values = configValueRepository.findByConfigItemIdOrderBySortOrderAsc(item.getId());
                if (values.isEmpty()) {
                    continue;
                }
                boolean optional = (index + item.getSortOrder()) % 4 == 0;
                ConfigValue selected = values.stream()
                        .filter(value -> optional ? !Boolean.TRUE.equals(value.getIsDefault()) : Boolean.TRUE.equals(value.getIsDefault()))
                        .findFirst()
                        .orElse(values.get(0));
                MachineConfig config = new MachineConfig();
                config.setMachineId(machine.getId());
                config.setConfigItemId(item.getId());
                config.setConfigValueId(selected.getId());
                config.setItemName(item.getItemName());
                config.setSelectedValue(selected.getValueLabel());
                config.setIsStandard(!optional);
                config.setConfigSource(optional ? "FACTORY_OPTIONAL" : "FACTORY_STANDARD");
                config.setInstalledDate(LocalDateTime.now().minusDays(60));
                config.setRemark(MARKER + " machine configuration");
                machineConfigRepository.saveAndFlush(config);
            }
            index++;
        }
    }

    private Map<String, PartInventory> ensureParts(Map<String, Warehouse> warehouses, Map<String, MachineInventory> machines) {
        Map<String, PartInventory> result = new LinkedHashMap<>();
        List<PartSpec> specs = List.of(
                part("TEST-PART-TIRE-65010", "Longgong", "Solid tire 6.50-10", "6.50-10", "Tire", "FD20/FD25/FD30", "PURCHASE", 48, "pcs", "WH-SZ", "480", "780", "620"),
                part("TEST-PART-TIRE-70012", "Longgong", "Solid tire 7.00-12", "7.00-12", "Tire", "FD30/FD35", "PURCHASE", 36, "pcs", "WH-SZ", "620", "980", "780"),
                part("TEST-PART-TIRE-NONMARK", "Cheng Shin", "White non-marking tire", "6.50-10", "Tire", "Electric forklift", "PURCHASE", 20, "pcs", "WH-SZ", "760", "1180", "960"),
                part("TEST-PART-FORK-1220", "Tianli", "Standard fork", "3T 1220mm", "Fork", "2.5T/3T/3.5T", "PURCHASE", 18, "pair", "WH-SZ", "900", "1450", "1180"),
                part("TEST-PART-FORK-1520", "Tianli", "Extended fork", "3T 1520mm", "Fork", "2.5T/3T/3.5T", "PURCHASE", 10, "pair", "WH-SZ", "1250", "1900", "1580"),
                part("TEST-PART-FORK-1800", "Tianli", "Heavy cargo fork", "3T 1800mm", "Fork", "3T/3.5T", "PURCHASE", 6, "pair", "WH-QC", "1680", "2600", "2150"),
                part("TEST-PART-BAT-LI-80V205", "Fanji", "Lithium battery pack", "80V205Ah", "Battery", "CPD20/CPD25", "PURCHASE", 8, "set", "WH-SZ", "8500", "12800", "10600"),
                part("TEST-PART-BAT-LI-80V300", "Fanji", "Large lithium battery pack", "80V300Ah", "Battery", "CPD25/CPD30", "PURCHASE", 5, "set", "WH-QC", "11800", "17800", "14600"),
                part("TEST-PART-BAT-START", "Yard", "Starter battery", "12V80Ah", "Battery", "Diesel forklift", "PURCHASE", 24, "pcs", "WH-SZ", "360", "580", "460"),
                part("TEST-PART-CHG-80V100A", "Ande", "Fast charger", "80V100A", "Charger", "CPD20/CPD25/CPD30", "PURCHASE", 6, "set", "WH-SZ", "2900", "4500", "3650"),
                part("TEST-PART-CHG-48V60A", "Ande", "Standard charger", "48V60A", "Charger", "Pallet truck/Stacker", "PURCHASE", 12, "set", "WH-SZ", "980", "1580", "1260"),
                part("TEST-PART-ATT-SIDESHIFT", "United", "Hydraulic side shifter", "3T ISO II", "Attachment", "FD25/FD30/CPD30", "PURCHASE", 7, "set", "WH-QC", "2400", "3800", "3150"),
                part("TEST-PART-ATT-FORKPOS", "United", "Fork positioner", "3T", "Attachment", "FD30/FD35", "PURCHASE", 4, "set", "WH-QC", "5200", "7800", "6500"),
                part("TEST-PART-HYD-SEAL", "Longgong", "Hydraulic seal kit", "Tilt/steering common", "Hydraulic", "Universal", "PURCHASE", 40, "kit", "WH-SZ", "85", "180", "135"),
                part("TEST-PART-HYD-HOSE", "Tianli", "High-pressure oil hose", "12mm connector", "Hydraulic", "Universal", "PURCHASE", 60, "pcs", "WH-SZ", "65", "150", "110"),
                part("TEST-PART-FILTER-DIESEL", "Longgong", "Diesel filter", "National III/IV", "Maintenance", "Diesel forklift", "PURCHASE", 80, "pcs", "WH-SZ", "35", "75", "55"),
                part("TEST-PART-FILTER-HYD", "Longgong", "Hydraulic oil filter", "Return filter", "Maintenance", "Universal", "PURCHASE", 75, "pcs", "WH-SZ", "42", "95", "70"),
                part("TEST-PART-BRAKE-SHOE", "Longgong", "Brake shoe", "3T rear wheel", "Brake", "FD25/FD30/FD35", "PURCHASE", 30, "set", "WH-SZ", "180", "320", "250"),
                part("TEST-PART-LIGHT-BLUE", "Tianli", "Blue warning light", "12V/24V", "Safety", "Universal", "PURCHASE", 28, "pcs", "WH-SZ", "95", "220", "160"),
                part("TEST-PART-CAMERA", "Tianli", "Rear camera kit", "7-inch display", "Safety", "Universal", "PURCHASE", 10, "set", "WH-SZ", "450", "880", "690"),
                part("TEST-PART-SEAT-BELT", "Tianli", "Seat belt assembly", "Universal", "Safety", "Universal", "PURCHASE", 35, "set", "WH-SZ", "55", "120", "88"),
                part("TEST-PART-CONTROLLER", "Fanji", "Electric controller", "80V AC controller", "Controller", "CPD20/CPD25/CPD30", "PURCHASE", 3, "set", "WH-QC", "3800", "6200", "5100"),
                part("TEST-PART-MAST-CHAIN", "Longgong", "Mast chain", "3T standard chain", "Mast", "FD25/FD30/CPD30", "PURCHASE", 16, "pcs", "WH-SZ", "260", "520", "390"),
                part("TEST-PART-VALVE-4WAY", "Longgong", "Four-way control valve", "3T standard", "Hydraulic", "FD30/FD35", "PURCHASE", 5, "pcs", "WH-QC", "1450", "2600", "2100"),
                part("TEST-PART-REMOVED-TIRE", "Removed", "Removed pneumatic tire", "28x9-15", "Tire", "CPCD30", "REMOVED", 2, "pcs", "WH-QC", "0", "300", "200"),
                part("TEST-PART-RETURN-CHARGER", "Return", "Returned charger to inspect", "80V100A", "Charger", "CPD30", "RETURN", 1, "set", "WH-QC", "0", "2600", "1900"),
                part("TEST-PART-ZERO-CONTROLLER", "Fanji", "Zero-stock controller sample", "80V debug", "Controller", "CPD25", "PURCHASE", 0, "set", "WH-QC", "3500", "5800", "4700"),
                part("TEST-PART-LOCKED-VALVE", "Longgong", "Locked precision valve", "custom", "Hydraulic", "FD35", "PURCHASE", 2, "pcs", "WH-QC", "1900", "3300", "2700"),
                part("TEST-PART-COLD-SEAL", "ColdPro", "Low-temperature seal kit", "-25C hydraulic kit", "Cold Storage", "CPD15/CPD20", "PURCHASE", 14, "kit", "WH-SH", "260", "520", "420"),
                part("TEST-PART-ANTI-STATIC", "Tianli", "Anti-static grounding chain", "Universal ESD", "Safety", "Universal", "PURCHASE", 22, "pcs", "WH-BJ", "38", "98", "76"),
                part("TEST-PART-FIRE-EXT", "Tianli", "Forklift fire extinguisher kit", "1kg dry powder with bracket", "Safety", "Universal", "PURCHASE", 18, "set", "WH-BJ", "120", "260", "210"),
                part("TEST-PART-MIRROR", "Tianli", "Wide-angle rear mirror", "Universal clamp", "Body", "Universal", "PURCHASE", 32, "pcs", "WH-SZ", "45", "110", "82"),
                part("TEST-PART-CABIN-RAIN", "United", "Rain cover cabin kit", "CPCD30 rain cover", "Cabin", "FD25/FD30", "PURCHASE", 4, "set", "WH-WH", "1800", "3200", "2650"),
                part("TEST-PART-CABIN-FULL", "United", "Closed cabin assembly", "CPCD35 full cabin", "Cabin", "FD30/FD35", "PURCHASE", 2, "set", "WH-WH", "5800", "9200", "7600"),
                part("TEST-PART-PAINT-BLUE", "Tianli", "Cold-chain blue paint kit", "5L industrial paint", "Paint", "Universal", "PURCHASE", 9, "set", "WH-QC", "260", "680", "520"),
                part("TEST-PART-GPS-BASIC", "Navistar", "GPS basic tracker", "4G positioning", "Telematics", "Universal", "PURCHASE", 15, "set", "WH-BJ", "180", "480", "360"),
                part("TEST-PART-IOT-ADV", "Navistar", "IoT operation monitor", "CAN bus data kit", "Telematics", "CPD20/CPD30/CPCD30", "PURCHASE", 6, "set", "WH-BJ", "980", "1980", "1560"),
                part("TEST-PART-TRADEIN-FORK", "Removed", "Trade-in worn fork pair", "3T 1220mm used", "Fork", "FD30", "REMOVED", 1, "pair", "WH-QC", "0", "500", "350"),
                part("TEST-PART-DAMAGED-HOSE", "Return", "Damaged hose return sample", "12mm damaged", "Hydraulic", "Universal", "RETURN", 0, "pcs", "WH-QC", "0", "60", "30")
        );
        for (PartSpec spec : specs) {
            Warehouse warehouse = warehouses.get(spec.warehouseCode());
            PartInventory part = partRepository.findByPartCode(spec.code()).orElseGet(PartInventory::new);
            part.setPartCode(spec.code());
            part.setPartBrand(spec.brand());
            part.setPartName(spec.name());
            part.setSpecification(spec.specification());
            part.setPartCategory(spec.category());
            part.setApplicableModels(spec.applicableModels());
            part.setSource(spec.source());
            if ("REMOVED".equals(spec.source())) {
                MachineInventory sourceMachine = machines.get("TEST-FD30-001");
                part.setSourceMachineId(sourceMachine == null ? null : sourceMachine.getId());
            }
            part.setQuantity(spec.quantity());
            part.setUnit(spec.unit());
            part.setWarehouseId(warehouse.getId());
            part.setManufacturingDate(LocalDate.now().minusMonths(10));
            part.setInboundDate(LocalDateTime.now().minusDays(45));
            part.setPurchasePrice(money(spec.purchasePrice()));
            part.setSalePrice(money(spec.salePrice()));
            part.setSettlementPrice(money(spec.settlementPrice()));
            part.setIsLocked(spec.code().contains("LOCKED"));
            part.setRemarks(MARKER + " part stock");
            PartInventory saved = partRepository.saveAndFlush(part);
            stockLedgerService.syncBalance(StockLedgerService.RESOURCE_PART, saved.getId(), saved.getWarehouseId(), saved.getQuantity());
            result.put(spec.code(), saved);
        }
        return result;
    }

    private void ensurePurchaseOrders(Map<String, Supplier> suppliers, Map<String, PartInventory> parts, Map<String, ConfigItem> configItems) {
        ensurePurchaseOrder("TEST-PO-001", suppliers.get("TEST-SUP-TL"), parts.get("TEST-PART-TIRE-65010"), null, null, 20, "ORDERED", "25", "Urgent tire restock");
        ensurePurchaseOrder("TEST-PO-002", suppliers.get("TEST-SUP-BAT"), parts.get("TEST-PART-BAT-LI-80V300"), null, null, 3, "RECEIVED", "180", "Battery purchase received");
        ensurePurchaseOrder("TEST-PO-003", suppliers.get("TEST-SUP-ATT"), parts.get("TEST-PART-ATT-SIDESHIFT"), null, null, 2, "CANCELLED", "0", "Supplier delivery delayed");
        ConfigItem tireItem = configItems.get("TIRE_TYPE");
        ConfigValue nonMarking = findConfigValue(tireItem, "NON_MARKING").orElse(null);
        ensurePurchaseOrder("TEST-PO-004", suppliers.get("TEST-SUP-LG"), null, tireItem, nonMarking, 12, "ORDERED", "60", "Config-linked purchase sample");
        ensurePurchaseOrder("TEST-PO-005", suppliers.get("TEST-SUP-SVC"), parts.get("TEST-PART-HYD-SEAL"), null, null, 50, "PARTIAL", "35", "Partial delivery status coverage");
        ensurePurchaseOrder("TEST-PO-006", suppliers.get("TEST-SUP-COLD"), parts.get("TEST-PART-COLD-SEAL"), null, null, 10, "CANCELED", "0", "Canceled order using frontend status spelling");
        ensurePurchaseOrder("TEST-PO-007", suppliers.get("TEST-SUP-LOG"), parts.get("TEST-PART-CABIN-FULL"), null, null, 2, "ORDERED", "980", "High freight amount coverage");
        ensurePurchaseOrder("TEST-PO-008", suppliers.get("TEST-SUP-USED"), parts.get("TEST-PART-TRADEIN-FORK"), null, null, 1, "RECEIVED", "0", "Used trade-in part received");
        ConfigItem telematicsItem = configItems.get("TELEMATICS");
        ConfigValue iotAdvanced = findConfigValue(telematicsItem, "IOT_ADVANCED").orElse(null);
        ensurePurchaseOrder("TEST-PO-009", suppliers.get("TEST-SUP-BAT"), null, telematicsItem, iotAdvanced, 6, "PARTIAL", "120", "Optional telematics config-linked purchase");
    }

    private void ensurePurchaseOrder(String purchaseNo, Supplier supplier, PartInventory part, ConfigItem item,
                                     ConfigValue value, int quantity, String status, String freight, String remark) {
        if (purchaseOrderRepository.existsByPurchaseNo(purchaseNo)) {
            return;
        }
        PurchaseOrder order = new PurchaseOrder();
        order.setPurchaseNo(purchaseNo);
        order.setSupplierId(supplier.getId());
        order.setSupplierName(supplier.getSupplierName());
        order.setConfigItemId(item == null ? null : item.getId());
        order.setConfigValueId(value == null ? null : value.getId());
        order.setResourceType(PurchaseOrder.RESOURCE_PART);
        order.setResourceCode(value != null ? value.getValueCode() : part.getPartCode());
        order.setResourceName(value != null ? value.getValueLabel() : part.getPartName());
        order.setSpecificationModel(item != null ? item.getItemName() : part.getSpecification());
        order.setQuantity(quantity);
        order.setUnit(value != null ? item.getUnit() : part.getUnit());
        order.setUnitPrice(value != null ? money("880") : part.getPurchasePrice());
        order.setTotalAmount(order.getUnitPrice().multiply(BigDecimal.valueOf(quantity)));
        order.setFreightAmount(money(freight));
        order.setOrderDate(LocalDate.now().minusDays(20));
        order.setExpectedArrivalDate(LocalDate.now().plusDays(10));
        order.setStatus(status);
        order.setOperator(OPERATOR);
        order.setRemark(MARKER + " " + remark);
        purchaseOrderRepository.saveAndFlush(order);
    }

    private void ensureOutboundOrders(Map<String, Customer> customers, Map<String, MachineInventory> machines, Map<String, PartInventory> parts) {
        ensureOutboundOrder("TEST-OO-M-001", customers.get("TEST-CUST-ALPHA"), machines.get("TEST-FD25-OUT"), null,
                1, "unit", "76000", "76000", "76000", "76000", true, true, false,
                "ISSUED", "REGISTERED", "STANDARD", false);
        ensureOutboundOrder("TEST-OO-M-002", customers.get("TEST-CUST-THETA"), machines.get("TEST-CPD25-001"), null,
                1, "unit", "91000", "96000", "91000", "35000", false, true, true,
                "APPLIED", "PENDING", "FRAME", true);
        ensureOutboundOrder("TEST-OO-P-001", customers.get("TEST-CUST-BETA"), null, parts.get("TEST-PART-FILTER-DIESEL"),
                8, "pcs", "55", "75", "440", "440", true, false, false,
                null, null, null, false);
        ensureOutboundOrder("TEST-OO-P-002", customers.get("TEST-CUST-DELTA"), null, parts.get("TEST-PART-LIGHT-BLUE"),
                3, "pcs", "160", "220", "480", "0", false, false, false,
                null, null, null, false);
        ensureOutboundOrder("TEST-OO-M-003", customers.get("TEST-CUST-EPSILON"), machines.get("TEST-FD70-PORT"), null,
                1, "unit", "210000", "225000", "210000", "0", false, false, false,
                "PENDING_APPLICATION", "NOT_REGISTERED", "NO_CONTRACT", true, -45, -60);
        ensureOutboundOrder("TEST-OO-M-004", customers.get("TEST-CUST-IOTA"), machines.get("TEST-DEMO-LOCKED"), null,
                1, "unit", "86000", "92000", "86000", "43000", false, true, true,
                "APPLIED_WAITING_ISSUE", "REGISTRATION_INCLUDED", "E_CONTRACT", true, 7, -8);
        ensureOutboundOrder("TEST-OO-P-003", customers.get("TEST-CUST-KAPPA"), null, parts.get("TEST-PART-FIRE-EXT"),
                5, "set", "210", "260", "1050", "200", false, false, false,
                null, null, null, false, -10, -25);
        ensureOutboundOrder("TEST-OO-P-004", customers.get("TEST-CUST-LAMBDA"), null, parts.get("TEST-PART-COLD-SEAL"),
                2, "kit", "420", "520", "840", "840", true, true, true,
                "TAX_INVOICE_ISSUED", null, "PAPER_CONTRACT", false, 30, -20);
        ensureOutboundOrder("TEST-OO-P-005", customers.get("TEST-CUST-MU"), null, parts.get("TEST-PART-IOT-ADV"),
                1, "set", "1560", "1980", "1560", "0", false, true, false,
                "NO_TAX_INVOICE", null, "NO_CONTRACT", false, 3, -3);
    }

    private void ensureOutboundOrder(String orderNo, Customer customer, MachineInventory machine, PartInventory part,
                                     int quantity, String unit, String settlement, String sale, String receivable,
                                     String received, boolean settled, boolean salesReported, boolean invoiceApplied,
                                     String invoiceStatus, String registrationStatus, String contractType,
                                     boolean locked) {
        ensureOutboundOrder(orderNo, customer, machine, part, quantity, unit, settlement, sale, receivable, received,
                settled, salesReported, invoiceApplied, invoiceStatus, registrationStatus, contractType, locked,
                15, -12);
    }

    private void ensureOutboundOrder(String orderNo, Customer customer, MachineInventory machine, PartInventory part,
                                     int quantity, String unit, String settlement, String sale, String receivable,
                                     String received, boolean settled, boolean salesReported, boolean invoiceApplied,
                                     String invoiceStatus, String registrationStatus, String contractType,
                                     boolean locked, int paymentDueOffsetDays, int salesDateOffsetDays) {
        if (outboundOrderRepository.existsByOrderNo(orderNo)) {
            return;
        }
        OutboundOrder order = new OutboundOrder();
        order.setOrderNo(orderNo);
        if (machine != null) {
            order.setResourceType(OutboundOrder.RESOURCE_MACHINE);
            order.setResourceId(machine.getId());
            order.setResourceCode(machine.getVehicleProductNumber());
            order.setResourceName(machine.getName());
            order.setSpecificationModel(machine.getSpecificationModel());
        } else {
            order.setResourceType(OutboundOrder.RESOURCE_PART);
            order.setResourceId(part.getId());
            order.setResourceCode(part.getPartCode());
            order.setResourceName(part.getPartName());
            order.setSpecificationModel(part.getSpecification());
        }
        order.setQuantity(quantity);
        order.setUnit(unit);
        copyCustomer(order, customer);
        order.setSettlementPrice(money(settlement));
        order.setSalePrice(money(sale));
        order.setReceivableAmount(money(receivable));
        order.setReceivedAmount(money(received));
        order.setPaymentSettled(settled);
        order.setPaymentDueDate(LocalDate.now().plusDays(paymentDueOffsetDays));
        order.setLastPaymentDate(settled ? LocalDate.now().minusDays(2) : null);
        order.setPaymentRemark(MARKER + " receivable sample");
        order.setSalesDate(LocalDate.now().plusDays(salesDateOffsetDays));
        order.setSalesReported(salesReported);
        order.setInvoiceApplied(invoiceApplied);
        order.setSalesReportDate(salesReported ? LocalDate.now().minusDays(10) : null);
        order.setInvoiceApplicationDate(invoiceApplied ? LocalDate.now().minusDays(6) : null);
        order.setInvoiceStatus(invoiceStatus);
        order.setInvoiceIssuedDate("ISSUED".equals(invoiceStatus) ? LocalDate.now().minusDays(3) : null);
        order.setRegistrationStatus(registrationStatus);
        order.setContractType(contractType);
        order.setOrderRemark(MARKER + " outbound coverage");
        order.setOperator(OPERATOR);
        order.setIsLocked(locked);
        order.setResourceLockedByOrder(locked);
        outboundOrderRepository.saveAndFlush(order);
    }

    private void ensureRentalRecords(Map<String, Customer> customers, Map<String, MachineInventory> machines) {
        ensureRentalRecord("TEST-RT-ACTIVE", customers.get("TEST-CUST-ZETA"), machines.get("TEST-CPD30-001"),
                RentalStatus.ACTIVE.code(), LocalDate.now().minusDays(18), LocalDate.now().plusMonths(2), "5600", "Active rental with future end date");
        ensureRentalRecord("TEST-RT-RETURNED", customers.get("TEST-CUST-BETA"), machines.get("TEST-PALLET-001"),
                RentalStatus.RETURNED.code(), LocalDate.now().minusMonths(4), LocalDate.now().minusDays(15), "1200", "Returned rental history");
        ensureRentalRecord("TEST-RT-ACTIVE-OVERDUE", customers.get("TEST-CUST-MU"), machines.get("TEST-STACKER-002"),
                RentalStatus.ACTIVE.code(), LocalDate.now().minusMonths(3), LocalDate.now().minusDays(5), "2600", "Active rental with overdue planned return");
        ensureRentalRecord("TEST-RT-ACTIVE-OPEN", customers.get("TEST-CUST-IOTA"), machines.get("TEST-CPD45-RENT"),
                RentalStatus.ACTIVE.code(), LocalDate.now().minusDays(7), null, "9800", "Open-ended rental without end date");
        ensureRentalRecord("TEST-RT-RETURNED-LONG", customers.get("TEST-CUST-KAPPA"), machines.get("TEST-MAN-002"),
                RentalStatus.RETURNED.code(), LocalDate.now().minusMonths(8), LocalDate.now().minusMonths(1), "360", "Long historical low-value rental");
    }

    private void ensureRentalRecord(String rentalNo, Customer customer, MachineInventory machine, String status,
                                    LocalDate startDate, LocalDate endDate, String price, String remark) {
        if (rentalRecordRepository.existsByRentalNo(rentalNo)) {
            return;
        }
        RentalRecord record = new RentalRecord();
        record.setRentalNo(rentalNo);
        record.setMachineId(machine.getId());
        record.setCustomerId(customer.getId());
        record.setVehicleNumber(machine.getVehicleProductNumber());
        record.setMachineName(machine.getName());
        record.setSpecificationModel(machine.getSpecificationModel());
        record.setCustomerName(customer.getCompanyName());
        record.setCustomerAddress(customer.getAddress());
        record.setDestination(customer.getAddress());
        record.setRentalPrice(money(price));
        record.setMonthlyRentalPrice(money(price));
        record.setStartDate(startDate);
        record.setEndDate(endDate);
        record.setStatus(status);
        record.setOperator(OPERATOR);
        record.setRemark(MARKER + " " + remark);
        rentalRecordRepository.saveAndFlush(record);
    }

    private void ensureRepairRecords(Map<String, Customer> customers, Map<String, MachineInventory> machines, Map<String, PartInventory> parts) {
        ensureRepairRecord("TEST-REPAIR-PENDING", customers.get("TEST-CUST-ETA"), machines.get("TEST-CPD30-002"),
                RepairStatus.PENDING.code(), "Abnormal lift noise", "Diagnose mast chain and hydraulic hose", "Mia", "TEST-PART-MAST-CHAIN,TEST-PART-HYD-HOSE", "2.5", "350", "260");
        ensureRepairRecord("TEST-REPAIR-COMPLETED", customers.get("TEST-CUST-ALPHA"), machines.get("TEST-FD30-001"),
                RepairStatus.COMPLETED.code(), "Regular maintenance", "Changed diesel and hydraulic filters", "Noah", "TEST-PART-FILTER-DIESEL,TEST-PART-FILTER-HYD", "3.0", "420", "125");
        ensureRepairRecord("TEST-REPAIR-EXTERNAL", customers.get("TEST-CUST-GAMMA"), machines.get("TEST-FD35-001"),
                RepairStatus.PENDING.code(), "Onsite brake issue", "External onsite service scheduled", "Olivia", "TEST-PART-BRAKE-SHOE", "4.0", "680", "250");
        ensureRepairRecord("TEST-REPAIR-NOPART", customers.get("TEST-CUST-DELTA"), machines.get("TEST-STACKER-001"),
                RepairStatus.COMPLETED.code(), "Software parameter reset", "Controller parameter recalibrated", "Peter", null, "1.0", "180", "0");
        ensureRepairRecord("TEST-REPAIR-LOCKED-UNIT", customers.get("TEST-CUST-THETA"), machines.get("TEST-DEMO-LOCKED"),
                RepairStatus.PENDING.code(), "Showroom loaner pre-delivery check", "Locked unit requires admin follow-up", "Quinn", "TEST-PART-MIRROR", "1.5", "260", "82");
        ensureRepairRecord("TEST-REPAIR-COLD", customers.get("TEST-CUST-LAMBDA"), machines.get("TEST-CPD15-COLD"),
                RepairStatus.COMPLETED.code(), "Cold-room seal hardening", "Installed low-temperature seal kit and tested lift cycle", "Rita", "TEST-PART-COLD-SEAL", "2.0", "360", "420");
        ensureRepairRecord("TEST-REPAIR-PARTONLY", customers.get("TEST-CUST-IOTA"), null,
                RepairStatus.PENDING.code(), "Returned charger needs bench diagnosis", "Part-only repair record without vehicle reference", "Sam", "TEST-PART-RETURN-CHARGER", "0.5", "80", "0");
    }

    private void ensureRepairRecord(String marker, Customer customer, MachineInventory machine, String status,
                                    String fault, String content, String person, String partCodes,
                                    String hours, String repairFee, String partsFee) {
        if (repairRecordRepository.existsByRemarks(MARKER + " " + marker)) {
            return;
        }
        RepairRecord record = new RepairRecord();
        record.setRepairDate(LocalDateTime.now().minusDays(status.equals(RepairStatus.COMPLETED.code()) ? 30 : 3));
        record.setMachineId(machine == null ? null : machine.getId());
        record.setVehicleNumber(machine == null ? null : machine.getVehicleProductNumber());
        record.setCustomerId(customer.getId());
        record.setCustomerName(customer.getCompanyName());
        record.setCustomerAddress(customer.getAddress());
        record.setFaultDescription(fault);
        record.setRepairContent(content);
        record.setRepairPerson(person);
        record.setRepairExternal(marker.contains("EXTERNAL"));
        record.setUsedParts(partCodes);
        record.setWorkHours(money(hours));
        record.setRepairFee(money(repairFee));
        record.setPartsFee(money(partsFee));
        record.setTotalFee(record.getRepairFee().add(record.getPartsFee()));
        record.setStatus(status);
        record.setRemarks(MARKER + " " + marker);
        repairRecordRepository.saveAndFlush(record);
    }

    private void ensureStocktakingRecords(Map<String, MachineInventory> machines, Map<String, PartInventory> parts) {
        ensureStocktakingRecord("TEST-ST-M-DRAFT", StocktakingRecord.RESOURCE_MACHINE, machines.get("TEST-FD30-001"), null, 1, "DRAFT");
        ensureStocktakingRecord("TEST-ST-M-COMP", StocktakingRecord.RESOURCE_MACHINE, machines.get("TEST-FD30-002"), null, 1, "COMPLETED");
        ensureStocktakingRecord("TEST-ST-P-DIFFPLUS", StocktakingRecord.RESOURCE_PART, null, parts.get("TEST-PART-HYD-SEAL"), 42, "DRAFT");
        ensureStocktakingRecord("TEST-ST-P-DIFFMINUS", StocktakingRecord.RESOURCE_PART, null, parts.get("TEST-PART-FILTER-DIESEL"), 78, "COMPLETED");
        ensureStocktakingRecord("TEST-ST-M-ZERO", StocktakingRecord.RESOURCE_MACHINE, machines.get("TEST-PALLET-002"), null, 0, "COMPLETED");
        ensureStocktakingRecord("TEST-ST-M-BULK", StocktakingRecord.RESOURCE_MACHINE, machines.get("TEST-MAN-002"), null, 1, "DRAFT");
        ensureStocktakingRecord("TEST-ST-P-EQUAL", StocktakingRecord.RESOURCE_PART, null, parts.get("TEST-PART-ANTI-STATIC"), 22, "COMPLETED");
        ensureStocktakingRecord("TEST-ST-P-ZERO", StocktakingRecord.RESOURCE_PART, null, parts.get("TEST-PART-DAMAGED-HOSE"), 0, "DRAFT");
    }

    private void ensureStocktakingRecord(String stocktakingNo, String resourceType, MachineInventory machine,
                                         PartInventory part, int actual, String status) {
        if (stocktakingRecordRepository.existsByStocktakingNo(stocktakingNo)) {
            return;
        }
        StocktakingRecord record = new StocktakingRecord();
        record.setStocktakingNo(stocktakingNo);
        record.setResourceType(resourceType);
        if (StocktakingRecord.RESOURCE_MACHINE.equals(resourceType)) {
            record.setResourceId(machine.getId());
            record.setResourceCode(machine.getVehicleProductNumber());
            record.setResourceName(machine.getName());
            record.setSpecificationModel(machine.getSpecificationModel());
            record.setBookQuantity(machine.getInventoryCount());
        } else {
            record.setResourceId(part.getId());
            record.setResourceCode(part.getPartCode());
            record.setResourceName(part.getPartName());
            record.setSpecificationModel(part.getSpecification());
            record.setBookQuantity(part.getQuantity());
        }
        record.setActualQuantity(actual);
        record.setDifferenceQuantity(actual - record.getBookQuantity());
        record.setStocktakingDate(LocalDate.now().minusDays(1));
        record.setStatus(status);
        record.setOperator(OPERATOR);
        record.setRemark(MARKER + " stocktaking coverage");
        stocktakingRecordRepository.saveAndFlush(record);
    }

    private void ensureStockMovements(Map<String, Warehouse> warehouses, Map<String, MachineInventory> machines, Map<String, PartInventory> parts) {
        ensureMovement("TEST-SM-IN-001", "INBOUND", StockLedgerService.RESOURCE_PART, parts.get("TEST-PART-TIRE-65010"), null, warehouses.get("WH-SZ"), 0, 48);
        ensureMovement("TEST-SM-OUT-001", "OUTBOUND", StockLedgerService.RESOURCE_PART, parts.get("TEST-PART-FILTER-DIESEL"), null, warehouses.get("WH-SZ"), 80, 72);
        ensureMovement("TEST-SM-ADJ-001", "ADJUST", StockLedgerService.RESOURCE_MACHINE, null, machines.get("TEST-MAN-001"), warehouses.get("WH-SZ"), 0, 1);
        ensureMovement("TEST-SM-XFER-001", "TRANSFER", StockLedgerService.RESOURCE_PART, parts.get("TEST-PART-FORK-1800"), null, warehouses.get("WH-QC"), 8, 6);
        ensureMovement("TEST-SM-IN-M-001", "INBOUND", StockLedgerService.RESOURCE_MACHINE, null, machines.get("TEST-FD50-HEAVY"), warehouses.get("WH-WH"), 0, 1);
        ensureMovement("TEST-SM-OUT-M-001", "OUTBOUND", StockLedgerService.RESOURCE_MACHINE, null, machines.get("TEST-PALLET-002"), warehouses.get("WH-SZ"), 1, 0);
        ensureMovement("TEST-SM-RETURN-001", "RETURN", StockLedgerService.RESOURCE_PART, parts.get("TEST-PART-RETURN-CHARGER"), null, warehouses.get("WH-QC"), 0, 1);
        ensureMovement("TEST-SM-SCRAP-001", "SCRAP", StockLedgerService.RESOURCE_PART, parts.get("TEST-PART-DAMAGED-HOSE"), null, warehouses.get("WH-QC"), 1, 0);
        ensureMovement("TEST-SM-ADJ-P-001", "ADJUST", StockLedgerService.RESOURCE_PART, parts.get("TEST-PART-MIRROR"), null, warehouses.get("WH-SZ"), 28, 32);
    }

    private void ensureMovement(String movementNo, String movementType, String resourceType, PartInventory part,
                                MachineInventory machine, Warehouse warehouse, int before, int after) {
        if (stockMovementRepository.findByMovementNo(movementNo).isPresent()) {
            return;
        }
        StockMovement movement = new StockMovement();
        movement.setMovementNo(movementNo);
        movement.setMovementType(movementType);
        movement.setResourceType(resourceType);
        movement.setSourceType("DEMO_SEED");
        movement.setSourceId(null);
        movement.setOperator(OPERATOR);
        movement.setRemark(MARKER + " stock movement coverage");
        StockMovement saved = stockMovementRepository.saveAndFlush(movement);

        StockMovementLine line = new StockMovementLine();
        line.setMovementId(saved.getId());
        line.setResourceType(resourceType);
        if (StockLedgerService.RESOURCE_MACHINE.equals(resourceType)) {
            line.setResourceId(machine.getId());
            line.setResourceCode(machine.getVehicleProductNumber());
            line.setResourceName(machine.getName());
        } else {
            line.setResourceId(part.getId());
            line.setResourceCode(part.getPartCode());
            line.setResourceName(part.getPartName());
        }
        line.setWarehouseId(warehouse.getId());
        line.setQuantityDelta(after - before);
        line.setBeforeQuantity(before);
        line.setAfterQuantity(after);
        stockMovementLineRepository.saveAndFlush(line);
    }

    private void ensureModificationWorkOrders(Map<String, Customer> customers, Map<String, MachineInventory> machines, Map<String, PartInventory> parts) {
        ensureWorkOrder("TEST-MWO-WAITING", machines.get("TEST-FD35-001"), customers.get("TEST-CUST-GAMMA"), parts.get("TEST-PART-TIRE-70012"),
                ModificationWorkOrderStatus.WAITING_PARTS.code(), null, null);
        ensureWorkOrder("TEST-MWO-PROGRESS", machines.get("TEST-FD35-002"), customers.get("TEST-CUST-THETA"), parts.get("TEST-PART-ATT-SIDESHIFT"),
                ModificationWorkOrderStatus.IN_PROGRESS.code(), null, null);
        ensureWorkOrder("TEST-MWO-COMPLETE", machines.get("TEST-FD30-001"), customers.get("TEST-CUST-ALPHA"), parts.get("TEST-PART-FORK-1520"),
                ModificationWorkOrderStatus.COMPLETED.code(), LocalDateTime.now().minusDays(5), null);
        ensureWorkOrder("TEST-MWO-CANCEL", machines.get("TEST-CPD20-001"), customers.get("TEST-CUST-DELTA"), parts.get("TEST-PART-CAMERA"),
                ModificationWorkOrderStatus.CANCELED.code(), null, LocalDateTime.now().minusDays(2));
        ensureWorkOrder("TEST-MWO-DISCOUNT", machines.get("TEST-USED-FD30"), customers.get("TEST-CUST-IOTA"), parts.get("TEST-PART-TRADEIN-FORK"),
                ModificationWorkOrderStatus.COMPLETED.code(), LocalDateTime.now().minusDays(1), null, PartChangeAction.DISCOUNT.code());
        ensureWorkOrder("TEST-MWO-COLD", machines.get("TEST-CPD35-MOD"), customers.get("TEST-CUST-LAMBDA"), parts.get("TEST-PART-CABIN-FULL"),
                ModificationWorkOrderStatus.WAITING_PARTS.code(), null, null, PartChangeAction.STOCK_IN.code());
        ensureWorkOrder("TEST-MWO-IOT", machines.get("TEST-CPD35-LITH"), customers.get("TEST-CUST-THETA"), parts.get("TEST-PART-IOT-ADV"),
                ModificationWorkOrderStatus.IN_PROGRESS.code(), null, null, PartChangeAction.STOCK_IN.code());
    }

    private void ensureWorkOrder(String workOrderNo, MachineInventory machine, Customer customer, PartInventory part,
                                 String status, LocalDateTime completedAt, LocalDateTime canceledAt) {
        ensureWorkOrder(workOrderNo, machine, customer, part, status, completedAt, canceledAt, PartChangeAction.STOCK_IN.code());
    }

    private void ensureWorkOrder(String workOrderNo, MachineInventory machine, Customer customer, PartInventory part,
                                 String status, LocalDateTime completedAt, LocalDateTime canceledAt, String oldPartAction) {
        if (workOrderRepository.findByWorkOrderNo(workOrderNo).isPresent()) {
            return;
        }
        List<MachineConfig> configs = machineConfigRepository.findByMachineId(machine.getId());
        if (configs.isEmpty()) {
            return;
        }
        MachineConfig sourceConfig = configs.get(0);
        ModificationWorkOrder order = new ModificationWorkOrder();
        order.setWorkOrderNo(workOrderNo);
        order.setMachineId(machine.getId());
        order.setCustomerName(customer.getCompanyName());
        order.setSalesOrderNo("SO-" + workOrderNo);
        order.setStatus(status);
        order.setOperator(OPERATOR);
        order.setRemark(MARKER + " modification work order coverage");
        order.setCompletedAt(completedAt);
        order.setCanceledAt(canceledAt);
        ModificationWorkOrder savedOrder = workOrderRepository.saveAndFlush(order);

        ModificationWorkOrderLine line = new ModificationWorkOrderLine();
        line.setWorkOrderId(savedOrder.getId());
        line.setMachineId(savedOrder.getMachineId());
        line.setMachineConfigId(sourceConfig.getId());
        line.setConfigItemId(sourceConfig.getConfigItemId());
        line.setItemName(sourceConfig.getItemName());
        line.setOldValue(sourceConfig.getSelectedValue());
        line.setNewPartId(part.getId());
        line.setNewPartCode(part.getPartCode());
        line.setNewPartName(part.getPartName());
        line.setNewValue(part.getPartName());
        line.setQuantity(1);
        line.setOldPartAction(oldPartAction);
        line.setPriceDifference(Optional.ofNullable(part.getSettlementPrice()).orElse(BigDecimal.ZERO));
        line.setRemark(MARKER + " work order line");
        workOrderLineRepository.saveAndFlush(line);
    }

    private Optional<ConfigValue> findConfigValue(ConfigItem item, String code) {
        if (item == null) {
            return Optional.empty();
        }
        return configValueRepository.findByConfigItemIdOrderBySortOrderAsc(item.getId()).stream()
                .filter(value -> code.equals(value.getValueCode()))
                .findFirst();
    }

    private void copyCustomer(OutboundOrder order, Customer customer) {
        order.setCustomerId(customer.getId());
        order.setCustomerName(customer.getCompanyName());
        order.setCustomerAddress(customer.getAddress());
        order.setContactName(customer.getContactName());
        order.setContactPhone(customer.getContactPhone());
        order.setTaxOrIdNumber(customer.getTaxOrIdNumber());
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }

    private static ConfigItemSpec item(String code, String category, String subCategory, String itemName,
                                       String unit, boolean required, int sortOrder, ConfigValueSpec... values) {
        return new ConfigItemSpec(code, category, subCategory, itemName, unit, required, sortOrder, Arrays.asList(values));
    }

    private static ConfigValueSpec value(String code, String label, boolean defaultValue, int sortOrder) {
        return new ConfigValueSpec(code, label, defaultValue, sortOrder);
    }

    private static CustomerSpec customer(String code, String companyName, String address, String contactName,
                                         String contactPhone, String taxNumber) {
        return new CustomerSpec(code, companyName, address, contactName, contactPhone, taxNumber);
    }

    private static SupplierSpec supplier(String code, String name, String type, String contactName, String contactPhone) {
        return new SupplierSpec(code, name, type, contactName, contactPhone);
    }

    private static MachineSpec machine(String vehicleNumber, String name, String model, String type, String configuration,
                                       String warehouseCode, int quantity, boolean modelOnly, String status, boolean locked,
                                       String supplier, String purchasePrice, String salePrice, String settlementPrice) {
        return new MachineSpec(vehicleNumber, name, model, type, configuration, warehouseCode, quantity, modelOnly, status,
                locked, supplier, purchasePrice, salePrice, settlementPrice);
    }

    private static PartSpec part(String code, String brand, String name, String specification, String category,
                                 String applicableModels, String source, int quantity, String unit, String warehouseCode,
                                 String purchasePrice, String salePrice, String settlementPrice) {
        return new PartSpec(code, brand, name, specification, category, applicableModels, source, quantity, unit,
                warehouseCode, purchasePrice, salePrice, settlementPrice);
    }

    private record ConfigItemSpec(String code, String category, String subCategory, String itemName, String unit,
                                  boolean required, int sortOrder, List<ConfigValueSpec> values) {
    }

    private record ConfigValueSpec(String code, String label, boolean defaultValue, int sortOrder) {
    }

    private record WarehouseSpec(String code, String name, String type, String address, boolean defaultWarehouse) {
    }

    private record CustomerSpec(String code, String companyName, String address, String contactName,
                                String contactPhone, String taxNumber) {
    }

    private record SupplierSpec(String code, String name, String type, String contactName, String contactPhone) {
    }

    private record MachineSpec(String vehicleNumber, String name, String model, String type, String configuration,
                               String warehouseCode, int quantity, boolean modelOnly, String status, boolean locked,
                               String supplier, String purchasePrice, String salePrice, String settlementPrice) {
    }

    private record PartSpec(String code, String brand, String name, String specification, String category,
                            String applicableModels, String source, int quantity, String unit, String warehouseCode,
                            String purchasePrice, String salePrice, String settlementPrice) {
    }
}
