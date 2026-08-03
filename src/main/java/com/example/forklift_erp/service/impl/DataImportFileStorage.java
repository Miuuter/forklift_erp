package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
public class DataImportFileStorage {
    private static final long MAX_IMPORT_FILE_SIZE = 20L * 1024 * 1024;
    private static final FileStorageSupport.UploadConstraints IMPORT_FILE_CONSTRAINTS =
            new FileStorageSupport.UploadConstraints(
                    MAX_IMPORT_FILE_SIZE,
                    Set.of("xlsx", "xls"),
                    "请选择导入文件",
                    "Import file cannot exceed 20MB",
                    "Only Excel files are supported"
            );

    private final String importStorageDir;
    private final FileStorageSupport fileStorageSupport;

    public DataImportFileStorage(
            @Value("${forklift-erp.import-storage-dir:${FORKLIFT_ERP_IMPORT_STORAGE_DIR:uploads/imports}}")
            String importStorageDir,
            FileStorageSupport fileStorageSupport
    ) {
        this.importStorageDir = importStorageDir;
        this.fileStorageSupport = fileStorageSupport;
    }

    String originalFileName(MultipartFile file, String fallbackName) {
        ensureImportFile(file);
        return fileStorageSupport.cleanOriginalName(
                file.getOriginalFilename(),
                fallbackName,
                "Import file name is invalid"
        );
    }

    Path store(MultipartFile file, Long jobId, String type) {
        String originalName = fileStorageSupport.cleanOriginalName(
                file == null ? null : file.getOriginalFilename(),
                "import.xlsx",
                "Import file name is invalid"
        );
        String extension = StringUtils.getFilenameExtension(originalName);
        String safeExtension = extension == null ? "xlsx" : extension.toLowerCase(Locale.ROOT);
        String storedFileName = "import-" + type.toLowerCase(Locale.ROOT) + "-" + jobId + "-"
                + UUID.randomUUID().toString().replace("-", "") + "." + safeExtension;
        return fileStorageSupport.store(
                file,
                storageRoot(),
                storedFileName,
                originalName,
                IMPORT_FILE_CONSTRAINTS,
                "Import path is invalid",
                "Import file save failed"
        ).filePath();
    }

    Path resolve(String stagedFileName) {
        return fileStorageSupport.resolveInRoot(storageRoot(), stagedFileName, "Import path is invalid");
    }

    void registerRollbackCleanup(Path file) {
        fileStorageSupport.registerRollbackCleanup(file, "Failed to delete import file");
    }

    void delete(String stagedFileName) {
        if (stagedFileName == null || stagedFileName.isBlank()) {
            return;
        }
        Path file = resolve(stagedFileName);
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "Failed to delete expired import file");
        }
    }

    String fingerprint(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.io.IOException | NoSuchAlgorithmException ex) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "Unable to fingerprint import file");
        }
    }

    private Path storageRoot() {
        return fileStorageSupport.storageRoot(importStorageDir);
    }

    private void ensureImportFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请选择导入文件");
        }
    }
}
