#!/usr/bin/env python3
"""Read-only verifier for the API-seeded forklift ERP test database.

The script deliberately has no mutation endpoints and the optional MySQL
check consists exclusively of SELECT statements.  It is intended to run after
``seed_closed_loop_test_data.py`` has completed successfully.

Examples
--------
    python scripts/verify_closed_loop_test_database.py
    $env:FORKLIFT_ERP_VERIFY_SQL = 'true'
    $env:FORKLIFT_ERP_DB_PASSWORD = '<local password>'
    python scripts/verify_closed_loop_test_database.py --sql

The administrator password may be supplied through ``--password`` or
``FORKLIFT_ERP_ADMIN_PASSWORD``.  The MySQL password is read only from
``MYSQL_PWD`` or ``FORKLIFT_ERP_DB_PASSWORD`` so it is never placed in a
mysql command line.  Neither password is included in output.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import ssl
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import asdict, dataclass, field
from datetime import date
from typing import Any, Mapping, Sequence


EXPECTED_FLYWAY_VERSION = 51

# The seed has an empty-business-data precondition and deliberately creates a
# scale fixture rather than a tiny smoke set: 30 serialized vehicles, 10 paid
# machine outbounds, 15 paid part outbounds, 6 returned rentals followed by 6
# completed repairs, 10 completed modifications, 4 cancellations, 6 repair
# attachments, 5 completed stocktakes, and a 5-row import.  These are minimum
# acceptance thresholds, not merely "table is non-empty" checks.
#
# Authentication/permission bootstrap tables are excluded because application
# startup, rather than the seed API, creates them.
MINIMUM_ROW_COUNTS: tuple[tuple[str, str, int], ...] = (
    ("warehouse", "warehouse", 2),
    ("supplier", "supplier_profile", 2),
    ("customer", "customer_profile", 3),
    ("config_item", "config_item", 2),
    ("config_value", "config_value", 4),
    ("vehicle_config_item", "vehicle_config_item", 1),
    ("vehicle_config_value", "vehicle_config_value", 2),
    ("machine_inventory", "machine_inventory", 30),
    ("machine_config", "machine_config", 60),
    ("part_inventory", "part_inventory", 20),
    ("stock_balance", "stock_balance", 50),
    ("stock_movement", "stock_movement", 100),
    ("stock_movement_line", "stock_movement_line", 100),
    ("stock_operation_log", "stock_operation_log", 25),
    ("stock_lot", "stock_lot", 50),
    ("stock_lot_consumption", "stock_lot_consumption", 41),
    ("stock_lot_cost_adjustment", "stock_lot_cost_adjustment", 10),
    ("financial_event", "financial_event", 90),
    ("payment_record", "payment_record", 90),
    ("purchase_order", "purchase_order", 35),
    ("outbound_order", "outbound_order", 25),
    ("rental_record", "rental_record", 6),
    ("rental_bill", "rental_bill", 18),
    ("repair_record", "repair_record", 6),
    ("repair_part_usage", "repair_part_usage", 6),
    ("modification_work_order", "modification_work_order", 14),
    ("modification_work_order_line", "modification_work_order_line", 14),
    ("config_replace_log", "config_replace_log", 10),
    ("stocktaking_record", "stocktaking_record", 5),
    ("resource_attachment", "resource_attachment", 6),
    ("data_import_job", "data_import_job", 1),
    ("data_import_row", "data_import_row", 5),
    ("operation_audit_log", "operation_audit_log", 150),
    ("request_idempotency", "request_idempotency", 90),
)

# State-oriented counts complement the volume checks above.  They demonstrate
# that the fixture is actually closed: documents are received/settled, rentals
# are returned, repairs and stocktakes completed, and no active workflow is
# left behind.  Values are keyed to the public seed contract documented above.
MINIMUM_SEMANTIC_COUNTS: tuple[tuple[str, int], ...] = (
    ("configured_machine_rows", 30),
    ("received_machine_purchase_rows", 30),
    ("received_part_purchase_rows", 5),
    ("financial_posted_purchase_rows", 35),
    ("outbound_machine_profile_rows", 10),
    ("settled_machine_outbound_rows", 10),
    ("settled_part_outbound_rows", 15),
    ("service_warehouse_machine_rows", 4),
    ("returned_rental_rows", 6),
    ("posted_rental_bill_rows", 24),
    ("completed_repair_rows", 6),
    ("completed_modification_rows", 10),
    ("canceled_modification_rows", 4),
    ("valued_removed_part_rows", 10),
    ("completed_stocktaking_rows", 5),
    ("active_repair_attachment_rows", 6),
    ("completed_import_job_rows", 1),
)


def _table_count_select(check_name: str, table_name: str) -> str:
    """Build a SELECT from hard-coded table identifiers only.

    Both values come from ``MINIMUM_ROW_COUNTS`` above rather than command-line
    input, so this does not open an SQL-injection surface.
    """

    return (
        f"SELECT '{check_name}' AS check_name, COUNT(*) AS check_value "
        f"FROM `{table_name}`"
    )


def _build_sql_check_query() -> str:
    selects = [
        """
        SELECT 'flyway_latest_version' AS check_name,
               COALESCE(MAX(CAST(`version` AS UNSIGNED)), 0) AS check_value
        FROM `flyway_schema_history`
        WHERE `success` = 1
          AND `version` REGEXP '^[0-9]+$'
        """,
        """
        SELECT 'flyway_v51_success' AS check_name, COUNT(*) AS check_value
        FROM `flyway_schema_history`
        WHERE `version` = '51' AND `success` = 1
        """,
        """
        SELECT 'flyway_failed_migrations' AS check_name, COUNT(*) AS check_value
        FROM `flyway_schema_history`
        WHERE `success` = 0
        """,
        """
        SELECT 'migration_exception_rows' AS check_name, COUNT(*) AS check_value
        FROM `migration_exception`
        """,
        # The application reconciliation projects master quantities against
        # total *available* balance.  Model-only machine templates do not
        # represent physical stock and are intentionally excluded.
        """
        SELECT 'master_balance_mismatch_rows' AS check_name,
               COUNT(*) AS check_value
        FROM (
            SELECT m.id
            FROM `machine_inventory` AS m
            LEFT JOIN `stock_balance` AS b
              ON b.resource_type = 'MACHINE' AND b.resource_id = m.id
            WHERE COALESCE(m.model_only, 0) = 0
            GROUP BY m.id, m.inventory_count
            HAVING COALESCE(m.inventory_count, 0)
                   <> COALESCE(SUM(b.available_quantity), 0)

            UNION ALL

            SELECT p.id
            FROM `part_inventory` AS p
            LEFT JOIN `stock_balance` AS b
              ON b.resource_type = 'PART' AND b.resource_id = p.id
            GROUP BY p.id, p.quantity
            HAVING COALESCE(p.quantity, 0)
                   <> COALESCE(SUM(b.available_quantity), 0)
        ) AS mismatches
        """,
        # FIFO lots represent physical stock, including quantities reserved or
        # locked in the balance.  This mirrors DailyReconciliationService.
        """
        SELECT 'balance_lot_mismatch_rows' AS check_name,
               COUNT(*) AS check_value
        FROM (
            SELECT b.resource_type, b.resource_id, b.warehouse_id
            FROM `stock_balance` AS b
            LEFT JOIN (
                SELECT resource_type, resource_id, warehouse_id,
                       SUM(remaining_quantity) AS fifo_quantity
                FROM `stock_lot`
                WHERE status <> 'REVERSED'
                GROUP BY resource_type, resource_id, warehouse_id
            ) AS lots
              ON lots.resource_type = b.resource_type
             AND lots.resource_id = b.resource_id
             AND lots.warehouse_id = b.warehouse_id
            WHERE (b.available_quantity + b.reserved_quantity + b.locked_quantity)
                  <> COALESCE(lots.fifo_quantity, 0)

            UNION ALL

            SELECT lots.resource_type, lots.resource_id, lots.warehouse_id
            FROM (
                SELECT resource_type, resource_id, warehouse_id,
                       SUM(remaining_quantity) AS fifo_quantity
                FROM `stock_lot`
                WHERE status <> 'REVERSED'
                GROUP BY resource_type, resource_id, warehouse_id
            ) AS lots
            LEFT JOIN `stock_balance` AS b
              ON b.resource_type = lots.resource_type
             AND b.resource_id = lots.resource_id
             AND b.warehouse_id = lots.warehouse_id
            WHERE b.id IS NULL AND lots.fifo_quantity <> 0
        ) AS mismatches
        """,
        """
        SELECT 'orphan_balance_rows' AS check_name, COUNT(*) AS check_value
        FROM `stock_balance` AS b
        LEFT JOIN `machine_inventory` AS m
          ON b.resource_type = 'MACHINE' AND m.id = b.resource_id
        LEFT JOIN `part_inventory` AS p
          ON b.resource_type = 'PART' AND p.id = b.resource_id
        WHERE (b.resource_type = 'MACHINE' AND m.id IS NULL)
           OR (b.resource_type = 'PART' AND p.id IS NULL)
           OR b.resource_type NOT IN ('MACHINE', 'PART')
        """,
        """
        SELECT 'negative_balance_rows' AS check_name, COUNT(*) AS check_value
        FROM `stock_balance`
        WHERE available_quantity < 0
           OR reserved_quantity < 0
           OR locked_quantity < 0
        """,
        """
        SELECT 'invalid_lot_quantity_rows' AS check_name, COUNT(*) AS check_value
        FROM `stock_lot`
        WHERE original_quantity <= 0
           OR remaining_quantity < 0
           OR remaining_quantity > original_quantity
        """,
        # Scale and terminal-state evidence for the seed's documented closure.
        """
        SELECT 'configured_machine_rows' AS check_name, COUNT(*) AS check_value
        FROM (
            SELECT m.id
            FROM `machine_inventory` AS m
            LEFT JOIN `machine_config` AS c ON c.machine_id = m.id
            WHERE COALESCE(m.model_only, 0) = 0
            GROUP BY m.id
            HAVING COUNT(c.id) >= 2
        ) AS configured_machines
        """,
        """
        SELECT 'received_machine_purchase_rows' AS check_name, COUNT(*) AS check_value
        FROM `purchase_order`
        WHERE resource_type = 'MACHINE'
          AND status = 'RECEIVED'
          AND received_stock_movement_id IS NOT NULL
          AND stock_lot_id IS NOT NULL
        """,
        """
        SELECT 'received_part_purchase_rows' AS check_name, COUNT(*) AS check_value
        FROM `purchase_order`
        WHERE resource_type = 'PART'
          AND status = 'RECEIVED'
          AND received_stock_movement_id IS NOT NULL
          AND stock_lot_id IS NOT NULL
        """,
        """
        SELECT 'financial_posted_purchase_rows' AS check_name, COUNT(*) AS check_value
        FROM `purchase_order`
        WHERE status = 'RECEIVED' AND COALESCE(financial_posted, 0) = 1
        """,
        """
        SELECT 'outbound_machine_profile_rows' AS check_name, COUNT(*) AS check_value
        FROM `machine_inventory`
        WHERE stock_status = 'OUTBOUND' AND COALESCE(inventory_count, 0) = 0
        """,
        """
        SELECT 'settled_machine_outbound_rows' AS check_name, COUNT(*) AS check_value
        FROM `outbound_order`
        WHERE resource_type = 'MACHINE'
          AND COALESCE(financial_posted, 0) = 1
          AND COALESCE(payment_settled, 0) = 1
        """,
        """
        SELECT 'settled_part_outbound_rows' AS check_name, COUNT(*) AS check_value
        FROM `outbound_order`
        WHERE resource_type = 'PART'
          AND COALESCE(financial_posted, 0) = 1
          AND COALESCE(payment_settled, 0) = 1
        """,
        """
        SELECT 'service_warehouse_machine_rows' AS check_name, COUNT(*) AS check_value
        FROM `machine_inventory` AS machine
        JOIN `warehouse` AS warehouse ON warehouse.id = machine.warehouse_id
        WHERE warehouse.warehouse_type = 'SERVICE'
          AND COALESCE(machine.inventory_count, 0) > 0
        """,
        """
        SELECT 'returned_rental_rows' AS check_name, COUNT(*) AS check_value
        FROM `rental_record`
        WHERE status = 'RETURNED' AND return_date IS NOT NULL
        """,
        """
        SELECT 'posted_rental_bill_rows' AS check_name, COUNT(*) AS check_value
        FROM `rental_bill`
        WHERE status = 'POSTED' AND financial_event_id IS NOT NULL
        """,
        """
        SELECT 'completed_repair_rows' AS check_name, COUNT(*) AS check_value
        FROM `repair_record`
        WHERE status = 'COMPLETED' AND COALESCE(financial_posted, 0) = 1
        """,
        """
        SELECT 'completed_modification_rows' AS check_name, COUNT(*) AS check_value
        FROM `modification_work_order`
        WHERE status = 'COMPLETED' AND completed_at IS NOT NULL
        """,
        """
        SELECT 'canceled_modification_rows' AS check_name, COUNT(*) AS check_value
        FROM `modification_work_order`
        WHERE status = 'CANCELED' AND canceled_at IS NOT NULL
        """,
        """
        SELECT 'valued_removed_part_rows' AS check_name, COUNT(*) AS check_value
        FROM `part_inventory`
        WHERE source = 'REMOVED'
          AND COALESCE(is_locked, 0) = 0
          AND COALESCE(landed_unit_cost, 0) > 0
        """,
        """
        SELECT 'completed_stocktaking_rows' AS check_name, COUNT(*) AS check_value
        FROM `stocktaking_record`
        WHERE status = 'COMPLETED'
          AND book_quantity = actual_quantity
          AND difference_quantity = 0
        """,
        """
        SELECT 'active_repair_attachment_rows' AS check_name, COUNT(*) AS check_value
        FROM `resource_attachment` AS attachment
        JOIN `repair_record` AS repair ON repair.id = attachment.resource_id
        WHERE attachment.resource_type = 'REPAIR'
          AND COALESCE(attachment.deleted, 0) = 0
          AND repair.status = 'COMPLETED'
        """,
        """
        SELECT 'completed_import_job_rows' AS check_name, COUNT(*) AS check_value
        FROM `data_import_job`
        WHERE status = 'COMPLETED'
          AND COALESCE(imported_rows, 0) >= 5
          AND COALESCE(error_rows, 0) = 0
        """,
        # The zero-count checks catch a partially constructed fixture even
        # when aggregate row counts happen to satisfy their lower bounds.
        """
        SELECT 'machines_missing_two_configs' AS check_name, COUNT(*) AS check_value
        FROM (
            SELECT m.id
            FROM `machine_inventory` AS m
            LEFT JOIN `machine_config` AS c ON c.machine_id = m.id
            WHERE COALESCE(m.model_only, 0) = 0
            GROUP BY m.id
            HAVING COUNT(c.id) < 2
        ) AS incomplete_machine_configs
        """,
        """
        SELECT 'active_rental_rows' AS check_name, COUNT(*) AS check_value
        FROM `rental_record`
        WHERE COALESCE(status, '') <> 'RETURNED'
        """,
        """
        SELECT 'pending_repair_rows' AS check_name, COUNT(*) AS check_value
        FROM `repair_record`
        WHERE COALESCE(status, '') <> 'COMPLETED'
        """,
        """
        SELECT 'active_modification_rows' AS check_name, COUNT(*) AS check_value
        FROM `modification_work_order`
        WHERE COALESCE(status, '') NOT IN ('COMPLETED', 'CANCELED')
        """,
        """
        SELECT 'active_purchase_rows' AS check_name, COUNT(*) AS check_value
        FROM `purchase_order`
        WHERE status IS NULL OR status NOT IN ('RECEIVED', 'CANCELED')
        """,
        """
        SELECT 'nonterminal_machine_stock_rows' AS check_name, COUNT(*) AS check_value
        FROM `machine_inventory`
        WHERE COALESCE(model_only, 0) = 0
          AND COALESCE(stock_status, '') NOT IN ('IN_STOCK', 'OUTBOUND')
        """,
        """
        SELECT 'pending_stocktaking_rows' AS check_name, COUNT(*) AS check_value
        FROM `stocktaking_record`
        WHERE COALESCE(status, '') <> 'COMPLETED'
        """,
        """
        SELECT 'invalid_repair_attachment_rows' AS check_name, COUNT(*) AS check_value
        FROM `resource_attachment` AS attachment
        LEFT JOIN `repair_record` AS repair ON repair.id = attachment.resource_id
        WHERE attachment.resource_type = 'REPAIR'
          AND COALESCE(attachment.deleted, 0) = 0
          AND (repair.id IS NULL OR COALESCE(repair.status, '') <> 'COMPLETED')
        """,
        """
        SELECT 'unsettled_outbound_rows' AS check_name, COUNT(*) AS check_value
        FROM `outbound_order`
        WHERE COALESCE(financial_posted, 0) <> 1
           OR COALESCE(payment_settled, 0) <> 1
        """,
        """
        SELECT 'received_purchase_without_payment_rows' AS check_name,
               COUNT(*) AS check_value
        FROM `purchase_order` AS purchase_row
        WHERE purchase_row.status = 'RECEIVED'
          AND COALESCE((
              SELECT SUM(payment.amount)
              FROM `payment_record` AS payment
              WHERE payment.source_type = 'PURCHASE_ORDER'
                AND payment.source_id = purchase_row.id
                AND payment.direction = 'PAYMENT'
          ), 0) < COALESCE(purchase_row.total_amount, 0)
                    + COALESCE(purchase_row.freight_amount, 0)
        """,
        """
        SELECT 'rental_bill_without_payment_rows' AS check_name,
               COUNT(*) AS check_value
        FROM `rental_bill` AS bill
        WHERE bill.status = 'POSTED'
          AND COALESCE((
              SELECT SUM(payment.amount)
              FROM `payment_record` AS payment
              WHERE payment.source_type = 'RENTAL_BILL'
                AND payment.source_id = bill.id
                AND payment.direction = 'RECEIPT'
          ), 0) < COALESCE(bill.amount, 0)
        """,
        """
        SELECT 'completed_repair_without_payment_rows' AS check_name,
               COUNT(*) AS check_value
        FROM `repair_record` AS repair
        WHERE repair.status = 'COMPLETED'
          AND COALESCE((
              SELECT SUM(payment.amount)
              FROM `payment_record` AS payment
              WHERE payment.source_type = 'REPAIR'
                AND payment.source_id = repair.id
                AND payment.direction = 'RECEIPT'
          ), 0) < COALESCE(repair.receivable_amount, 0)
        """,
        """
        SELECT 'nonterminal_or_failed_import_job_rows' AS check_name,
               COUNT(*) AS check_value
        FROM `data_import_job`
        WHERE COALESCE(status, '') <> 'COMPLETED'
           OR COALESCE(error_rows, 0) <> 0
           OR COALESCE(imported_rows, 0) < 5
        """,
    ]
    selects.extend(
        _table_count_select(f"table_{check_name}_rows", table_name)
        for check_name, table_name, _minimum_rows in MINIMUM_ROW_COUNTS
    )
    return "\nUNION ALL\n".join(statement.strip() for statement in selects) + "\nORDER BY check_name"


SQL_CHECK_QUERY = _build_sql_check_query()


@dataclass
class CheckResult:
    name: str
    expected: str
    actual: str
    passed: bool


@dataclass
class VerificationReport:
    status: str
    api: list[CheckResult] = field(default_factory=list)
    sql: list[CheckResult] = field(default_factory=list)
    failures: list[str] = field(default_factory=list)
    sqlEnabled: bool = False
    baseUrl: str | None = None


class VerificationError(RuntimeError):
    """An expected verification failure with a deliberately safe message."""


def _env_bool(name: str, default: bool = False) -> bool:
    value = os.environ.get(name)
    if value is None:
        return default
    normalized = value.strip().lower()
    if normalized in {"1", "true", "yes", "on"}:
        return True
    if normalized in {"0", "false", "no", "off", ""}:
        return False
    raise argparse.ArgumentTypeError(
        f"{name} must be one of 1/0, true/false, yes/no, or on/off"
    )


def _positive_int(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("must be an integer") from exc
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be greater than zero")
    return parsed


def _port(value: str) -> int:
    parsed = _positive_int(value)
    if parsed > 65535:
        raise argparse.ArgumentTypeError("must be at most 65535")
    return parsed


def _iso_date(value: str) -> str:
    try:
        return date.fromisoformat(value).isoformat()
    except ValueError as exc:
        raise argparse.ArgumentTypeError("must use YYYY-MM-DD") from exc


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Read-only validation for the API-seeded closed-loop forklift ERP test database."
        )
    )
    parser.add_argument(
        "--base-url",
        default=os.environ.get("FORKLIFT_ERP_BASE_URL", "http://127.0.0.1:8080"),
        help="ERP HTTP(S) base URL (default: FORKLIFT_ERP_BASE_URL or local port 8080).",
    )
    parser.add_argument(
        "--username",
        default=os.environ.get("FORKLIFT_ERP_ADMIN_USERNAME", "admin"),
        help="Administrator username (default: FORKLIFT_ERP_ADMIN_USERNAME or admin).",
    )
    parser.add_argument(
        "--password",
        default=os.environ.get("FORKLIFT_ERP_ADMIN_PASSWORD", "admin123"),
        help="Administrator password; output is always redacted.",
    )
    parser.add_argument(
        "--timeout",
        type=_positive_int,
        default=_positive_int(os.environ.get("FORKLIFT_ERP_VERIFY_TIMEOUT", "15")),
        help="Per-HTTP-request timeout in seconds (default: 15).",
    )
    parser.add_argument(
        "--insecure",
        action="store_true",
        default=_env_bool("FORKLIFT_ERP_VERIFY_INSECURE"),
        help="Accept a self-signed HTTPS certificate for local development.",
    )
    parser.add_argument(
        "--reconciliation-date",
        "--date",
        dest="reconciliation_date",
        type=_iso_date,
        help="Optional YYYY-MM-DD activity date for the daily reconciliation endpoint.",
    )
    parser.add_argument(
        "--finance-year",
        "--year",
        dest="finance_year",
        type=int,
        help="Optional year for the finance dashboard endpoint.",
    )

    parser.set_defaults(sql_checks=_env_bool("FORKLIFT_ERP_VERIFY_SQL"))
    sql_group = parser.add_mutually_exclusive_group()
    sql_group.add_argument(
        "--sql",
        "--sql-checks",
        dest="sql_checks",
        action="store_true",
        help="Also run local read-only MySQL checks.",
    )
    sql_group.add_argument(
        "--no-sql",
        dest="sql_checks",
        action="store_false",
        help="Skip local MySQL checks even when FORKLIFT_ERP_VERIFY_SQL is enabled.",
    )
    parser.add_argument(
        "--mysql-client",
        "--mysql-executable",
        dest="mysql_client",
        default=os.environ.get("FORKLIFT_ERP_MYSQL_CLIENT", "mysql"),
        help="Local mysql client executable (default: FORKLIFT_ERP_MYSQL_CLIENT or mysql).",
    )
    parser.add_argument(
        "--db-host",
        default=os.environ.get("FORKLIFT_ERP_DB_HOST", "127.0.0.1"),
        help="MySQL host for optional checks (default: FORKLIFT_ERP_DB_HOST or 127.0.0.1).",
    )
    parser.add_argument(
        "--db-port",
        type=_port,
        default=_port(os.environ.get("FORKLIFT_ERP_DB_PORT", "3306")),
        help="MySQL port for optional checks (default: FORKLIFT_ERP_DB_PORT or 3306).",
    )
    parser.add_argument(
        "--db-name",
        default=os.environ.get("FORKLIFT_ERP_DB_NAME", "forklift_erp"),
        help="MySQL schema for optional checks (default: FORKLIFT_ERP_DB_NAME or forklift_erp).",
    )
    parser.add_argument(
        "--db-user",
        default=os.environ.get("FORKLIFT_ERP_DB_USERNAME", "root"),
        help="MySQL user for optional checks (default: FORKLIFT_ERP_DB_USERNAME or root).",
    )
    parser.add_argument(
        "--mysql-timeout",
        type=_positive_int,
        default=_positive_int(os.environ.get("FORKLIFT_ERP_VERIFY_MYSQL_TIMEOUT", "20")),
        help="Total mysql client timeout in seconds (default: 20).",
    )
    return parser


def _display_base_url(base_url: str) -> str:
    """Return a presentation-safe URL that cannot expose user-info secrets."""

    parsed = urllib.parse.urlsplit(base_url)
    if not parsed.scheme or not parsed.netloc:
        raise VerificationError("base URL must be an absolute http:// or https:// URL")
    if parsed.scheme not in {"http", "https"}:
        raise VerificationError("base URL must use http or https")
    host = parsed.hostname
    if not host:
        raise VerificationError("base URL must contain a host")
    netloc = host
    if ":" in host and not host.startswith("["):
        netloc = f"[{host}]"
    if parsed.port is not None:
        netloc = f"{netloc}:{parsed.port}"
    return urllib.parse.urlunsplit((parsed.scheme, netloc, parsed.path.rstrip("/"), "", ""))


def _endpoint(base_url: str, path: str, query: Mapping[str, Any] | None = None) -> str:
    if not path.startswith("/"):
        raise ValueError("endpoint path must start with a slash")
    url = base_url.rstrip("/") + path
    if query:
        filtered = {key: value for key, value in query.items() if value is not None}
        if filtered:
            url += "?" + urllib.parse.urlencode(filtered)
    return url


@dataclass
class ApiClient:
    base_url: str
    timeout: int
    insecure: bool
    token: str = field(repr=False)

    @classmethod
    def login(
        cls,
        base_url: str,
        username: str,
        password: str,
        timeout: int,
        insecure: bool,
    ) -> "ApiClient":
        login_data = cls._request_json(
            base_url=base_url,
            timeout=timeout,
            insecure=insecure,
            method="POST",
            path="/api/auth/login",
            payload={"username": username, "password": password},
        )
        if not isinstance(login_data, Mapping):
            raise VerificationError("login returned an unexpected API payload")
        token = login_data.get("token")
        if not isinstance(token, str) or not token.strip():
            raise VerificationError("login succeeded but did not return a token")
        return cls(base_url=base_url, timeout=timeout, insecure=insecure, token=token)

    def get(self, path: str, query: Mapping[str, Any] | None = None) -> Any:
        return self._request_json(
            base_url=self.base_url,
            timeout=self.timeout,
            insecure=self.insecure,
            method="GET",
            path=path,
            query=query,
            token=self.token,
        )

    @staticmethod
    def _request_json(
        *,
        base_url: str,
        timeout: int,
        insecure: bool,
        method: str,
        path: str,
        payload: Mapping[str, Any] | None = None,
        query: Mapping[str, Any] | None = None,
        token: str | None = None,
    ) -> Any:
        data = None
        headers = {"Accept": "application/json"}
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = f"Bearer {token}"
        request = urllib.request.Request(
            _endpoint(base_url, path, query), data=data, headers=headers, method=method
        )
        context = ssl._create_unverified_context() if insecure else None
        try:
            with urllib.request.urlopen(request, timeout=timeout, context=context) as response:
                raw_body = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            # Do not include an HTTP response body: it is not needed for an
            # assertion and this verifier must never risk echoing a secret.
            raise VerificationError(f"{method} {path} returned HTTP {exc.code}") from exc
        except urllib.error.URLError as exc:
            raise VerificationError(f"{method} {path} could not reach the ERP API") from exc
        except TimeoutError as exc:
            raise VerificationError(f"{method} {path} timed out") from exc

        try:
            envelope = json.loads(raw_body)
        except json.JSONDecodeError as exc:
            raise VerificationError(f"{method} {path} returned invalid JSON") from exc
        if not isinstance(envelope, Mapping):
            raise VerificationError(f"{method} {path} returned an invalid API envelope")
        if envelope.get("code") != 200:
            raise VerificationError(f"{method} {path} returned an unsuccessful API envelope")
        if "data" not in envelope:
            raise VerificationError(f"{method} {path} returned no API data")
        return envelope["data"]


def _record_result(
    results: list[CheckResult],
    failures: list[str],
    name: str,
    expected: str,
    actual: object,
    passed: bool,
) -> None:
    actual_text = str(actual)
    results.append(CheckResult(name=name, expected=expected, actual=actual_text, passed=passed))
    if not passed:
        failures.append(f"{name}: expected {expected}, got {actual_text}")


def _expect_mapping(value: Any, description: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise VerificationError(f"{description} returned an unexpected payload")
    return value


def _expect_non_negative_int(value: Any, description: str) -> int:
    # bool is an int subclass in Python, but it is not a valid JSON count.
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise VerificationError(f"{description} returned an invalid count")
    return value


def verify_api(client: ApiClient, args: argparse.Namespace) -> tuple[list[CheckResult], list[str]]:
    results: list[CheckResult] = []
    failures: list[str] = []

    try:
        reconciliation = _expect_mapping(
            client.get(
                "/api/statistics/reconciliation/daily",
                {"date": args.reconciliation_date},
            ),
            "daily reconciliation",
        )
        summary = _expect_mapping(reconciliation.get("summary"), "daily reconciliation summary")
    except VerificationError as exc:
        _record_result(results, failures, "daily_reconciliation_error_count", "0", "unavailable", False)
        failures[-1] = str(exc)
        _record_result(
            results,
            failures,
            "daily_reconciliation_overpaid_sales_count",
            "0",
            "unavailable",
            False,
        )
        failures[-1] = str(exc)
    else:
        try:
            error_count = _expect_non_negative_int(
                summary.get("errorCount"),
                "daily reconciliation errorCount",
            )
            _record_result(
                results,
                failures,
                "daily_reconciliation_error_count",
                "0",
                error_count,
                error_count == 0,
            )
        except VerificationError as exc:
            _record_result(results, failures, "daily_reconciliation_error_count", "0", "unavailable", False)
            failures[-1] = str(exc)

        try:
            overpaid_sales_count = _expect_non_negative_int(
                summary.get("overpaidSalesCount"),
                "daily reconciliation overpaidSalesCount",
            )
            _record_result(
                results,
                failures,
                "daily_reconciliation_overpaid_sales_count",
                "0",
                overpaid_sales_count,
                overpaid_sales_count == 0,
            )
        except VerificationError as exc:
            _record_result(
                results,
                failures,
                "daily_reconciliation_overpaid_sales_count",
                "0",
                "unavailable",
                False,
            )
            failures[-1] = str(exc)

    try:
        exceptions = client.get("/api/data-quality/migration-exceptions")
        if not isinstance(exceptions, list):
            raise VerificationError("migration exception endpoint returned an unexpected payload")
        _record_result(results, failures, "migration_exception_count", "0", len(exceptions), not exceptions)
    except VerificationError as exc:
        _record_result(results, failures, "migration_exception_count", "0", "unavailable", False)
        failures[-1] = str(exc)

    try:
        finance = _expect_mapping(
            client.get("/api/statistics/finance", {"year": args.finance_year}),
            "finance dashboard",
        )
        warnings = finance.get("dataWarnings")
        if not isinstance(warnings, list):
            raise VerificationError("finance dashboard returned an invalid dataWarnings field")
        _record_result(results, failures, "finance_data_warning_count", "0", len(warnings), not warnings)
    except VerificationError as exc:
        _record_result(results, failures, "finance_data_warning_count", "0", "unavailable", False)
        failures[-1] = str(exc)

    return results, failures


def _mysql_password_from_environment() -> str | None:
    # MYSQL_PWD takes precedence because it is the value mysql itself would
    # consume.  This function intentionally has no command-line password
    # counterpart, avoiding accidental exposure through process listings.
    return os.environ.get("MYSQL_PWD") or os.environ.get("FORKLIFT_ERP_DB_PASSWORD")


def _mysql_command(args: argparse.Namespace) -> list[str]:
    return [
        args.mysql_client,
        # Keep client option files from supplying an init-command or a
        # different connection target.  It must be the first mysql option.
        "--no-defaults",
        "--protocol=TCP",
        f"--host={args.db_host}",
        f"--port={args.db_port}",
        f"--user={args.db_user}",
        f"--database={args.db_name}",
        "--connect-timeout=5",
        "--batch",
        "--skip-column-names",
        "--raw",
        "--default-character-set=utf8mb4",
        f"--execute={SQL_CHECK_QUERY}",
    ]


def _parse_mysql_results(output: str) -> dict[str, int]:
    values: dict[str, int] = {}
    for raw_line in output.splitlines():
        if not raw_line.strip():
            continue
        try:
            name, raw_value = raw_line.split("\t", maxsplit=1)
        except ValueError as exc:
            raise VerificationError("mysql returned an unexpected result format") from exc
        if name in values:
            raise VerificationError("mysql returned duplicate check names")
        try:
            value = int(raw_value.strip())
        except ValueError as exc:
            raise VerificationError(f"mysql returned a non-numeric value for {name}") from exc
        values[name] = value
    if not values:
        raise VerificationError("mysql returned no verification results")
    return values


def verify_sql(args: argparse.Namespace) -> tuple[list[CheckResult], list[str]]:
    results: list[CheckResult] = []
    failures: list[str] = []
    password = _mysql_password_from_environment()
    if not password:
        return results, [
            "SQL verification requires MYSQL_PWD or FORKLIFT_ERP_DB_PASSWORD; the password is never printed."
        ]
    if not shutil.which(args.mysql_client) and not os.path.isfile(args.mysql_client):
        return results, ["mysql client executable was not found for SQL verification"]

    environment = os.environ.copy()
    environment["MYSQL_PWD"] = password
    try:
        completed = subprocess.run(
            _mysql_command(args),
            check=False,
            cwd=None,
            env=environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=args.mysql_timeout,
        )
    except FileNotFoundError:
        return results, ["mysql client executable was not found for SQL verification"]
    except subprocess.TimeoutExpired:
        return results, ["mysql client timed out during SQL verification"]

    if completed.returncode != 0:
        # Stderr can vary by client/server version.  Do not relay it: an error
        # from a custom wrapper could conceivably include sensitive data.
        return results, [f"mysql client failed with exit code {completed.returncode}"]

    try:
        values = _parse_mysql_results(completed.stdout)
    except VerificationError as exc:
        return results, [str(exc)]

    exact_expectations: dict[str, int] = {
        "flyway_latest_version": EXPECTED_FLYWAY_VERSION,
        "flyway_v51_success": 1,
        "flyway_failed_migrations": 0,
        "migration_exception_rows": 0,
        "master_balance_mismatch_rows": 0,
        "balance_lot_mismatch_rows": 0,
        "orphan_balance_rows": 0,
        "negative_balance_rows": 0,
        "invalid_lot_quantity_rows": 0,
        "machines_missing_two_configs": 0,
        "active_rental_rows": 0,
        "pending_repair_rows": 0,
        "active_modification_rows": 0,
        "active_purchase_rows": 0,
        "nonterminal_machine_stock_rows": 0,
        "pending_stocktaking_rows": 0,
        "invalid_repair_attachment_rows": 0,
        "unsettled_outbound_rows": 0,
        "received_purchase_without_payment_rows": 0,
        "rental_bill_without_payment_rows": 0,
        "completed_repair_without_payment_rows": 0,
        "nonterminal_or_failed_import_job_rows": 0,
    }
    for name, expected in exact_expectations.items():
        actual = values.get(name)
        if actual is None:
            _record_result(results, failures, name, str(expected), "missing", False)
        else:
            _record_result(results, failures, name, str(expected), actual, actual == expected)

    minimum_expectations: dict[str, int] = {
        f"table_{check_name}_rows": minimum_rows
        for check_name, _table_name, minimum_rows in MINIMUM_ROW_COUNTS
    }
    minimum_expectations.update(dict(MINIMUM_SEMANTIC_COUNTS))
    for name, minimum_rows in minimum_expectations.items():
        actual = values.get(name)
        if actual is None:
            _record_result(results, failures, name, f">= {minimum_rows}", "missing", False)
        else:
            _record_result(results, failures, name, f">= {minimum_rows}", actual, actual >= minimum_rows)
    return results, failures


def _json_report(report: VerificationReport) -> str:
    # Convert dataclasses explicitly so neither the bearer token nor parsed
    # argparse state (which could contain a password) can reach stdout.
    payload = {
        "status": report.status,
        "baseUrl": report.baseUrl,
        "sqlEnabled": report.sqlEnabled,
        "api": [asdict(result) for result in report.api],
        "sql": [asdict(result) for result in report.sql],
        "failures": report.failures,
    }
    return json.dumps(payload, ensure_ascii=False, indent=2)


def run(args: argparse.Namespace) -> VerificationReport:
    display_url = _display_base_url(args.base_url)
    report = VerificationReport(
        status="failed",
        sqlEnabled=bool(args.sql_checks),
        baseUrl=display_url,
    )
    try:
        client = ApiClient.login(
            args.base_url.rstrip("/"),
            args.username,
            args.password,
            args.timeout,
            args.insecure,
        )
    except VerificationError as exc:
        report.failures.append(str(exc))
        return report

    report.api, api_failures = verify_api(client, args)
    report.failures.extend(api_failures)
    if args.sql_checks:
        report.sql, sql_failures = verify_sql(args)
        report.failures.extend(sql_failures)
    report.status = "passed" if not report.failures else "failed"
    return report


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    try:
        args = parser.parse_args(argv)
        report = run(args)
    except VerificationError as exc:
        # All VerificationError messages are constructed from endpoint/check
        # labels, never a server response or a credential value.
        report = VerificationReport(status="failed", failures=[str(exc)])
    except Exception:
        # Avoid echoing arbitrary exception text, which could contain a URL
        # with user-info or another credential supplied by a wrapper.
        report = VerificationReport(status="failed", failures=["verification aborted unexpectedly"])
    print(_json_report(report))
    return 0 if report.status == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
