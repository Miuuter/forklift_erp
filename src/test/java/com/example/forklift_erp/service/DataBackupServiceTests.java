package com.example.forklift_erp.service;

import com.example.forklift_erp.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataBackupServiceTests {

    private static final String FORMAT = "forklift-erp-json-backup-v2";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void dryRunReportsRowsWithoutMutatingDatabase() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplateWithUsersTable();
        DataBackupService service = new DataBackupService(jdbcTemplate, objectMapper);
        DataBackupService.BackupFile backup = backupWithUsers(
                List.of("id", "username"),
                row("id", 1, "username", "admin")
        );

        DataBackupService.BackupValidationResult result = service.dryRunRestore(multipartBackup(backup));

        assertThat(result.isDryRun()).isTrue();
        assertThat(result.getFormat()).isEqualTo(FORMAT);
        assertThat(result.getBackupSchemaVersion()).isEqualTo("35");
        assertThat(result.getCurrentSchemaVersion()).isEqualTo("35");
        assertThat(result.getTotalRows()).isEqualTo(1);
        assertThat(result.getTableRows()).containsEntry("users", 1L);
        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void dryRunRejectsUnknownColumns() throws Exception {
        DataBackupService service = new DataBackupService(jdbcTemplateWithUsersTable(), objectMapper);
        DataBackupService.BackupFile backup = backupWithUsers(
                List.of("id", "username"),
                row("id", 1, "username", "admin", "unknown_column", "value")
        );

        assertThatThrownBy(() -> service.dryRunRestore(multipartBackup(backup)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Backup contains unknown column: unknown_column");
    }

    @Test
    void dryRunRejectsRowsOutsideDeclaredColumns() throws Exception {
        DataBackupService service = new DataBackupService(jdbcTemplateWithUsersTable(), objectMapper);
        DataBackupService.BackupFile backup = backupWithUsers(
                List.of("id"),
                row("id", 1, "username", "admin")
        );

        assertThatThrownBy(() -> service.dryRunRestore(multipartBackup(backup)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Backup row contains undeclared column: username");
    }

    @Test
    void dryRunRejectsDuplicateTables() throws Exception {
        DataBackupService service = new DataBackupService(jdbcTemplateWithUsersTable(), objectMapper);
        DataBackupService.BackupFile backup = new DataBackupService.BackupFile();
        backup.setFormat(FORMAT);
        backup.setSchemaVersion("35");
        backup.setTables(List.of(
                backupTable("users", List.of("id", "username"),
                        row("id", 1, "username", "admin")),
                backupTable("users", List.of("id", "username"),
                        row("id", 2, "username", "operator"))
        ));

        assertThatThrownBy(() -> service.dryRunRestore(multipartBackup(backup)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Backup contains duplicate table: users");
    }

    @Test
    void dryRunRejectsAStructurallyValidButIncompleteTableSet() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList("show tables", String.class)).thenReturn(List.of("roles", "users"));
        when(jdbcTemplate.queryForList("show columns from `roles`")).thenReturn(columns("id", "name"));
        when(jdbcTemplate.queryForList("show columns from `users`")).thenReturn(columns("id", "username"));
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn("35");
        DataBackupService service = new DataBackupService(jdbcTemplate, objectMapper);
        DataBackupService.BackupFile backup = backupWithUsers(
                List.of("id", "username"),
                row("id", 1, "username", "admin")
        );

        assertThatThrownBy(() -> service.restoreBackup(multipartBackup(backup)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Backup is missing required tables: roles");

        verify(jdbcTemplate, never()).execute(anyString());
        verify(jdbcTemplate, never()).update(anyString());
    }

    @Test
    void dryRunRejectsBlankSchemaVersion() throws Exception {
        DataBackupService service = new DataBackupService(jdbcTemplateWithUsersTable(), objectMapper);
        DataBackupService.BackupFile backup = backupWithUsers(
                List.of("id", "username"),
                row("id", 1, "username", "admin")
        );
        backup.setSchemaVersion(" ");

        assertThatThrownBy(() -> service.dryRunRestore(multipartBackup(backup)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Backup schema version is required");
    }

    @Test
    void dryRunRejectsRowsChangedAfterManifestCreation() throws Exception {
        DataBackupService service = new DataBackupService(jdbcTemplateWithUsersTable(), objectMapper);
        DataBackupService.BackupFile backup = backupWithUsers(
                List.of("id", "username"),
                row("id", 1, "username", "admin")
        );
        service.populateManifest(backup);
        backup.getTables().get(0).getRows().get(0).put("username", "tampered");

        assertThatThrownBy(() -> service.dryRunRestore(multipartBackupWithoutSealing(backup)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Backup checksum mismatch for table: users");
    }

    @Test
    void restoreRejectsOrphanedForeignKeyBeforeDeletingAnyLiveRows() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList("show tables", String.class))
                .thenReturn(List.of("child_table", "parent_table"));
        when(jdbcTemplate.queryForList("show columns from `child_table`"))
                .thenReturn(columns("id", "parent_id"));
        when(jdbcTemplate.queryForList("show columns from `parent_table`"))
                .thenReturn(columns("id"));
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn("35");
        when(jdbcTemplate.queryForList(contains("information_schema.key_column_usage")))
                .thenReturn(List.of(Map.of(
                        "child_table", "child_table",
                        "constraint_name", "fk_child_parent",
                        "child_column", "parent_id",
                        "parent_table", "parent_table",
                        "parent_column", "id",
                        "ordinal_position", 1
                )));
        DataBackupService service = new DataBackupService(jdbcTemplate, objectMapper);
        DataBackupService.BackupFile backup = new DataBackupService.BackupFile();
        backup.setFormat(FORMAT);
        backup.setSchemaVersion("35");
        backup.setTables(List.of(
                backupTable("child_table", List.of("id", "parent_id"),
                        row("id", 10, "parent_id", 99)),
                backupTable("parent_table", List.of("id"), row("id", 1))
        ));

        assertThatThrownBy(() -> service.restoreBackup(multipartBackup(backup)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Backup violates foreign key fk_child_parent on table child_table");

        verify(jdbcTemplate, never()).execute(anyString());
        verify(jdbcTemplate, never()).update(anyString());
    }

    @Test
    void createBackupExcludesGeneratedInvariantColumnsButKeepsDefaultGeneratedValues() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList("show tables", String.class)).thenReturn(List.of("warehouse"));
        when(jdbcTemplate.queryForList("show columns from `warehouse`"))
                .thenReturn(List.of(
                        Map.of("Field", "id", "Extra", ""),
                        Map.of("Field", "created_at", "Extra", "DEFAULT_GENERATED"),
                        Map.of("Field", "default_guard", "Extra", "STORED GENERATED")
                ));
        when(jdbcTemplate.queryForList("select `id`, `created_at` from `warehouse`"))
                .thenReturn(List.of(row("id", 1, "created_at", "2000-01-02T03:04:05.123456")));
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn("50");
        DataBackupService service = new DataBackupService(jdbcTemplate, objectMapper);

        DataBackupService.BackupFile backup = objectMapper.readValue(
                service.createBackup(), DataBackupService.BackupFile.class);

        assertThat(backup.getTables()).hasSize(1);
        assertThat(backup.getTables().get(0).getColumns()).containsExactly("id", "created_at");
        assertThat(backup.getTables().get(0).getRows().get(0))
                .containsOnlyKeys("id", "created_at")
                .containsEntry("created_at", "2000-01-02T03:04:05.123456");
    }

    @Test
    void createBackupFailsInsteadOfEmittingAnUnrestorableUnknownSchemaBackup() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class)))
                .thenThrow(new IllegalStateException("schema history unavailable"));
        DataBackupService service = new DataBackupService(jdbcTemplate, objectMapper);

        assertThatThrownBy(service::createBackup)
                .isInstanceOf(BusinessException.class)
                .hasMessage("Current database schema version cannot be verified");
        verify(jdbcTemplate, never()).queryForList("show tables", String.class);
    }

    private JdbcTemplate jdbcTemplateWithUsersTable() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList("show tables", String.class)).thenReturn(List.of("users"));
        when(jdbcTemplate.queryForList("show columns from `users`")).thenReturn(columns("id", "username"));
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn("35");
        return jdbcTemplate;
    }

    private DataBackupService.BackupFile backupWithUsers(List<String> columns, Map<String, Object> row) {
        DataBackupService.BackupFile backup = new DataBackupService.BackupFile();
        backup.setFormat(FORMAT);
        backup.setSchemaVersion("35");
        backup.setTables(List.of(backupTable("users", columns, row)));
        return backup;
    }

    private DataBackupService.BackupTable backupTable(String tableName, List<String> columns, Map<String, Object> row) {
        DataBackupService.BackupTable table = new DataBackupService.BackupTable();
        table.setName(tableName);
        table.setColumns(columns);
        table.setRows(List.of(row));
        return table;
    }

    private MockMultipartFile multipartBackup(DataBackupService.BackupFile backup) throws Exception {
        new DataBackupService(mock(JdbcTemplate.class), objectMapper).populateManifest(backup);
        return multipartBackupWithoutSealing(backup);
    }

    private MockMultipartFile multipartBackupWithoutSealing(DataBackupService.BackupFile backup) throws Exception {
        return new MockMultipartFile(
                "file",
                "backup.json",
                "application/json",
                objectMapper.writeValueAsBytes(backup)
        );
    }

    private List<Map<String, Object>> columns(String... names) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (String name : names) {
            columns.add(Map.of("Field", name));
        }
        return columns;
    }

    private Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            row.put(String.valueOf(values[i]), values[i + 1]);
        }
        return row;
    }
}
