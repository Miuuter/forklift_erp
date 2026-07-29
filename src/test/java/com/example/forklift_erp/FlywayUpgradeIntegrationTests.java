package com.example.forklift_erp;

import com.example.forklift_erp.service.DataBackupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

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
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("docker-integration")
class FlywayUpgradeIntegrationTests {
    private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.0.43");

    @Test
    void v18AndV36SnapshotsUpgradeThroughV51WithoutValidationDrift() throws Exception {
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

            verifyUpgrade(new DatabaseHandle(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FORKLIFT_ERP_TEST_MYSQL_URL", matches = ".+")
    void localMysqlV18AndV36SnapshotsUpgradeThroughV51() throws Exception {
        String adminUrl = System.getenv("FORKLIFT_ERP_TEST_MYSQL_URL");
        String username = System.getenv("FORKLIFT_ERP_TEST_MYSQL_USERNAME");
        String password = System.getenv("FORKLIFT_ERP_TEST_MYSQL_PASSWORD");
        String databaseName = "forklift_erp_upgrade_"
                + UUID.randomUUID().toString().replace("-", "");
        DatabaseHandle admin = new DatabaseHandle(adminUrl, username, password);
        try (Connection connection = connection(admin); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + databaseName
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        try {
            verifyUpgrade(new DatabaseHandle(
                    databaseUrl(adminUrl, databaseName), username, password));
        } finally {
            if (!Boolean.parseBoolean(System.getenv("FORKLIFT_ERP_TEST_KEEP_DATABASE"))) {
                try (Connection connection = connection(admin);
                     Statement statement = connection.createStatement()) {
                    statement.execute("DROP DATABASE `" + databaseName + "`");
                }
            }
        }
    }

    private void verifyUpgrade(DatabaseHandle mysql) throws Exception {
            migrateTo(mysql, "18");
            LegacySaleIds legacySale = insertV18LegacyPartSale(mysql);

            migrateTo(mysql, "36");
            SnapshotIds snapshot = insertV36Snapshot(mysql);

            migrateTo(mysql, "40");
            LegacyFactIds legacyFacts = insertV40Facts(mysql, snapshot);
            insertPartialLegacySalesPosting(mysql, legacySale.orderId());
            LegacyReversalIds invalidReversal = insertInvalidV40PaymentReversal(mysql);
            LegacyLotReversalIds invalidLotReversal = insertInvalidV40LotReversal(mysql, snapshot);
            LegacyMovementReversalIds invalidMovementReversal =
                    insertInvalidV40MovementReversal(mysql);
            LegacyCounterpartyReversalIds counterpartyMismatch =
                    insertV40CounterpartyMismatchPaymentReversal(mysql);
            LegacyCostOverflowLotIds costOverflowLot =
                    insertV40CostOverflowLot(mysql, snapshot);
            assertThat(runV40Preflight(mysql))
                    .contains(
                            "LEGACY_SETTLEMENT_RECEIPT_GAP",
                            "FIFO_BALANCE_MISMATCH",
                            "MISSING_LEGACY_SALES_POSTING",
                            "INVALID_LINKED_PAYMENT_SIGN",
                             "INVALID_PAYMENT_REVERSAL_IDENTITY",
                             "INVALID_FINANCIAL_REVERSAL_IDENTITY",
                             "INVALID_LOT_CONSUMPTION_REVERSAL_IDENTITY",
                             "INVALID_STOCK_MOVEMENT_REVERSAL_IDENTITY",
                             "INVALID_STOCK_MOVEMENT_REVERSAL_LINES",
                             "STOCK_LOT_COST_AMOUNT_OVERFLOW"
                    )
                    .doesNotContain(
                            "INVALID_MOVEMENT_ARITHMETIC",
                            "INVALID_PAYMENT_EVENT_IDENTITY",
                            "INVALID_FINANCIAL_EVENT_SIGN"
                    );
            assertThat(queryString(mysql, """
                    SELECT version
                    FROM flyway_schema_history
                    WHERE success = 1
                    ORDER BY installed_rank DESC
                    LIMIT 1
                    """)).isEqualTo("40");
            deleteV40PaymentReversal(mysql, invalidReversal);
            deleteV40LotReversal(mysql, invalidLotReversal);
            deleteV40MovementReversal(mysql, invalidMovementReversal);
            assertThat(runV40Preflight(mysql))
                    .contains(
                            "INVALID_PAYMENT_REVERSAL_IDENTITY",
                            "INVALID_FINANCIAL_REVERSAL_IDENTITY",
                            "STOCK_LOT_COST_AMOUNT_OVERFLOW"
                    )
                    .doesNotContain(
                             "INVALID_LINKED_PAYMENT_SIGN",
                             "INVALID_LOT_CONSUMPTION_REVERSAL_IDENTITY",
                             "INVALID_STOCK_MOVEMENT_REVERSAL_IDENTITY",
                             "INVALID_STOCK_MOVEMENT_REVERSAL_LINES"
                    );
            deleteV40CounterpartyMismatchPaymentReversal(mysql, counterpartyMismatch);
            deleteV40CostOverflowLot(mysql, costOverflowLot);
            assertThat(runV40Preflight(mysql))
                    .doesNotContain(
                            "INVALID_LINKED_PAYMENT_SIGN",
                            "INVALID_PAYMENT_REVERSAL_IDENTITY",
                             "INVALID_FINANCIAL_REVERSAL_IDENTITY",
                             "INVALID_LOT_CONSUMPTION_REVERSAL_IDENTITY",
                             "INVALID_STOCK_MOVEMENT_REVERSAL_IDENTITY",
                             "INVALID_STOCK_MOVEMENT_REVERSAL_LINES",
                             "STOCK_LOT_COST_AMOUNT_OVERFLOW"
                     );

            Flyway latest = flyway(mysql, null);
            latest.migrate();
            latest.validate();

            assertThat(queryString(mysql, """
                    SELECT version
                    FROM flyway_schema_history
                    WHERE success = 1
                    ORDER BY installed_rank DESC
                    LIMIT 1
                    """)).isEqualTo("51");
            assertThat(queryString(mysql,
                    "SELECT request_id FROM payment_record WHERE id = ?",
                    legacyFacts.paymentId()))
                    .isEqualTo("LEGACY-PAYMENT:" + legacyFacts.paymentId());
            assertThat(queryLong(mysql,
                    "SELECT reversal_of_financial_event_id FROM payment_record WHERE id = ?",
                    legacyFacts.reversalPaymentId()))
                    .isEqualTo(legacyFacts.reversalOriginalEventId());
            assertThat(queryLong(mysql,
                    "SELECT reversal_of_event_id FROM financial_event WHERE id = ?",
                    legacyFacts.reversalEventId()))
                    .isEqualTo(legacyFacts.reversalOriginalEventId());
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
            assertThat(queryLong(mysql, """
                    SELECT COUNT(*)
                    FROM information_schema.table_constraints
                    WHERE constraint_schema = DATABASE()
                      AND constraint_name IN (
                        'fk_machine_inventory_supplier',
                        'fk_part_inventory_source_machine',
                         'fk_repair_record_machine',
                         'chk_payment_record_direction',
                         'fk_payment_record_event_identity',
                         'chk_payment_reversal_sign',
                        'fk_purchase_order_config_item',
                        'fk_purchase_order_config_value',
                        'chk_purchase_order_config_pair',
                        'fk_modification_line_new_config_value',
                        'fk_modification_line_new_config_value_item',
                        'fk_modification_line_machine_config_item',
                        'fk_modification_line_work_order_machine',
                        'fk_modification_line_machine_config_machine',
                        'chk_modification_line_config_pair',
                        'chk_repair_usage_consumption_warehouse',
                        'fk_stock_lot_consumption_identity',
                        'fk_stock_movement_line_lot_identity',
                        'fk_repair_usage_consumption_identity',
                        'fk_config_replace_log_machine_config'
                      )
                    """)).isEqualTo(20);
            assertThat(queryLong(mysql, """
                    SELECT COUNT(*)
                    FROM information_schema.table_constraints
                    WHERE constraint_schema = DATABASE()
                      AND constraint_name IN (
                        'chk_stock_move_line_reversal_pair',
                        'fk_stock_move_line_header_reversal',
                        'fk_stock_move_line_reversal_core',
                        'fk_stock_move_line_reversal_money'
                      )
                    """)).isEqualTo(4);
            assertThat(queryLong(mysql, """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name = 'request_idempotency'
                    """)).isEqualTo(1);
            assertThat(queryBigDecimal(mysql,
                    "SELECT received_amount FROM outbound_order WHERE id = ?",
                    legacySale.orderId())).isEqualByComparingTo("500.00");
            assertThat(queryBigDecimal(mysql,
                    "SELECT unit_sale_price FROM outbound_order WHERE id = ?",
                    legacySale.orderId())).isEqualByComparingTo("100.00");
            assertThat(queryBigDecimal(mysql,
                    "SELECT unit_revenue FROM stock_operation_log WHERE id = ?",
                    legacySale.stockOperationLogId())).isEqualByComparingTo("100.00");
            assertThat(queryString(mysql,
                    "SELECT unit_cost FROM stock_operation_log WHERE id = ?",
                    legacySale.stockOperationLogId())).isNull();
            assertThat(queryBigDecimal(mysql, """
                    SELECT COALESCE(SUM(amount), 0)
                    FROM payment_record
                    WHERE source_type = 'OUTBOUND_ORDER'
                      AND source_id = ?
                      AND direction = 'RECEIPT'
                    """, legacySale.orderId())).isEqualByComparingTo("500.00");
            assertThat(queryLong(mysql, """
                    SELECT COUNT(*)
                    FROM migration_exception
                    WHERE exception_type = 'UNVERIFIED_STOCK_LOG_COST'
                      AND source_type = 'STOCK_OPERATION_LOG'
                      AND source_id = ?
                      AND status = 'OPEN'
                    """, legacySale.stockOperationLogId())).isEqualTo(1);
            assertThat(queryLong(mysql, """
                    SELECT COUNT(*)
                    FROM migration_exception
                    WHERE exception_type = 'MISSING_LEGACY_SALES_POSTING'
                      AND source_type = 'OUTBOUND_ORDER'
                      AND source_id = ?
                      AND status = 'OPEN'
                    """, legacySale.orderId())).isEqualTo(1);
            assertThat(queryString(mysql, """
                    SELECT collation_name
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'request_idempotency'
                      AND column_name = 'request_id'
                    """)).isEqualTo("utf8mb4_bin");
            assertThat(queryLong(mysql, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND column_name = 'version'
                      AND table_name IN (
                        'rental_record', 'resource_attachment', 'data_import_job',
                        'vehicle_config_item', 'vehicle_config_value'
                      )
                      AND is_nullable = 'NO'
                    """)).isEqualTo(5);

            executeUpdate(mysql, """
                    INSERT INTO request_idempotency (scope, request_id)
                    VALUES ('UPGRADE_CASE', 'Request-Key'), ('UPGRADE_CASE', 'request-key')
                    """);
            assertThat(queryLong(mysql, """
                    SELECT COUNT(*) FROM request_idempotency
                    WHERE scope = 'UPGRADE_CASE'
                    """)).isEqualTo(2);

            assertThatThrownBy(() -> executeUpdate(
                    mysql,
                    "UPDATE stock_balance SET available_quantity = -1 WHERE id = ?",
                    snapshot.stockBalanceId()
            )).isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_stock_balance_available");
            assertThatThrownBy(() -> executeUpdate(
                    mysql,
                    "UPDATE stock_movement_line SET after_quantity = 2 WHERE id = ?",
                    legacyFacts.movementLineId()
            )).isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_stock_movement_line_arithmetic");
            assertThatThrownBy(() -> executeUpdate(
                    mysql,
                    "UPDATE rental_record SET version = NULL WHERE id = ?",
                    snapshot.rentalId()
            )).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> executeUpdate(mysql, """
                    INSERT INTO warehouse
                        (warehouse_code, warehouse_name, warehouse_type, is_default,
                         created_at, updated_at)
                    VALUES
                        ('SECOND-DEFAULT', 'Second default', 'MAIN', b'1', NOW(6), NOW(6))
                    """)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("uk_warehouse_single_default");
            assertThatThrownBy(() -> executeUpdate(mysql, """
                    INSERT INTO purchase_order
                        (purchase_no, resource_type, quantity, status, warehouse_id,
                         created_at, updated_at)
                    VALUES
                        ('INVALID-WAREHOUSE-PO', 'PART', 1, 'ORDERED', 999999999,
                         NOW(6), NOW(6))
                    """)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("fk_purchase_order_warehouse");
            verifyV50CompositeIdentityGuards(mysql, snapshot);
            verifyLedgerReversalGuards(mysql, snapshot);
            verifyJsonBackupRoundTrip(mysql);
            verifyJsonBackupRollbackOnInsertFailure(mysql);
    }

    private LegacySaleIds insertV18LegacyPartSale(DatabaseHandle mysql) throws SQLException {
        long warehouseId = queryLong(mysql,
                "SELECT id FROM warehouse WHERE warehouse_code = 'DEFAULT'");
        long partId = insertReturningId(mysql, """
                INSERT INTO part_inventory
                    (part_code, part_name, quantity, warehouse_id, purchase_price,
                     settlement_price, created_at, updated_at)
                VALUES
                    ('LEGACY-PART-001', 'Legacy five-unit sale', 0, ?, 40.00,
                     80.00, '2090-01-01 08:00:00', '2090-01-01 08:00:00')
                """, warehouseId);
        long stockOperationLogId = insertReturningId(mysql, """
                INSERT INTO stock_operation_log
                    (resource_type, operation_type, resource_id, resource_code,
                     resource_name, quantity, before_quantity, after_quantity,
                     operator, created_at)
                VALUES
                    ('PART', 'OUTBOUND', ?, 'LEGACY-PART-001',
                     'Legacy five-unit sale', 5, 5, 0, 'upgrade-fixture',
                     '2090-01-02 08:00:00')
                """, partId);
        long orderId = insertReturningId(mysql, """
                INSERT INTO outbound_order
                    (order_no, resource_type, resource_id, resource_code,
                     resource_name, quantity, settlement_price, sale_price,
                     payment_settled, sales_date, stock_operation_log_id,
                     created_at, updated_at)
                VALUES
                    ('LEGACY-SALE-001', 'PART', ?, 'LEGACY-PART-001',
                     'Legacy five-unit sale', 5, 500.00, 500.00,
                     b'1', '2090-01-02', ?,
                     '2090-01-02 08:00:00', '2090-01-02 08:00:00')
                """, partId, stockOperationLogId);
        return new LegacySaleIds(orderId, stockOperationLogId);
    }

    private SnapshotIds insertV36Snapshot(DatabaseHandle mysql) throws SQLException {
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
            DatabaseHandle mysql,
            SnapshotIds snapshot
    ) throws SQLException {
        long receivableEventId = insertReturningId(mysql, """
                INSERT INTO financial_event
                    (event_no, event_type, amount, business_date, source_type, source_id,
                     idempotency_key, created_at)
                VALUES
                    ('UPGRADE-FE-AR-001', 'ACCOUNTS_RECEIVABLE', 3100.00, '2091-01-31',
                     'RENTAL_BILL', NULL, 'UPGRADE-FE-AR-001', NOW(6))
                """);
        long cashEventId = insertReturningId(mysql, """
                INSERT INTO financial_event
                    (event_no, event_type, amount, business_date, source_type, source_id,
                     idempotency_key, created_at)
                VALUES
                    ('UPGRADE-FE-CASH-001', 'CASH_RECEIPT', 3100.00, '2091-01-31',
                     'RENTAL_BILL', NULL, 'UPGRADE-FE-CASH-001', NOW(6))
                """);
        long paymentId = insertReturningId(mysql, """
                INSERT INTO payment_record
                    (payment_no, direction, amount, payment_date, source_type, source_id,
                     financial_event_id, idempotency_key, created_at)
                VALUES
                    ('UPGRADE-PAY-001', 'RECEIPT', 3100.00, '2091-01-31',
                     'RENTAL_BILL', NULL, ?, 'UPGRADE-PAY-001', NOW(6))
                """, cashEventId);
        long rentalBillId = insertReturningId(mysql, """
                INSERT INTO rental_bill
                    (rental_id, bill_period, business_date, amount, status,
                     financial_event_id, created_at, updated_at)
                VALUES
                    (?, '2091-01-01', '2091-01-31', 3100.00, 'POSTED', ?, NOW(6), NOW(6))
                """, snapshot.rentalId(), receivableEventId);
        executeUpdate(mysql,
                "UPDATE financial_event SET source_id = ? WHERE id = ?",
                rentalBillId,
                receivableEventId);
        executeUpdate(mysql,
                "UPDATE financial_event SET source_id = ? WHERE id = ?",
                rentalBillId,
                cashEventId);
        executeUpdate(mysql,
                "UPDATE payment_record SET source_id = ? WHERE id = ?",
                rentalBillId,
                paymentId);

        long reversalOriginalEventId = insertReturningId(mysql, """
                INSERT INTO financial_event
                    (event_no, event_type, amount, business_date, source_type, source_id,
                     idempotency_key, created_at)
                VALUES
                    ('UPGRADE-FE-REVERSAL-ORIGINAL', 'CASH_RECEIPT', 10.00, '2091-01-30',
                     'OUTBOUND_ORDER', 799001, 'UPGRADE-FE-REVERSAL-ORIGINAL', NOW(6))
                """);
        long reversalOriginalPaymentId = insertReturningId(mysql, """
                INSERT INTO payment_record
                    (payment_no, direction, amount, payment_date, source_type, source_id,
                     financial_event_id, idempotency_key, created_at)
                VALUES
                    ('UPGRADE-PAY-REVERSAL-ORIGINAL', 'RECEIPT', 10.00, '2091-01-30',
                     'OUTBOUND_ORDER', 799001, ?, 'UPGRADE-PAY-REVERSAL-ORIGINAL', NOW(6))
                """, reversalOriginalEventId);
        long reversalEventId = insertReturningId(mysql, """
                INSERT INTO financial_event
                    (event_no, event_type, amount, business_date, source_type, source_id,
                     reversal_of_event_id, idempotency_key, created_at)
                VALUES
                    ('UPGRADE-FE-REVERSAL', 'CASH_RECEIPT', -10.00, '2091-01-31',
                     'OUTBOUND_ORDER', 799001, NULL, 'UPGRADE-FE-REVERSAL', NOW(6))
                """);
        long reversalPaymentId = insertReturningId(mysql, """
                INSERT INTO payment_record
                    (payment_no, direction, amount, payment_date, source_type, source_id,
                     financial_event_id, reversal_of_payment_id, idempotency_key, created_at)
                VALUES
                    ('UPGRADE-PAY-REVERSAL', 'RECEIPT', -10.00, '2091-01-31',
                     'OUTBOUND_ORDER', 799001, ?, ?, 'UPGRADE-PAY-REVERSAL', NOW(6))
                """, reversalEventId, reversalOriginalPaymentId);

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
        long movementId = insertReturningId(mysql, """
                INSERT INTO stock_movement
                    (movement_no, movement_type, resource_type, business_date,
                     business_type, idempotency_key, created_at)
                VALUES
                    ('UPGRADE-MOVE-001', 'ADJUSTMENT', 'PART', '2091-01-31',
                     'UPGRADE_FIXTURE', 'UPGRADE-MOVE-001', NOW(6))
                """);
        long movementLineId = insertReturningId(mysql, """
                INSERT INTO stock_movement_line
                    (movement_id, resource_type, resource_id, resource_code,
                     warehouse_id, quantity_delta, before_quantity, after_quantity,
                     created_at)
                VALUES
                    (?, 'PART', ?, 'UPGRADE-PART-001', ?, 3, 0, 3, NOW(6))
                """, movementId, snapshot.partId(), snapshot.warehouseId());
        return new LegacyFactIds(
                paymentId,
                importJobId,
                movementLineId,
                reversalPaymentId,
                reversalEventId,
                reversalOriginalEventId
        );
    }

    private LegacyReversalIds insertInvalidV40PaymentReversal(DatabaseHandle mysql) throws SQLException {
        long originalEventId = insertReturningId(mysql, """
                INSERT INTO financial_event
                    (event_no, event_type, amount, business_date, source_type, source_id,
                     idempotency_key, created_at)
                VALUES
                    ('UPGRADE-FE-INVALID-ORIGINAL', 'CASH_RECEIPT', 10.00, '2091-02-01',
                     'OUTBOUND_ORDER', 799002, 'UPGRADE-FE-INVALID-ORIGINAL', NOW(6))
                """);
        long originalPaymentId = insertReturningId(mysql, """
                INSERT INTO payment_record
                    (payment_no, direction, amount, payment_date, source_type, source_id,
                     financial_event_id, idempotency_key, created_at)
                VALUES
                    ('UPGRADE-PAY-INVALID-ORIGINAL', 'RECEIPT', 10.00, '2091-02-01',
                     'OUTBOUND_ORDER', 799002, ?, 'UPGRADE-PAY-INVALID-ORIGINAL', NOW(6))
                """, originalEventId);
        long invalidEventId = insertReturningId(mysql, """
                INSERT INTO financial_event
                    (event_no, event_type, amount, business_date, source_type, source_id,
                     reversal_of_event_id, idempotency_key, created_at)
                VALUES
                    ('UPGRADE-FE-INVALID-REVERSAL', 'CASH_RECEIPT', 9.00, '2091-02-02',
                     'OUTBOUND_ORDER', 799002, ?, 'UPGRADE-FE-INVALID-REVERSAL', NOW(6))
                """, originalEventId);
        long invalidPaymentId = insertReturningId(mysql, """
                INSERT INTO payment_record
                    (payment_no, direction, amount, payment_date, source_type, source_id,
                     financial_event_id, reversal_of_payment_id, idempotency_key, created_at)
                VALUES
                    ('UPGRADE-PAY-INVALID-REVERSAL', 'RECEIPT', 9.00, '2091-02-02',
                     'OUTBOUND_ORDER', 799002, ?, ?, 'UPGRADE-PAY-INVALID-REVERSAL', NOW(6))
                """, invalidEventId, originalPaymentId);
        return new LegacyReversalIds(
                originalPaymentId,
                originalEventId,
                invalidPaymentId,
                invalidEventId
        );
    }

    private void deleteV40PaymentReversal(DatabaseHandle mysql, LegacyReversalIds ids) throws SQLException {
        executeUpdate(mysql, "DELETE FROM payment_record WHERE id IN (?, ?)",
                ids.invalidPaymentId(), ids.originalPaymentId());
        executeUpdate(mysql, "DELETE FROM financial_event WHERE id IN (?, ?)",
                ids.invalidEventId(), ids.originalEventId());
    }

    private LegacyCounterpartyReversalIds insertV40CounterpartyMismatchPaymentReversal(
            DatabaseHandle mysql
    ) throws SQLException {
        long originalEventId = insertReturningId(mysql, """
                INSERT INTO financial_event
                    (event_no, event_type, amount, business_date, source_type, source_id,
                     counterparty_type, counterparty_id, counterparty_name,
                     idempotency_key, created_at)
                VALUES
                    ('UPGRADE-FE-COUNTERPARTY-ORIGINAL', 'CASH_RECEIPT', 10.00,
                     '2091-02-03', 'OUTBOUND_ORDER', 799006,
                     'CUSTOMER', 910001, 'Original customer',
                     'UPGRADE-FE-COUNTERPARTY-ORIGINAL', NOW(6))
                """);
        long originalPaymentId = insertReturningId(mysql, """
                INSERT INTO payment_record
                    (payment_no, direction, amount, payment_date, source_type, source_id,
                     financial_event_id, idempotency_key, created_at)
                VALUES
                    ('UPGRADE-PAY-COUNTERPARTY-ORIGINAL', 'RECEIPT', 10.00,
                     '2091-02-03', 'OUTBOUND_ORDER', 799006, ?,
                     'UPGRADE-PAY-COUNTERPARTY-ORIGINAL', NOW(6))
                """, originalEventId);
        long reversalEventId = insertReturningId(mysql, """
                INSERT INTO financial_event
                    (event_no, event_type, amount, business_date, source_type, source_id,
                     counterparty_type, counterparty_id, counterparty_name,
                     reversal_of_event_id, idempotency_key, created_at)
                VALUES
                    ('UPGRADE-FE-COUNTERPARTY-REVERSAL', 'CASH_RECEIPT', -10.00,
                     '2091-02-04', 'OUTBOUND_ORDER', 799006,
                     'CUSTOMER', 910002, 'Different customer', ?,
                     'UPGRADE-FE-COUNTERPARTY-REVERSAL', NOW(6))
                """, originalEventId);
        long reversalPaymentId = insertReturningId(mysql, """
                INSERT INTO payment_record
                    (payment_no, direction, amount, payment_date, source_type, source_id,
                     financial_event_id, reversal_of_payment_id, idempotency_key, created_at)
                VALUES
                    ('UPGRADE-PAY-COUNTERPARTY-REVERSAL', 'RECEIPT', -10.00,
                     '2091-02-04', 'OUTBOUND_ORDER', 799006, ?, ?,
                     'UPGRADE-PAY-COUNTERPARTY-REVERSAL', NOW(6))
                """, reversalEventId, originalPaymentId);
        return new LegacyCounterpartyReversalIds(
                originalPaymentId, originalEventId, reversalPaymentId, reversalEventId);
    }

    private void deleteV40CounterpartyMismatchPaymentReversal(
            DatabaseHandle mysql,
            LegacyCounterpartyReversalIds ids
    ) throws SQLException {
        executeUpdate(mysql, "DELETE FROM payment_record WHERE id IN (?, ?)",
                ids.reversalPaymentId(), ids.originalPaymentId());
        executeUpdate(mysql, "DELETE FROM financial_event WHERE id IN (?, ?)",
                ids.reversalEventId(), ids.originalEventId());
    }

    private LegacyCostOverflowLotIds insertV40CostOverflowLot(
            DatabaseHandle mysql,
            SnapshotIds snapshot
    ) throws SQLException {
        long lotId = insertReturningId(mysql, """
                INSERT INTO stock_lot
                    (resource_type, resource_id, warehouse_id, source_type,
                     source_id, received_business_date, original_quantity,
                     remaining_quantity, unit_cost, freight_allocated, status,
                     idempotency_key, created_at, updated_at)
                VALUES
                    ('PART', ?, ?, 'UPGRADE_COST_OVERFLOW', 799007, '2091-02-05',
                     2000000000, 2000000000, 9999999999.99, 0, 'OPEN',
                     'UPGRADE-LOT-COST-OVERFLOW', NOW(6), NOW(6))
                """, snapshot.partId(), snapshot.warehouseId());
        return new LegacyCostOverflowLotIds(lotId);
    }

    private void deleteV40CostOverflowLot(
            DatabaseHandle mysql,
            LegacyCostOverflowLotIds ids
    ) throws SQLException {
        executeUpdate(mysql, "DELETE FROM stock_lot WHERE id = ?", ids.lotId());
    }

    private LegacyLotReversalIds insertInvalidV40LotReversal(
            DatabaseHandle mysql,
            SnapshotIds snapshot
    ) throws SQLException {
        long lotId = insertReturningId(mysql, """
                INSERT INTO stock_lot
                    (resource_type, resource_id, warehouse_id, source_type,
                     source_id, received_business_date, original_quantity,
                     remaining_quantity, unit_cost, freight_allocated, status,
                     idempotency_key, created_at, updated_at)
                VALUES
                    ('PART', ?, ?, 'UPGRADE_LOT', 799003, '2091-02-01', 2,
                     0, 10.00, 0, 'CLOSED', 'UPGRADE-LOT-INVALID-REVERSAL',
                     NOW(6), NOW(6))
                """, snapshot.partId(), snapshot.warehouseId());
        long originalConsumptionId = insertReturningId(mysql, """
                INSERT INTO stock_lot_consumption
                    (stock_lot_id, resource_type, resource_id, warehouse_id,
                     source_type, source_id, source_line_id, quantity, unit_cost,
                     total_cost, business_date, idempotency_key, created_at)
                VALUES
                    (?, 'PART', ?, ?, 'UPGRADE_USE', 799004, 31, 2, 10.00,
                     20.00, '2091-02-01', 'UPGRADE-CONS-INVALID-ORIGINAL', NOW(6))
                """, lotId, snapshot.partId(), snapshot.warehouseId());
        long invalidConsumptionId = insertReturningId(mysql, """
                INSERT INTO stock_lot_consumption
                    (stock_lot_id, resource_type, resource_id, warehouse_id,
                     source_type, source_id, source_line_id, quantity, unit_cost,
                     total_cost, business_date, reversal_of_consumption_id,
                     idempotency_key, created_at)
                VALUES
                    (?, 'PART', ?, ?, 'UPGRADE_USE_REVERSAL', 799004, 31, -1, 10.00,
                     -10.00, '2091-02-02', ?, 'UPGRADE-CONS-INVALID-REVERSAL', NOW(6))
                """, lotId, snapshot.partId(), snapshot.warehouseId(), originalConsumptionId);
        return new LegacyLotReversalIds(lotId, originalConsumptionId, invalidConsumptionId);
    }

    private void deleteV40LotReversal(DatabaseHandle mysql, LegacyLotReversalIds ids) throws SQLException {
        executeUpdate(mysql, "DELETE FROM stock_lot_consumption WHERE id IN (?, ?)",
                ids.invalidConsumptionId(), ids.originalConsumptionId());
        executeUpdate(mysql, "DELETE FROM stock_lot WHERE id = ?", ids.lotId());
    }

    private LegacyMovementReversalIds insertInvalidV40MovementReversal(
            DatabaseHandle mysql
    ) throws SQLException {
        long originalMovementId = insertReturningId(mysql, """
                INSERT INTO stock_movement
                    (movement_no, movement_type, resource_type, source_type,
                     source_id, source_line_id, business_date, business_type,
                     idempotency_key, created_at)
                VALUES
                    ('UPGRADE-MOVE-INVALID-ORIGINAL', 'OUTBOUND', 'PART',
                     'OUTBOUND_ORDER', 799005, 41, '2091-02-01',
                     'UPGRADE_FIXTURE', 'UPGRADE-MOVE-INVALID-ORIGINAL', NOW(6))
                """);
        long invalidMovementId = insertReturningId(mysql, """
                INSERT INTO stock_movement
                    (movement_no, movement_type, resource_type, source_type,
                     source_id, source_line_id, business_date, business_type,
                     reversal_of_movement_id, idempotency_key, created_at)
                VALUES
                    ('UPGRADE-MOVE-INVALID-REVERSAL', 'INBOUND', 'MACHINE',
                     'OUTBOUND_ORDER', 799005, 41, '2091-02-02',
                     'UPGRADE_FIXTURE', ?, 'UPGRADE-MOVE-INVALID-REVERSAL', NOW(6))
                """, originalMovementId);
        return new LegacyMovementReversalIds(originalMovementId, invalidMovementId);
    }

    private void deleteV40MovementReversal(
            DatabaseHandle mysql,
            LegacyMovementReversalIds ids
    ) throws SQLException {
        executeUpdate(mysql, "DELETE FROM stock_movement WHERE id = ?", ids.invalidMovementId());
        executeUpdate(mysql, "DELETE FROM stock_movement WHERE id = ?", ids.originalMovementId());
    }

    private void insertPartialLegacySalesPosting(DatabaseHandle mysql, long orderId) throws SQLException {
        insertReturningId(mysql, """
                INSERT INTO financial_event
                    (event_no, event_type, amount, business_date, source_type, source_id,
                     idempotency_key, created_at)
                VALUES
                    ('UPGRADE-PARTIAL-SALE-REV', 'REVENUE', 500.00, '2090-01-02',
                     'OUTBOUND_ORDER', ?, 'UPGRADE-PARTIAL-SALE-REV', NOW(6))
                """, orderId);
    }

    private void verifyV50CompositeIdentityGuards(
            DatabaseHandle mysql,
            SnapshotIds snapshot
    ) throws SQLException {
        long cashEventId = insertReturningId(mysql, """
                INSERT INTO financial_event
                    (event_no, event_type, amount, business_date, source_type, source_id,
                     idempotency_key, created_at)
                VALUES
                    ('UPGRADE-GUARD-CASH', 'CASH_RECEIPT', 10.00, '2091-02-01',
                     'OUTBOUND_ORDER', 700001, 'UPGRADE-GUARD-CASH', NOW(6))
                """);
        assertThatThrownBy(() -> executeUpdate(mysql, """
                INSERT INTO payment_record
                    (payment_no, request_id, direction, amount, payment_date,
                     source_type, source_id, financial_event_id, idempotency_key, created_at)
                VALUES
                    ('UPGRADE-GUARD-PAY', 'UPGRADE-GUARD-PAY', 'RECEIPT', 11.00,
                     '2091-02-01', 'OUTBOUND_ORDER', 700001, ?,
                     'UPGRADE-GUARD-PAY', NOW(6))
                """, cashEventId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_payment_record_event_identity");

        long configItemId = insertReturningId(mysql, """
                INSERT INTO config_item
                    (category, item_name, item_code, input_type, created_at, updated_at)
                VALUES
                    ('UPGRADE', 'Upgrade guard item', 'UPGRADE-GUARD-ITEM',
                     'SELECT', NOW(6), NOW(6))
                """);
        long configValueId = insertReturningId(mysql, """
                INSERT INTO config_value
                    (config_item_id, value_label, value_code, is_default,
                     sort_order, created_at)
                VALUES
                    (?, 'Upgrade guard value', 'UPGRADE-GUARD-VALUE', b'1', 0, NOW(6))
                """, configItemId);
        long machineConfigId = insertReturningId(mysql, """
                INSERT INTO machine_config
                    (machine_id, config_item_id, config_value_id, item_name,
                     selected_value, created_at, updated_at)
                VALUES
                    (?, ?, ?, 'Upgrade guard item', 'Upgrade guard value', NOW(6), NOW(6))
                """, snapshot.machineId(), configItemId, configValueId);
        long otherMachineId = insertReturningId(mysql, """
                INSERT INTO machine_inventory
                    (vehicle_number, name, inventory_count, warehouse_id,
                     stock_status, created_at, updated_at)
                VALUES
                    ('UPGRADE-MACHINE-OTHER', 'Other guard machine', 0, ?,
                     'PENDING_INBOUND', NOW(6), NOW(6))
                """, snapshot.warehouseId());

        assertThatThrownBy(() -> executeUpdate(mysql, """
                INSERT INTO config_replace_log
                    (machine_id, machine_config_id, item_name, new_value, created_at)
                VALUES (?, ?, 'Upgrade guard item', 'Mismatch', NOW(6))
                """, otherMachineId, machineConfigId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_config_replace_log_machine_config");

        long workOrderId = insertReturningId(mysql, """
                INSERT INTO modification_work_order
                    (work_order_no, machine_id, status, created_at, updated_at)
                VALUES
                    ('UPGRADE-GUARD-MWO', ?, 'WAITING_PARTS', NOW(6), NOW(6))
                """, otherMachineId);
        assertThatThrownBy(() -> executeUpdate(mysql, """
                INSERT INTO modification_work_order_line
                    (work_order_id, machine_id, machine_config_id, config_item_id,
                     item_name, quantity, old_part_action, price_difference,
                     created_at, updated_at)
                VALUES
                    (?, ?, ?, ?, 'Upgrade guard item', 1, 'STOCK_IN', 0,
                     NOW(6), NOW(6))
                """, workOrderId, otherMachineId, machineConfigId, configItemId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_modification_line_machine_config_machine");

        long repairId = insertReturningId(mysql, """
                INSERT INTO repair_record
                    (repair_date, machine_id, status, created_at, updated_at)
                VALUES (NOW(6), ?, 'PENDING', NOW(6), NOW(6))
                """, snapshot.machineId());
        long usageId = insertReturningId(mysql, """
                INSERT INTO repair_part_usage
                    (repair_id, part_id, quantity, warehouse_id, created_at)
                VALUES (?, ?, 1, ?, NOW(6))
                """, repairId, snapshot.partId(), snapshot.warehouseId());
        long wrongLotId = insertReturningId(mysql, """
                INSERT INTO stock_lot
                    (resource_type, resource_id, warehouse_id, source_type,
                     source_id, source_line_id, received_business_date,
                     original_quantity, remaining_quantity, unit_cost,
                     original_cost_amount, remaining_cost_amount,
                     freight_allocated, status, idempotency_key, created_at, updated_at)
                VALUES
                    ('MACHINE', ?, ?, 'UPGRADE_GUARD', ?, ?, '2091-02-01',
                     1, 0, 10.00, 10.00, 0.00,
                     0, 'CLOSED', 'UPGRADE-GUARD-WRONG-LOT',
                     NOW(6), NOW(6))
                """, snapshot.partId(), snapshot.warehouseId(), repairId, usageId);
        long wrongConsumptionId = insertReturningId(mysql, """
                INSERT INTO stock_lot_consumption
                    (stock_lot_id, resource_type, resource_id, warehouse_id,
                     source_type, source_id, source_line_id, quantity,
                     unit_cost, total_cost, business_date, idempotency_key, created_at)
                VALUES
                    (?, 'MACHINE', ?, ?, 'REPAIR', ?, ?, 1,
                     10.00, 10.00, '2091-02-01', 'UPGRADE-GUARD-WRONG-CONSUMPTION', NOW(6))
                """, wrongLotId, snapshot.partId(), snapshot.warehouseId(), repairId, usageId);
        assertThatThrownBy(() -> executeUpdate(mysql, """
                UPDATE repair_part_usage
                SET stock_lot_consumption_id = ?
                WHERE id = ?
                """, wrongConsumptionId, usageId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_repair_usage_consumption_identity");
    }

    private void verifyLedgerReversalGuards(
            DatabaseHandle mysql,
            SnapshotIds snapshot
    ) throws SQLException {
        assertThat(queryLong(mysql, """
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE constraint_schema = DATABASE()
                  AND constraint_name IN (
                     'fk_fin_event_reversal_identity',
                     'fk_lot_cons_reversal_identity',
                     'fk_stock_move_reversal_identity',
                     'fk_payment_reversal_identity',
                      'chk_payment_reversal_event_pair',
                      'chk_payment_reversal_sign',
                      'chk_financial_event_type',
                      'chk_financial_event_sign',
                      'chk_financial_cash_source_line',
                      'fk_payment_reversal_payment_event',
                      'fk_payment_reversal_fin_event'
                   )
                """)).isEqualTo(11);
        verifyFinancialEventReversalGuards(mysql);
        verifyPaymentReversalGuards(mysql);
        verifyStockMovementReversalGuards(mysql);
        verifyStockConsumptionReversalGuards(mysql, snapshot);
    }

    private void verifyFinancialEventReversalGuards(DatabaseHandle mysql) throws SQLException {
        assertThatThrownBy(() -> executeUpdate(mysql, """
                INSERT INTO financial_event
                    (event_no, event_type, amount, business_date, source_type,
                     source_id, idempotency_key, created_at)
                VALUES
                    ('LEDGER-FE-UNKNOWN-TYPE', 'UNKNOWN_EVENT', 1.00,
                     '2091-03-01', 'OUTBOUND_ORDER', 810000,
                     'LEDGER-FE-UNKNOWN-TYPE', NOW(6))
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_financial_event_type");
        assertThatThrownBy(() -> executeUpdate(mysql, """
                INSERT INTO financial_event
                    (event_no, event_type, amount, business_date, source_type,
                     source_id, source_line_id, idempotency_key, created_at)
                VALUES
                    ('LEDGER-FE-CASH-SOURCE-LINE', 'CASH_RECEIPT', 1.00,
                     '2091-03-01', 'OUTBOUND_ORDER', 810000, 1,
                     'LEDGER-FE-CASH-SOURCE-LINE', NOW(6))
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_financial_cash_source_line");
        long originalId = insertReturningId(mysql, """
                INSERT INTO financial_event
                    (event_no, event_type, amount, business_date, source_type,
                     source_id, source_line_id, counterparty_type, counterparty_id,
                     counterparty_name, idempotency_key, created_at)
                VALUES
                    ('LEDGER-FE-ORIGINAL', 'COST_OF_GOODS_SOLD', 10.00,
                     '2091-03-01', 'OUTBOUND_ORDER', 810001, 17,
                     'CUSTOMER', 810101, 'Ledger customer',
                     'LEDGER-FE-ORIGINAL', NOW(6))
                """);
        assertThatThrownBy(() -> executeUpdate(mysql, """
                INSERT INTO financial_event
                    (event_no, event_type, amount, business_date, source_type,
                     source_id, source_line_id, reversal_of_event_id, created_at)
                VALUES
                    ('LEDGER-FE-WRONG-AMOUNT', 'COST_OF_GOODS_SOLD', -9.00,
                     '2091-03-02', 'OUTBOUND_ORDER', 810001, 17, ?, NOW(6))
                """, originalId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_fin_event_reversal_identity");
        assertThatThrownBy(() -> executeUpdate(mysql, """
                INSERT INTO financial_event
                    (event_no, event_type, amount, business_date, source_type,
                     source_id, source_line_id, reversal_of_event_id, created_at)
                VALUES
                    ('LEDGER-FE-WRONG-SOURCE', 'COST_OF_GOODS_SOLD', -10.00,
                     '2091-03-02', 'OUTBOUND_ORDER', 810002, 17, ?, NOW(6))
                """, originalId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_fin_event_reversal_identity");
        long reversalId = insertReturningId(mysql, """
                INSERT INTO financial_event
                    (event_no, event_type, amount, business_date, source_type,
                     source_id, source_line_id, counterparty_type, counterparty_id,
                     counterparty_name, reversal_of_event_id,
                     idempotency_key, created_at)
                VALUES
                    ('LEDGER-FE-REVERSAL', 'COST_OF_GOODS_SOLD', -10.00,
                     '2091-03-02', 'OUTBOUND_ORDER', 810001, 17,
                     'CUSTOMER', 810101, 'Ledger customer', ?,
                     'LEDGER-FE-REVERSAL', NOW(6))
                """, originalId);
        assertThat(queryLong(mysql,
                "SELECT COUNT(*) FROM financial_event WHERE reversal_of_event_id = ?",
                originalId)).isEqualTo(1);
        long counterpartyOriginalId = insertReturningId(mysql, """
                INSERT INTO financial_event
                    (event_no, event_type, amount, business_date, source_type,
                     source_id, source_line_id, counterparty_type, counterparty_id,
                     counterparty_name, idempotency_key, created_at)
                VALUES
                    ('LEDGER-FE-COUNTERPARTY-ORIGINAL', 'COST_OF_GOODS_SOLD', 8.00,
                     '2091-03-01', 'OUTBOUND_ORDER', 810003, 18,
                     'CUSTOMER', 810101, 'Ledger customer',
                     'LEDGER-FE-COUNTERPARTY-ORIGINAL', NOW(6))
                """);
        assertThatThrownBy(() -> executeUpdate(mysql, """
                INSERT INTO financial_event
                    (event_no, event_type, amount, business_date, source_type,
                     source_id, source_line_id, counterparty_type, counterparty_id,
                     counterparty_name, reversal_of_event_id, created_at)
                VALUES
                    ('LEDGER-FE-WRONG-COUNTERPARTY', 'COST_OF_GOODS_SOLD', -8.00,
                     '2091-03-02', 'OUTBOUND_ORDER', 810003, 18,
                     'CUSTOMER', 810102, 'Other customer', ?, NOW(6))
                """, counterpartyOriginalId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_fin_event_reversal_identity");
        assertThatThrownBy(() -> executeUpdate(mysql, """
                INSERT INTO financial_event
                    (event_no, event_type, amount, business_date, source_type,
                     source_id, source_line_id, reversal_of_event_id, created_at)
                VALUES
                    ('LEDGER-FE-CHAIN', 'COST_OF_GOODS_SOLD', 10.00,
                     '2091-03-03', 'OUTBOUND_ORDER', 810001, 17, ?, NOW(6))
                """, reversalId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_financial_event_sign");
        assertThatThrownBy(() -> executeUpdate(mysql, """
                INSERT INTO financial_event
                    (event_no, event_type, amount, business_date, source_type,
                     source_id, source_line_id, reversal_of_event_id, created_at)
                VALUES
                    ('LEDGER-FE-DUPLICATE', 'COST_OF_GOODS_SOLD', -10.00,
                     '2091-03-02', 'OUTBOUND_ORDER', 810001, 17, ?, NOW(6))
                """, originalId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uk_financial_event_reversal");
        assertThatThrownBy(() -> executeUpdate(mysql, """
                INSERT INTO financial_event
                    (id, event_no, event_type, amount, business_date, source_type,
                     source_id, source_line_id, reversal_of_event_id, created_at)
                VALUES
                    (9000000001, 'LEDGER-FE-SELF', 'COST_OF_GOODS_SOLD', 0.00,
                     '2091-03-03', 'OUTBOUND_ORDER', 810001, 17,
                     9000000001, NOW(6))
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_fin_event_reversal_identity");
    }

    private void verifyPaymentReversalGuards(DatabaseHandle mysql) throws SQLException {
        assertThatThrownBy(() -> insertCashEvent(
                mysql, "LEDGER-PAY-EVENT-UNLINKED-NEGATIVE", "-1.00", 815005L))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_financial_event_sign");

        long wrongAmountOriginalEventId = insertCashEvent(
                mysql, "LEDGER-PAY-EVENT-WRONG-AMOUNT-ORIGINAL", "10.00", 815011L);
        long wrongAmountOriginalPaymentId = insertReceipt(
                mysql, "LEDGER-PAY-WRONG-AMOUNT-ORIGINAL", "10.00", 815011L,
                wrongAmountOriginalEventId, null);
        long wrongAmountEventId = insertCashEvent(
                mysql, "LEDGER-PAY-EVENT-WRONG-AMOUNT", "-10.00", 815011L,
                wrongAmountOriginalEventId);
        assertThatThrownBy(() -> insertReceipt(
                mysql, "LEDGER-PAY-WRONG-AMOUNT", "-9.00", 815011L,
                wrongAmountEventId, wrongAmountOriginalPaymentId,
                wrongAmountOriginalEventId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_payment_record_event_identity");

        long wrongSourceOriginalEventId = insertCashEvent(
                mysql, "LEDGER-PAY-EVENT-WRONG-SOURCE-ORIGINAL", "10.00", 815021L);
        long wrongSourceOriginalPaymentId = insertReceipt(
                mysql, "LEDGER-PAY-WRONG-SOURCE-ORIGINAL", "10.00", 815021L,
                wrongSourceOriginalEventId, null);
        long wrongSourceEventId = insertCashEvent(
                mysql, "LEDGER-PAY-EVENT-WRONG-SOURCE", "-10.00", 815021L,
                wrongSourceOriginalEventId);
        assertThatThrownBy(() -> insertReceipt(
                mysql, "LEDGER-PAY-WRONG-SOURCE", "-10.00", 815022L,
                wrongSourceEventId, wrongSourceOriginalPaymentId,
                wrongSourceOriginalEventId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_payment_record_event_identity");

        long originalEventId = insertCashEvent(
                mysql, "LEDGER-PAY-EVENT-ORIGINAL", "10.00", 815001L);
        long originalPaymentId = insertReceipt(
                mysql, "LEDGER-PAY-ORIGINAL", "10.00", 815001L,
                originalEventId, null);
        long reversalEventId = insertCashEvent(
                mysql, "LEDGER-PAY-EVENT-REVERSAL", "-10.00", 815001L,
                originalEventId);
        long reversalPaymentId = insertReceipt(
                mysql, "LEDGER-PAY-REVERSAL", "-10.00", 815001L,
                reversalEventId, originalPaymentId, originalEventId);
        assertThat(queryLong(mysql, """
                SELECT COUNT(*) FROM payment_record
                WHERE reversal_of_payment_id = ?
                """, originalPaymentId)).isEqualTo(1);
        assertThat(queryLong(mysql, """
                SELECT reversal_of_event_id FROM financial_event WHERE id = ?
                """, reversalEventId)).isEqualTo(originalEventId);

        long chainEventId = insertCashEvent(
                mysql, "LEDGER-PAY-EVENT-CHAIN", "10.00", 815001L);
        assertThatThrownBy(() -> insertReceipt(
                mysql, "LEDGER-PAY-CHAIN", "10.00", 815001L,
                chainEventId, reversalPaymentId, reversalEventId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_payment_reversal_sign");

        assertThatThrownBy(() -> insertReceipt(
                mysql, "LEDGER-PAY-DUPLICATE", "-10.00", 815001L,
                reversalEventId, originalPaymentId, originalEventId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uk_payment_record_financial_event");

        long mismatchedPairEventId = insertCashEvent(
                mysql, "LEDGER-PAY-EVENT-MISMATCHED-PAIR", "0.00", 815011L);
        long mismatchedCashOriginalEventId = insertCashEvent(
                mysql, "LEDGER-PAY-EVENT-MISMATCHED-CASH-ORIGINAL", "10.00", 815011L);
        long mismatchedPairCashEventId = insertCashEvent(
                mysql, "LEDGER-PAY-EVENT-MISMATCHED-CASH", "-10.00", 815011L,
                mismatchedCashOriginalEventId);
        assertThatThrownBy(() -> executeUpdate(mysql, """
                INSERT INTO payment_record
                    (id, payment_no, request_id, direction, amount, payment_date,
                     source_type, source_id, financial_event_id,
                     reversal_of_payment_id, reversal_of_financial_event_id,
                     idempotency_key, created_at)
                VALUES
                    (9000000004, 'LEDGER-PAY-MISMATCHED-PAIR', 'LEDGER-PAY-MISMATCHED-PAIR',
                     'RECEIPT', -10.00, '2091-03-03', 'OUTBOUND_ORDER', 815011,
                     ?, ?, ?, 'LEDGER-PAY-MISMATCHED-PAIR', NOW(6))
                """, mismatchedPairCashEventId, wrongAmountOriginalPaymentId,
                mismatchedPairEventId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_payment_reversal_payment_event");

        long selfOriginalEventId = insertCashEvent(
                mysql, "LEDGER-PAY-EVENT-SELF-ORIGINAL", "10.00", 815031L);
        long selfEventId = insertCashEvent(
                mysql, "LEDGER-PAY-EVENT-SELF", "-10.00", 815031L, selfOriginalEventId);
        assertThatThrownBy(() -> executeUpdate(mysql, """
                INSERT INTO payment_record
                    (id, payment_no, request_id, direction, amount, payment_date,
                     source_type, source_id, financial_event_id,
                     reversal_of_payment_id, reversal_of_financial_event_id,
                     idempotency_key, created_at)
                VALUES
                    (9000000005, 'LEDGER-PAY-SELF', 'LEDGER-PAY-SELF',
                     'RECEIPT', -10.00, '2091-03-03', 'OUTBOUND_ORDER', 815031,
                     ?, 9000000005, ?, 'LEDGER-PAY-SELF', NOW(6))
                """, selfEventId, selfOriginalEventId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_payment_reversal_identity");
    }

    private long insertCashEvent(
            DatabaseHandle mysql,
            String eventNo,
            String amount,
            long sourceId
    ) throws SQLException {
        return insertCashEvent(mysql, eventNo, amount, sourceId, null);
    }

    private long insertCashEvent(
            DatabaseHandle mysql,
            String eventNo,
            String amount,
            long sourceId,
            Long reversalOfEventId
    ) throws SQLException {
        return insertReturningId(mysql, """
                INSERT INTO financial_event
                    (event_no, event_type, amount, business_date,
                     source_type, source_id, reversal_of_event_id,
                     idempotency_key, created_at)
                VALUES (?, 'CASH_RECEIPT', ?, '2091-03-01',
                        'OUTBOUND_ORDER', ?, ?, ?, NOW(6))
                """, eventNo, new BigDecimal(amount), sourceId,
                reversalOfEventId, eventNo);
    }

    private long insertReceipt(
            DatabaseHandle mysql,
            String paymentNo,
            String amount,
            long sourceId,
            long financialEventId,
            Long reversalOfPaymentId
    ) throws SQLException {
        return insertReceipt(
                mysql,
                paymentNo,
                amount,
                sourceId,
                financialEventId,
                reversalOfPaymentId,
                null
        );
    }

    private long insertReceipt(
            DatabaseHandle mysql,
            String paymentNo,
            String amount,
            long sourceId,
            long financialEventId,
            Long reversalOfPaymentId,
            Long reversalOfFinancialEventId
    ) throws SQLException {
        return insertReturningId(mysql, """
                INSERT INTO payment_record
                    (payment_no, request_id, direction, amount, payment_date,
                     source_type, source_id, financial_event_id,
                     reversal_of_payment_id, reversal_of_financial_event_id,
                     idempotency_key, created_at)
                VALUES (?, ?, 'RECEIPT', ?, '2091-03-01',
                        'OUTBOUND_ORDER', ?, ?, ?, ?, ?, NOW(6))
                """, paymentNo, paymentNo, new BigDecimal(amount), sourceId,
                financialEventId, reversalOfPaymentId,
                reversalOfFinancialEventId, paymentNo);
    }

    private void verifyStockMovementReversalGuards(DatabaseHandle mysql) throws SQLException {
        long originalId = insertReturningId(mysql, """
                INSERT INTO stock_movement
                    (movement_no, movement_type, resource_type, source_type,
                     source_id, source_line_id, business_date, business_type,
                     idempotency_key, created_at)
                VALUES
                    ('LEDGER-MOVE-ORIGINAL', 'OUTBOUND', 'PART', 'OUTBOUND_ORDER',
                     820001, 23, '2091-03-01', 'SALE_OUTBOUND',
                     'LEDGER-MOVE-ORIGINAL', NOW(6))
                """);
        assertThatThrownBy(() -> executeUpdate(mysql, """
                INSERT INTO stock_movement
                    (movement_no, movement_type, resource_type, source_type,
                     source_id, source_line_id, reversal_of_movement_id, created_at)
                VALUES
                    ('LEDGER-MOVE-WRONG-SOURCE', 'INBOUND', 'PART',
                     'OUTBOUND_ORDER', 820002, 23, ?, NOW(6))
                """, originalId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_stock_move_reversal_identity");
        long reversalId = insertReturningId(mysql, """
                INSERT INTO stock_movement
                    (movement_no, movement_type, resource_type, source_type,
                     source_id, source_line_id, business_date, business_type,
                     reversal_of_movement_id, idempotency_key, created_at)
                VALUES
                    ('LEDGER-MOVE-REVERSAL', 'INBOUND', 'PART', 'OUTBOUND_ORDER',
                     820001, 23, '2091-03-02', 'SALE_OUTBOUND_REVERSAL', ?,
                     'LEDGER-MOVE-REVERSAL', NOW(6))
                """, originalId);
        assertThat(queryLong(mysql,
                "SELECT COUNT(*) FROM stock_movement WHERE reversal_of_movement_id = ?",
                originalId)).isEqualTo(1);
        assertThatThrownBy(() -> executeUpdate(mysql, """
                INSERT INTO stock_movement
                    (movement_no, movement_type, resource_type, source_type,
                     source_id, source_line_id, reversal_of_movement_id, created_at)
                VALUES
                    ('LEDGER-MOVE-CHAIN', 'OUTBOUND', 'PART',
                     'OUTBOUND_ORDER', 820001, 23, ?, NOW(6))
                """, reversalId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_stock_move_reversal_identity");
        assertThatThrownBy(() -> executeUpdate(mysql, """
                INSERT INTO stock_movement
                    (movement_no, movement_type, resource_type, source_type,
                     source_id, source_line_id, reversal_of_movement_id, created_at)
                VALUES
                    ('LEDGER-MOVE-DUPLICATE', 'INBOUND', 'PART',
                     'OUTBOUND_ORDER', 820001, 23, ?, NOW(6))
                """, originalId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uk_stock_movement_reversal");
        assertThatThrownBy(() -> executeUpdate(mysql, """
                INSERT INTO stock_movement
                    (id, movement_no, movement_type, resource_type, source_type,
                     source_id, source_line_id, reversal_of_movement_id, created_at)
                VALUES
                    (9000000002, 'LEDGER-MOVE-SELF', 'ADJUSTMENT', 'PART',
                     'OUTBOUND_ORDER', 820001, 23, 9000000002, NOW(6))
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_stock_move_reversal_identity");
    }

    private void verifyStockConsumptionReversalGuards(
            DatabaseHandle mysql,
            SnapshotIds snapshot
    ) throws SQLException {
        long lotId = insertReturningId(mysql, """
                INSERT INTO stock_lot
                    (resource_type, resource_id, warehouse_id, source_type,
                     source_id, source_line_id, received_business_date,
                     original_quantity, remaining_quantity, unit_cost,
                     original_cost_amount, remaining_cost_amount,
                     freight_allocated, status, idempotency_key, created_at, updated_at)
                VALUES
                    ('PART', ?, ?, 'LEDGER_GUARD', 830001, 29, '2091-03-01',
                     2, 0, 5.000000, 10.00, 0.00,
                     0, 'CLOSED', 'LEDGER-LOT-ORIGINAL', NOW(6), NOW(6))
                """, snapshot.partId(), snapshot.warehouseId());
        long originalId = insertReturningId(mysql, """
                INSERT INTO stock_lot_consumption
                    (stock_lot_id, resource_type, resource_id, warehouse_id,
                     source_type, source_id, source_line_id, quantity,
                     unit_cost, total_cost, business_date, idempotency_key, created_at)
                VALUES
                    (?, 'PART', ?, ?, 'REPAIR', 830002, 29, 2,
                     5.000000, 10.00, '2091-03-01',
                     'LEDGER-CONS-ORIGINAL', NOW(6))
                """, lotId, snapshot.partId(), snapshot.warehouseId());
        assertThatThrownBy(() -> executeUpdate(mysql, """
                INSERT INTO stock_lot_consumption
                    (stock_lot_id, resource_type, resource_id, warehouse_id,
                     source_type, source_id, source_line_id, quantity,
                     unit_cost, total_cost, business_date, created_at)
                VALUES
                    (?, 'PART', ?, ?, 'REPAIR', 830002, 29, -2,
                     5.000000, -10.00, '2091-03-02', NOW(6))
                """, lotId, snapshot.partId(), snapshot.warehouseId()))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_stock_lot_consumption_cost_arithmetic");
        assertThatThrownBy(() -> executeUpdate(mysql, """
                INSERT INTO stock_lot_consumption
                    (stock_lot_id, resource_type, resource_id, warehouse_id,
                     source_type, source_id, source_line_id, quantity,
                     unit_cost, total_cost, business_date,
                     reversal_of_consumption_id, created_at)
                VALUES
                    (?, 'PART', ?, ?, 'REPAIR', 830002, 29, -1,
                     5.000000, -5.00, '2091-03-02', ?, NOW(6))
                """, lotId, snapshot.partId(), snapshot.warehouseId(), originalId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_lot_cons_reversal_identity");
        assertThatThrownBy(() -> executeUpdate(mysql, """
                INSERT INTO stock_lot_consumption
                    (stock_lot_id, resource_type, resource_id, warehouse_id,
                     source_type, source_id, source_line_id, quantity,
                     unit_cost, total_cost, business_date,
                     reversal_of_consumption_id, created_at)
                VALUES
                    (?, 'PART', ?, ?, 'REPAIR', 830003, 29, -2,
                     5.000000, -10.00, '2091-03-02', ?, NOW(6))
                """, lotId, snapshot.partId(), snapshot.warehouseId(), originalId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_lot_cons_reversal_identity");
        long reversalId = insertReturningId(mysql, """
                INSERT INTO stock_lot_consumption
                    (stock_lot_id, resource_type, resource_id, warehouse_id,
                     source_type, source_id, source_line_id, quantity,
                     unit_cost, total_cost, business_date,
                     reversal_of_consumption_id, idempotency_key, created_at)
                VALUES
                    (?, 'PART', ?, ?, 'REPAIR', 830002, 29, -2,
                     5.000000, -10.00, '2091-03-02', ?,
                     'LEDGER-CONS-REVERSAL', NOW(6))
                """, lotId, snapshot.partId(), snapshot.warehouseId(), originalId);
        assertThat(queryLong(mysql, """
                SELECT COUNT(*) FROM stock_lot_consumption
                WHERE reversal_of_consumption_id = ?
                """, originalId)).isEqualTo(1);
        assertThatThrownBy(() -> executeUpdate(mysql, """
                INSERT INTO stock_lot_consumption
                    (stock_lot_id, resource_type, resource_id, warehouse_id,
                     source_type, source_id, source_line_id, quantity,
                     unit_cost, total_cost, business_date,
                     reversal_of_consumption_id, created_at)
                VALUES
                    (?, 'PART', ?, ?, 'REPAIR', 830002, 29, -2,
                     5.000000, -10.00, '2091-03-03', ?, NOW(6))
                """, lotId, snapshot.partId(), snapshot.warehouseId(), reversalId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_lot_cons_reversal_identity");
        assertThatThrownBy(() -> executeUpdate(mysql, """
                INSERT INTO stock_lot_consumption
                    (stock_lot_id, resource_type, resource_id, warehouse_id,
                     source_type, source_id, source_line_id, quantity,
                     unit_cost, total_cost, business_date,
                     reversal_of_consumption_id, created_at)
                VALUES
                    (?, 'PART', ?, ?, 'REPAIR', 830002, 29, -2,
                     5.000000, -10.00, '2091-03-02', ?, NOW(6))
                """, lotId, snapshot.partId(), snapshot.warehouseId(), originalId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uk_stock_lot_consumption_reversal");
        assertThatThrownBy(() -> executeUpdate(mysql, """
                INSERT INTO stock_lot_consumption
                    (id, stock_lot_id, resource_type, resource_id, warehouse_id,
                     source_type, source_id, source_line_id, quantity,
                     unit_cost, total_cost, business_date,
                     reversal_of_consumption_id, created_at)
                VALUES
                    (9000000003, ?, 'PART', ?, ?, 'REPAIR', 830002, 29, -1,
                     0.000000, 0.00, '2091-03-03', 9000000003, NOW(6))
                """, lotId, snapshot.partId(), snapshot.warehouseId()))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_lot_cons_reversal_identity");

        long fractionalLotId = insertReturningId(mysql, """
                INSERT INTO stock_lot
                    (resource_type, resource_id, warehouse_id, source_type,
                     source_id, received_business_date,
                     original_quantity, remaining_quantity, unit_cost,
                     original_cost_amount, remaining_cost_amount,
                     freight_allocated, status, idempotency_key, created_at, updated_at)
                VALUES
                    ('PART', ?, ?, 'LEDGER_GUARD', 830004, '2091-03-04',
                     20001, 20001, 0.000000, 0.01, 0.01,
                     0, 'OPEN', 'LEDGER-LOT-FRACTIONAL', NOW(6), NOW(6))
                """, snapshot.partId(), snapshot.warehouseId());
        insertReturningId(mysql, """
                INSERT INTO stock_lot_consumption
                    (stock_lot_id, resource_type, resource_id, warehouse_id,
                     source_type, source_id, quantity, unit_cost, total_cost,
                     business_date, idempotency_key, created_at)
                VALUES
                    (?, 'PART', ?, ?, 'LEDGER_GUARD', 830004, 20001,
                     0.000000, 0.01, '2091-03-04',
                     'LEDGER-CONS-FRACTIONAL', NOW(6))
                """, fractionalLotId, snapshot.partId(), snapshot.warehouseId());
        long toleranceBoundaryLotId = insertReturningId(mysql, """
                INSERT INTO stock_lot
                    (resource_type, resource_id, warehouse_id, source_type,
                     source_id, received_business_date,
                     original_quantity, remaining_quantity, unit_cost,
                     original_cost_amount, remaining_cost_amount,
                     freight_allocated, status, idempotency_key, created_at, updated_at)
                VALUES
                    ('PART', ?, ?, 'LEDGER_GUARD', 830006, '2091-03-04',
                     10000, 10000, 1.000000, 10000.01, 10000.01,
                     0, 'OPEN', 'LEDGER-LOT-TOLERANCE-BOUNDARY', NOW(6), NOW(6))
                """, snapshot.partId(), snapshot.warehouseId());
        insertReturningId(mysql, """
                INSERT INTO stock_lot_consumption
                    (stock_lot_id, resource_type, resource_id, warehouse_id,
                     source_type, source_id, quantity, unit_cost, total_cost,
                     business_date, idempotency_key, created_at)
                VALUES
                    (?, 'PART', ?, ?, 'LEDGER_GUARD', 830006, 10000,
                     1.000000, 10000.01, '2091-03-04',
                     'LEDGER-CONS-TOLERANCE-10000', NOW(6))
                """, toleranceBoundaryLotId, snapshot.partId(), snapshot.warehouseId());
        assertThatThrownBy(() -> executeUpdate(mysql, """
                INSERT INTO stock_lot_consumption
                    (stock_lot_id, resource_type, resource_id, warehouse_id,
                     source_type, source_id, quantity, unit_cost, total_cost,
                     business_date, created_at)
                VALUES
                    (?, 'PART', ?, ?, 'LEDGER_GUARD', 830007, 9999,
                     1.000000, 9999.01, '2091-03-04', NOW(6))
                """, toleranceBoundaryLotId, snapshot.partId(), snapshot.warehouseId()))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_stock_lot_consumption_cost_arithmetic");
        assertThatThrownBy(() -> executeUpdate(mysql, """
                INSERT INTO stock_lot_consumption
                    (stock_lot_id, resource_type, resource_id, warehouse_id,
                     source_type, source_id, quantity, unit_cost, total_cost,
                     business_date, created_at)
                VALUES
                    (?, 'PART', ?, ?, 'LEDGER_GUARD', 830005, 2,
                     5.000000, 9.99, '2091-03-04', NOW(6))
                """, lotId, snapshot.partId(), snapshot.warehouseId()))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_stock_lot_consumption_cost_arithmetic");
    }

    private void migrateTo(DatabaseHandle mysql, String target) {
        flyway(mysql, target).migrate();
    }

    private Flyway flyway(DatabaseHandle mysql, String target) {
        var configuration = Flyway.configure()
                .dataSource(mysql.jdbcUrl(), mysql.username(), mysql.password())
                .locations("classpath:db/migration")
                .validateMigrationNaming(true);
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target));
        }
        return configuration.load();
    }

    private long insertReturningId(
            DatabaseHandle mysql,
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
            DatabaseHandle mysql,
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
            DatabaseHandle mysql,
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
            DatabaseHandle mysql,
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

    private BigDecimal queryBigDecimal(
            DatabaseHandle mysql,
            String sql,
            Object... parameters
    ) throws SQLException {
        try (Connection connection = connection(mysql);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getBigDecimal(1);
            }
        }
    }

    private Connection connection(DatabaseHandle mysql) throws SQLException {
        return DriverManager.getConnection(
                mysql.jdbcUrl(),
                mysql.username(),
                mysql.password()
        );
    }

    private Set<String> runV40Preflight(DatabaseHandle mysql) throws Exception {
        String sql = Files.readString(
                Path.of("scripts", "mysql-upgrade-preflight-v40.sql"),
                StandardCharsets.UTF_8
        );
        Set<String> issueTypes = new HashSet<>();
        try (Connection connection = connection(mysql);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                issueTypes.add(resultSet.getString("issue_type"));
            }
        }
        return issueTypes;
    }

    private void verifyJsonBackupRoundTrip(DatabaseHandle mysql) throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                mysql.jdbcUrl(), mysql.username(), mysql.password());
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        DataBackupService backupService = new DataBackupService(jdbcTemplate, new ObjectMapper());
        String idempotencyScope = "BACKUP-ROUNDTRIP";
        String idempotencyRequestId = UUID.randomUUID().toString();
        String originalClaimTime = "2000-01-02 03:04:05.123456";
        executeUpdate(mysql, """
                INSERT INTO request_idempotency (scope, request_id, created_at)
                VALUES (?, ?, ?)
                """, idempotencyScope, idempotencyRequestId, originalClaimTime);
        long customerCount = queryLong(mysql, "SELECT COUNT(*) FROM customer_profile");
        byte[] backup = backupService.createBackup();

        executeUpdate(mysql, """
                INSERT INTO customer_profile
                    (company_name, created_at, updated_at)
                VALUES ('RESTORE-ROUNDTRIP-EXTRA', NOW(6), NOW(6))
                """);
        assertThat(queryLong(mysql, "SELECT COUNT(*) FROM customer_profile"))
                .isEqualTo(customerCount + 1);

        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(ignored -> backupService.restoreBackup(
                new MockMultipartFile(
                        "file", "backup-v2.json", "application/json", backup)));

        assertThat(queryLong(mysql, "SELECT COUNT(*) FROM customer_profile"))
                .isEqualTo(customerCount);
        assertThat(queryString(mysql, """
                SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f')
                FROM request_idempotency
                WHERE scope = ? AND request_id = ?
                """, idempotencyScope, idempotencyRequestId))
                .isEqualTo(originalClaimTime);
        assertThat(queryLong(mysql, """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name IN ('warehouse', 'config_value')
                  AND extra LIKE '%GENERATED%'
                """)).isEqualTo(2);
    }

    private void verifyJsonBackupRollbackOnInsertFailure(DatabaseHandle mysql) throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                mysql.jdbcUrl(), mysql.username(), mysql.password());
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        ObjectMapper objectMapper = new ObjectMapper();
        DataBackupService backupService = new DataBackupService(jdbcTemplate, objectMapper);
        DataBackupService.BackupFile malformed = objectMapper.readValue(
                backupService.createBackup(),
                DataBackupService.BackupFile.class
        );
        DataBackupService.BackupTable warehouse = malformed.getTables().stream()
                .filter(table -> "warehouse".equals(table.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(warehouse.getRows()).isNotEmpty();
        warehouse.getRows().get(0).put("warehouse_name", null);
        ReflectionTestUtils.invokeMethod(backupService, "populateManifest", malformed);
        byte[] invalidBackup = objectMapper.writeValueAsBytes(malformed);

        String sentinel = "RESTORE-ROLLBACK-SENTINEL-" + UUID.randomUUID();
        executeUpdate(mysql, """
                INSERT INTO customer_profile
                    (company_name, created_at, updated_at)
                VALUES (?, NOW(6), NOW(6))
                """, sentinel);
        long customerCountBefore = queryLong(mysql, "SELECT COUNT(*) FROM customer_profile");
        long paymentCountBefore = queryLong(mysql, "SELECT COUNT(*) FROM payment_record");

        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored ->
                backupService.restoreBackup(new MockMultipartFile(
                        "file", "malformed-backup-v2.json", "application/json", invalidBackup))))
                .isInstanceOf(RuntimeException.class);

        assertThat(queryLong(mysql, "SELECT COUNT(*) FROM customer_profile"))
                .isEqualTo(customerCountBefore);
        assertThat(queryLong(mysql,
                "SELECT COUNT(*) FROM customer_profile WHERE company_name = ?", sentinel))
                .isEqualTo(1);
        assertThat(queryLong(mysql, "SELECT COUNT(*) FROM payment_record"))
                .isEqualTo(paymentCountBefore);
        assertThat(queryLong(mysql, "SELECT @@FOREIGN_KEY_CHECKS")).isEqualTo(1);
    }

    private String databaseUrl(String adminUrl, String databaseName) {
        int queryIndex = adminUrl.indexOf('?');
        String query = queryIndex < 0 ? "" : adminUrl.substring(queryIndex);
        String base = queryIndex < 0 ? adminUrl : adminUrl.substring(0, queryIndex);
        int databaseSlash = base.indexOf('/', "jdbc:mysql://".length());
        if (databaseSlash < 0) {
            return base + "/" + databaseName + query;
        }
        return base.substring(0, databaseSlash + 1) + databaseName + query;
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

    private record DatabaseHandle(String jdbcUrl, String username, String password) {
    }

    private record LegacySaleIds(long orderId, long stockOperationLogId) {
    }

    private record LegacyFactIds(
            long paymentId,
            long importJobId,
            long movementLineId,
            long reversalPaymentId,
            long reversalEventId,
            long reversalOriginalEventId
    ) {
    }

    private record LegacyReversalIds(
            long originalPaymentId,
            long originalEventId,
            long invalidPaymentId,
            long invalidEventId
    ) {
    }

    private record LegacyCounterpartyReversalIds(
            long originalPaymentId,
            long originalEventId,
            long reversalPaymentId,
            long reversalEventId
    ) {
    }

    private record LegacyCostOverflowLotIds(long lotId) {
    }

    private record LegacyLotReversalIds(
            long lotId,
            long originalConsumptionId,
            long invalidConsumptionId
    ) {
    }

    private record LegacyMovementReversalIds(
            long originalMovementId,
            long invalidMovementId
    ) {
    }
}
