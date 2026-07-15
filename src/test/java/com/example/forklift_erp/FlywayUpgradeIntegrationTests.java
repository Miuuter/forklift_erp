package com.example.forklift_erp;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("docker-integration")
class FlywayUpgradeIntegrationTests {
    private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.0.43");

    @Test
    void v36SnapshotUpgradesThroughV43WithoutValidationDrift() throws Exception {
        try (MySQLContainer<?> mysql = new MySQLContainer<>(MYSQL_IMAGE)
                .withDatabaseName("forklift_erp_upgrade")
                .withUsername("forklift")
                .withPassword("forklift")
                .withUrlParam("useUnicode", "true")
                .withUrlParam("characterEncoding", "utf-8")
                .withUrlParam("serverTimezone", "Asia/Shanghai")
                .withUrlParam("useSSL", "false")
                .withUrlParam("allowPublicKeyRetrieval", "true")) {
            mysql.start();

            migrateTo(mysql, "36");
            SnapshotIds snapshot = insertV36Snapshot(mysql);

            migrateTo(mysql, "40");
            LegacyFactIds legacyFacts = insertV40Facts(mysql, snapshot);

            Flyway latest = flyway(mysql, null);
            latest.migrate();
            latest.validate();

            assertThat(queryString(mysql, """
                    SELECT version
                    FROM flyway_schema_history
                    WHERE success = 1
                    ORDER BY installed_rank DESC
                    LIMIT 1
                    """)).isEqualTo("43");
            assertThat(queryString(mysql,
                    "SELECT request_id FROM payment_record WHERE id = ?",
                    legacyFacts.paymentId()))
                    .isEqualTo("LEGACY-PAYMENT:" + legacyFacts.paymentId());
            assertThat(queryLong(mysql,
                    "SELECT reorder_point FROM part_inventory WHERE id = ?",
                    snapshot.partId()))
                    .isEqualTo(5);
            assertThat(queryLong(mysql,
                    "SELECT error_details FROM data_import_job WHERE id = ?",
                    legacyFacts.importJobId()))
                    .isEqualTo(1);
            assertThat(queryLong(mysql, """
                    SELECT COUNT(*)
                    FROM information_schema.table_constraints
                    WHERE constraint_schema = DATABASE()
                      AND constraint_name IN (
                        'fk_rental_bill_rental',
                        'fk_payment_record_financial_event',
                        'fk_data_import_row_job',
                        'chk_stock_balance_available'
                      )
                    """)).isEqualTo(4);

            assertThatThrownBy(() -> executeUpdate(
                    mysql,
                    "UPDATE stock_balance SET available_quantity = -1 WHERE id = ?",
                    snapshot.stockBalanceId()
            )).isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_stock_balance_available");
        }
    }

    private SnapshotIds insertV36Snapshot(MySQLContainer<?> mysql) throws SQLException {
        long warehouseId = queryLong(mysql,
                "SELECT id FROM warehouse WHERE warehouse_code = 'DEFAULT'");
        long machineId = insertReturningId(mysql, """
                INSERT INTO machine_inventory
                    (vehicle_number, name, inventory_count, warehouse_id, created_at, updated_at)
                VALUES
                    ('UPGRADE-MACHINE-001', 'Upgrade fixture machine', 1, ?, NOW(6), NOW(6))
                """, warehouseId);
        long partId = insertReturningId(mysql, """
                INSERT INTO part_inventory
                    (part_code, part_name, quantity, warehouse_id, created_at, updated_at)
                VALUES
                    ('UPGRADE-PART-001', 'Upgrade fixture part', 3, ?, NOW(6), NOW(6))
                """, warehouseId);
        long rentalId = insertReturningId(mysql, """
                INSERT INTO rental_record
                    (rental_no, machine_id, destination, rental_price, start_date, end_date, status)
                VALUES
                    ('UPGRADE-RENTAL-001', ?, 'Upgrade destination', 3100.00,
                     '2091-01-01', '2091-01-31', 'RETURNED')
                """, machineId);
        long stockBalanceId = insertReturningId(mysql, """
                INSERT INTO stock_balance
                    (resource_type, resource_id, warehouse_id, available_quantity,
                     reserved_quantity, locked_quantity, version, created_at, updated_at)
                VALUES
                    ('PART', ?, ?, 3, 0, 0, 0, NOW(6), NOW(6))
                """, partId, warehouseId);
        return new SnapshotIds(warehouseId, machineId, partId, rentalId, stockBalanceId);
    }

    private LegacyFactIds insertV40Facts(
            MySQLContainer<?> mysql,
            SnapshotIds snapshot
    ) throws SQLException {
        long financialEventId = insertReturningId(mysql, """
                INSERT INTO financial_event
                    (event_no, event_type, amount, business_date, source_type, source_id,
                     idempotency_key, created_at)
                VALUES
                    ('UPGRADE-FE-001', 'REVENUE', 3100.00, '2091-01-31',
                     'RENTAL_BILL', NULL, 'UPGRADE-FE-001', NOW(6))
                """);
        long paymentId = insertReturningId(mysql, """
                INSERT INTO payment_record
                    (payment_no, direction, amount, payment_date, source_type, source_id,
                     financial_event_id, idempotency_key, created_at)
                VALUES
                    ('UPGRADE-PAY-001', 'RECEIPT', 3100.00, '2091-01-31',
                     'RENTAL_BILL', NULL, ?, 'UPGRADE-PAY-001', NOW(6))
                """, financialEventId);
        long rentalBillId = insertReturningId(mysql, """
                INSERT INTO rental_bill
                    (rental_id, bill_period, business_date, amount, status,
                     financial_event_id, created_at, updated_at)
                VALUES
                    (?, '2091-01-01', '2091-01-31', 3100.00, 'POSTED', ?, NOW(6), NOW(6))
                """, snapshot.rentalId(), financialEventId);
        executeUpdate(mysql,
                "UPDATE financial_event SET source_id = ? WHERE id = ?",
                rentalBillId,
                financialEventId);
        executeUpdate(mysql,
                "UPDATE payment_record SET source_id = ? WHERE id = ?",
                rentalBillId,
                paymentId);

        long importJobId = insertReturningId(mysql, """
                INSERT INTO data_import_job
                    (version, import_type, import_mode, original_file_name, staged_file_name,
                     file_fingerprint, status, total_rows, valid_rows, error_rows,
                     imported_rows, skipped_rows, created_at, updated_at)
                VALUES
                    (0, 'PARTS', 'BUSINESS_DOCUMENT', 'upgrade.xlsx', 'upgrade-staged.xlsx',
                     'upgrade-fingerprint', 'READY', 2, 1, 1, 0, 0, NOW(6), NOW(6))
                """);
        insertReturningId(mysql, """
                INSERT INTO data_import_row
                    (import_job_id, import_type, import_mode, file_fingerprint, sheet_name,
                     row_no, business_key, idempotency_key, created_at)
                VALUES
                    (?, 'PARTS', 'BUSINESS_DOCUMENT', 'upgrade-fingerprint', 'Parts',
                     2, 'UPGRADE-PART-001', 'UPGRADE-ROW-001', NOW(6))
                """, importJobId);
        return new LegacyFactIds(paymentId, importJobId);
    }

    private void migrateTo(MySQLContainer<?> mysql, String target) {
        flyway(mysql, target).migrate();
    }

    private Flyway flyway(MySQLContainer<?> mysql, String target) {
        var configuration = Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration")
                .validateMigrationNaming(true);
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target));
        }
        return configuration.load();
    }

    private long insertReturningId(
            MySQLContainer<?> mysql,
            String sql,
            Object... parameters
    ) throws SQLException {
        try (Connection connection = connection(mysql);
             PreparedStatement statement = connection.prepareStatement(
                     sql,
                     Statement.RETURN_GENERATED_KEYS
             )) {
            bind(statement, parameters);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    private int executeUpdate(
            MySQLContainer<?> mysql,
            String sql,
            Object... parameters
    ) throws SQLException {
        try (Connection connection = connection(mysql);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            return statement.executeUpdate();
        }
    }

    private long queryLong(
            MySQLContainer<?> mysql,
            String sql,
            Object... parameters
    ) throws SQLException {
        try (Connection connection = connection(mysql);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getLong(1);
            }
        }
    }

    private String queryString(
            MySQLContainer<?> mysql,
            String sql,
            Object... parameters
    ) throws SQLException {
        try (Connection connection = connection(mysql);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getString(1);
            }
        }
    }

    private Connection connection(MySQLContainer<?> mysql) throws SQLException {
        return DriverManager.getConnection(
                mysql.getJdbcUrl(),
                mysql.getUsername(),
                mysql.getPassword()
        );
    }

    private void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }

    private record SnapshotIds(
            long warehouseId,
            long machineId,
            long partId,
            long rentalId,
            long stockBalanceId
    ) {
    }

    private record LegacyFactIds(long paymentId, long importJobId) {
    }
}
