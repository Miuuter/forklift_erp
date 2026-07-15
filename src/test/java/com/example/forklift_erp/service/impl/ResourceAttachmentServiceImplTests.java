package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.entity.ResourceAttachment;
import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.OutboundOrderRepository;
import com.example.forklift_erp.repository.ResourceAttachmentRepository;
import com.example.forklift_erp.service.OperationAuditService;
import com.example.forklift_erp.service.ResourceVisibilityPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourceAttachmentServiceImplTests {
    Path tempDir;

    private ResourceAttachmentRepository attachmentRepository;
    private OutboundOrderRepository outboundOrderRepository;
    private ResourceAttachmentContextResolver contextResolver;
    private ResourceAttachmentServiceImpl service;

    @BeforeEach
    void setUp() throws IOException {
        Path root = Path.of("target", "resource-attachment-service-tests");
        Files.createDirectories(root);
        tempDir = Files.createTempDirectory(root, "case-");
        attachmentRepository = mock(ResourceAttachmentRepository.class);
        contextResolver = mock(ResourceAttachmentContextResolver.class);
        service = new ResourceAttachmentServiceImpl();
        ResourceAttachmentStorage storage = new ResourceAttachmentStorage(
                new FileStorageSupport(),
                tempDir.resolve("attachments").toString(),
                tempDir.resolve("invoices").toString(),
                tempDir.resolve("contracts").toString()
        );

        ReflectionTestUtils.setField(service, "attachmentRepository", attachmentRepository);
        outboundOrderRepository = mock(OutboundOrderRepository.class);
        ReflectionTestUtils.setField(service, "outboundOrderRepository", outboundOrderRepository);
        ReflectionTestUtils.setField(service, "operationAuditService", mock(OperationAuditService.class));
        ReflectionTestUtils.setField(service, "attachmentStorage", storage);
        ReflectionTestUtils.setField(service, "uploadReadinessPolicy", mock(OutboundUploadReadinessPolicy.class));
        ReflectionTestUtils.setField(service, "permissionPolicy", new ResourceAttachmentPermissionPolicy());
        ReflectionTestUtils.setField(service, "contextResolver", contextResolver);
        ReflectionTestUtils.setField(service, "outboundSnapshotService", mock(OutboundOrderAttachmentSnapshotService.class));
        ReflectionTestUtils.setField(service, "visibilityPolicy", new ResourceVisibilityPolicy());

        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", "password", "PERM_vehicle:write")
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        deleteTempDir();
    }

    private void deleteTempDir() {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Best effort cleanup for files created during rollback lifecycle tests.
        }
    }

    @Test
    void uploadRollbackDeletesStoredFileWhenRepositorySaveFails() throws IOException {
        when(contextResolver.resolve("MACHINE", 1L))
                .thenReturn(new ResourceContext("MACHINE", 1L, "M-001", "Test machine", false));
        when(attachmentRepository.save(any(ResourceAttachment.class)))
                .thenThrow(new RuntimeException("database is down"));
        MockMultipartFile file = new MockMultipartFile(
                "files",
                "photo.jpg",
                "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}
        );

        TransactionSynchronizationManager.initSynchronization();
        assertThatThrownBy(() -> service.upload("MACHINE", 1L, "PHOTO", null, null, new MockMultipartFile[]{file}))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("database is down");
        var synchronizations = new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
        TransactionSynchronizationManager.clearSynchronization();
        synchronizations.forEach(synchronization ->
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        try (var files = Files.walk(tempDir)) {
            assertThat(files.filter(Files::isRegularFile).toList()).isEmpty();
        }
    }

    @Test
    void downloadMissingPhysicalFileReturnsBusinessNotFound() {
        ResourceAttachment attachment = new ResourceAttachment();
        attachment.setId(7L);
        attachment.setResourceType("MACHINE");
        attachment.setResourceId(1L);
        attachment.setStorageScope("ATTACHMENT");
        attachment.setStoredFileName("missing.pdf");
        attachment.setOriginalName("missing.pdf");
        attachment.setAttachmentCategory("OTHER");
        when(attachmentRepository.findByIdAndDeletedFalse(7L)).thenReturn(Optional.of(attachment));
        when(contextResolver.resolve("MACHINE", 1L))
                .thenReturn(new ResourceContext("MACHINE", 1L, "M-001", "Test machine", false));

        assertThatThrownBy(() -> service.download(7L, false))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(ResultCode.NOT_FOUND.getCode());
                    assertThat(error.getMessage()).isEqualTo("Attachment file not found");
                });
    }

    @Test
    void legacyBackfillSkipsMetadataWhenPhysicalFileIsMissing() {
        OutboundOrder order = legacyInvoiceOrder("missing.pdf");
        when(outboundOrderRepository.findLegacyAttachmentBackfillCandidates(any()))
                .thenReturn(java.util.List.of(order));
        when(attachmentRepository.findFirstByResourceTypeAndResourceIdAndAttachmentCategoryAndDeletedFalseOrderByUploadedAtDesc(
                "OUTBOUND_ORDER", 11L, "INVOICE"))
                .thenReturn(Optional.empty());

        service.backfillLegacyOutboundAttachments();

        verify(attachmentRepository, never()).save(any(ResourceAttachment.class));
    }

    @Test
    void legacyBackfillPersistsMetadataWhenPhysicalFileExists() throws IOException {
        Path invoiceDir = tempDir.resolve("invoices");
        Files.createDirectories(invoiceDir);
        Files.writeString(invoiceDir.resolve("existing.pdf"), "pdf");
        OutboundOrder order = legacyInvoiceOrder("existing.pdf");
        when(outboundOrderRepository.findLegacyAttachmentBackfillCandidates(any()))
                .thenReturn(java.util.List.of(order), java.util.List.of());
        when(attachmentRepository.findFirstByResourceTypeAndResourceIdAndAttachmentCategoryAndDeletedFalseOrderByUploadedAtDesc(
                "OUTBOUND_ORDER", 11L, "INVOICE"))
                .thenReturn(Optional.empty());
        when(attachmentRepository.save(any(ResourceAttachment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.backfillLegacyOutboundAttachments();

        verify(attachmentRepository).save(any(ResourceAttachment.class));
    }

    private OutboundOrder legacyInvoiceOrder(String storedFileName) {
        OutboundOrder order = new OutboundOrder();
        order.setId(11L);
        order.setOrderNo("OO-011");
        order.setCustomerName("Test customer");
        order.setInvoiceStoredFileName(storedFileName);
        order.setInvoiceOriginalName("invoice.pdf");
        order.setInvoiceContentType("application/pdf");
        order.setInvoiceFileSize(3L);
        return order;
    }
}
