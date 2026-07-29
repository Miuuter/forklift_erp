package com.example.forklift_erp.service;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.config.MaintenanceOperation;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.util.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class DataBackupService {
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");
    private static final String FORMAT = "forklift-erp-json-backup-v2";
    private static final long MAX_BACKUP_FILE_SIZE = 50L * 1024 * 1024;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DataBackupService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public byte[] createBackup() {
        BackupFile backup = new BackupFile();
        backup.setFormat(FORMAT);
        backup.setSchemaVersion(requireCurrentSchemaVersion());
        backup.setCreatedAt(LocalDateTime.now().toString());
        backup.setCreatedBy(SecurityUtils.currentUsername());
        for (String tableName : applicationTableNames()) {
            BackupTable table = new BackupTable();
            table.setName(tableName);
            table.setColumns(applicationColumnNames(tableName));
            String selectedColumns = table.getColumns().stream()
                    .map(this::quote)
                    .reduce((left, right) -> left + ", " + right)
                    .orElseThrow(() -> new BusinessException(
                            ResultCode.SYSTEM_ERROR,
                            "Backup table has no restorable columns: " + tableName));
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "select " + selectedColumns + " from " + quote(tableName));
            table.setRows(rows.stream().map(this::normalizeRow).toList());
            backup.getTables().add(table);
        }
        populateManifest(backup);
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(backup);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "Failed to create backup");
        }
    }

    @Transactional(readOnly = true)
    public BackupValidationResult dryRunRestore(MultipartFile file) {
        RestorePlan plan = prepareRestore(file);
        BackupValidationResult result = new BackupValidationResult();
        result.setDryRun(true);
        result.setFormat(plan.backup().getFormat());
        result.setBackupSchemaVersion(plan.backup().getSchemaVersion());
        result.setCurrentSchemaVersion(plan.currentSchemaVersion());
        for (String tableName : plan.currentTables()) {
            BackupTable table = plan.backupTables().get(tableName);
            long rows = table == null || table.getRows() == null ? 0L : table.getRows().size();
            result.getTableRows().put(tableName, rows);
            result.setTotalRows(result.getTotalRows() + rows);
        }
        return result;
    }

    @Transactional
    @MaintenanceOperation
    public Map<String, Long> restoreBackup(MultipartFile file) {
        RestorePlan plan = prepareRestore(file);

        Map<String, Long> summary = new LinkedHashMap<>();
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS=0");
        try {
            for (int i = plan.currentTables().size() - 1; i >= 0; i--) {
                jdbcTemplate.update("delete from " + quote(plan.currentTables().get(i)));
            }
            for (String tableName : plan.currentTables()) {
                BackupTable table = plan.backupTables().get(tableName);
                long inserted = 0;
                if (table != null && table.getRows() != null) {
                    for (Map<String, Object> row : table.getRows()) {
                        insertRow(tableName, row);
                        inserted++;
                    }
                }
                summary.put(tableName, inserted);
            }
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS=1");
        }
        verifyRestoredState(plan);
        return summary;
    }

    private RestorePlan prepareRestore(MultipartFile file) {
        ensureBackupFile(file);
        BackupFile backup;
        try {
            backup = objectMapper.readValue(file.getBytes(), BackupFile.class);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Invalid backup file");
        }
        if (backup == null || !FORMAT.equals(backup.getFormat()) || backup.getTables() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Unsupported backup format");
        }

        List<String> currentTables = applicationTableNames();
        Map<String, Set<String>> currentColumns = applicationColumnsByTable(currentTables);
        String currentSchemaVersion = currentSchemaVersion();
        validateBackupBeforeRestore(backup, currentTables, currentColumns, currentSchemaVersion);

        Map<String, BackupTable> backupTables = new LinkedHashMap<>();
        for (BackupTable table : backup.getTables()) {
            backupTables.put(table.getName(), table);
        }
        List<ForeignKeyDefinition> foreignKeys = applicationForeignKeys();
        validateBackupForeignKeys(backupTables, foreignKeys);
        return new RestorePlan(backup, currentTables, backupTables, currentSchemaVersion, foreignKeys);
    }

    private List<ForeignKeyDefinition> applicationForeignKeys() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select
                    table_name as child_table,
                    constraint_name,
                    column_name as child_column,
                    referenced_table_name as parent_table,
                    referenced_column_name as parent_column,
                    ordinal_position
                from information_schema.key_column_usage
                where constraint_schema = database()
                  and referenced_table_name is not null
                order by table_name, constraint_name, ordinal_position
                """);
        Map<String, ForeignKeyBuilder> definitions = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String childTable = requiredMetadataValue(row, "child_table");
            String constraintName = requiredMetadataValue(row, "constraint_name");
            String childColumn = requiredMetadataValue(row, "child_column");
            String parentTable = requiredMetadataValue(row, "parent_table");
            String parentColumn = requiredMetadataValue(row, "parent_column");
            validateIdentifier(childTable);
            validateIdentifier(constraintName);
            validateIdentifier(childColumn);
            validateIdentifier(parentTable);
            validateIdentifier(parentColumn);
            String key = childTable + '\u0000' + constraintName;
            ForeignKeyBuilder builder = definitions.computeIfAbsent(key,
                    ignored -> new ForeignKeyBuilder(constraintName, childTable, parentTable));
            if (!builder.parentTable().equals(parentTable)) {
                throw new BusinessException(ResultCode.SYSTEM_ERROR,
                        "Database foreign key metadata is inconsistent: " + constraintName);
            }
            builder.childColumns().add(childColumn);
            builder.parentColumns().add(parentColumn);
        }
        return definitions.values().stream()
                .map(builder -> new ForeignKeyDefinition(
                        builder.constraintName(),
                        builder.childTable(),
                        List.copyOf(builder.childColumns()),
                        builder.parentTable(),
                        List.copyOf(builder.parentColumns())))
                .toList();
    }

    private String requiredMetadataValue(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR,
                    "Database foreign key metadata is incomplete");
        }
        return String.valueOf(value);
    }

    private void validateBackupForeignKeys(
            Map<String, BackupTable> backupTables,
            List<ForeignKeyDefinition> foreignKeys
    ) {
        for (ForeignKeyDefinition foreignKey : foreignKeys) {
            BackupTable child = backupTables.get(foreignKey.childTable());
            BackupTable parent = backupTables.get(foreignKey.parentTable());
            if (child == null || parent == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "Backup cannot validate foreign key: " + foreignKey.constraintName());
            }

            Set<List<String>> parentKeys = new HashSet<>();
            for (Map<String, Object> row : parent.getRows()) {
                List<Object> values = valuesForColumns(row, foreignKey.parentColumns());
                if (values.stream().noneMatch(java.util.Objects::isNull)) {
                    parentKeys.add(values.stream().map(this::canonicalValue).toList());
                }
            }
            for (Map<String, Object> row : child.getRows()) {
                List<Object> values = valuesForColumns(row, foreignKey.childColumns());
                // MySQL's default MATCH SIMPLE semantics do not require a
                // parent row when any component of a composite key is NULL.
                if (values.stream().anyMatch(java.util.Objects::isNull)) {
                    continue;
                }
                List<String> childKey = values.stream().map(this::canonicalValue).toList();
                if (!parentKeys.contains(childKey)) {
                    throw new BusinessException(ResultCode.PARAM_ERROR,
                            "Backup violates foreign key " + foreignKey.constraintName()
                                    + " on table " + foreignKey.childTable());
                }
            }
        }
    }

    private List<Object> valuesForColumns(Map<String, Object> row, List<String> columns) {
        return columns.stream().map(row::get).toList();
    }

    private void verifyRestoredState(RestorePlan plan) {
        for (String tableName : plan.currentTables()) {
            long expected = plan.backupTables().get(tableName).getRowCount();
            Long actual = jdbcTemplate.queryForObject(
                    "select count(*) from " + quote(tableName), Long.class);
            if (actual == null || actual != expected) {
                throw new BusinessException(ResultCode.SYSTEM_ERROR,
                        "Restore row count verification failed for table: " + tableName);
            }
        }
        for (ForeignKeyDefinition foreignKey : plan.foreignKeys()) {
            Long violations = jdbcTemplate.queryForObject(
                    foreignKeyViolationSql(foreignKey), Long.class);
            if (violations == null || violations != 0) {
                throw new BusinessException(ResultCode.SYSTEM_ERROR,
                        "Restore foreign key verification failed: " + foreignKey.constraintName());
            }
        }
    }

    private String foreignKeyViolationSql(ForeignKeyDefinition foreignKey) {
        List<String> joins = new ArrayList<>();
        List<String> nonNullChecks = new ArrayList<>();
        for (int i = 0; i < foreignKey.childColumns().size(); i++) {
            String childColumn = quote(foreignKey.childColumns().get(i));
            String parentColumn = quote(foreignKey.parentColumns().get(i));
            joins.add("child." + childColumn + " = parent." + parentColumn);
            nonNullChecks.add("child." + childColumn + " is not null");
        }
        String firstParentColumn = quote(foreignKey.parentColumns().get(0));
        return "select count(*) from " + quote(foreignKey.childTable()) + " child "
                + "left join " + quote(foreignKey.parentTable()) + " parent on "
                + String.join(" and ", joins)
                + " where " + String.join(" and ", nonNullChecks)
                + " and parent." + firstParentColumn + " is null";
    }

    private List<String> applicationTableNames() {
        return jdbcTemplate.queryForList("show tables", String.class).stream()
                .filter(table -> !"flyway_schema_history".equalsIgnoreCase(table))
                .sorted()
                .toList();
    }

    private Map<String, Set<String>> applicationColumnsByTable(List<String> tableNames) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String tableName : tableNames) {
            result.put(tableName, new HashSet<>(applicationColumnNames(tableName)));
        }
        return result;
    }

    private List<String> applicationColumnNames(String tableName) {
        return jdbcTemplate.queryForList("show columns from " + quote(tableName)).stream()
                // Computed guard columns are derived database invariants and
                // cannot be serialized or explicitly inserted during restore.
                // DEFAULT_GENERATED, however, is an ordinary writable column
                // whose default expression must not make its stored value vanish
                // from a backup.
                .filter(row -> !isDatabaseGeneratedColumn(row))
                .map(row -> String.valueOf(row.get("Field")))
                .toList();
    }

    private boolean isDatabaseGeneratedColumn(Map<String, Object> columnMetadata) {
        String extra = String.valueOf(columnMetadata.getOrDefault("Extra", ""))
                .toUpperCase(java.util.Locale.ROOT);
        return extra.contains("STORED GENERATED")
                || extra.contains("VIRTUAL GENERATED");
    }

    private String currentSchemaVersion() {
        try {
            return jdbcTemplate.queryForObject("""
                    select version
                    from flyway_schema_history
                    where success = 1
                    order by installed_rank desc
                    limit 1
                    """, String.class);
        } catch (RuntimeException ex) {
            return "unknown";
        }
    }

    private String requireCurrentSchemaVersion() {
        String version = currentSchemaVersion();
        if (version == null || version.isBlank() || "unknown".equals(version)) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR,
                    "Current database schema version cannot be verified");
        }
        return version;
    }

    private void validateBackupBeforeRestore(
            BackupFile backup,
            List<String> currentTables,
            Map<String, Set<String>> currentColumns,
            String currentSchemaVersion
    ) {
        if (backup.getSchemaVersion() == null || backup.getSchemaVersion().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Backup schema version is required");
        }
        if (currentSchemaVersion == null || currentSchemaVersion.isBlank()
                || "unknown".equals(currentSchemaVersion)) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR,
                    "Current database schema version cannot be verified");
        }
        if (!currentSchemaVersion.equals(backup.getSchemaVersion())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Backup schema version does not match current database");
        }

        Set<String> allowedTables = new HashSet<>(currentTables);
        Set<String> seenTables = new HashSet<>();
        for (BackupTable table : backup.getTables()) {
            validateIdentifier(table.getName());
            if (!allowedTables.contains(table.getName())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "Backup contains unknown table: " + table.getName());
            }
            if (!seenTables.add(table.getName())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "Backup contains duplicate table: " + table.getName());
            }
            validateBackupColumns(table, currentColumns.getOrDefault(table.getName(), Set.of()));
            validateTableManifest(table);
        }
        if (!seenTables.equals(allowedTables)) {
            Set<String> missingTables = new java.util.TreeSet<>(allowedTables);
            missingTables.removeAll(seenTables);
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Backup is missing required tables: " + String.join(", ", missingTables));
        }
    }

    private void validateBackupColumns(BackupTable table, Set<String> allowedColumns) {
        if (table.getColumns() == null || table.getColumns().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Backup table has no declared columns: " + table.getName());
        }
        Set<String> declaredColumns = new HashSet<>();
        for (String column : table.getColumns()) {
            validateColumnAllowed(column, allowedColumns);
            if (!declaredColumns.add(column)) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "Backup contains duplicate column: " + column);
            }
        }
        if (table.getRows() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Backup table rows are required: " + table.getName());
        }
        for (Map<String, Object> row : table.getRows()) {
            if (row == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "Backup contains a null row in table: " + table.getName());
            }
            for (String column : row.keySet()) {
                validateColumnAllowed(column, allowedColumns);
                if (!declaredColumns.contains(column)) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "Backup row contains undeclared column: " + column);
                }
            }
            if (!row.keySet().equals(allowedColumns)) {
                Set<String> missingColumns = new java.util.TreeSet<>(allowedColumns);
                missingColumns.removeAll(row.keySet());
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "Backup row is missing required columns in " + table.getName() + ": "
                                + String.join(", ", missingColumns));
            }
        }
        if (!declaredColumns.equals(allowedColumns)) {
            Set<String> missingColumns = new java.util.TreeSet<>(allowedColumns);
            missingColumns.removeAll(declaredColumns);
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Backup is missing required columns in " + table.getName() + ": "
                            + String.join(", ", missingColumns));
        }
    }

    void populateManifest(BackupFile backup) {
        if (backup == null || backup.getTables() == null) {
            return;
        }
        for (BackupTable table : backup.getTables()) {
            List<Map<String, Object>> rows = table.getRows() == null ? List.of() : table.getRows();
            table.setRowCount((long) rows.size());
            table.setSha256(tableDigest(table));
        }
    }

    private void validateTableManifest(BackupTable table) {
        if (table.getRowCount() == null || table.getRows() == null
                || table.getRowCount() != table.getRows().size()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Backup row count manifest mismatch for table: " + table.getName());
        }
        String expectedDigest = tableDigest(table);
        if (table.getSha256() == null || table.getSha256().isBlank()
                || !MessageDigest.isEqual(
                expectedDigest.getBytes(StandardCharsets.US_ASCII),
                table.getSha256().getBytes(StandardCharsets.US_ASCII)
        )) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Backup checksum mismatch for table: " + table.getName());
        }
    }

    private String tableDigest(BackupTable table) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, table.getName());
            List<String> columns = table.getColumns() == null ? List.of() : table.getColumns();
            for (String column : columns) {
                updateDigest(digest, column);
            }
            List<Map<String, Object>> rows = table.getRows() == null ? List.of() : table.getRows();
            for (Map<String, Object> row : rows) {
                for (String column : columns) {
                    updateDigest(digest, canonicalValue(row == null ? null : row.get(column)));
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "SHA-256 is unavailable");
        }
    }

    private void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) '|');
    }

    private String canonicalValue(Object value) {
        if (value == null) {
            return "N";
        }
        if (value instanceof Number number) {
            try {
                return "D:" + new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException ignored) {
                return "D:" + number;
            }
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue ? "B:1" : "B:0";
        }
        return "S:" + value;
    }

    private void validateColumnAllowed(String column, Set<String> allowedColumns) {
        validateIdentifier(column);
        if (!allowedColumns.contains(column)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Backup contains unknown column: " + column);
        }
    }

    private Map<String, Object> normalizeRow(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        row.forEach((key, value) -> normalized.put(key, normalizeValue(value)));
        return normalized;
    }

    private Object normalizeValue(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (value instanceof Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof Time time) {
            return time.toLocalTime().toString();
        }
        if (value instanceof byte[] bytes) {
            if (bytes.length == 1) {
                return Byte.toUnsignedInt(bytes[0]);
            }
            return Base64.getEncoder().encodeToString(bytes);
        }
        return String.valueOf(value);
    }

    private void insertRow(String tableName, Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return;
        }
        row.keySet().forEach(this::validateIdentifier);
        List<String> columns = new ArrayList<>(row.keySet());
        String columnSql = columns.stream().map(this::quote).reduce((a, b) -> a + ", " + b).orElse("");
        String placeholders = columns.stream().map(column -> "?").reduce((a, b) -> a + ", " + b).orElse("");
        Object[] values = columns.stream().map(row::get).toArray();
        jdbcTemplate.update("insert into " + quote(tableName) + " (" + columnSql + ") values (" + placeholders + ")", values);
    }

    private String quote(String identifier) {
        validateIdentifier(identifier);
        return "`" + identifier + "`";
    }

    private void validateIdentifier(String identifier) {
        if (identifier == null || !SQL_IDENTIFIER.matcher(identifier).matches()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Invalid backup identifier");
        }
    }

    private void ensureBackupFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Backup file is required");
        }
        if (file.getSize() > MAX_BACKUP_FILE_SIZE) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Backup file cannot exceed 50MB");
        }
        String filename = file.getOriginalFilename();
        if (filename != null) {
            String normalized = filename.trim().replace('\\', '/');
            if (normalized.contains("../") || normalized.contains("/") || !normalized.toLowerCase(java.util.Locale.ROOT).endsWith(".json")) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "Backup file name must be a JSON file");
            }
        }
    }

    @Data
    public static class BackupFile {
        private String format;
        private String schemaVersion;
        private String createdAt;
        private String createdBy;
        private List<BackupTable> tables = new ArrayList<>();
    }

    @Data
    public static class BackupTable {
        private String name;
        private List<String> columns = new ArrayList<>();
        private List<Map<String, Object>> rows = new ArrayList<>();
        private Long rowCount;
        private String sha256;
    }

    @Data
    public static class BackupValidationResult {
        private boolean dryRun;
        private String format;
        private String backupSchemaVersion;
        private String currentSchemaVersion;
        private long totalRows;
        private Map<String, Long> tableRows = new LinkedHashMap<>();
    }

    private record RestorePlan(
            BackupFile backup,
            List<String> currentTables,
            Map<String, BackupTable> backupTables,
            String currentSchemaVersion,
            List<ForeignKeyDefinition> foreignKeys
    ) {
    }

    private record ForeignKeyDefinition(
            String constraintName,
            String childTable,
            List<String> childColumns,
            String parentTable,
            List<String> parentColumns
    ) {
    }

    private record ForeignKeyBuilder(
            String constraintName,
            String childTable,
            String parentTable,
            List<String> childColumns,
            List<String> parentColumns
    ) {
        private ForeignKeyBuilder(String constraintName, String childTable, String parentTable) {
            this(constraintName, childTable, parentTable, new ArrayList<>(), new ArrayList<>());
        }
    }
}
