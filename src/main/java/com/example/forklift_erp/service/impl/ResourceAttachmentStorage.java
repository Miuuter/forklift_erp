package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.entity.ResourceAttachment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
public class ResourceAttachmentStorage {
    private static final long MAX_ATTACHMENT_FILE_SIZE = 30L * 1024 * 1024;
    private static final FileStorageSupport.UploadConstraints ATTACHMENT_CONSTRAINTS =
            new FileStorageSupport.UploadConstraints(
                    MAX_ATTACHMENT_FILE_SIZE,
                    Set.of("pdf", "ofd", "jpg", "jpeg", "png", "webp", "gif", "bmp",
                            "doc", "docx", "xls", "xlsx", "csv"),
                    "Attachment file is required",
                    "Attachment file cannot exceed 30MB",
                    "Unsupported attachment file type"
            );

    private final FileStorageSupport fileStorageSupport;
    private final String attachmentStorageDir;
    private final String invoiceStorageDir;
    private final String contractStorageDir;

    public ResourceAttachmentStorage(
            FileStorageSupport fileStorageSupport,
            @Value("${forklift-erp.attachment-storage-dir:${FORKLIFT_ERP_ATTACHMENT_STORAGE_DIR:uploads/attachments}}")
            String attachmentStorageDir,
            @Value("${forklift-erp.invoice-storage-dir:${forklift.invoice-storage-dir:${FORKLIFT_ERP_INVOICE_STORAGE_DIR:uploads/invoices}}}")
            String invoiceStorageDir,
            @Value("${forklift-erp.contract-storage-dir:${forklift.contract-storage-dir:${FORKLIFT_ERP_CONTRACT_STORAGE_DIR:uploads/contracts}}}")
            String contractStorageDir
    ) {
        this.fileStorageSupport = fileStorageSupport;
        this.attachmentStorageDir = attachmentStorageDir;
        this.invoiceStorageDir = invoiceStorageDir;
        this.contractStorageDir = contractStorageDir;
    }

    StoredAttachmentFile storeFile(MultipartFile file, String storageScope) {
        String originalName = fileStorageSupport.cleanOriginalName(
                file == null ? null : file.getOriginalFilename(),
                "attachment.bin",
                "Attachment file name is invalid"
        );
        FileStorageSupport.StoredFile stored = fileStorageSupport.store(
                file,
                storageRoot(storageScope),
                buildStoredFileName(storageScope, originalName),
                originalName,
                ATTACHMENT_CONSTRAINTS,
                "Attachment path is invalid",
                "Attachment file save failed"
        );
        return new StoredAttachmentFile(
                stored.filePath(),
                stored.storedFileName(),
                stored.originalName(),
                stored.contentType(),
                stored.fileSize(),
                stored.fileExtension(),
                fileStorageSupport.isPreviewable(stored.contentType(), stored.originalName()),
                storageScope
        );
    }

    Path resolveAttachmentPath(ResourceAttachment attachment) {
        return fileStorageSupport.resolveInRoot(
                storageRoot(attachment.getStorageScope()),
                attachment.getStoredFileName(),
                "Attachment path is invalid"
        );
    }

    boolean attachmentExists(String storageScope, String storedFileName) {
        Path path = fileStorageSupport.resolveInRoot(
                storageRoot(storageScope),
                storedFileName,
                "Attachment path is invalid"
        );
        return Files.isRegularFile(path);
    }

    void registerRollbackCleanup(Path file) {
        fileStorageSupport.registerRollbackCleanup(file, "Failed to delete rolled back attachment");
    }

    void registerAfterCommit(Runnable runnable) {
        fileStorageSupport.registerAfterCommit(runnable);
    }

    void deleteQuietly(Path path, String message) {
        fileStorageSupport.deleteQuietly(path, message);
    }

    boolean isPreviewable(String contentType, String originalName) {
        return fileStorageSupport.isPreviewable(contentType, originalName);
    }

    private Path storageRoot(String storageScope) {
        return switch (storageScope) {
            case "LEGACY_ORDER_INVOICE" -> fileStorageSupport.storageRoot(invoiceStorageDir);
            case "LEGACY_ORDER_CONTRACT" -> fileStorageSupport.storageRoot(contractStorageDir);
            default -> fileStorageSupport.storageRoot(attachmentStorageDir);
        };
    }

    private String buildStoredFileName(String storageScope, String originalName) {
        String extension = StringUtils.getFilenameExtension(originalName);
        String safeExtension = extension == null ? "bin" : extension.toLowerCase(Locale.ROOT);
        String prefix = switch (storageScope) {
            case "LEGACY_ORDER_INVOICE" -> "invoice";
            case "LEGACY_ORDER_CONTRACT" -> "contract";
            default -> "attachment";
        };
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "") + "." + safeExtension;
    }
}
