package com.example.forklift_erp;

import com.example.forklift_erp.entity.Role;
import com.example.forklift_erp.entity.User;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.RoleRepository;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CollaborationIntegrationTests extends TestcontainersDatabaseSupport {

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
    private PasswordEncoder passwordEncoder;

    private final List<String> vehiclesToCleanup = new ArrayList<>();
    private String superToken;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        String superUsername = unique("super");
        String adminUsername = unique("admin");
        createUserDirectly(superUsername, "SUPER_ADMIN");
        createUserDirectly(adminUsername, "ADMIN");
        superToken = login(superUsername);
        adminToken = login(adminUsername);
    }

    @AfterEach
    void tearDown() {
        for (String vehicleProductNumber : vehiclesToCleanup.reversed()) {
            machineRepository.findByVehicleProductNumber(vehicleProductNumber)
                    .ifPresent(machineRepository::delete);
        }
        vehiclesToCleanup.clear();

    }

    @Test
    void staleWritesAreResolvedByRolePriority() throws Exception {
        String vehicleProductNumber = "COLLAB-" + unique("vehicle");
        vehiclesToCleanup.add(vehicleProductNumber);

        JsonNode created = performCreateVehicle(vehicleProductNumber, "协同测试车", superToken);
        Long id = created.path("id").asLong();
        Long originalVersion = created.path("version").asLong();

        JsonNode adminUpdated = performUpdateVehicle(id, vehicleProductNumber, "管理员更新", originalVersion, adminToken)
                .path("data");
        assertThat(adminUpdated.path("version").asLong()).isGreaterThan(originalVersion);

        JsonNode superUpdated = performUpdateVehicle(id, vehicleProductNumber, "超级管理员旧版本覆盖", originalVersion, superToken)
                .path("data");
        assertThat(superUpdated.path("name").asText()).isEqualTo("超级管理员旧版本覆盖");

        mockMvc.perform(put("/api/inventory/{id}", id)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(vehiclePayload(vehicleProductNumber, "管理员旧版本被拒绝", originalVersion))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));

        assertThat(machineRepository.findById(id).orElseThrow().getLastModifiedPriority()).isEqualTo(300);
    }

    @Test
    void updateWithoutVersionReturnsConflict() throws Exception {
        String vehicleProductNumber = "COLLAB-" + unique("missing-version");
        vehiclesToCleanup.add(vehicleProductNumber);

        JsonNode created = performCreateVehicle(vehicleProductNumber, "Missing version test", superToken);
        Long id = created.path("id").asLong();

        mockMvc.perform(put("/api/inventory/{id}", id)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(vehiclePayload(vehicleProductNumber, "No version update", null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    private JsonNode performCreateVehicle(String vehicleProductNumber, String name, String token) throws Exception {
        String response = mockMvc.perform(post("/api/inventory")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(vehiclePayload(vehicleProductNumber, name, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private JsonNode performUpdateVehicle(
            Long id,
            String vehicleProductNumber,
            String name,
            Long version,
            String token
    ) throws Exception {
        String response = mockMvc.perform(put("/api/inventory/{id}", id)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(vehiclePayload(vehicleProductNumber, name, version))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private Map<String, Object> vehiclePayload(String vehicleProductNumber, String name, Long version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (version != null) {
            payload.put("version", version);
        }
        payload.put("vehicleProductNumber", vehicleProductNumber);
        payload.put("name", name);
        payload.put("specificationModel", "CPD25");
        payload.put("machineType", "TEST");
        payload.put("warehouseId", defaultWarehouseId());
        payload.put("inventoryCount", 1);
        return payload;
    }

}
