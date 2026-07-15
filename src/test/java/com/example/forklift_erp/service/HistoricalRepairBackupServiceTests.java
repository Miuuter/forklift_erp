package com.example.forklift_erp.service;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HistoricalRepairBackupServiceTests {

    @Test
    void persistsAndReadsACompletePreRepairBackup() throws Exception {
        Path backupDirectory = newBackupDirectory();
        DataBackupService dataBackupService = mock(DataBackupService.class);
        byte[] payload = "{\"format\":\"forklift-erp-json-backup-v1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(dataBackupService.createBackup()).thenReturn(payload);
        HistoricalRepairBackupService service = new HistoricalRepairBackupService(
                dataBackupService, backupDirectory.toString()
        );

        HistoricalRepairBackupService.BackupReceipt receipt = service.createPreRepairBackup();

        assertThat(receipt.fileName()).startsWith("pre-historical-repair-").endsWith(".json");
        assertThat(receipt.byteSize()).isEqualTo(payload.length);
        assertThat(receipt.sha256()).hasSize(64);
        assertThat(Files.readAllBytes(backupDirectory.resolve(receipt.fileName()))).isEqualTo(payload);
        assertThat(service.read(receipt.fileName())).isEqualTo(payload);
    }

    @Test
    void refusesTraversalWhenDownloadingStoredBackup() {
        Path backupDirectory = newBackupDirectory();
        HistoricalRepairBackupService service = new HistoricalRepairBackupService(
                mock(DataBackupService.class), backupDirectory.toString()
        );

        assertThatThrownBy(() -> service.read("../backup.json"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ResultCode.PARAM_ERROR.getCode());
    }

    private Path newBackupDirectory() {
        return Path.of("target", "test-historical-repair-backups", UUID.randomUUID().toString());
    }
}
