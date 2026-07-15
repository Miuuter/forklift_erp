package com.example.forklift_erp;

import com.example.forklift_erp.entity.ConfigItem;
import com.example.forklift_erp.entity.ConfigValue;
import com.example.forklift_erp.entity.MachineConfig;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.Role;
import com.example.forklift_erp.entity.User;
import com.example.forklift_erp.repository.ConfigItemRepository;
import com.example.forklift_erp.repository.ConfigValueRepository;
import com.example.forklift_erp.repository.MachineConfigRepository;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderLineRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.RoleRepository;
import com.example.forklift_erp.repository.StockMovementRepository;
import com.example.forklift_erp.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ModificationWorkOrderIntegrationTests extends TestcontainersDatabaseSupport {

    private static final String PASSWORD = "CodexTest123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private MachineInventoryRepository machineRepository;

    @Autowired
    private MachineConfigRepository machineConfigRepository;

    @Autowired
    private ConfigItemRepository configItemRepository;

    @Autowired
    private ConfigValueRepository configValueRepository;

    @Autowired
    private PartInventoryRepository partRepository;

    @Autowired
    private ModificationWorkOrderRepository workOrderRepository;

    @Autowired
    private ModificationWorkOrderLineRepository workOrderLineRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final List<String> partsToCleanup = new ArrayList<>();
    private final List<Long> machinesToCleanup = new ArrayList<>();
    private final List<Long> configsToCleanup = new ArrayList<>();
    private final List<Long> configValuesToCleanup = new ArrayList<>();
    private final List<Long> configItemsToCleanup = new ArrayList<>();
    private final List<Long> workOrdersToCleanup = new ArrayList<>();
    private String superToken;

    @BeforeEach
    void setUp() throws Exception {
        String superUsername = unique("super");
        createUserDirectly(superUsername, "SUPER_ADMIN");
        superToken = login(superUsername);
    }

    @AfterEach
    void tearDown() {
        for (Long workOrderId : workOrdersToCleanup.reversed()) {
            workOrderLineRepository.findByWorkOrderIdOrderByIdAsc(workOrderId)
                    .forEach(workOrderLineRepository::delete);
            workOrderRepository.findById(workOrderId).ifPresent(workOrderRepository::delete);
        }
        workOrdersToCleanup.clear();

        for (Long configId : configsToCleanup.reversed()) {
            machineConfigRepository.findById(configId).ifPresent(machineConfigRepository::delete);
        }
        configsToCleanup.clear();

        for (Long configValueId : configValuesToCleanup.reversed()) {
            configValueRepository.findById(configValueId).ifPresent(configValueRepository::delete);
        }
        configValuesToCleanup.clear();

        for (Long configItemId : configItemsToCleanup.reversed()) {
            configItemRepository.findById(configItemId).ifPresent(configItemRepository::delete);
        }
        configItemsToCleanup.clear();

        for (Long machineId : machinesToCleanup.reversed()) {
            partRepository.findBySourceMachineId(machineId).forEach(partRepository::delete);
            machineRepository.findById(machineId).ifPresent(machineRepository::delete);
        }
        machinesToCleanup.clear();

        for (String partCode : partsToCleanup.reversed()) {
            partRepository.findByPartCode(partCode).ifPresent(partRepository::delete);
        }
        partsToCleanup.clear();

    }

    @Test
    void completeWorkOrderMovesStockAndUpdatesVehicleConfig() throws Exception {
        JsonNode machine = createMachine();
        Long machineId = machine.path("id").asLong();
        machinesToCleanup.add(machineId);

        MachineConfig tireConfig = createMachineConfig(machineId);
        JsonNode part = createPart("TIRE", 2);
        Long partId = part.path("id").asLong();

        Map<String, Object> line = new LinkedHashMap<>();
        line.put("machineConfigId", tireConfig.getId());
        line.put("machineConfigVersion", tireConfig.getVersion());
        line.put("newPartId", partId);
        line.put("newPartVersion", part.path("version").asLong());
        line.put("warehouseId", part.path("warehouseId").asLong());
        line.put("quantity", 1);
        line.put("oldPartAction", "STOCK_IN");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("machineId", machineId);
        payload.put("machineVersion", machine.path("version").asLong());
        payload.put("warehouseId", machine.path("warehouseId").asLong());
        payload.put("customerName", "Codex Customer");
        payload.put("salesOrderNo", "SO-" + unique("order"));
        payload.put("operator", "work-order-test");
        payload.put("remark", "customer requested solid tire");
        payload.put("lines", List.of(line));

        String createResponse = mockMvc.perform(post("/api/modification-work-orders")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("WAITING_PARTS"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode createdOrder = objectMapper.readTree(createResponse).path("data");
        Long workOrderId = createdOrder.path("id").asLong();
        workOrdersToCleanup.add(workOrderId);
        assertThat(createdOrder.path("machineProductNumber").asText())
                .isEqualTo(machine.path("vehicleProductNumber").asText());
        assertThat(createdOrder.path("machineName").asText()).isEqualTo("Work order test forklift");
        assertThat(createdOrder.path("specificationModel").asText()).isEqualTo("CPCD30");
        assertThat(machineRepository.findById(machineId).orElseThrow().getStockStatus())
                .isEqualTo("PENDING_MODIFICATION");

        mockMvc.perform(get("/api/modification-work-orders")
                        .header("Authorization", bearer(superToken))
                        .param("paged", "true")
                        .param("page", "0")
                        .param("size", "5")
                        .param("keyword", machine.path("vehicleProductNumber").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(workOrderId))
                .andExpect(jsonPath("$.data.content[0].machineProductNumber").value(machine.path("vehicleProductNumber").asText()));

        Map<String, Object> action = Map.of(
                "version", createdOrder.path("version").asLong(),
                "operator", "work-order-test",
                "remark", "completed in integration test"
        );

        mockMvc.perform(put("/api/modification-work-orders/{id}/complete", workOrderId)
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(action)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.lines[0].replaceLogId").isNumber());

        PartInventory adjustedPart = partRepository.findById(partId).orElseThrow();
        assertThat(adjustedPart.getQuantity()).isEqualTo(1);
        assertThat(partRepository.findBySourceMachineId(machineId))
                .anySatisfy(removed -> {
                    assertThat(removed.getSource()).isEqualTo("REMOVED");
                    assertThat(removed.getQuantity()).isEqualTo(1);
                    assertThat(removed.getPartCategory()).isEqualTo("TIRE");
                });

        MachineConfig adjustedConfig = machineConfigRepository.findById(tireConfig.getId()).orElseThrow();
        assertThat(adjustedConfig.getSelectedValue()).isEqualTo("Solid tire / 28x9-15");
        assertThat(adjustedConfig.getConfigSource()).isEqualTo("WAREHOUSE");
        assertThat(machineRepository.findById(machineId).orElseThrow().getStockStatus())
                .isEqualTo("PENDING_OUTBOUND");
        assertThat(stockMovementRepository.findBySourceTypeAndSourceId("MODIFICATION_WORK_ORDER", workOrderId))
                .hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void discountWorkOrderUsesConfigValueWithoutConsumingPartStockAndCountsIncome() throws Exception {
        JsonNode beforeStats = loadFinanceStats();
        BigDecimal beforeIncome = beforeStats.path("annualSummary").path("modificationIncome").decimalValue();
        BigDecimal beforeExpense = beforeStats.path("annualSummary").path("modificationExpense").decimalValue();

        JsonNode machine = createMachine();
        Long machineId = machine.path("id").asLong();
        machinesToCleanup.add(machineId);

        MachineConfig tireConfig = createMachineConfig(machineId);
        ConfigValue targetValue = new ConfigValue();
        targetValue.setConfigItemId(tireConfig.getConfigItemId());
        targetValue.setValueLabel("Premium solid tire");
        targetValue.setValueCode("PREMIUM-SOLID");
        ConfigValue savedTargetValue = configValueRepository.saveAndFlush(targetValue);
        configValuesToCleanup.add(savedTargetValue.getId());

        Map<String, Object> line = new LinkedHashMap<>();
        line.put("machineConfigId", tireConfig.getId());
        line.put("machineConfigVersion", tireConfig.getVersion());
        line.put("newConfigValueId", savedTargetValue.getId());
        line.put("newConfigValueVersion", savedTargetValue.getVersion());
        line.put("oldPartAction", "DISCOUNT");
        line.put("chargeUnitPrice", "1000.00");
        line.put("discountAmount", "120.00");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("machineId", machineId);
        payload.put("machineVersion", machine.path("version").asLong());
        payload.put("warehouseId", machine.path("warehouseId").asLong());
        payload.put("workOrderType", "AFTER_SALE");
        payload.put("customerName", "折扣改装客户");
        payload.put("operator", "discount-work-order-test");
        payload.put("remark", "discount replacement");
        payload.put("lines", List.of(line));

        String createResponse = mockMvc.perform(post("/api/modification-work-orders")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.lines[0].oldPartAction").value("DISCOUNT"))
                .andExpect(jsonPath("$.data.lines[0].newConfigValueId").value(savedTargetValue.getId()))
                .andExpect(jsonPath("$.data.lines[0].chargeAmount").value(880.00))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode createdOrder = objectMapper.readTree(createResponse).path("data");
        Long workOrderId = createdOrder.path("id").asLong();
        workOrdersToCleanup.add(workOrderId);

        Map<String, Object> action = Map.of(
                "version", createdOrder.path("version").asLong(),
                "operator", "discount-work-order-test"
        );

        mockMvc.perform(put("/api/modification-work-orders/{id}/complete", workOrderId)
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(action)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.lines[0].replaceLogId").isEmpty());

        MachineConfig adjustedConfig = machineConfigRepository.findById(tireConfig.getId()).orElseThrow();
        assertThat(adjustedConfig.getConfigValueId()).isEqualTo(savedTargetValue.getId());
        assertThat(adjustedConfig.getSelectedValue()).isEqualTo("Premium solid tire");
        assertThat(adjustedConfig.getConfigSource()).isEqualTo("DISCOUNT");
        assertThat(partRepository.findBySourceMachineId(machineId)).isEmpty();
        assertThat(stockMovementRepository.findBySourceTypeAndSourceId("MODIFICATION_WORK_ORDER", workOrderId)).isEmpty();

        JsonNode afterStats = loadFinanceStats();
        BigDecimal afterIncome = afterStats.path("annualSummary").path("modificationIncome").decimalValue();
        BigDecimal afterExpense = afterStats.path("annualSummary").path("modificationExpense").decimalValue();
        assertThat(afterIncome.subtract(beforeIncome)).isEqualByComparingTo("880.00");
        assertThat(afterExpense.subtract(beforeExpense)).isEqualByComparingTo("0.00");

        ConfigValue rebateValue = new ConfigValue();
        rebateValue.setConfigItemId(tireConfig.getConfigItemId());
        rebateValue.setValueLabel("Rebate solid tire");
        rebateValue.setValueCode("REBATE-SOLID");
        ConfigValue savedRebateValue = configValueRepository.saveAndFlush(rebateValue);
        configValuesToCleanup.add(savedRebateValue.getId());

        MachineInventory currentMachine = machineRepository.findById(machineId).orElseThrow();
        MachineConfig currentConfig = machineConfigRepository.findById(tireConfig.getId()).orElseThrow();
        Map<String, Object> rebateLine = new LinkedHashMap<>();
        rebateLine.put("machineConfigId", currentConfig.getId());
        rebateLine.put("machineConfigVersion", currentConfig.getVersion());
        rebateLine.put("newConfigValueId", savedRebateValue.getId());
        rebateLine.put("newConfigValueVersion", savedRebateValue.getVersion());
        rebateLine.put("oldPartAction", "DISCOUNT");
        rebateLine.put("chargeUnitPrice", "200.00");
        rebateLine.put("discountAmount", "80.00");

        Map<String, Object> rebatePayload = new LinkedHashMap<>();
        rebatePayload.put("machineId", machineId);
        rebatePayload.put("machineVersion", currentMachine.getVersion());
        rebatePayload.put("warehouseId", currentMachine.getWarehouseId());
        rebatePayload.put("workOrderType", "AFTER_SALE");
        rebatePayload.put("customerName", "折扣改装客户");
        rebatePayload.put("operator", "discount-work-order-test");
        rebatePayload.put("lines", List.of(rebateLine));

        String rebateCreateResponse = mockMvc.perform(post("/api/modification-work-orders")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(rebatePayload)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode rebateOrder = objectMapper.readTree(rebateCreateResponse).path("data");
        Long rebateOrderId = rebateOrder.path("id").asLong();
        workOrdersToCleanup.add(rebateOrderId);

        mockMvc.perform(put("/api/modification-work-orders/{id}/complete", rebateOrderId)
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "version", rebateOrder.path("version").asLong(),
                                "operator", "discount-work-order-test"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        JsonNode rebateStats = loadFinanceStats();
        BigDecimal rebateIncome = rebateStats.path("annualSummary").path("modificationIncome").decimalValue();
        BigDecimal rebateExpense = rebateStats.path("annualSummary").path("modificationExpense").decimalValue();
        assertThat(rebateIncome.subtract(afterIncome)).isEqualByComparingTo("120.00");
        assertThat(rebateExpense.subtract(afterExpense)).isEqualByComparingTo("0.00");
    }

    @Test
    void installVehiclePartConsumesWarehousePartAndCreatesVehicleConfig() throws Exception {
        JsonNode machine = createMachine();
        Long machineId = machine.path("id").asLong();
        machinesToCleanup.add(machineId);

        ConfigItem item = new ConfigItem();
        item.setCategory("ELECTRIC");
        item.setSubCategory("TIRE");
        item.setItemName("Tire type");
        item.setItemCode("INSTALL-CFG-" + unique("config"));
        item.setInputType("SELECT");
        ConfigItem savedItem = configItemRepository.saveAndFlush(item);
        configItemsToCleanup.add(savedItem.getId());

        ConfigValue value = new ConfigValue();
        value.setConfigItemId(savedItem.getId());
        value.setValueLabel("Solid tire");
        ConfigValue savedValue = configValueRepository.saveAndFlush(value);
        configValuesToCleanup.add(savedValue.getId());

        JsonNode part = createPart("TIRE", 3);
        Long partId = part.path("id").asLong();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("machineId", machineId);
        payload.put("machineVersion", machine.path("version").asLong());
        payload.put("configItemId", savedItem.getId());
        payload.put("newPartId", partId);
        payload.put("newPartVersion", part.path("version").asLong());
        payload.put("warehouseId", part.path("warehouseId").asLong());
        payload.put("quantity", 2);
        payload.put("operator", "install-test");

        String response = mockMvc.perform(post("/api/replace/install-part")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.replaceType").value("PART_INSTALL"))
                .andExpect(jsonPath("$.data.newPartId").value(partId))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode log = objectMapper.readTree(response).path("data");
        Long configId = log.path("machineConfigId").asLong();
        configsToCleanup.add(configId);

        PartInventory adjustedPart = partRepository.findById(partId).orElseThrow();
        assertThat(adjustedPart.getQuantity()).isEqualTo(1);

        MachineConfig installedConfig = machineConfigRepository.findById(configId).orElseThrow();
        assertThat(installedConfig.getMachineId()).isEqualTo(machineId);
        assertThat(installedConfig.getConfigItemId()).isEqualTo(savedItem.getId());
        assertThat(installedConfig.getConfigValueId()).isEqualTo(savedValue.getId());
        assertThat(installedConfig.getSelectedValue()).isEqualTo("Solid tire / 28x9-15");
        assertThat(installedConfig.getConfigSource()).isEqualTo("WAREHOUSE");
        assertThat(installedConfig.getIsStandard()).isFalse();
    }

    @Test
    void createConfigItemAutoFillsSequentialCodeWhenBlank() throws Exception {
        JsonNode first = createConfigItemByApi("电动车", "轮胎", "轮胎类型");
        JsonNode second = createConfigItemByApi("内燃车", "货叉", "货叉类型");

        String firstCode = first.path("itemCode").asText();
        String secondCode = second.path("itemCode").asText();

        assertThat(firstCode).matches("^CFG-\\d+$");
        assertThat(secondCode).matches("^CFG-\\d+$");
        assertThat(Integer.parseInt(secondCode.substring(4)))
                .isEqualTo(Integer.parseInt(firstCode.substring(4)) + 1);
    }

    @Test
    void modelTemplateAndManualInboundUseGeneratedNumbersAndDictionaryConfigs() throws Exception {
        Map<String, Object> modelPayload = new LinkedHashMap<>();
        modelPayload.put("name", "手动测试车型");
        modelPayload.put("specificationModel", "CBY25");
        modelPayload.put("machineType", "手动叉车");
        modelPayload.put("modelOnly", true);
        modelPayload.put("warehouseId", defaultWarehouseId());

        String modelResponse = mockMvc.perform(post("/api/inventory")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(modelPayload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.modelOnly").value(true))
                .andExpect(jsonPath("$.data.inventoryCount").value(0))
                .andExpect(jsonPath("$.data.stockStatus").value("PENDING_INBOUND"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode model = objectMapper.readTree(modelResponse).path("data");
        machinesToCleanup.add(model.path("id").asLong());
        assertThat(model.path("vehicleProductNumber").asText()).startsWith("MODEL-");

        ConfigItem item = new ConfigItem();
        item.setCategory("手动叉车");
        item.setSubCategory("货叉");
        item.setItemName("货叉规格");
        item.setItemCode("MAN-CFG-" + unique("config"));
        item.setInputType("SELECT");
        ConfigItem savedItem = configItemRepository.saveAndFlush(item);
        configItemsToCleanup.add(savedItem.getId());

        ConfigValue value = new ConfigValue();
        value.setConfigItemId(savedItem.getId());
        value.setValueLabel("550x1150");
        value.setValueCode("FORK-550-1150");
        ConfigValue savedValue = configValueRepository.saveAndFlush(value);
        configValuesToCleanup.add(savedValue.getId());

        Map<String, Object> machine = new LinkedHashMap<>();
        machine.put("name", "手动测试车型");
        machine.put("specificationModel", "CBY25");
        machine.put("machineType", "手动叉车");
        machine.put("inventoryCount", 1);
        machine.put("warehouseId", defaultWarehouseId());

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("configItemId", savedItem.getId());
        config.put("configValueId", savedValue.getId());
        config.put("itemName", "前端不再信任这个名称");
        config.put("selectedValue", "前端不再信任这个值");

        String inboundResponse = mockMvc.perform(post("/api/inventory/inbound")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "machineInventory", machine,
                                "configs", List.of(config)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.modelOnly").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode inbound = objectMapper.readTree(inboundResponse).path("data");
        Long machineId = inbound.path("id").asLong();
        machinesToCleanup.add(machineId);
        assertThat(inbound.path("vehicleProductNumber").asText()).startsWith("MAN-");

        List<MachineConfig> configs = machineConfigRepository.findByMachineId(machineId);
        configs.forEach(configRow -> configsToCleanup.add(configRow.getId()));
        assertThat(configs).singleElement().satisfies(saved -> {
            assertThat(saved.getItemName()).isEqualTo("货叉规格");
            assertThat(saved.getSelectedValue()).isEqualTo("550x1150");
        });
    }

    @Test
    void vehicleModelSummaryEndpointAggregatesPagedRowsAndListsModelVehicles() throws Exception {
        String suffix = unique("model");
        String modelName = "Codex model " + suffix;
        String specificationModel = "CPCD-" + suffix;
        String machineType = "diesel-test";
        String firstVehicleNumber = "VM-" + unique("first");
        String secondVehicleNumber = "VM-" + unique("second");

        Map<String, Object> templatePayload = new LinkedHashMap<>();
        templatePayload.put("name", modelName);
        templatePayload.put("specificationModel", specificationModel);
        templatePayload.put("machineType", machineType);
        templatePayload.put("supplier", "Codex Supplier");
        templatePayload.put("purchasePrice", new BigDecimal("1000.00"));
        templatePayload.put("modelOnly", true);
        JsonNode template = createInventory(templatePayload);
        machinesToCleanup.add(template.path("id").asLong());

        Map<String, Object> firstPayload = new LinkedHashMap<>();
        firstPayload.put("vehicleProductNumber", firstVehicleNumber);
        firstPayload.put("name", modelName);
        firstPayload.put("specificationModel", specificationModel);
        firstPayload.put("machineType", machineType);
        firstPayload.put("supplier", "Codex Supplier");
        firstPayload.put("inventoryCount", 1);
        JsonNode first = createInventory(firstPayload);
        machinesToCleanup.add(first.path("id").asLong());

        Map<String, Object> secondPayload = new LinkedHashMap<>(firstPayload);
        secondPayload.put("vehicleProductNumber", secondVehicleNumber);
        secondPayload.put("inventoryCount", 1);
        JsonNode second = createInventory(secondPayload);
        machinesToCleanup.add(second.path("id").asLong());

        mockMvc.perform(get("/api/inventory/models")
                        .header("Authorization", bearer(superToken))
                        .param("paged", "true")
                        .param("page", "0")
                        .param("size", "5")
                        .param("keyword", suffix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].name").value(modelName))
                .andExpect(jsonPath("$.data.content[0].specificationModel").value(specificationModel))
                .andExpect(jsonPath("$.data.content[0].machineType").value(machineType))
                .andExpect(jsonPath("$.data.content[0].modelTemplateId").value(template.path("id").asLong()))
                .andExpect(jsonPath("$.data.content[0].unitCount").value(2))
                .andExpect(jsonPath("$.data.content[0].inventoryCount").value(2))
                .andExpect(jsonPath("$.data.content[0].vehicleNumbers").value(containsString(firstVehicleNumber)))
                .andExpect(jsonPath("$.data.content[0].vehicleNumbers").value(containsString(secondVehicleNumber)));

        mockMvc.perform(get("/api/inventory/model-vehicles")
                        .header("Authorization", bearer(superToken))
                        .param("name", modelName)
                        .param("specificationModel", specificationModel)
                        .param("machineType", machineType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].vehicleProductNumber").value(firstVehicleNumber))
                .andExpect(jsonPath("$.data[0].modelOnly").value(false))
                .andExpect(jsonPath("$.data[1].vehicleProductNumber").value(secondVehicleNumber));
    }

    private JsonNode createMachine() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("vehicleProductNumber", "MO-" + unique("machine"));
        payload.put("name", "Work order test forklift");
        payload.put("specificationModel", "CPCD30");
        payload.put("machineType", "TEST");
        payload.put("supplier", "Codex Supplier");
        payload.put("inventoryCount", 1);

        return createInventory(payload);
    }

    private JsonNode createInventory(Map<String, Object> payload) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>(payload);
        request.putIfAbsent("warehouseId", defaultWarehouseId());
        String response = mockMvc.perform(post("/api/inventory")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private JsonNode loadFinanceStats() throws Exception {
        String response = mockMvc.perform(get("/api/statistics/finance")
                        .header("Authorization", bearer(superToken))
                        .param("year", String.valueOf(LocalDate.now().getYear())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private MachineConfig createMachineConfig(Long machineId) {
        ConfigItem configItem = new ConfigItem();
        configItem.setCategory("ELECTRIC");
        configItem.setSubCategory("TIRE");
        configItem.setItemName("Tire type");
        configItem.setItemCode("MO-CFG-" + unique("config"));
        configItem.setInputType("SELECT");
        ConfigItem savedItem = configItemRepository.saveAndFlush(configItem);
        configItemsToCleanup.add(savedItem.getId());

        MachineConfig config = new MachineConfig();
        config.setMachineId(machineId);
        config.setConfigItemId(savedItem.getId());
        config.setConfigValueId(Math.abs(UUID.randomUUID().getLeastSignificantBits()));
        config.setItemName(savedItem.getItemName());
        config.setSelectedValue("Pneumatic tire");
        config.setIsStandard(true);
        config.setConfigSource("FACTORY_STANDARD");
        MachineConfig saved = machineConfigRepository.saveAndFlush(config);
        configsToCleanup.add(saved.getId());
        return saved;
    }

    private JsonNode createConfigItemByApi(String category, String subCategory, String itemName) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("category", category);
        payload.put("subCategory", subCategory);
        payload.put("itemName", itemName);
        payload.put("inputType", "SELECT");

        String response = mockMvc.perform(post("/api/config/items")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode item = objectMapper.readTree(response).path("data");
        configItemsToCleanup.add(item.path("id").asLong());
        return item;
    }

    private JsonNode createPart(String category, int quantity) throws Exception {
        String partCode = "MO-PART-" + unique("part");
        partsToCleanup.add(partCode);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("partCode", partCode);
        payload.put("partName", "Solid tire");
        payload.put("specification", "28x9-15");
        payload.put("partCategory", category);
        payload.put("quantity", quantity);
        payload.put("unit", "pcs");
        payload.put("warehouseId", defaultWarehouseId());

        String response = mockMvc.perform(post("/api/parts")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

}
