package com.example.forklift_erp;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MySQL-only coverage for the V51 detail-level reversal contract. The test is
 * intentionally JDBC/Flyway based so it exercises the same DDL path as a
 * production upgrade rather than Hibernate's schema generator.
 */
@Tag("docker-integration")
class StockMovementReversalLineMigrationTests {

    private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.0.43");

    @Test
    void incompleteHistoricalReversalIsBlockedBeforePersistentV51Ddl() throws Exception {
        try (MySQLContainer<?> mysql = container()) {
            mysql.start();
            DatabaseHandle database = database(mysql);
            migrate(database, "50");
            long warehouseId = defaultWarehouse(database);

            long originalMovementId = insertHeader(database, "V51-INCOMPLETE-ORIGINAL", null);
            insertLine(database, originalMovementId, line(
                    "TEST", 951001L, warehouseId, -3, 10, 7,
                    null, "V51-ORIGINAL-LINE", "V51-ORIGINAL-LINE"));
            insertLine(database, originalMovementId, line(
                    "TEST", 951001L, warehouseId, 2, 4, 6,
                    null, "V51-ORIGINAL-LINE-2", "V51-ORIGINAL-LINE-2"));
            long reversalMovementId = insertHeader(
                    database, "V51-INCOMPLETE-REVERSAL", originalMovementId);
            insertLine(database, reversalMovementId, line(
                    "TEST", 951001L, warehouseId, 3, 7, 10,
                    null, "V51-REVERSAL-LINE", "V51-REVERSAL-LINE"));

            assertThat(runV40Preflight(database))
                    .contains("INVALID_STOCK_MOVEMENT_REVERSAL_LINES");
            assertThatThrownBy(() -> migrate(database, null))
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("chk_v51_stock_move_reversal_lines");
            assertThat(queryLong(database, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'stock_movement_line'
                      AND column_name = 'reversal_of_movement_line_id'
                    """)).isZero();
        }
    }

    @Test
    void completeHistoricalReversalBackfillsLinksAndRejectsBadFutureLines() throws Exception {
        try (MySQLContainer<?> mysql = container()) {
            mysql.start();
            DatabaseHandle database = database(mysql);
            migrate(database, "50");
            long warehouseId = defaultWarehouse(database);
            long lotId = insertLot(database, warehouseId, 951002L, "V51-LOT-1");
            long otherLotId = insertLot(database, warehouseId, 951002L, "V51-LOT-2");

            long originalMovementId = insertHeader(database, "V51-COMPLETE-ORIGINAL", null);
            long originalFirstLineId = insertLine(database, originalMovementId, line(
                    "TEST", 951002L, warehouseId, -3, 10, 7,
                    lotId, "V51-COMPLETE-LINE-1", "V51-COMPLETE-LINE-1"));
            long originalSecondLineId = insertLine(database, originalMovementId, line(
                    "TEST", 951002L, warehouseId, 2, 4, 6,
                    lotId, "V51-COMPLETE-LINE-2", "V51-COMPLETE-LINE-2"));
            long reversalMovementId = insertHeader(
                    database, "V51-COMPLETE-REVERSAL", originalMovementId);

            // Deliberately insert in the opposite order: V51 must pair by the
            // complete identity, not by incidental insertion order.
            insertLine(database, reversalMovementId, line(
                    "TEST", 951002L, warehouseId, -2, 6, 4,
                    lotId, "V51-COMPLETE-LINE-2", "V51-COMPLETE-LINE-2"));
            insertLine(database, reversalMovementId, line(
                    "TEST", 951002L, warehouseId, 3, 7, 10,
                    lotId, "V51-COMPLETE-LINE-1", "V51-COMPLETE-LINE-1"));

            migrate(database, null);
            assertThat(queryString(database, """
                    SELECT version
                    FROM flyway_schema_history
                    WHERE success = 1
                    ORDER BY installed_rank DESC
                    LIMIT 1
                    """)).isEqualTo("51");
            assertThat(queryLong(database, """
                    SELECT COUNT(*)
                    FROM stock_movement_line
                    WHERE movement_id = ?
                      AND reversal_of_movement_id = ?
                      AND reversal_of_movement_line_id IN (?, ?)
                    """, reversalMovementId, originalMovementId,
                    originalFirstLineId, originalSecondLineId)).isEqualTo(2);

            long newOriginalMovementId = insertHeader(database, "V51-FUTURE-ORIGINAL", null);
            long newOriginalLineId = insertLine(database, newOriginalMovementId, line(
                    "TEST", 951002L, warehouseId, -3, 10, 7,
                    lotId, "V51-FUTURE-LINE", "V51-FUTURE-LINE"));
            long newReversalMovementId = insertHeader(
                    database, "V51-FUTURE-REVERSAL", newOriginalMovementId);

            assertThatThrownBy(() -> insertLine(database, newReversalMovementId, line(
                    "TEST", 951002L, warehouseId, 3, 7, 10,
                    lotId, "V51-FUTURE-UNLINKED", "V51-FUTURE-UNLINKED")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("fk_stock_move_line_header_reversal");

            assertThatThrownBy(() -> insertLinkedLine(
                    database, newReversalMovementId, newOriginalMovementId,
                    newOriginalLineId, line(
                            "TEST", 951002L, warehouseId, 2, 8, 10,
                            lotId, "V51-FUTURE-WRONG-QUANTITY", "V51-FUTURE-LINE")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("fk_stock_move_line_reversal_core");

            assertThatThrownBy(() -> insertLinkedLine(
                    database, newReversalMovementId, newOriginalMovementId,
                    newOriginalLineId, line(
                            "TEST", 951002L, warehouseId, 3, 7, 10,
                            otherLotId, "V51-FUTURE-WRONG-LOT", "V51-FUTURE-LINE")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("fk_stock_move_line_reversal_core");

            LineSpec wrongCost = line(
                    "TEST", 951002L, warehouseId, 3, 7, 10,
                    lotId, "V51-FUTURE-WRONG-COST", "V51-FUTURE-LINE");
            assertThatThrownBy(() -> insertLinkedLine(
                    database, newReversalMovementId, newOriginalMovementId,
                    newOriginalLineId, wrongCost.withCostAmount("15.01")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("fk_stock_move_line_reversal_money");

            insertLinkedLine(database, newReversalMovementId, newOriginalMovementId,
                    newOriginalLineId, line(
                            "TEST", 951002L, warehouseId, 3, 7, 10,
                            lotId, "V51-FUTURE-VALID", "V51-FUTURE-LINE"));
            assertThatThrownBy(() -> insertLinkedLine(
                    database, newReversalMovementId, newOriginalMovementId,
                    newOriginalLineId, line(
                            "TEST", 951002L, warehouseId, 3, 7, 10,
                            lotId, "V51-FUTURE-DUPLICATE", "V51-FUTURE-LINE")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uk_stock_move_line_reversal");
        }
    }

    private MySQLContainer<?> container() {
        return new MySQLContainer<>(MYSQL_IMAGE)
                .withDatabaseName("forklift_erp_v51")
                .withUsername("forklift")
                .withPassword("forklift")
                .withUrlParam("useUnicode", "true")
                .withUrlParam("characterEncoding", "utf-8")
                .withUrlParam("serverTimezone", "Asia/Shanghai")
                .withUrlParam("useSSL", "false")
                .withUrlParam("allowPublicKeyRetrieval", "true");
    }

    private DatabaseHandle database(MySQLContainer<?> mysql) {
        return new DatabaseHandle(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    private void migrate(DatabaseHandle database, String target) {
        var configuration = Flyway.configure()
                .dataSource(database.jdbcUrl(), database.username(), database.password())
                .locations("classpath:db/migration")
                .validateMigrationNaming(true);
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target));
        }
        configuration.load().migrate();
    }

    private long defaultWarehouse(DatabaseHandle database) throws SQLException {
        return queryLong(database, """
                SELECT id FROM warehouse
                WHERE is_default = b'1'
                ORDER BY id
                LIMIT 1
                """);
    }

    private long insertHeader(
            DatabaseHandle database,
            String movementNo,
            Long reversalOfMovementId
    ) throws SQLException {
        return insertReturningId(database, """
                INSERT INTO stock_movement
                    (movement_no, movement_type, resource_type, source_type,
                     source_id, source_line_id, business_date, business_type,
                     reversal_of_movement_id, idempotency_key, created_at)
                VALUES (?, 'OUTBOUND', 'TEST', 'V51_TEST', 951000, 1,
                        '2091-06-01', 'V51_TEST', ?, ?, NOW(6))
                """, movementNo, reversalOfMovementId, movementNo);
    }

    private long insertLot(
            DatabaseHandle database,
            long warehouseId,
            long resourceId,
            String idempotencyKey
    ) throws SQLException {
        return insertReturningId(database, """
                INSERT INTO stock_lot
                    (resource_type, resource_id, warehouse_id, source_type,
                     source_id, received_business_date, original_quantity,
                     remaining_quantity, unit_cost, original_cost_amount,
                     remaining_cost_amount, freight_allocated, status,
                     idempotency_key, created_at, updated_at)
                VALUES ('TEST', ?, ?, 'V51_TEST', 951000, '2091-06-01',
                        10, 10, 5.000000, 50.00, 50.00, 0.00, 'OPEN',
                        ?, NOW(6), NOW(6))
                """, resourceId, warehouseId, idempotencyKey);
    }

    private long insertLine(DatabaseHandle database, long movementId, LineSpec line)
            throws SQLException {
        return insertReturningId(database, """
                INSERT INTO stock_movement_line
                    (movement_id, resource_type, resource_id, resource_code,
                     resource_name, warehouse_id, quantity_delta, before_quantity,
                     after_quantity, unit_cost, unit_revenue, line_amount,
                     cost_amount, stock_lot_id, source_line_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(6))
                """, movementId, line.resourceType(), line.resourceId(),
                line.resourceCode(), line.resourceName(), line.warehouseId(),
                line.quantityDelta(), line.beforeQuantity(), line.afterQuantity(),
                line.unitCost(), line.unitRevenue(), line.lineAmount(), line.costAmount(),
                line.stockLotId(), line.sourceLineId());
    }

    private long insertLinkedLine(
            DatabaseHandle database,
            long movementId,
            Long reversalOfMovementId,
            Long reversalOfMovementLineId,
            LineSpec line
    ) throws SQLException {
        return insertReturningId(database, """
                INSERT INTO stock_movement_line
                    (movement_id, reversal_of_movement_id,
                     reversal_of_movement_line_id, resource_type, resource_id,
                     resource_code, resource_name, warehouse_id, quantity_delta,
                     before_quantity, after_quantity, unit_cost, unit_revenue,
                     line_amount, cost_amount, stock_lot_id, source_line_id,
                     created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(6))
                """, movementId, reversalOfMovementId, reversalOfMovementLineId,
                line.resourceType(), line.resourceId(), line.resourceCode(), line.resourceName(),
                line.warehouseId(), line.quantityDelta(), line.beforeQuantity(), line.afterQuantity(),
                line.unitCost(), line.unitRevenue(), line.lineAmount(), line.costAmount(),
                line.stockLotId(), line.sourceLineId());
    }

    private LineSpec line(
            String resourceType,
            long resourceId,
            long warehouseId,
            int quantityDelta,
            int beforeQuantity,
            int afterQuantity,
            Long stockLotId,
            String resourceCode,
            String sourceLineId
    ) {
        BigDecimal unitCost = new BigDecimal("5.00");
        BigDecimal unitRevenue = new BigDecimal("12.00");
        BigDecimal magnitude = BigDecimal.valueOf(Math.abs((long) quantityDelta));
        return new LineSpec(
                resourceType, resourceId, warehouseId, quantityDelta,
                beforeQuantity, afterQuantity, stockLotId, resourceCode,
                resourceCode, unitCost, unitRevenue,
                unitRevenue.multiply(magnitude), unitCost.multiply(magnitude),
                Long.valueOf(sourceLineId.hashCode() & 0x7fffffff));
    }

    private Set<String> runV40Preflight(DatabaseHandle database) throws Exception {
        String sql = Files.readString(
                Path.of("scripts", "mysql-upgrade-preflight-v40.sql"),
                StandardCharsets.UTF_8);
        Set<String> issueTypes = new HashSet<>();
        try (Connection connection = connection(database);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                issueTypes.add(resultSet.getString("issue_type"));
            }
        }
        return issueTypes;
    }

    private long insertReturningId(DatabaseHandle database, String sql, Object... parameters)
            throws SQLException {
        try (Connection connection = connection(database);
             PreparedStatement statement = connection.prepareStatement(
                     sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(statement, parameters);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    private long queryLong(DatabaseHandle database, String sql, Object... parameters)
            throws SQLException {
        try (Connection connection = connection(database);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getLong(1);
            }
        }
    }

    private String queryString(DatabaseHandle database, String sql, Object... parameters)
            throws SQLException {
        try (Connection connection = connection(database);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getString(1);
            }
        }
    }

    private Connection connection(DatabaseHandle database) throws SQLException {
        return DriverManager.getConnection(
                database.jdbcUrl(), database.username(), database.password());
    }

    private void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }

    private record DatabaseHandle(String jdbcUrl, String username, String password) {
    }

    private record LineSpec(
            String resourceType,
            long resourceId,
            long warehouseId,
            int quantityDelta,
            int beforeQuantity,
            int afterQuantity,
            Long stockLotId,
            String resourceCode,
            String resourceName,
            BigDecimal unitCost,
            BigDecimal unitRevenue,
            BigDecimal lineAmount,
            BigDecimal costAmount,
            Long sourceLineId
    ) {
        LineSpec withCostAmount(String value) {
            return new LineSpec(
                    resourceType, resourceId, warehouseId, quantityDelta,
                    beforeQuantity, afterQuantity, stockLotId, resourceCode,
                    resourceName, unitCost, unitRevenue,
                    lineAmount, new BigDecimal(value), sourceLineId);
        }
    }
}
