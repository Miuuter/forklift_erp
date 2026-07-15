package com.example.forklift_erp.service;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Persists the complete application backup generated immediately before a
 * historical repair. It intentionally never deletes older snapshots.
 */
@Service
public class HistoricalRepairBackupService {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final DataBackupService dataBackupService;
    private final String storageDirectory;

    public HistoricalRepairBackupService(
            DataBackupService dataBackupService,
            @Value("${forklift-erp.historical-repair-backup-dir:${FORKLIFT_ERP_HISTORICAL_REPAIR_BACKUP_DIR:uploads/historical-repair-backups}}")
            String storageDirectory
    ) {
        this.dataBackupService = dataBackupService;
        this.storageDirectory = storageDirectory;
    }

    public BackupReceipt createPreRepairBackup() {
        byte[] payload = dataBackupService.createBackup();
        String filename = "pre-historical-repair-" + FILE_TIME.format(LocalDateTime.now())
                + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + ".json";
        Path root = Path.of(storageDirectory).toAbsolutePath().normalize();
        Path target = root.resolve(filename).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Historical repair backup path is invalid");
        }
        Path temporary = null;
        try {
            Files.createDirectories(root);
            temporary = Files.createTempFile(root, "historical-repair-", ".tmp");
            Files.write(temporary, payload);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new BackupReceipt(filename, payload.length, sha256(payload), LocalDateTime.now());
        } catch (IOException exception) {
            deleteQuietly(temporary);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "Failed to persist historical repair backup");
        }
    }

    public byte[] read(String filename) {
        if (filename == null || filename.isBlank()
                || filename.contains("/") || filename.contains("\\") || filename.contains("..")
                || !filename.endsWith(".json")) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Historical repair backup filename is invalid");
        }
        Path root = Path.of(storageDirectory).toAbsolutePath().normalize();
        Path target = root.resolve(filename).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "Historical repair backup not found");
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException exception) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "Failed to read historical repair backup");
        }
    }

    private String sha256(byte[] payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            StringBuilder text = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                text.append(String.format("%02x", value));
            }
            return text.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "SHA-256 is unavailable");
        }
    }

    private void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Keep the original storage error.
        }
    }

    public record BackupReceipt(String fileName, long byteSize, String sha256, LocalDateTime createdAt) {
    }
}
