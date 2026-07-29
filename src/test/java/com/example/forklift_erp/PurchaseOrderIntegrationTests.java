package com.example.forklift_erp;

import com.example.forklift_erp.entity.ConfigItem;
import com.example.forklift_erp.entity.ConfigValue;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.StockBalance;
import com.example.forklift_erp.repository.ConfigItemRepository;
import com.example.forklift_erp.repository.ConfigValueRepository;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.PurchaseOrderRepository;
import com.example.forklift_erp.repository.SupplierRepository;
import com.example.forklift_erp.repository.StockBalanceRepository;
import com.example.forklift_erp.repository.StockMovementLineRepository;
import com.example.forklift_erp.repository.StockMovementRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class PurchaseOrderIntegrationTests extends TestcontainersDatabaseSupport {

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private ConfigItemRepository configItemRepository;

    @Autowired
    private ConfigValueRepository configValueRepository;

    @Autowired
    private PartInventoryRepository partInventoryRepository;

    @Autowired
    private MachineInventoryRepository machineInventoryRepository;

    @Autowired
    private StockBalanceRepository stockBalanceRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private StockMovementLineRepository stockMovementLineRepository;

    private final List<Long> purchaseOrderIdsToCleanup = new ArrayList<>();
    private final List<Long> supplierIdsToCleanup = new ArrayList<>();
    private final List<Long> configValueIdsToCleanup = new ArrayList<>();
    private final List<Long> configItemIdsToCleanup = new ArrayList<>();
    private final List<Long> partResourceIdsToCleanup = new ArrayList<>();
    private final List<Long> machineResourceIdsToCleanup = new ArrayList<>();
    private final List<Long> stockBalanceIdsToCleanup = new ArrayList<>();

    private String superToken;

    @BeforeEach
    void setUp() throws Exception {
        String superUsername = unique("super");
        createUserDirectly(superUsername, "SUPER_ADMIN");
        superToken = login(superUsername);
    }

    @AfterEach
    void tearDownPurchaseOrders() {
        for (Long orderId : purchaseOrderIdsToCleanup.reversed()) {
            purchaseOrderRepository.findById(orderId).ifPresent(purchaseOrderRepository::delete);
        }
        purchaseOrderIdsToCleanup.clear();

        for (Long supplierId : supplierIdsToCleanup.reversed()) {
            supplierRepository.findById(supplierId).ifPresent(supplierRepository::delete);
        }
        supplierIdsToCleanup.clear();

        for (Long valueId : configValueIdsToCleanup.reversed()) {
            configValueRepository.findById(valueId).ifPresent(configValueRepository::delete);
        }
        configValueIdsToCleanup.clear();

        for (Long itemId : configItemIdsToCleanup.reversed()) {
            configItemRepository.findById(itemId).ifPresent(configItemRepository::delete);
        }
        configItemIdsToCleanup.clear();

        for (Long partId : partResourceIdsToCleanup.reversed()) {
            partInventoryRepository.findById(partId).ifPresent(partInventoryRepository::delete);
        }
        partResourceIdsToCleanup.clear();

        for (Long balanceId : stockBalanceIdsToCleanup.reversed()) {
            stockBalanceRepository.findById(balanceId).ifPresent(stockBalanceRepository::delete);
        }
        stockBalanceIdsToCleanup.clear();

        for (Long machineId : machineResourceIdsToCleanup.reversed()) {
            machineInventoryRepository.findById(machineId).ifPresent(machineInventoryRepository::delete);
        }
        machineResourceIdsToCleanup.clear();
    }

    @Test
    void purchaseOrdersCanBeFilteredByPartAndMachineResourceType() throws Exception {
        String marker = unique("purchase");
        Long supplierId = createSupplier("配件供应商-" + marker);
        Long configItemId = createConfigItem(marker);
        Long configValueId = createConfigValue(configItemId, marker);
        Long partResourceId = createPartResource(marker);
        Long machineResourceId = createMachineResource(marker);

        JsonNode partOrder = createPurchaseOrder(Map.of(
                "supplierId", supplierId,
                "resourceType", "PART",
                "resourceId", partResourceId,
                "configItemId", configItemId,
                "configValueId", configValueId,
                "quantity", 2,
                "unitPrice", "15.00",
                "status", "ORDERED",
                "remark", marker
        ));

        JsonNode machineOrder = createPurchaseOrder(
                machineOrderPayload(marker, "ORDERED", supplierId, machineResourceId));

        assertThat(partOrder.path("resourceType").asText()).isEqualTo("PART");
        assertThat(machineOrder.path("resourceType").asText()).isEqualTo("MACHINE");

        mockMvc.perform(get("/api/purchase-orders")
                        .header("Authorization", bearer(superToken))
                        .param("paged", "true")
                        .param("keyword", marker)
                        .param("resourceType", "PART")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].resourceType").value("PART"))
                .andExpect(jsonPath("$.data.content[0].resourceName").value("配件-" + marker));

        mockMvc.perform(get("/api/purchase-orders")
                        .header("Authorization", bearer(superToken))
                        .param("paged", "true")
                        .param("keyword", marker)
                        .param("resourceType", "MACHINE")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].resourceType").value("MACHINE"))
                .andExpect(jsonPath("$.data.content[0].specificationModel").value("CPCD30-" + marker));

        mockMvc.perform(get("/api/statistics/list-summary")
                        .header("Authorization", bearer(superToken))
                        .param("type", "purchases")
                        .param("keyword", marker)
                        .param("resourceType", "MACHINE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cards[0].value").value(1))
                .andExpect(jsonPath("$.data.cards[2].value").value(0));
    }

    @Test
    void receiptToggleRestoresPartialStatusAndRejectsCanceledOrders() throws Exception {
        String marker = unique("receipt");
        Long supplierId = createSupplier("Machine supplier " + marker);
        Long machineResourceId = createMachineResource(marker);
        Map<String, Object> partialPayload = machineOrderPayload(
                marker + "-partial", "PARTIAL", supplierId, machineResourceId);
        JsonNode partialOrder = createPurchaseOrder(partialPayload);

        String receivedResponse = mockMvc.perform(put("/api/purchase-orders/{id}/received", partialOrder.path("id").asLong())
                        .header("Authorization", bearer(superToken))
                        .param("received", "true")
                        .param("version", partialOrder.path("version").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RECEIVED"))
                .andExpect(jsonPath("$.data.statusBeforeReceived").value("PARTIAL"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode receivedOrder = objectMapper.readTree(receivedResponse).path("data");

        String restoredResponse = mockMvc.perform(put("/api/purchase-orders/{id}/received", partialOrder.path("id").asLong())
                        .header("Authorization", bearer(superToken))
                        .param("received", "false")
                        .param("version", receivedOrder.path("version").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PARTIAL"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode restoredOrder = objectMapper.readTree(restoredResponse).path("data");
        assertThat(restoredOrder.path("statusBeforeReceived").isNull()).isTrue();
        Long originalMovementId = receivedOrder.path("receivedStockMovementId").asLong();
        var reversalMovement = stockMovementRepository
                .findByIdempotencyKey("PURCHASE-REVERSAL:" + partialOrder.path("id").asLong())
                .orElseThrow();
        assertThat(reversalMovement.getReversalOfMovementId()).isEqualTo(originalMovementId);
        var originalLine = stockMovementLineRepository
                .findByMovementIdOrderByIdAsc(originalMovementId).getFirst();
        var reversalLine = stockMovementLineRepository
                .findByMovementIdOrderByIdAsc(reversalMovement.getId()).getFirst();
        assertThat(reversalLine.getReversalOfMovementId()).isEqualTo(originalMovementId);
        assertThat(reversalLine.getReversalOfMovementLineId()).isEqualTo(originalLine.getId());
        assertThat(reversalLine.getQuantityDelta()).isEqualTo(-originalLine.getQuantityDelta());
        assertThat(reversalLine.getBeforeQuantity()).isEqualTo(originalLine.getAfterQuantity());
        assertThat(reversalLine.getAfterQuantity()).isEqualTo(originalLine.getBeforeQuantity());

        Map<String, Object> canceledPayload = machineOrderPayload(
                marker + "-canceled", "CANCELED", supplierId, machineResourceId);
        JsonNode canceledOrder = createPurchaseOrder(canceledPayload);
        mockMvc.perform(put("/api/purchase-orders/{id}/received", canceledOrder.path("id").asLong())
                        .header("Authorization", bearer(superToken))
                        .param("received", "true")
                        .param("version", canceledOrder.path("version").asText()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4001));

        Map<String, Object> canceledUpdate = new LinkedHashMap<>(canceledPayload);
        canceledUpdate.put("version", canceledOrder.path("version").asLong());
        canceledUpdate.put("status", "RECEIVED");
        mockMvc.perform(put("/api/purchase-orders/{id}", canceledOrder.path("id").asLong())
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(canceledUpdate)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4001));
    }

    @Test
    void legacyReceivedOrderWithoutHistoryFallsBackToOrdered() throws Exception {
        String marker = unique("legacy");
        Long supplierId = createSupplier("Machine supplier " + marker);
        Long machineResourceId = createMachineResource(marker);
        JsonNode created = createPurchaseOrder(machineOrderPayload(marker, "ORDERED", supplierId, machineResourceId));
        var legacyOrder = purchaseOrderRepository.findById(created.path("id").asLong()).orElseThrow();
        legacyOrder.setStatus("RECEIVED");
        legacyOrder.setStatusBeforeReceived(null);
        legacyOrder = purchaseOrderRepository.saveAndFlush(legacyOrder);

        mockMvc.perform(put("/api/purchase-orders/{id}/received", legacyOrder.getId())
                        .header("Authorization", bearer(superToken))
                        .param("received", "false")
                        .param("version", String.valueOf(legacyOrder.getVersion())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ORDERED"))
                .andExpect(jsonPath("$.data.statusBeforeReceived").isEmpty());
    }

    @Test
    void machineReceiptRejectsLockedRentalQuantityWithoutChangingInventory() throws Exception {
        String marker = unique("rented-receipt");
        Long supplierId = createSupplier("Machine supplier " + marker);
        Long machineId = createMachineResource(marker);
        MachineInventory machine = machineInventoryRepository.findById(machineId).orElseThrow();
        machine.setStockStatus("RENTED");
        machineInventoryRepository.saveAndFlush(machine);

        StockBalance balance = new StockBalance();
        balance.setResourceType("MACHINE");
        balance.setResourceId(machineId);
        balance.setWarehouseId(defaultWarehouseId());
        balance.setAvailableQuantity(0);
        balance.setReservedQuantity(0);
        balance.setLockedQuantity(1);
        balance = stockBalanceRepository.saveAndFlush(balance);
        stockBalanceIdsToCleanup.add(balance.getId());

        JsonNode order = createPurchaseOrder(
                machineOrderPayload(marker, "ORDERED", supplierId, machineId));

        mockMvc.perform(put("/api/purchase-orders/{id}/received", order.path("id").asLong())
                        .header("Authorization", bearer(superToken))
                        .param("received", "true")
                        .param("version", order.path("version").asText()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));

        StockBalance persistedBalance = stockBalanceRepository.findById(balance.getId()).orElseThrow();
        assertThat(persistedBalance.getAvailableQuantity()).isZero();
        assertThat(persistedBalance.getLockedQuantity()).isEqualTo(1);
        MachineInventory persistedMachine = machineInventoryRepository.findById(machineId).orElseThrow();
        assertThat(persistedMachine.getStockStatus()).isEqualTo("RENTED");
        assertThat(persistedMachine.getInventoryCount()).isZero();
        var persistedOrder = purchaseOrderRepository.findById(order.path("id").asLong()).orElseThrow();
        assertThat(persistedOrder.getStatus()).isEqualTo("ORDERED");
        assertThat(persistedOrder.getReceivedStockMovementId()).isNull();
    }

    private Map<String, Object> machineOrderPayload(String marker, String status, Long supplierId, Long machineResourceId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("supplierId", supplierId);
        payload.put("resourceType", "MACHINE");
        payload.put("resourceId", machineResourceId);
        payload.put("quantity", 1);
        payload.put("unitPrice", "88000.00");
        payload.put("status", status);
        payload.put("remark", marker);
        return payload;
    }

    private Long createPartResource(String marker) {
        PartInventory part = new PartInventory();
        part.setPartCode("PO-PART-" + marker);
        part.setPartName("配件-" + marker);
        part.setSpecification("PART-SPEC-" + marker);
        part.setPartCategory("PURCHASE_TEST");
        part.setQuantity(0);
        part.setUnit("件");
        part.setWarehouseId(defaultWarehouseId());
        Long id = partInventoryRepository.saveAndFlush(part).getId();
        partResourceIdsToCleanup.add(id);
        return id;
    }

    private Long createMachineResource(String marker) {
        MachineInventory machine = new MachineInventory();
        machine.setVehicleProductNumber("PO-MACHINE-" + marker);
        machine.setName("测试整车-" + marker);
        machine.setSpecificationModel("CPCD30-" + marker);
        machine.setMachineType("TEST");
        machine.setInventoryCount(0);
        machine.setWarehouseId(defaultWarehouseId());
        machine.setStockStatus("PENDING_INBOUND");
        machine.setModelOnly(false);
        Long id = machineInventoryRepository.saveAndFlush(machine).getId();
        machineResourceIdsToCleanup.add(id);
        return id;
    }

    private Long createSupplier(String supplierName) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("supplierName", supplierName);
        payload.put("supplierType", "配件供应商");

        String response = mockMvc.perform(post("/api/suppliers")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long id = objectMapper.readTree(response).path("data").path("id").asLong();
        supplierIdsToCleanup.add(id);
        return id;
    }

    private Long createConfigItem(String marker) throws Exception {
        ConfigItem item = new ConfigItem();
        item.setCategory("测试配件");
        item.setSubCategory("测试分类");
        item.setItemName("测试规格-" + marker);
        item.setItemCode("PO-CFG-" + marker);
        item.setInputType("SELECT");
        item.setUnit("件");
        item.setIsRequired(false);
        item.setSortOrder(0);

        String response = mockMvc.perform(post("/api/config/items")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(item)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long id = objectMapper.readTree(response).path("data").path("id").asLong();
        configItemIdsToCleanup.add(id);
        return id;
    }

    private Long createConfigValue(Long configItemId, String marker) throws Exception {
        ConfigValue value = new ConfigValue();
        value.setConfigItemId(configItemId);
        value.setValueLabel("配件-" + marker);
        value.setValueCode("PART-" + marker);
        value.setIsDefault(false);
        value.setSortOrder(0);

        String response = mockMvc.perform(post("/api/config/values")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(value)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long id = objectMapper.readTree(response).path("data").path("id").asLong();
        configValueIdsToCleanup.add(id);
        return id;
    }

    private JsonNode createPurchaseOrder(Map<String, Object> payload) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>(payload);
        request.putIfAbsent("warehouseId", defaultWarehouseId());
        String response = mockMvc.perform(post("/api/purchase-orders")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        purchaseOrderIdsToCleanup.add(data.path("id").asLong());
        return data;
    }
}
