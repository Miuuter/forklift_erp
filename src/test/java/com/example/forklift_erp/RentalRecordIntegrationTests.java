package com.example.forklift_erp;

import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.RentalRecord;
import com.example.forklift_erp.entity.Permission;
import com.example.forklift_erp.entity.Role;
import com.example.forklift_erp.entity.User;
import com.example.forklift_erp.repository.CustomerRepository;
import com.example.forklift_erp.repository.FinancialEventRepository;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.OutboundOrderRepository;
import com.example.forklift_erp.repository.PermissionRepository;
import com.example.forklift_erp.repository.RentalBillRepository;
import com.example.forklift_erp.repository.RentalRecordRepository;
import com.example.forklift_erp.repository.RoleRepository;
import com.example.forklift_erp.repository.UserRepository;
import com.example.forklift_erp.service.FinancialEventService;
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

import java.util.ArrayList;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "forklift.seed-demo-data.enabled=false",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false"
})
@AutoConfigureMockMvc
class RentalRecordIntegrationTests extends TestcontainersDatabaseSupport {
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
    private PermissionRepository permissionRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private MachineInventoryRepository machineRepository;

    @Autowired
    private RentalRecordRepository rentalRecordRepository;

    @Autowired
    private RentalBillRepository rentalBillRepository;

    @Autowired
    private FinancialEventRepository financialEventRepository;

    @Autowired
    private OutboundOrderRepository outboundOrderRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final List<Long> ordersToCleanup = new ArrayList<>();
    private final List<Long> rentalsToCleanup = new ArrayList<>();
    private final List<Long> machinesToCleanup = new ArrayList<>();
    private final List<Long> customersToCleanup = new ArrayList<>();

    private String superToken;

    @BeforeEach
    void setUp() throws Exception {
        String username = unique("super");
        createUserDirectly(username, "SUPER_ADMIN");
        superToken = login(username);
    }

    @AfterEach
    void tearDown() {
        for (Long orderId : ordersToCleanup.reversed()) {
            outboundOrderRepository.findById(orderId).ifPresent(outboundOrderRepository::delete);
        }
        ordersToCleanup.clear();

        for (Long rentalId : rentalsToCleanup.reversed()) {
            deleteRentalBillingFacts(rentalId);
            rentalRecordRepository.findById(rentalId).ifPresent(rentalRecordRepository::delete);
        }
        rentalsToCleanup.clear();

        for (Long machineId : machinesToCleanup.reversed()) {
            machineRepository.findById(machineId).ifPresent(machineRepository::delete);
        }
        machinesToCleanup.clear();

        for (Long customerId : customersToCleanup.reversed()) {
            customerRepository.findById(customerId).ifPresent(customerRepository::delete);
        }
        customersToCleanup.clear();

    }

    private void deleteRentalBillingFacts(Long rentalId) {
        var bills = rentalBillRepository.findByRentalIdOrderByBillPeriodAsc(rentalId);
        var financialEventIds = bills.stream()
                .flatMap(bill -> financialEventRepository
                        .findBySourceTypeAndSourceIdOrderByIdAsc(
                                FinancialEventService.SOURCE_RENTAL_BILL,
                                bill.getId()
                        )
                        .stream())
                .map(event -> event.getId())
                .toList();
        rentalBillRepository.deleteAllInBatch(bills);
        financialEventRepository.deleteAllByIdInBatch(financialEventIds);
    }

    @Test
    void activeRentalBlocksVehicleSaleUntilReturned() throws Exception {
        JsonNode customer = createCustomer("租赁客户");
        JsonNode machine = createMachine("RT-BLOCK");
        JsonNode rental = createRental(customer, machine, "佛山仓外租赁点");

        Map<String, Object> outboundPayload = vehicleOutboundPayload(customer, machine);
        mockMvc.perform(post("/api/outbound-orders/vehicle")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(outboundPayload)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("车辆正在租赁中，不能创建销售出库订单"));

        Map<String, Object> returnPayload = new LinkedHashMap<>();
        returnPayload.put("version", rental.path("version").asLong());
        returnPayload.put("customerId", customer.path("id").asLong());
        returnPayload.put("destination", "已归还仓库");
        returnPayload.put("monthlyRentalPrice", "3200.00");
        returnPayload.put("startDate", "2026-05-20");
        returnPayload.put("endDate", "2026-05-27");
        returnPayload.put("status", "RETURNED");
        returnPayload.put("operator", "rental-test");

        mockMvc.perform(put("/api/rentals/{id}", rental.path("id").asLong())
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(returnPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RETURNED"));

        outboundPayload.put("machineVersion", machineRepository.findById(machine.path("id").asLong())
                .orElseThrow()
                .getVersion());
        String orderResponse = mockMvc.perform(post("/api/outbound-orders/vehicle")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(outboundPayload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.resourceType").value("MACHINE"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode order = objectMapper.readTree(orderResponse).path("data");
        ordersToCleanup.add(order.path("id").asLong());

        MachineInventory savedMachine = machineRepository.findById(machine.path("id").asLong()).orElseThrow();
        assertThat(savedMachine.getInventoryCount()).isZero();
        assertThat(savedMachine.getStockStatus()).isEqualTo("OUTBOUND");
    }

    @Test
    void rentalListUsesPagedSearchAndKeepsPermissionBoundary() throws Exception {
        JsonNode customer = createCustomer("分页租赁客户");
        JsonNode matchingMachine = createMachine("RT-PAGE-A");
        JsonNode otherMachine = createMachine("RT-PAGE-B");
        createRental(customer, matchingMachine, "南海目标租赁点");
        createRental(customer, otherMachine, "顺德普通租赁点");

        mockMvc.perform(get("/api/rentals")
                        .param("paged", "true")
                        .param("page", "0")
                        .param("size", "1")
                        .param("keyword", "南海目标")
                        .header("Authorization", bearer(superToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].destination").value("南海目标租赁点"));

        String deniedUser = unique("denied");
        createUserWithPermissions(deniedUser, "ROLE_NO_RENTAL_" + unique("role"));
        String deniedToken = login(deniedUser);

        mockMvc.perform(get("/api/rentals")
                        .header("Authorization", bearer(deniedToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void dueSoonFilterAppliesToPageTotalAndListSummary() throws Exception {
        String marker = unique("due-soon");
        JsonNode customer = createCustomer("Due soon customer");
        JsonNode overdueMachine = createMachine("RT-DUE-A");
        JsonNode dueMachine = createMachine("RT-DUE-B");
        JsonNode futureMachine = createMachine("RT-DUE-C");
        JsonNode overdue = createRental(customer, overdueMachine, marker + " overdue");
        JsonNode due = createRental(customer, dueMachine, marker + " due");
        JsonNode future = createRental(customer, futureMachine, marker + " future");
        setRentalEndDate(overdue, LocalDate.now().minusDays(1));
        setRentalEndDate(due, LocalDate.now().plusDays(3));
        setRentalEndDate(future, LocalDate.now().plusDays(20));

        mockMvc.perform(get("/api/rentals")
                        .param("paged", "true")
                        .param("page", "0")
                        .param("size", "1")
                        .param("keyword", marker)
                        .param("status", "dueSoon")
                        .header("Authorization", bearer(superToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content.length()").value(1));

        mockMvc.perform(get("/api/statistics/list-summary")
                        .param("type", "rentals")
                        .param("keyword", marker)
                        .param("status", "dueSoon")
                        .header("Authorization", bearer(superToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cards[0].value").value(2));
    }

    @Test
    void longIdleFilterReturnsOnlyOldAvailableUnrentedVehicles() throws Exception {
        String modelName = unique("Long idle model");
        String modelSpec = unique("IDLE-SPEC");
        JsonNode customer = createCustomer("Long idle customer");
        JsonNode oldMachine = createMachine("IDLE-OLD");
        JsonNode recentMachine = createMachine("IDLE-RECENT");
        JsonNode rentedMachine = createMachine("IDLE-RENTED");
        createRental(customer, rentedMachine, "active long-idle exclusion");
        updateMachineAgeAndModel(oldMachine, modelName, modelSpec, LocalDateTime.now().minusDays(100));
        updateMachineAgeAndModel(recentMachine, modelName, modelSpec, LocalDateTime.now().minusDays(20));
        updateMachineAgeAndModel(rentedMachine, modelName, modelSpec, LocalDateTime.now().minusDays(100));

        mockMvc.perform(get("/api/inventory/models")
                        .param("stock", "longIdle")
                        .param("keyword", "IDLE-")
                        .param("page", "0")
                        .param("size", "20")
                        .header("Authorization", bearer(superToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].inventoryCount").value(1))
                .andExpect(jsonPath("$.data.content[0].vehicleNumbers").value(oldMachine.path("vehicleProductNumber").asText()));

        mockMvc.perform(get("/api/inventory/model-vehicles")
                        .param("name", modelName)
                        .param("specificationModel", modelSpec)
                        .param("machineType", "内燃叉车")
                        .param("stock", "longIdle")
                        .header("Authorization", bearer(superToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].vehicleProductNumber").value(oldMachine.path("vehicleProductNumber").asText()));
    }

    private void setRentalEndDate(JsonNode rentalNode, LocalDate endDate) {
        RentalRecord rental = rentalRecordRepository.findById(rentalNode.path("id").asLong()).orElseThrow();
        rental.setEndDate(endDate);
        rentalRecordRepository.saveAndFlush(rental);
    }

    private void updateMachineAgeAndModel(JsonNode machineNode, String name, String specificationModel,
                                          LocalDateTime inboundDate) {
        MachineInventory machine = machineRepository.findById(machineNode.path("id").asLong()).orElseThrow();
        machine.setName(name);
        machine.setSpecificationModel(specificationModel);
        machine.setInboundDate(inboundDate);
        machineRepository.saveAndFlush(machine);
    }

    private JsonNode createRental(JsonNode customer, JsonNode machine, String destination) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("machineId", machine.path("id").asLong());
        payload.put("machineVersion", machine.path("version").asLong());
        payload.put("warehouseId", machine.path("warehouseId").asLong());
        payload.put("customerId", customer.path("id").asLong());
        payload.put("destination", destination);
        payload.put("monthlyRentalPrice", "3200.00");
        payload.put("startDate", "2026-05-20");
        payload.put("operator", "rental-test");
        payload.put("remark", "租赁集成测试");

        String response = mockMvc.perform(post("/api/rentals")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.destination").value(destination))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode rental = objectMapper.readTree(response).path("data");
        rentalsToCleanup.add(rental.path("id").asLong());
        return rental;
    }

    private Map<String, Object> vehicleOutboundPayload(JsonNode customer, JsonNode machine) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("machineId", machine.path("id").asLong());
        payload.put("machineVersion", machine.path("version").asLong());
        payload.put("warehouseId", machine.path("warehouseId").asLong());
        payload.put("customerId", customer.path("id").asLong());
        payload.put("salesDate", "2026-05-27");
        payload.put("settlementPrice", "118000.00");
        payload.put("operator", "rental-sale-test");
        payload.put("orderRemark", "租赁归还后销售测试");
        return payload;
    }

    private JsonNode createCustomer(String companyName) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("companyName", companyName + "-" + unique("customer"));
        payload.put("address", "广东省佛山市测试路 1 号");
        payload.put("contactName", "陈经理");
        payload.put("contactPhone", "13800138000");
        payload.put("taxOrIdNumber", "91440000TEST");

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

    private JsonNode createMachine(String prefix) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("vehicleProductNumber", prefix + "-" + unique("machine"));
        payload.put("name", "租赁测试车型");
        payload.put("specificationModel", "CPCD30");
        payload.put("configuration", "国四 / 二节 3 米 / 1220 货叉");
        payload.put("machineType", "内燃叉车");
        payload.put("supplier", "Codex Supplier");
        payload.put("applicationNumber", "APP-" + unique("apply"));
        payload.put("materialNumber", "MAT-" + unique("material"));
        payload.put("inventoryCount", 1);
        payload.put("warehouseId", defaultWarehouseId());
        payload.put("remarks", "租赁集成测试数据");

        String response = mockMvc.perform(post("/api/inventory")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode machine = objectMapper.readTree(response).path("data");
        machinesToCleanup.add(machine.path("id").asLong());
        return machine;
    }

}
