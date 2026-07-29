package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.PageResult;
import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.dto.ResourceAttachmentDownload;
import com.example.forklift_erp.dto.ResourceAttachmentVO;
import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.entity.ResourceAttachment;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.OutboundOrderRepository;
import com.example.forklift_erp.repository.ResourceAttachmentRepository;
import com.example.forklift_erp.service.OperationAuditService;
import com.example.forklift_erp.service.ResourceVisibilityPolicy;
import com.example.forklift_erp.service.ResourceAttachmentService;
import com.example.forklift_erp.util.ListPageSupport;
import com.example.forklift_erp.util.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
public class ResourceAttachmentServiceImpl implements ResourceAttachmentService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ResourceAttachmentServiceImpl.class);
    private static final int LEGACY_BACKFILL_BATCH_SIZE = 100;

    @Autowired
    private ResourceAttachmentRepository attachmentRepository;

    @Autowired
    private OutboundOrderRepository outboundOrderRepository;

    @Autowired
    private OperationAuditService operationAuditService;

    @Autowired
    private ResourceAttachmentStorage attachmentStorage;

    @Autowired
    private OutboundUploadReadinessPolicy uploadReadinessPolicy;

    @Autowired
    private ResourceAttachmentPermissionPolicy permissionPolicy;

    @Autowired
    private ResourceAttachmentContextResolver contextResolver;

    @Autowired
    private OutboundOrderAttachmentSnapshotService outboundSnapshotService;

    @Autowired
    private ResourceVisibilityPolicy visibilityPolicy;

    @Override
    @Transactional(readOnly = true)
    public PageResult<ResourceAttachmentVO> findPage(String resourceType, Long resourceId, String category,
                                                     String keyword, boolean includeDeleted, Integer page, Integer size) {
        if (includeDeleted && !SecurityUtils.isAdminOrSuperAdmin()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "Only admins can view deleted attachments");
        }
        String normalizedType = trimToNull(resourceType) == null ? null : permissionPolicy.normalizeResourceType(resourceType);
        permissionPolicy.ensureAttachmentListPermission(normalizedType);
        int pageNumber = ListPageSupport.page(page);
        int pageSize = ListPageSupport.size(size);
        Page<ResourceAttachment> result = attachmentRepository.search(
                trimToNull(normalizedType),
                resourceId,
                trimToNull(category),
                trimToNull(keyword),
                includeDeleted,
                visibilityPolicy.canSeeLockedResources(),
                ListPageSupport.pageRequest(page, size, Sort.by(Sort.Direction.DESC, "uploadedAt", "id"))
        );
        return PageResult.of(result.getContent().stream().map(ResourceAttachmentVO::fromEntity).toList(),
                pageNumber, pageSize, result.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResourceAttachmentVO> findByResource(String resourceType, Long resourceId) {
        String normalizedType = permissionPolicy.normalizeResourceType(resourceType);
        permissionPolicy.ensureResourcePermission(normalizedType);
        ResourceContext context = contextResolver.resolve(normalizedType, resourceId);
        visibilityPolicy.ensureVisible(context.locked(), ResultCode.NOT_FOUND, hiddenMessage(normalizedType));
        return attachmentRepository.findByResourceTypeAndResourceIdAndDeletedFalseOrderByUploadedAtDesc(normalizedType, resourceId)
                .stream()
                .map(ResourceAttachmentVO::fromEntity)
                .toList();
    }

    @Override
    @Transactional
    public List<ResourceAttachmentVO> upload(String resourceType, Long resourceId, String category,
                                             String attachmentLabel, String uploadNote, MultipartFile[] files) {
        String normalizedType = permissionPolicy.normalizeResourceType(resourceType);
        String normalizedCategory = normalizeCategory(category);
        permissionPolicy.ensureResourcePermission(normalizedType);
        ResourceContext context = contextResolver.resolve(normalizedType, resourceId);
        visibilityPolicy.ensureWritable(context.locked(), lockedWriteMessage(normalizedType));
        if (isOutboundOrder(normalizedType) && isOrderManagedCategory(normalizedCategory)) {
            OutboundOrder order = outboundOrderRepository.findById(resourceId)
                    .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Outbound order not found"));
            visibilityPolicy.ensureWritable(order.getIsLocked(), lockedWriteMessage(normalizedType));
            if ("INVOICE".equals(normalizedCategory) && !uploadReadinessPolicy.isInvoiceUploadReady(order)) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "Order is not ready for invoice upload");
            }
            if ("CONTRACT".equals(normalizedCategory) && !uploadReadinessPolicy.isContractUploadReady(order)) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "Order is not ready for contract upload");
            }
        }
        MultipartFile[] payloadFiles = files == null ? new MultipartFile[0] : files;
        if (payloadFiles.length == 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请选择附件文件");
        }

        List<ResourceAttachmentVO> created = Arrays.stream(payloadFiles)
                .filter(file -> file != null && !file.isEmpty())
                .map(file -> storeAndPersist(normalizedType, normalizedCategory, context, attachmentLabel, uploadNote, file))
                .map(ResourceAttachmentVO::fromEntity)
                .toList();
        outboundSnapshotService.refreshIfOutboundOrder(normalizedType, resourceId);
        return created;
    }

    @Override
    @Transactional
    public ResourceAttachmentVO recordLegacyOrderAttachment(OutboundOrder order, String category, StoredOutboundFile storedFile) {
        if (order == null || order.getId() == null || storedFile == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Invalid order attachment");
        }
        ResourceContext context = new ResourceContext(
                "OUTBOUND_ORDER",
                order.getId(),
                order.getOrderNo(),
                order.getCustomerName()
        );
        ResourceAttachment attachment = persistExistingFile(
                context,
                normalizeCategory(category),
                defaultAttachmentLabel(category),
                order.getOrderRemark(),
                storedFile,
                chooseStorageScope(permissionPolicy.normalizeResourceType("OUTBOUND_ORDER"), category)
        );
        outboundSnapshotService.refresh(order.getId());
        return ResourceAttachmentVO.fromEntity(attachment);
    }

    @Override
    @Transactional(readOnly = true)
    public ResourceAttachmentDownload download(Long id, boolean inlinePreview) {
        ResourceAttachment attachment = attachmentRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Attachment not found"));
        permissionPolicy.ensureResourcePermission(attachment.getResourceType());
        ResourceContext context = contextResolver.resolve(attachment.getResourceType(), attachment.getResourceId());
        visibilityPolicy.ensureVisible(context.locked(), ResultCode.NOT_FOUND, "Attachment not found");
        Path filePath = attachmentStorage.resolveAttachmentPath(attachment);
        if (!Files.isRegularFile(filePath)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "Attachment file not found");
        }
        try {
            Resource resource = new UrlResource(filePath.toUri());
            String contentType = firstNonBlank(attachment.getContentType(), Files.probeContentType(filePath), "application/octet-stream");
            boolean previewable = Boolean.TRUE.equals(attachment.getPreviewable())
                    && attachmentStorage.isPreviewable(contentType, attachment.getOriginalName());
            if (inlinePreview && !previewable) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "This attachment type cannot be previewed inline");
            }
            return new ResourceAttachmentDownload(
                    resource,
                    firstNonBlank(attachment.getOriginalName(), attachment.getAttachmentLabel(), "attachment"),
                    contentType,
                    Files.size(filePath),
                    inlinePreview && previewable
            );
        } catch (IOException e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "Attachment download failed");
        }
    }

    @Override
    @Transactional
    public ResourceAttachmentVO delete(Long id, String reason) {
        ResourceAttachment attachment = attachmentRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Attachment not found"));
        permissionPolicy.ensureResourcePermission(attachment.getResourceType());
        ResourceContext context = contextResolver.resolve(attachment.getResourceType(), attachment.getResourceId());
        visibilityPolicy.ensureWritable(context.locked(), lockedWriteMessage(attachment.getResourceType()));
        attachment.setDeleted(true);
        attachment.setDeletedAt(LocalDateTime.now());
        attachment.setDeletedBy(SecurityUtils.currentUsername());
        attachment.setDeleteReason(trimToNull(reason));
        ResourceAttachment saved = attachmentRepository.save(attachment);
        outboundSnapshotService.refreshIfOutboundOrder(saved.getResourceType(), saved.getResourceId());
        Path filePath = attachmentStorage.resolveAttachmentPath(saved);
        attachmentStorage.registerAfterCommit(() -> attachmentStorage.deleteQuietly(filePath, "Failed to delete attachment file"));
        operationAuditService.record(
                "ATTACHMENT",
                "DELETE",
                saved.getResourceType(),
                saved.getResourceId(),
                saved.getResourceCode(),
                saved.getResourceName(),
                "Delete attachment: " + saved.getOriginalName(),
                saved.getDeletedBy(),
                saved.getDeleteReason()
        );
        return ResourceAttachmentVO.fromEntity(saved);
    }

    @Override
    @Transactional
    public void backfillLegacyOutboundAttachments() {
        int backfilled = 0;
        int page = 0;
        while (true) {
            List<OutboundOrder> candidates = outboundOrderRepository.findLegacyAttachmentBackfillCandidates(
                    PageRequest.of(page, LEGACY_BACKFILL_BATCH_SIZE)
            );
            if (candidates.isEmpty()) {
                break;
            }
            boolean changedInBatch = false;
            for (OutboundOrder order : candidates) {
                boolean changed = false;
                if (hasText(order.getInvoiceStoredFileName()) && !hasLegacyAttachment(order.getId(), "INVOICE")) {
                    changed = persistLegacyRow(order, "INVOICE", order.getInvoiceStoredFileName(), order.getInvoiceOriginalName(),
                            order.getInvoiceContentType(), order.getInvoiceFileSize(), order.getInvoiceUploadedAt()) || changed;
                }
                if (hasText(order.getContractStoredFileName()) && !hasLegacyAttachment(order.getId(), "CONTRACT")) {
                    changed = persistLegacyRow(order, "CONTRACT", order.getContractStoredFileName(), order.getContractOriginalName(),
                            order.getContractContentType(), order.getContractFileSize(), order.getContractUploadedAt()) || changed;
                }
                if (changed) {
                    outboundSnapshotService.refresh(order.getId());
                    backfilled++;
                    changedInBatch = true;
                }
            }
            if (!changedInBatch && candidates.size() < LEGACY_BACKFILL_BATCH_SIZE) {
                break;
            }
            page = changedInBatch ? 0 : page + 1;
        }
        if (backfilled > 0) {
            log.info("Legacy outbound attachment backfill completed for {} orders", backfilled);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpLegacyAttachments() {
        try {
            backfillLegacyOutboundAttachments();
        } catch (Exception error) {
            log.warn("Legacy attachment backfill skipped", error);
        }
    }

    private ResourceAttachment storeAndPersist(String resourceType, String category, ResourceContext context,
                                               String attachmentLabel, String uploadNote, MultipartFile file) {
        String storageScope = chooseStorageScope(resourceType, category);
        StoredAttachmentFile stored = attachmentStorage.storeFile(file, storageScope);
        attachmentStorage.registerRollbackCleanup(stored.filePath());

        ResourceAttachment attachment = new ResourceAttachment();
        attachment.setResourceType(resourceType);
        attachment.setResourceId(context.resourceId());
        attachment.setResourceCode(context.resourceCode());
        attachment.setResourceName(context.resourceName());
        attachment.setAttachmentCategory(category);
        attachment.setAttachmentLabel(trimToNull(attachmentLabel));
        attachment.setStorageScope(storageScope);
        attachment.setOriginalName(stored.originalName());
        attachment.setStoredFileName(stored.storedFileName());
        attachment.setContentType(stored.contentType());
        attachment.setFileSize(stored.fileSize());
        attachment.setFileExtension(stored.fileExtension());
        attachment.setPreviewable(stored.previewable());
        attachment.setUploadNote(trimToNull(uploadNote));
        attachment.setUploadedBy(SecurityUtils.currentUsername());
        attachment.setDeleted(false);
        ResourceAttachment saved = attachmentRepository.save(attachment);
        operationAuditService.record(
                "ATTACHMENT",
                "UPLOAD",
                resourceType,
                context.resourceId(),
                context.resourceCode(),
                context.resourceName(),
                "Upload attachment: " + saved.getOriginalName(),
                saved.getUploadedBy(),
                saved.getUploadNote()
        );
        if (isOutboundOrder(resourceType) && isOrderManagedCategory(category)) {
            outboundSnapshotService.refresh(context.resourceId());
        }
        return saved;
    }

    private ResourceAttachment persistExistingFile(ResourceContext context, String category, String label, String note,
                                                   StoredOutboundFile storedFile, String storageScope) {
        List<ResourceAttachment> activeAttachments = attachmentRepository
                .findByResourceTypeAndResourceIdAndAttachmentCategoryAndDeletedFalseOrderByUploadedAtDesc(
                        "OUTBOUND_ORDER", context.resourceId(), category);
        ResourceAttachment matching = activeAttachments.stream()
                .filter(existing -> Objects.equals(existing.getStoredFileName(), storedFile.storedFileName())
                        && Objects.equals(existing.getOriginalName(), storedFile.originalName()))
                .findFirst()
                .orElse(null);
        retireReplacedOrderAttachments(activeAttachments, matching, storedFile);
        if (matching != null) {
            return matching;
        }
        ResourceAttachment attachment = new ResourceAttachment();
        attachment.setResourceType("OUTBOUND_ORDER");
        attachment.setResourceId(context.resourceId());
        attachment.setResourceCode(context.resourceCode());
        attachment.setResourceName(context.resourceName());
        attachment.setAttachmentCategory(category);
        attachment.setAttachmentLabel(trimToNull(label));
        attachment.setStorageScope(storageScope);
        attachment.setOriginalName(storedFile.originalName());
        attachment.setStoredFileName(storedFile.storedFileName());
        attachment.setContentType(storedFile.contentType());
        attachment.setFileSize(storedFile.fileSize());
        attachment.setFileExtension(StringUtils.getFilenameExtension(storedFile.originalName()));
        attachment.setPreviewable(isPreviewable(storedFile.contentType(), storedFile.originalName()));
        attachment.setUploadNote(trimToNull(note));
        attachment.setUploadedBy(SecurityUtils.currentUsername());
        attachment.setUploadedAt(storedFile.uploadedAt());
        attachment.setDeleted(false);
        return attachmentRepository.save(attachment);
    }

    private void retireReplacedOrderAttachments(
            List<ResourceAttachment> activeAttachments,
            ResourceAttachment retained,
            StoredOutboundFile replacement
    ) {
        List<ResourceAttachment> retired = activeAttachments.stream()
                .filter(existing -> existing != retained)
                .peek(existing -> {
                    existing.setDeleted(true);
                    existing.setDeletedAt(LocalDateTime.now());
                    existing.setDeletedBy(SecurityUtils.currentUsername());
                    existing.setDeleteReason("Replaced by " + replacement.originalName());
                })
                .toList();
        if (retired.isEmpty()) {
            return;
        }
        attachmentRepository.saveAll(retired);
        for (ResourceAttachment attachment : retired) {
            Path oldFile = attachmentStorage.resolveAttachmentPath(attachment);
            attachmentStorage.registerAfterCommit(() -> attachmentStorage.deleteQuietly(
                    oldFile,
                    "Failed to delete replaced outbound attachment"
            ));
        }
    }

    private boolean persistLegacyRow(OutboundOrder order, String category, String storedFileName, String originalName,
                                     String contentType, Long fileSize, LocalDateTime uploadedAt) {
        String storageScope = chooseStorageScope("OUTBOUND_ORDER", category);
        if (!attachmentStorage.attachmentExists(storageScope, storedFileName)) {
            log.warn("Skipping legacy {} attachment backfill for order {} because file {} does not exist",
                    category, order.getOrderNo(), storedFileName);
            return false;
        }
        ResourceAttachment attachment = new ResourceAttachment();
        attachment.setResourceType("OUTBOUND_ORDER");
        attachment.setResourceId(order.getId());
        attachment.setResourceCode(order.getOrderNo());
        attachment.setResourceName(order.getCustomerName());
        attachment.setAttachmentCategory(category);
        attachment.setAttachmentLabel(defaultAttachmentLabel(category));
        attachment.setStorageScope(storageScope);
        attachment.setOriginalName(firstNonBlank(originalName, storedFileName));
        attachment.setStoredFileName(storedFileName);
        attachment.setContentType(firstNonBlank(contentType, "application/octet-stream"));
        attachment.setFileSize(fileSize);
        attachment.setFileExtension(StringUtils.getFilenameExtension(storedFileName));
        attachment.setPreviewable(isPreviewable(contentType, originalName));
        attachment.setUploadNote("Legacy backfill");
        attachment.setUploadedBy("system");
        attachment.setUploadedAt(uploadedAt == null ? LocalDateTime.now() : uploadedAt);
        attachment.setDeleted(false);
        attachmentRepository.save(attachment);
        return true;
    }

    private boolean hasLegacyAttachment(Long orderId, String category) {
        return attachmentRepository.findFirstByResourceTypeAndResourceIdAndAttachmentCategoryAndDeletedFalseOrderByUploadedAtDesc(
                "OUTBOUND_ORDER", orderId, category
        ).isPresent();
    }

    private String chooseStorageScope(String resourceType, String category) {
        String normalizedType = permissionPolicy.normalizeResourceType(resourceType);
        String normalizedCategory = normalizeCategory(category);
        if ("OUTBOUND_ORDER".equals(normalizedType) && "INVOICE".equals(normalizedCategory)) {
            return "LEGACY_ORDER_INVOICE";
        }
        if ("OUTBOUND_ORDER".equals(normalizedType) && "CONTRACT".equals(normalizedCategory)) {
            return "LEGACY_ORDER_CONTRACT";
        }
        return "ATTACHMENT";
    }

    private boolean isOutboundOrder(String resourceType) {
        return "OUTBOUND_ORDER".equalsIgnoreCase(trimToNull(resourceType));
    }

    private boolean isOrderManagedCategory(String category) {
        String normalized = normalizeCategory(category);
        return "INVOICE".equals(normalized) || "CONTRACT".equals(normalized);
    }

    private String hiddenMessage(String resourceType) {
        return switch (permissionPolicy.normalizeResourceType(resourceType)) {
            case "MACHINE" -> "Vehicle not found";
            case "PART" -> "Part not found";
            case "REPAIR" -> "Repair record not found";
            case "OUTBOUND_ORDER" -> "Outbound order not found";
            default -> "Attachment resource not found";
        };
    }

    private String lockedWriteMessage(String resourceType) {
        return switch (permissionPolicy.normalizeResourceType(resourceType)) {
            case "MACHINE" -> "Vehicle is locked and cannot be modified";
            case "PART" -> "Part is locked and cannot be modified";
            case "REPAIR" -> "Repair record is locked and cannot be modified";
            case "OUTBOUND_ORDER" -> "Outbound order is locked and cannot be modified";
            default -> "Attachment resource is locked and cannot be modified";
        };
    }

    private boolean isPreviewable(String contentType, String originalName) {
        return attachmentStorage.isPreviewable(contentType, originalName);
    }

    private String normalizeCategory(String category) {
        String normalized = trimToNull(category);
        if (normalized == null) {
            return "OTHER";
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private String defaultAttachmentLabel(String category) {
        return switch (normalizeCategory(category)) {
            case "INVOICE" -> "发票";
            case "CONTRACT" -> "合同";
            case "PHOTO" -> "图片";
            default -> "附件";
        };
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
