package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileStorageSupportTests {

    private static final FileStorageSupport.UploadConstraints PDF_ONLY =
            new FileStorageSupport.UploadConstraints(
                    1024,
                    Set.of("pdf"),
                    "File is required",
                    "File is too large",
                    "Unsupported file type"
            );

    private static final FileStorageSupport.UploadConstraints CSV_ONLY =
            new FileStorageSupport.UploadConstraints(
                    1024,
                    Set.of("csv"),
                    "File is required",
                    "File is too large",
                    "Unsupported file type"
            );

    Path tempDir;

    private final FileStorageSupport fileStorageSupport = new FileStorageSupport();

    @BeforeEach
    void createTempDir() throws IOException {
        Path root = Path.of("target", "file-storage-tests");
        Files.createDirectories(root);
        tempDir = Files.createTempDirectory(root, "case-");
    }

    @AfterEach
    void deleteTempDir() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void storeWritesMultipartFileUnderNormalizedRoot() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "invoice.pdf",
                "application/pdf",
                "%PDF-1.4\npayload".getBytes(StandardCharsets.UTF_8)
        );

        FileStorageSupport.StoredFile stored = fileStorageSupport.store(
                file,
                tempDir,
                "stored.pdf",
                fileStorageSupport.cleanOriginalName(file.getOriginalFilename(), "fallback.pdf", "Invalid file name"),
                PDF_ONLY,
                "Invalid path",
                "Save failed"
        );

        assertThat(stored.storedFileName()).isEqualTo("stored.pdf");
        assertThat(stored.originalName()).isEqualTo("invoice.pdf");
        assertThat(stored.contentType()).isEqualTo("application/pdf");
        assertThat(stored.fileExtension()).isEqualTo("pdf");
        assertThat(Files.readString(stored.filePath())).isEqualTo("%PDF-1.4\npayload");
    }

    @Test
    void cleanOriginalNameRejectsTraversal() {
        assertThatThrownBy(() -> fileStorageSupport.cleanOriginalName("../evil.pdf", "fallback.pdf", "Invalid file name"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid file name");
    }

    @Test
    void storeRejectsUnsupportedExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "invoice.exe",
                "application/octet-stream",
                "payload".getBytes(StandardCharsets.UTF_8)
        );
        String originalName = fileStorageSupport.cleanOriginalName(file.getOriginalFilename(), "fallback.pdf", "Invalid file name");

        assertThatThrownBy(() -> fileStorageSupport.store(
                file,
                tempDir,
                "stored.exe",
                originalName,
                PDF_ONLY,
                "Invalid path",
                "Save failed"
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Unsupported file type");
    }

    @Test
    void storeRejectsSpoofedPdfSignature() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "invoice.pdf",
                "application/pdf",
                "not-a-pdf".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> fileStorageSupport.store(
                file,
                tempDir,
                "stored.pdf",
                "invoice.pdf",
                PDF_ONLY,
                "Invalid path",
                "Save failed"
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Unsupported file type");
    }

    @Test
    void storeStreamsUtf8CsvSignatureValidation() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "customers.csv",
                "text/csv",
                "company,contact\n叉车公司,李工\n".getBytes(StandardCharsets.UTF_8)
        );

        FileStorageSupport.StoredFile stored = fileStorageSupport.store(
                file,
                tempDir,
                "customers.csv",
                "customers.csv",
                CSV_ONLY,
                "Invalid path",
                "Save failed"
        );

        assertThat(stored.fileSize()).isEqualTo(Files.size(stored.filePath()));
        assertThat(Files.readString(stored.filePath())).contains("叉车公司");
    }

    @Test
    void resolveInRootRejectsEscapingPath() {
        assertThatThrownBy(() -> fileStorageSupport.resolveInRoot(tempDir, "../evil.pdf", "Invalid path"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid path");
    }

    @Test
    void rollbackCleanupDeletesStoredFile() throws IOException {
        Path storedFile = Files.writeString(tempDir.resolve("stored.pdf"), "payload");

        TransactionSynchronizationManager.initSynchronization();
        try {
            fileStorageSupport.registerRollbackCleanup(storedFile, "cleanup failed");
            var synchronizations = new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
            TransactionSynchronizationManager.clearSynchronization();
            synchronizations.forEach(synchronization ->
                    synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }

        assertThat(storedFile).doesNotExist();
    }
}
