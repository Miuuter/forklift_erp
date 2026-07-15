package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataImportFileStorageTests {

    private Path tempDir;
    private DataImportFileStorage storage;

    @BeforeEach
    void setUp() throws IOException {
        Path root = Path.of("target", "data-import-file-storage-tests");
        Files.createDirectories(root);
        tempDir = Files.createTempDirectory(root, "case-");
        storage = new DataImportFileStorage(tempDir.toString(), new FileStorageSupport());
    }

    @AfterEach
    void tearDown() throws IOException {
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
    void storeWritesExcelFileWithImportPrefixAndResolvesIt() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "import.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00}
        );

        Path stored = storage.store(file, 12L, "vehicle-workbook");

        assertThat(stored.getFileName().toString())
                .startsWith("import-vehicle-workbook-12-")
                .endsWith(".xlsx");
        assertThat(Files.readAllBytes(stored))
                .containsExactly(new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00});
        assertThat(storage.resolve(stored.getFileName().toString())).isEqualTo(stored);
    }

    @Test
    void originalFileNameRejectsTraversal() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "../evil.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "payload".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> storage.originalFileName(file, "fallback.xlsx"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Import file name is invalid");
    }

    @Test
    void storeRejectsUnsupportedExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "import.exe",
                "application/octet-stream",
                "payload".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> storage.store(file, 12L, "vehicle-workbook"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Only Excel files are supported");
    }

    @Test
    void storeRejectsEmptyFileWithReadableMessage() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "import.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[0]
        );

        assertThatThrownBy(() -> storage.store(file, 12L, "vehicle-workbook"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请选择导入文件");
    }
}
