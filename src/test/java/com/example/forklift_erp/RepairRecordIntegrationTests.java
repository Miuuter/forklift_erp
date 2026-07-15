package com.example.forklift_erp;

import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.StockBalance;
import com.example.forklift_erp.entity.StockMovementLine;
import com.example.forklift_erp.repository.CustomerRepository;
import com.example.forklift_erp.repository.OperationAuditLogRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.RepairRecordRepository;
import com.example.forklift_erp.repository.StockBalanceRepository;
import com.example.forklift_erp.repository.StockMovementLineRepository;
import com.example.forklift_erp.repository.StockOperationLogRepository;
import com.example.forklift_erp.service.RepairRecordService;
import com.example.forklift_erp.service.StockLedgerService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
@AutoConfigureMockMvc
class RepairRecordIntegrationTests extends TestcontainersDatabaseSupport {

    private final List<Long> repairsToCleanup = new ArrayList<>();
    private final List<Long> customersToCleanup = new ArrayList<>();
    private final List<String> partsToCleanup = new ArrayList<>();
    private String superToken;

    @jakarta.annotation.Resource
    private CustomerRepository customerRepository;

    @jakarta.annotation.Resource
    private PartInventoryRepository partRepository;

    @jakarta.annotation.Resource
    private RepairRecordRepository repairRepository;

    @jakarta.annotation.Resource
    private RepairRecordService repairRecordService;

    @jakarta.annotation.Resource
    private StockMovementLineRepository stockMovementLineRepository;

    @jakarta.annotation.Resource
    private StockBalanceRepository stockBalanceRepository;

    @jakarta.annotation.Resource
    private StockOperationLogRepository stockOperationLogRepository;

    @jakarta.annotation.Resource
    private OperationAuditLogRepository operationAuditLogRepository;

    @BeforeEach
    void setUp() throws Exception {
        String superUsername = unique("super");
        createUserDirectly(superUsername, "SUPER_ADMIN");
        superToken = login(superUsername);
    }

    @AfterEach
    void tearDown() {
        for (Long repairId : repairsToCleanup.reversed()) {
            repairRepository.findById(repairId).ifPresent(repair -> repairRecordService.deleteById(repair.getId()));
        }
        repairsToCleanup.clear();

        for (String partCode : partsToCleanup.reversed()) {
            partRepository.findByPartCode(partCode).ifPresent(partRepository::delete);
        }
        partsToCleanup.clear();

        for (Long customerId : customersToCleanup.reversed()) {
            customerRepository.findById(customerId).ifPresent(customerRepository::delete);
        }
        customersToCleanup.clear();
    }

    @Test
    void completedExternalRepairDeductsUsedPartAndReportsServiceIncomeNetProfit() throws Exception {
        JsonNode customer = createCustomer();
        JsonNode part = createPart();
        Long partId = part.path("id").asLong();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("repairDate", LocalDateTime.of(2030, 5, 10, 9, 30).toString());
        payload.put("customerId", customer.path("id").asLong());
        payload.put("faultDescription", "维修配件扣库与利润口径测试");
        payload.put("repairContent", "更换测试配件");
        payload.put("repairPersonChoice", "OTHER");
        payload.put("partUsages", List.of(Map.of(
                "partId", partId,
                "warehouseId", part.path("warehouseId").asLong(),
                "quantity", 1,
                "chargeUnitPrice", "150.00",
                "discountAmount", "0.00"
        )));
        payload.put("repairFee", "500.00");
        payload.put("repairExpense", "200.00");
        payload.put("partsFee", "150.00");
        payload.put("status", "COMPLETED");

        String repairResponse = mockMvc.perform(post("/api/repairs")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.repairExpense").value(200.00))
                .andExpect(jsonPath("$.data.partsCost").value(80.00))
                .andExpect(jsonPath("$.data.totalFee").value(650.00))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode repair = objectMapper.readTree(repairResponse).path("data");
        repairsToCleanup.add(repair.path("id").asLong());

        PartInventory adjustedPart = partRepository.findById(partId).orElseThrow();
        assertThat(adjustedPart.getQuantity()).isEqualTo(3);
        assertThat(stockMovementLineRepository
                .findByResourceTypeAndResourceIdOrderByCreatedAtDesc(StockLedgerService.RESOURCE_PART, partId))
                .anySatisfy(line -> assertRepairUseLine(line));

        String statsResponse = mockMvc.perform(get("/api/statistics/finance")
                        .header("Authorization", bearer(superToken))
                        .param("year", "2030"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode annual = objectMapper.readTree(statsResponse).path("data").path("annualSummary");
        assertMoney(annual.path("repairIncome"), "650.00");
        assertMoney(annual.path("repairReceivable"), "650.00");
        assertMoney(annual.path("repairExpense"), "200.00");
        assertMoney(annual.path("repairPartsCost"), "80.00");
        assertMoney(annual.path("totalIncome"), "650.00");
        assertMoney(annual.path("netProfit"), "370.00");
        assertMoney(annual.path("netCashflow"), "0.00");
    }

    @Test
    void updatingRepairRestoresPreviousUsedPartInventoryAndLedger() throws Exception {
        JsonNode customer = createCustomer();
        JsonNode part = createPart();
        Long partId = part.path("id").asLong();
        Long warehouseId = part.path("warehouseId").asLong();

        String repairResponse = mockMvc.perform(post("/api/repairs")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(repairPayload(customer.path("id").asLong(), List.of(partId), null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode repair = objectMapper.readTree(repairResponse).path("data");
        Long repairId = repair.path("id").asLong();
        repairsToCleanup.add(repairId);

        assertThat(partRepository.findById(partId).orElseThrow().getQuantity()).isEqualTo(3);
        assertBalance(partId, warehouseId, 3);

        mockMvc.perform(put("/api/repairs/{id}", repairId)
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(repairPayload(customer.path("id").asLong(), List.of(), repair.path("version").asLong()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(partRepository.findById(partId).orElseThrow().getQuantity()).isEqualTo(4);
        assertBalance(partId, warehouseId, 4);
        assertMovementLine(partId, 1, 3, 4);
        assertThat(operationAuditLogRepository.findAll()).anySatisfy(log -> {
            assertThat(log.getModule()).isEqualTo("Repair");
            assertThat(log.getAction()).isEqualTo("UPDATE");
            assertThat(log.getTargetType()).isEqualTo("REPAIR");
            assertThat(log.getTargetId()).isEqualTo(repairId);
        });
    }

    private void assertRepairUseLine(StockMovementLine line) {
        assertThat(line.getQuantityDelta()).isEqualTo(-1);
        assertThat(line.getBeforeQuantity()).isEqualTo(4);
        assertThat(line.getAfterQuantity()).isEqualTo(3);
    }

    private void assertMovementLine(Long partId, int delta, int before, int after) {
        assertThat(stockMovementLineRepository
                .findByResourceTypeAndResourceIdOrderByCreatedAtDesc(StockLedgerService.RESOURCE_PART, partId))
                .anySatisfy(line -> {
                    assertThat(line.getQuantityDelta()).isEqualTo(delta);
                    assertThat(line.getBeforeQuantity()).isEqualTo(before);
                    assertThat(line.getAfterQuantity()).isEqualTo(after);
                });
    }

    private void assertBalance(Long partId, Long warehouseId, int quantity) {
        StockBalance balance = stockBalanceRepository
                .findByResourceTypeAndResourceIdAndWarehouseId(StockLedgerService.RESOURCE_PART, partId, warehouseId)
                .orElseThrow();
        assertThat(balance.getAvailableQuantity()).isEqualTo(quantity);
    }

    private Map<String, Object> repairPayload(Long customerId, List<Long> usedPartIds, Long version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (version != null) {
            payload.put("version", version);
        }
        payload.put("repairDate", LocalDateTime.of(2030, 6, 10, 9, 30).toString());
        payload.put("customerId", customerId);
        payload.put("faultDescription", "Repair ledger restore test");
        payload.put("repairContent", "Replace test part");
        payload.put("repairPersonChoice", "OTHER");
        List<Map<String, Object>> partUsages = new ArrayList<>();
        for (Long partId : usedPartIds) {
            PartInventory part = partRepository.findById(partId).orElseThrow();
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("partId", partId);
            usage.put("warehouseId", part.getWarehouseId());
            usage.put("quantity", 1);
            usage.put("chargeUnitPrice", "0.00");
            usage.put("discountAmount", "0.00");
            partUsages.add(usage);
        }
        payload.put("partUsages", partUsages);
        payload.put("repairFee", "100.00");
        payload.put("repairExpense", "0.00");
        payload.put("partsFee", "0.00");
        payload.put("status", "COMPLETED");
        return payload;
    }

    private void assertMoney(JsonNode node, String expected) {
        assertThat(node.decimalValue()).isEqualByComparingTo(new BigDecimal(expected));
    }

    private JsonNode createCustomer() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("companyName", "维修统计客户-" + unique("customer"));
        payload.put("address", "广东省佛山市测试路 1 号");
        payload.put("contactName", "陈经理");
        payload.put("contactPhone", "13800138000");

        String response = mockMvc.perform(post("/api/customers")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode customer = objectMapper.readTree(response).path("data");
        customersToCleanup.add(customer.path("id").asLong());
        return customer;
    }

    private JsonNode createPart() throws Exception {
        String partCode = "RP-PART-" + unique("part");
        partsToCleanup.add(partCode);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("partCode", partCode);
        payload.put("partName", "维修测试配件");
        payload.put("partCategory", "测试配件");
        payload.put("quantity", 4);
        payload.put("unit", "件");
        payload.put("purchasePrice", "80.00");
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
