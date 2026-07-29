package com.example.forklift_erp;

import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.RepairRecord;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.OutboundOrderRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.RepairRecordRepository;
import com.example.forklift_erp.repository.ResourceAttachmentRepository;
import com.example.forklift_erp.security.PermissionCodes;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AttachmentVisibilityIntegrationTests extends TestcontainersDatabaseSupport {
    private static final Path ATTACHMENT_STORAGE_DIR = Paths.get("target", "attachment-test-files", UUID.randomUUID().toString());

    @DynamicPropertySource
    static void registerAttachmentStorage(DynamicPropertyRegistry registry) {
        registry.add("forklift-erp.attachment-storage-dir", () -> ATTACHMENT_STORAGE_DIR.toString());
    }

    @jakarta.annotation.Resource
    private MachineInventoryRepository machineRepository;

    @jakarta.annotation.Resource
    private PartInventoryRepository partRepository;

    @jakarta.annotation.Resource
    private RepairRecordRepository repairRepository;

    @jakarta.annotation.Resource
    private OutboundOrderRepository outboundOrderRepository;

    @jakarta.annotation.Resource
    private ResourceAttachmentRepository attachmentRepository;

    private final List<Long> attachmentIdsToCleanup = new ArrayList<>();
    private final List<Long> machinesToCleanup = new ArrayList<>();
    private final List<Long> partsToCleanup = new ArrayList<>();
    private final List<Long> repairsToCleanup = new ArrayList<>();
    private final List<Long> ordersToCleanup = new ArrayList<>();
    private String superToken;
    private String userToken;

    @BeforeEach
    void setUp() throws Exception {
        String superUsername = unique("super");
        String userUsername = unique("attachment-user");
        createUserDirectly(superUsername, "SUPER_ADMIN");
        createUserWithPermissions(
                userUsername,
                unique("attachment-role"),
                PermissionCodes.VEHICLE_WRITE,
                PermissionCodes.PART_WRITE,
                PermissionCodes.REPAIR_WRITE,
                PermissionCodes.STOCK_ADJUST
        );
        superToken = login(superUsername);
        userToken = login(userUsername);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Long attachmentId : attachmentIdsToCleanup.reversed()) {
            attachmentRepository.findById(attachmentId).ifPresent(attachmentRepository::delete);
        }
        attachmentIdsToCleanup.clear();

        for (Long orderId : ordersToCleanup.reversed()) {
            outboundOrderRepository.findById(orderId).ifPresent(outboundOrderRepository::delete);
        }
        ordersToCleanup.clear();

        for (Long repairId : repairsToCleanup.reversed()) {
            repairRepository.findById(repairId).ifPresent(repairRepository::delete);
        }
        repairsToCleanup.clear();

        for (Long partId : partsToCleanup.reversed()) {
            partRepository.findById(partId).ifPresent(partRepository::delete);
        }
        partsToCleanup.clear();

        for (Long machineId : machinesToCleanup.reversed()) {
            machineRepository.findById(machineId).ifPresent(machineRepository::delete);
        }
        machinesToCleanup.clear();

        if (Files.exists(ATTACHMENT_STORAGE_DIR)) {
            try (var paths = Files.walk(ATTACHMENT_STORAGE_DIR)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    void normalUserCannotSeeOrMutateAttachmentsForLockedVehicle() throws Exception {
        JsonNode machine = createMachine();
        Long machineId = machine.path("id").asLong();
        UploadedAttachment attachment = uploadAttachment("MACHINE", machineId);

        mockMvc.perform(put("/api/inventory/{id}/lock", machineId)
                        .header("Authorization", bearer(superToken))
                        .param("locked", "true")
                        .param("version", machine.path("version").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertNormalUserCannotSeeOrMutateLockedAttachment("MACHINE", machineId, attachment);
    }

    @Test
    void normalUserCannotSeeOrMutateAttachmentsForLockedPart() throws Exception {
        Long partId = createPart();
        UploadedAttachment attachment = uploadAttachment("PART", partId);
        partRepository.findById(partId).ifPresent(part -> {
            part.setIsLocked(true);
            partRepository.saveAndFlush(part);
        });

        assertNormalUserCannotSeeOrMutateLockedAttachment("PART", partId, attachment);
    }

    @Test
    void normalUserCannotSeeOrMutateAttachmentsForLockedRepair() throws Exception {
        Long repairId = createRepair();
        UploadedAttachment attachment = uploadAttachment("REPAIR", repairId);
        repairRepository.findById(repairId).ifPresent(repair -> {
            repair.setIsLocked(true);
            repairRepository.saveAndFlush(repair);
        });

        assertNormalUserCannotSeeOrMutateLockedAttachment("REPAIR", repairId, attachment);
    }

    @Test
    void normalUserCannotSeeOrMutateAttachmentsForLockedOutboundOrder() throws Exception {
        Long orderId = createOutboundOrder();
        UploadedAttachment attachment = uploadAttachment("OUTBOUND_ORDER", orderId);
        outboundOrderRepository.findById(orderId).ifPresent(order -> {
            order.setIsLocked(true);
            outboundOrderRepository.saveAndFlush(order);
        });

        assertNormalUserCannotSeeOrMutateLockedAttachment("OUTBOUND_ORDER", orderId, attachment);
    }

    private void assertNormalUserCannotSeeOrMutateLockedAttachment(
            String resourceType,
            Long resourceId,
            UploadedAttachment attachment
    ) throws Exception {
        String allAttachments = mockMvc.perform(get("/api/attachments")
                        .header("Authorization", bearer(userToken))
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(allAttachments).doesNotContain(attachment.originalName());

        mockMvc.perform(get("/api/attachments")
                        .header("Authorization", bearer(userToken))
                        .param("resourceType", resourceType)
                        .param("resourceId", String.valueOf(resourceId))
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));

        mockMvc.perform(get("/api/attachments/resource")
                        .header("Authorization", bearer(userToken))
                        .param("resourceType", resourceType)
                        .param("resourceId", String.valueOf(resourceId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(get("/api/attachments/{id}/download", attachment.id())
                        .header("Authorization", bearer(userToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(delete("/api/attachments/{id}", attachment.id())
                        .header("Authorization", bearer(userToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        MockMultipartFile file = new MockMultipartFile(
                "files",
                "locked.jpg",
                "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}
        );
        mockMvc.perform(multipart("/api/attachments")
                        .file(file)
                        .header("Authorization", bearer(userToken))
                        .param("resourceType", resourceType)
                        .param("resourceId", String.valueOf(resourceId))
                        .param("category", "PHOTO"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    private JsonNode createMachine() throws Exception {
        String vehicleProductNumber = "ATT-" + unique("vehicle");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("vehicleProductNumber", vehicleProductNumber);
        payload.put("name", "Attachment visibility vehicle");
        payload.put("specificationModel", "CPD25");
        payload.put("machineType", "TEST");
        payload.put("inventoryCount", 1);

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

    private Long createPart() {
        PartInventory part = new PartInventory();
        part.setPartCode("ATT-PART-" + unique("part"));
        part.setPartName("Attachment visibility part");
        part.setQuantity(1);
        part.setWarehouseId(defaultWarehouseId());
        PartInventory saved = partRepository.saveAndFlush(part);
        partsToCleanup.add(saved.getId());
        return saved.getId();
    }

    private Long createRepair() {
        RepairRecord repair = new RepairRecord();
        repair.setRepairDate(LocalDateTime.now());
        repair.setCustomerName("Attachment visibility customer");
        repair.setFaultDescription("Attachment visibility fault");
        RepairRecord saved = repairRepository.saveAndFlush(repair);
        repairsToCleanup.add(saved.getId());
        return saved.getId();
    }

    private Long createOutboundOrder() {
        OutboundOrder order = new OutboundOrder();
        order.setOrderNo("ATT-ORDER-" + unique("order"));
        order.setResourceType("PART");
        order.setResourceCode("ATT-ORDER-RESOURCE");
        order.setResourceName("Attachment visibility outbound resource");
        order.setQuantity(1);
        order.setCustomerName("Attachment visibility customer");
        OutboundOrder saved = outboundOrderRepository.saveAndFlush(order);
        ordersToCleanup.add(saved.getId());
        return saved.getId();
    }

    private UploadedAttachment uploadAttachment(String resourceType, Long resourceId) throws Exception {
        String originalName = "visible-" + resourceType.toLowerCase(Locale.ROOT) + "-" + unique("file") + ".jpg";
        MockMultipartFile file = new MockMultipartFile(
                "files",
                originalName,
                "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}
        );
        String response = mockMvc.perform(multipart("/api/attachments")
                        .file(file)
                        .header("Authorization", bearer(superToken))
                        .param("resourceType", resourceType)
                        .param("resourceId", String.valueOf(resourceId))
                        .param("category", "PHOTO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long attachmentId = objectMapper.readTree(response).path("data").get(0).path("id").asLong();
        attachmentIdsToCleanup.add(attachmentId);
        return new UploadedAttachment(attachmentId, originalName);
    }

    private record UploadedAttachment(Long id, String originalName) {
    }
}
