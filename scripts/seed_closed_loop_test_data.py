#!/usr/bin/env python3
"""Create a coherent, closed-loop regression dataset through the public REST API.

This script deliberately has no database connection and does not call the
business-data reset endpoint.  Run ``rebuild_complete_test_database.ps1`` (or
another approved database rebuild) first, start the application with demo data
disabled, and then run this script against that empty application.

The data is intentionally synthetic.  Its value is that every inventory and
financial fact is created by the same API workflow used in production: purchase
receipts create FIFO lots, sales consume them, payments settle source documents,
rental/repair/modification workflows move stock, and imports/attachments use
their real upload APIs.

Example:
    python scripts/seed_closed_loop_test_data.py --base-url http://127.0.0.1:8123

The script refuses to run on a database containing business data.  That makes a
failed or partially run seed visibly safe instead of silently mixing two test
datasets.  A single pre-existing empty/default warehouse is permitted because
the application may have created it before the seed starts.
"""

from __future__ import annotations

import argparse
import base64
import io
import json
import ssl
import sys
import uuid
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP
from typing import Any, Iterable, Mapping
from urllib.parse import urlencode
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from openpyxl import load_workbook


NAMESPACE = "CLOSELOOP"
OPERATOR = "closed-loop-seed"
MACHINE_MODEL = "CLOSELOOP-MODEL"
MONEY = Decimal("0.01")

# Exactly thirty serialised vehicles.  The groups deliberately overlap across
# workflow history only where the lifecycle permits it: every sold vehicle is
# first modified, and every repair vehicle is first rented and returned.
# That gives a compact but broad regression data set rather than a pile of
# unrelated static inventory.
MACHINE_GROUPS: dict[str, tuple[str, ...]] = {
    "sold": tuple(f"sold_{index:02d}" for index in range(1, 11)),
    "rental_repair": tuple(f"rental_repair_{index:02d}" for index in range(1, 7)),
    "mod_cancel": tuple(f"mod_cancel_{index:02d}" for index in range(1, 5)),
    "transfer": tuple(f"transfer_{index:02d}" for index in range(1, 5)),
    "available": tuple(f"available_{index:02d}" for index in range(1, 7)),
}
MACHINE_KEYS = tuple(key for group in MACHINE_GROUPS.values() for key in group)
if len(MACHINE_KEYS) != 30 or len(set(MACHINE_KEYS)) != len(MACHINE_KEYS):
    raise RuntimeError("Closed-loop fixture matrix must contain exactly 30 unique vehicle keys")

# Five validated opening rows make imports and counts useful without creating
# an unmanageably large workbook.  Their five SKUs, five purchased SKUs, and
# ten removed parts produced by completed modifications yield at least twenty
# part master rows.
IMPORTED_PART_SPECS: tuple[tuple[str, str, str, int, str], ...] = tuple(
    (
        f"imported_{index:02d}",
        f"{NAMESPACE}-IMPORTED-OPENING-{index:02d}",
        f"Closed Loop Imported Opening Part {index:02d}",
        6,
        f"{30 + index}.00",
    )
    for index in range(1, 6)
)

# key, code, name, category, quantity, purchase price, sale price, freight,
# specification.  The three sale SKUs support fifteen traceable part orders;
# tire and service-kit quantities support the vehicle workflows below.
PART_PURCHASE_SPECS: tuple[tuple[str, str, str, str, int, str, str, str, str], ...] = (
    ("tire", f"{NAMESPACE}-TIRE", "Closed Loop Replacement Tire", "TIRE", 20, "110.00", "180.00", "20.00",
     "Replacement tire for closed-loop modification"),
    ("service", f"{NAMESPACE}-SERVICE-KIT", "Closed Loop Service Kit", "SERVICE", 18, "60.00", "125.00", "12.00",
     "Traceable repair material kit"),
    ("sale_a", f"{NAMESPACE}-SALE-KIT-A", "Closed Loop Sale Accessory A", "ACCESSORY", 16, "45.00", "85.00", "8.00",
     "Standalone accessory sale fixture A"),
    ("sale_b", f"{NAMESPACE}-SALE-KIT-B", "Closed Loop Sale Accessory B", "ACCESSORY", 16, "48.00", "92.00", "8.00",
     "Standalone accessory sale fixture B"),
    ("sale_c", f"{NAMESPACE}-SALE-KIT-C", "Closed Loop Sale Accessory C", "ACCESSORY", 16, "52.00", "99.00", "8.00",
     "Standalone accessory sale fixture C"),
)

# A tiny, valid PNG.  It exercises actual multipart file storage rather than
# forging an attachment row, while keeping the seed self-contained.
ONE_PIXEL_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4"
    "z8DwHwAFgAI/ScLzYQAAAABJRU5ErkJggg=="
)


class SeedError(RuntimeError):
    """A descriptive API or data-integrity failure raised by this seed."""


def money(value: Any) -> str:
    """Return a JSON-safe, two-decimal monetary value accepted by BigDecimal."""
    return format(Decimal(str(value)).quantize(MONEY, rounding=ROUND_HALF_UP), "f")


def decimal_value(value: Any, field_name: str) -> Decimal:
    if value is None:
        raise SeedError(f"Response field {field_name!r} is missing")
    try:
        return Decimal(str(value))
    except Exception as exc:  # pragma: no cover - defensive server-response diagnostic
        raise SeedError(f"Response field {field_name!r} is not a decimal: {value!r}") from exc


def as_list(value: Any) -> list[dict[str, Any]]:
    """Normalize REST list/page responses without accepting malformed payloads."""
    if value is None:
        return []
    if isinstance(value, list):
        return [row for row in value if isinstance(row, dict)]
    if isinstance(value, dict):
        for key in ("content", "records", "items", "list"):
            nested = value.get(key)
            if isinstance(nested, list):
                return [row for row in nested if isinstance(row, dict)]
    raise SeedError(f"Expected a list/page response but received: {value!r}")


def require_id(row: Mapping[str, Any], label: str) -> int:
    value = row.get("id")
    if not isinstance(value, int):
        raise SeedError(f"{label} did not return a numeric id: {row!r}")
    return value


def require_version(row: Mapping[str, Any], label: str) -> int:
    value = row.get("version")
    if not isinstance(value, int):
        raise SeedError(f"{label} did not return a numeric optimistic-lock version: {row!r}")
    return value


def require_equal(actual: Any, expected: Any, label: str) -> None:
    if actual != expected:
        raise SeedError(f"{label}: expected {expected!r}, got {actual!r}")


@dataclass
class ApiClient:
    base_url: str
    username: str
    password: str
    timeout_seconds: int = 30
    token: str = field(init=False)

    def __post_init__(self) -> None:
        self.base_url = self.base_url.rstrip("/")
        self.token = self._login()

    def _login(self) -> str:
        data = self.request(
            "POST",
            "/api/auth/login",
            {"username": self.username, "password": self.password},
            authenticate=False,
        )
        if not isinstance(data, dict) or not data.get("token"):
            raise SeedError("Login succeeded but did not return an access token")
        return str(data["token"])

    def request(
        self,
        method: str,
        path: str,
        payload: Mapping[str, Any] | None = None,
        *,
        authenticate: bool = True,
    ) -> Any:
        data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers = {"Accept": "application/json"}
        if payload is not None:
            headers["Content-Type"] = "application/json; charset=utf-8"
        if authenticate:
            headers["Authorization"] = f"Bearer {self.token}"
        return self._read_result(method, path, data, headers)

    def download(self, path: str) -> bytes:
        url = f"{self.base_url}{path}"
        request = Request(
            url,
            headers={"Accept": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Authorization": f"Bearer {self.token}"},
            method="GET",
        )
        try:
            with urlopen(request, timeout=self.timeout_seconds, context=ssl._create_unverified_context()) as response:
                return response.read()
        except HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            raise SeedError(f"GET {path} failed with HTTP {exc.code}: {body}") from exc
        except URLError as exc:
            raise SeedError(f"GET {path} could not reach {self.base_url}: {exc}") from exc

    def multipart(
        self,
        method: str,
        path: str,
        fields: Mapping[str, Any],
        *,
        file_field: str,
        filename: str,
        content_type: str,
        content: bytes,
    ) -> Any:
        boundary = f"----forklift-erp-seed-{uuid.uuid4().hex}"
        body = bytearray()
        for key, value in fields.items():
            if value is None:
                continue
            body.extend(f"--{boundary}\r\n".encode("ascii"))
            body.extend(f'Content-Disposition: form-data; name="{key}"\r\n\r\n'.encode("utf-8"))
            body.extend(str(value).encode("utf-8"))
            body.extend(b"\r\n")
        body.extend(f"--{boundary}\r\n".encode("ascii"))
        body.extend(
            f'Content-Disposition: form-data; name="{file_field}"; filename="{filename}"\r\n'.encode("utf-8")
        )
        body.extend(f"Content-Type: {content_type}\r\n\r\n".encode("ascii"))
        body.extend(content)
        body.extend(b"\r\n")
        body.extend(f"--{boundary}--\r\n".encode("ascii"))
        headers = {
            "Accept": "application/json",
            "Authorization": f"Bearer {self.token}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        }
        return self._read_result(method, path, bytes(body), headers)

    def _read_result(self, method: str, path: str, data: bytes | None, headers: Mapping[str, str]) -> Any:
        url = f"{self.base_url}{path}"
        request = Request(url, data=data, headers=dict(headers), method=method)
        try:
            with urlopen(request, timeout=self.timeout_seconds, context=ssl._create_unverified_context()) as response:
                raw = response.read().decode("utf-8")
        except HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            raise SeedError(f"{method} {path} failed with HTTP {exc.code}: {body}") from exc
        except URLError as exc:
            raise SeedError(f"{method} {path} could not reach {self.base_url}: {exc}") from exc
        try:
            body = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise SeedError(f"{method} {path} returned non-JSON content: {raw[:500]!r}") from exc
        if not isinstance(body, dict) or body.get("code") != 200:
            raise SeedError(f"{method} {path} returned an unsuccessful API envelope: {body!r}")
        return body.get("data")


@dataclass(frozen=True)
class SeedDates:
    today: date

    @property
    def procurement(self) -> date:
        return self.today - timedelta(days=70)

    @property
    def modification(self) -> date:
        return self.today - timedelta(days=35)

    @property
    def sale(self) -> date:
        return self.today - timedelta(days=14)

    @property
    def rental_start(self) -> date:
        return self.today - timedelta(days=105)

    @property
    def repair(self) -> date:
        return self.today - timedelta(days=2)

    def timestamp(self, day: date, hour: int = 10) -> str:
        return datetime.combine(day, datetime.min.time()).replace(hour=hour).isoformat(timespec="seconds")


@dataclass
class SeedState:
    warehouse: dict[str, Any]
    service_warehouse: dict[str, Any] | None = None
    suppliers: dict[str, dict[str, Any]] = field(default_factory=dict)
    customers: dict[str, dict[str, Any]] = field(default_factory=dict)
    config_items: dict[str, dict[str, Any]] = field(default_factory=dict)
    config_values: dict[str, dict[str, Any]] = field(default_factory=dict)
    parts: dict[str, dict[str, Any]] = field(default_factory=dict)
    machines: dict[str, dict[str, Any]] = field(default_factory=dict)
    purchase_orders: dict[str, dict[str, Any]] = field(default_factory=dict)
    outbound_orders: dict[str, dict[str, Any]] = field(default_factory=dict)
    rentals: dict[str, dict[str, Any]] = field(default_factory=dict)
    repairs: dict[str, dict[str, Any]] = field(default_factory=dict)
    completed_modifications: dict[str, dict[str, Any]] = field(default_factory=dict)
    canceled_modifications: dict[str, dict[str, Any]] = field(default_factory=dict)
    removed_parts: dict[str, dict[str, Any]] = field(default_factory=dict)
    imported_parts: dict[str, dict[str, Any]] = field(default_factory=dict)
    attachments: dict[str, dict[str, Any]] = field(default_factory=dict)
    stocktakes: dict[str, dict[str, Any]] = field(default_factory=dict)
    import_job: dict[str, Any] | None = None


# This is emitted with the final JSON report.  It is intentionally a mapping
# from physical table names to the REST workflows that create/verify them, so a
# developer can see why each non-master fact is trustworthy.
TABLE_COVERAGE: dict[str, list[str]] = {
    "warehouse": ["POST /api/warehouses", "POST /api/warehouses/transfer"],
    "supplier": ["POST /api/suppliers", "POST /api/purchase-orders"],
    "customer_profile": ["POST /api/customers", "sales/rental/repair APIs"],
    "config_item": ["POST /api/config/items"],
    "config_value": ["POST /api/config/values"],
    "vehicle_config_item": ["POST /api/config/vehicle-items"],
    "vehicle_config_value": ["POST /api/config/vehicle-values"],
    "machine_inventory": ["POST /api/workflows/machine-inbound-purchase"],
    "machine_config": ["machine inbound configs", "modification work order"],
    "part_inventory": ["POST /api/parts", "PART import", "removed-part valuation"],
    "purchase_order": ["POST /api/purchase-orders", "PUT /received"],
    "stock_balance": ["purchase receipt, sales, rental, repair, transfer, stocktake"],
    "stock_lot": ["purchase receipt, import opening, modification removed-part receipt"],
    "stock_lot_consumption": ["outbound sale, repair usage, modification replacement"],
    "stock_lot_cost_adjustment": ["PUT /api/parts/{id}/valuation"],
    "stock_movement": ["receipt, outbound, rental, repair, transfer, stocktake APIs"],
    "stock_movement_line": ["receipt, outbound, rental, repair, transfer, stocktake APIs"],
    "stock_operation_log": ["receipt/outbound/repair/transfer workflow services"],
    "outbound_order": ["POST /api/outbound-orders/vehicle", "POST /part"],
    "financial_event": ["purchase receipt, sales, rental bills, repair completion, payments"],
    "payment_record": ["POST /api/payments"],
    "request_idempotency": ["duplicate payment request assertion"],
    "rental_record": ["POST /api/rentals", "PUT returned"],
    "rental_bill": ["POST /api/rentals/{id}/bills/refresh", "return final refresh"],
    "repair_record": ["POST /api/repairs", "PUT /status"],
    "repair_part_usage": ["repair partUsages payload"],
    "modification_work_order": ["POST /api/modification-work-orders", "complete/cancel"],
    "modification_work_order_line": ["completed STOCK_IN and canceled DISCOUNT line"],
    "config_replace_log": ["completed modification replacement"],
    "stocktaking_record": ["POST /api/stocktaking-records", "PUT /complete"],
    "resource_attachment": ["multipart POST /api/attachments"],
    "data_import_job": ["POST /api/imports/PART/validate", "POST /confirm"],
    "data_import_row": ["POST /api/imports/PART/validate"],
    "operation_audit_log": ["all workflow writes above"],
    "migration_exception": ["GET /api/data-quality/migration-exceptions?status=OPEN (assert zero)"],
}


def list_resource(client: ApiClient, path: str) -> list[dict[str, Any]]:
    return as_list(client.request("GET", path))


def first_matching(rows: Iterable[dict[str, Any]], key: str, value: Any) -> dict[str, Any] | None:
    return next((row for row in rows if row.get(key) == value), None)


def assert_empty_business_database(client: ApiClient) -> list[dict[str, Any]]:
    """Fail safely before any writes if a previous test dataset is present."""
    checks = {
        "suppliers": "/api/suppliers?paged=false",
        "customers": "/api/customers?paged=false",
        "configuration items": "/api/config/items",
        "vehicle config templates": "/api/config/vehicle-items",
        "machines": "/api/inventory?paged=false",
        "parts": "/api/parts?paged=false",
        "purchase orders": "/api/purchase-orders?paged=false",
        "outbound orders": "/api/outbound-orders?paged=false",
        "rentals": "/api/rentals?paged=false",
        "repairs": "/api/repairs?paged=false",
        "modification work orders": "/api/modification-work-orders?paged=false",
        "stocktaking records": "/api/stocktaking-records?paged=false",
        "import jobs": "/api/imports",
    }
    non_empty: dict[str, int] = {}
    for label, path in checks.items():
        rows = list_resource(client, path)
        if rows:
            non_empty[label] = len(rows)
    if non_empty:
        details = ", ".join(f"{label}={count}" for label, count in non_empty.items())
        raise SeedError(
            "Refusing to mix the closed-loop seed into non-empty business data ("
            f"{details}). Rebuild/clear the business database first."
        )
    warehouses = list_resource(client, "/api/warehouses?paged=false")
    if len(warehouses) > 1:
        raise SeedError(
            "The PART opening-import workflow requires exactly one warehouse before import; "
            f"found {len(warehouses)}. Rebuild the database first."
        )
    return warehouses


def ensure_primary_warehouse(client: ApiClient, existing: list[dict[str, Any]]) -> dict[str, Any]:
    if not existing:
        created = client.request(
            "POST",
            "/api/warehouses",
            {
                "warehouseCode": f"{NAMESPACE}-WH",
                "warehouseName": "Closed Loop Main Warehouse",
                "warehouseType": "MAIN",
                "address": "Synthetic test data / primary warehouse",
                "defaultWarehouse": True,
            },
        )
        if not isinstance(created, dict):
            raise SeedError("Warehouse creation did not return a warehouse")
        return created

    warehouse = existing[0]
    # Import confirmation resolves a null warehouse id.  With exactly one
    # warehouse this is normally sufficient, but make the row explicitly
    # default as well so its intent remains obvious in future environments.
    if not warehouse.get("defaultWarehouse") and warehouse.get("warehouseCode") != "DEFAULT":
        updated = client.request(
            "PUT",
            f"/api/warehouses/{require_id(warehouse, 'existing warehouse')}",
            {
                "version": require_version(warehouse, "existing warehouse"),
                "warehouseCode": warehouse.get("warehouseCode"),
                "warehouseName": warehouse.get("warehouseName"),
                "warehouseType": warehouse.get("warehouseType") or "MAIN",
                "address": warehouse.get("address"),
                "defaultWarehouse": True,
            },
        )
        if not isinstance(updated, dict):
            raise SeedError("Existing default warehouse update did not return a warehouse")
        warehouse = updated
    return warehouse


def create_masters(client: ApiClient, state: SeedState) -> None:
    state.suppliers["machine"] = client.request(
        "POST",
        "/api/suppliers",
        {
            "supplierName": f"{NAMESPACE} Machine Supplier",
            "supplierType": "MANUFACTURER",
            "active": True,
            "contactName": "Machine Procurement",
            "contactPhone": "13800000011",
            "address": "Synthetic Supplier District A",
            "taxNumber": "CL-MACHINE-SUPPLIER",
            "bankAccount": "TEST-MACHINE-001",
            "remarks": "Source master for closed-loop vehicle procurement",
        },
    )
    state.suppliers["parts"] = client.request(
        "POST",
        "/api/suppliers",
        {
            "supplierName": f"{NAMESPACE} Parts Supplier",
            "supplierType": "PARTS",
            "active": True,
            "contactName": "Parts Procurement",
            "contactPhone": "13800000012",
            "address": "Synthetic Supplier District B",
            "taxNumber": "CL-PARTS-SUPPLIER",
            "bankAccount": "TEST-PARTS-001",
            "remarks": "Source master for FIFO part receipts",
        },
    )
    for key, company, phone in (
        ("sale", f"{NAMESPACE} Sales Customer", "13900000011"),
        ("rental", f"{NAMESPACE} Rental Customer", "13900000012"),
        ("repair", f"{NAMESPACE} Repair Customer", "13900000013"),
    ):
        state.customers[key] = client.request(
            "POST",
            "/api/customers",
            {
                "companyName": company,
                "address": f"Synthetic customer address / {key}",
                "contactName": f"{key.title()} Contact",
                "contactPhone": phone,
                "taxOrIdNumber": f"CL-CUSTOMER-{key.upper()}",
                "remarks": "Closed-loop synthetic customer; created through REST API",
            },
        )

    item_specs = (
        (
            "tire",
            {
                "category": "Closed Loop Configuration",
                "subCategory": "TIRE",
                "itemName": "Closed Loop Tire Configuration",
                "itemCode": f"{NAMESPACE}-TIRE",
                "inputType": "SELECT",
                "unit": "EA",
                "isRequired": True,
                "sortOrder": 10,
            },
            (
                ("tire_standard", "Closed Loop Standard Tire", f"{NAMESPACE}-TIRE-STANDARD", True, 10),
                ("tire_premium", "Closed Loop Premium Tire", f"{NAMESPACE}-TIRE-PREMIUM", False, 20),
            ),
        ),
        (
            "power",
            {
                "category": "Closed Loop Configuration",
                "subCategory": "POWER",
                "itemName": "Closed Loop Power Configuration",
                "itemCode": f"{NAMESPACE}-POWER",
                "inputType": "SELECT",
                "unit": "SET",
                "isRequired": True,
                "sortOrder": 20,
            },
            (
                ("power_standard", "Closed Loop Standard Power", f"{NAMESPACE}-POWER-STANDARD", True, 10),
                ("power_upgrade", "Closed Loop Power Upgrade", f"{NAMESPACE}-POWER-UPGRADE", False, 20),
            ),
        ),
    )
    for item_key, item_payload, values in item_specs:
        item = client.request("POST", "/api/config/items", item_payload)
        if not isinstance(item, dict):
            raise SeedError(f"Configuration item {item_key} was not returned")
        state.config_items[item_key] = item
        for value_key, label, code, is_default, sort_order in values:
            value = client.request(
                "POST",
                "/api/config/values",
                {
                    "configItemId": require_id(item, f"config item {item_key}"),
                    "valueLabel": label,
                    "valueCode": code,
                    "isDefault": is_default,
                    "sortOrder": sort_order,
                    "remark": f"{NAMESPACE} fixture value",
                },
            )
            if not isinstance(value, dict):
                raise SeedError(f"Configuration value {value_key} was not returned")
            state.config_values[value_key] = value

    template = client.request(
        "POST",
        "/api/config/vehicle-items",
        {
            "specificationModel": MACHINE_MODEL,
            "sortOrder": 10,
            "remark": "Template used by all closed-loop synthetic vehicles",
        },
    )
    if not isinstance(template, dict):
        raise SeedError("Vehicle configuration template was not returned")
    for index, (item_key, value_key) in enumerate((("tire", "tire_standard"), ("power", "power_standard")), start=1):
        client.request(
            "POST",
            "/api/config/vehicle-values",
            {
                "vehicleConfigItemId": require_id(template, "vehicle config template"),
                "configItemId": require_id(state.config_items[item_key], f"config item {item_key}"),
                "configValueId": require_id(state.config_values[value_key], f"config value {value_key}"),
                "sortOrder": index * 10,
                "remark": f"{NAMESPACE} {item_key} default for {MACHINE_MODEL}",
            },
        )


def build_import_workbook(client: ApiClient, dates: SeedDates, warehouse: Mapping[str, Any]) -> bytes:
    template = client.download("/api/imports/templates/PART")
    workbook = load_workbook(io.BytesIO(template))
    try:
        worksheet = workbook["Parts"]
        for index, (_, part_code, part_name, quantity, unit_price) in enumerate(IMPORTED_PART_SPECS, start=1):
            worksheet.append([
                (dates.procurement - timedelta(days=10 + index)).isoformat(),
                part_code,
                "OPENING",
                f"Closed loop import document {index:02d}",
                part_name,
                "Imported test accessory",
                "EA",
                quantity,
                unit_price,
                warehouse.get("warehouseCode"),
                "Opening balance created through validated workbook import",
                "Validated opening FIFO balance",
                "CLOSED_LOOP_IMPORT",
            ])
        output = io.BytesIO()
        workbook.save(output)
        return output.getvalue()
    finally:
        workbook.close()


def import_opening_part(client: ApiClient, state: SeedState, dates: SeedDates) -> None:
    content = build_import_workbook(client, dates, state.warehouse)
    validation = client.multipart(
        "POST",
        "/api/imports/PART/validate?mode=OPENING_MIGRATION",
        {},
        file_field="file",
        filename="closed-loop-opening-parts.xlsx",
        content_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        content=content,
    )
    if not isinstance(validation, dict) or not validation.get("importable"):
        raise SeedError(f"PART import validation unexpectedly failed: {validation!r}")
    job = validation.get("job")
    if not isinstance(job, dict):
        raise SeedError(f"PART import validation did not return an import job: {validation!r}")
    confirmed = client.request("POST", f"/api/imports/{require_id(job, 'import job')}/confirm")
    if not isinstance(confirmed, dict):
        raise SeedError(f"PART import confirmation did not return its result: {confirmed!r}")
    confirmed_job = confirmed.get("job")
    if isinstance(confirmed_job, dict):
        state.import_job = confirmed_job
    else:
        state.import_job = job
    for key, part_code, _, quantity, _ in IMPORTED_PART_SPECS:
        imported = client.request("GET", f"/api/parts/code/{part_code}")
        if not isinstance(imported, dict):
            raise SeedError(f"Imported opening part {key} cannot be read back")
        require_equal(imported.get("quantity"), quantity, f"Imported part {key} opening quantity")
        state.imported_parts[key] = imported


def create_service_warehouse(client: ApiClient) -> dict[str, Any]:
    warehouse = client.request(
        "POST",
        "/api/warehouses",
        {
            "warehouseCode": f"{NAMESPACE}-SERVICE-WH",
            "warehouseName": "Closed Loop Service Warehouse",
            "warehouseType": "SERVICE",
            "address": "Synthetic test data / service warehouse",
            "defaultWarehouse": False,
        },
    )
    if not isinstance(warehouse, dict):
        raise SeedError("Service warehouse creation did not return a warehouse")
    return warehouse


def create_part_and_receive(
    client: ApiClient,
    state: SeedState,
    dates: SeedDates,
    *,
    key: str,
    code: str,
    name: str,
    category: str,
    quantity: int,
    unit_price: str,
    sale_price: str,
    freight: str,
    specification: str,
) -> None:
    part = client.request(
        "POST",
        "/api/parts",
        {
            "partCode": code,
            "partBrand": "Closed Loop Test Supply",
            "partName": name,
            "specification": specification,
            "partCategory": category,
            "applicableModels": MACHINE_MODEL,
            "source": "PURCHASE",
            "warehouseId": require_id(state.warehouse, "main warehouse"),
            "quantity": 0,
            "reorderPoint": 2,
            "unit": "EA",
            "purchasePrice": unit_price,
            "landedUnitCost": unit_price,
            "salePrice": sale_price,
            "settlementPrice": sale_price,
            "remarks": f"{NAMESPACE} {key} SKU; inventory comes only from its received purchase order",
            "inboundDate": dates.timestamp(dates.procurement),
        },
    )
    if not isinstance(part, dict):
        raise SeedError(f"Part {key} creation did not return a part")
    purchase = client.request(
        "POST",
        "/api/purchase-orders",
        {
            "supplierId": require_id(state.suppliers["parts"], "parts supplier"),
            "resourceType": "PART",
            "resourceId": require_id(part, f"part {key}"),
            "warehouseId": require_id(state.warehouse, "main warehouse"),
            "quantity": quantity,
            "unit": "EA",
            "unitPrice": unit_price,
            "totalAmount": money(Decimal(unit_price) * quantity),
            "freightAmount": freight,
            "orderDate": dates.procurement.isoformat(),
            "receivedDate": dates.procurement.isoformat(),
            "status": "ORDERED",
            "operator": OPERATOR,
            "remark": f"{NAMESPACE} FIFO purchase receipt for {key}",
        },
    )
    if not isinstance(purchase, dict):
        raise SeedError(f"Purchase order for part {key} was not returned")
    received = client.request(
        "PUT",
        f"/api/purchase-orders/{require_id(purchase, f'part purchase {key}')}/received?"
        + urlencode({"received": "true", "version": require_version(purchase, f"part purchase {key}")}),
    )
    if not isinstance(received, dict):
        raise SeedError(f"Purchase receipt for part {key} was not returned")
    require_equal(received.get("status"), "RECEIVED", f"Part purchase {key} receipt status")
    state.parts[key] = client.request("GET", f"/api/parts/{require_id(part, f'part {key}')}")
    state.purchase_orders[f"part_{key}"] = received
    pay_purchase_order(client, received, f"part-{key}", dates.today)


def create_machine_and_receive(
    client: ApiClient,
    state: SeedState,
    dates: SeedDates,
    *,
    key: str,
    vehicle_number: str,
    purchase_price: str,
    sale_price: str,
) -> None:
    machine_supplier = state.suppliers["machine"]
    inbound = {
        "machineInventory": {
            "vehicleProductNumber": vehicle_number,
            "name": "Closed Loop Electric Forklift",
            "specificationModel": MACHINE_MODEL,
            "machineType": "ELECTRIC_FORKLIFT",
            "configuration": "Synthetic closed-loop machine; all operational history is API-created",
            "supplierId": require_id(machine_supplier, "machine supplier"),
            "warehouseId": require_id(state.warehouse, "main warehouse"),
            "purchasePrice": purchase_price,
            "landedUnitCost": purchase_price,
            "salePrice": sale_price,
            "settlementPrice": sale_price,
            "engineNumber": f"ENG-{vehicle_number}",
            "frameNumber": f"FRAME-{vehicle_number}",
            "warrantyCardNumber": f"WARRANTY-{vehicle_number}",
            "manufacturingDate": (dates.procurement - timedelta(days=50)).isoformat(),
            "inboundDate": dates.timestamp(dates.procurement),
            "inventoryCount": 0,
            "modelOnly": False,
            "stockStatus": "PENDING_INBOUND",
            "remarks": f"{NAMESPACE} machine {key}; created pending receipt",
        },
        "configs": [
            {
                "configItemId": require_id(state.config_items["tire"], "tire config item"),
                "configValueId": require_id(state.config_values["tire_standard"], "standard tire config value"),
                "isStandard": True,
                "configSource": "FACTORY_STANDARD",
            },
            {
                "configItemId": require_id(state.config_items["power"], "power config item"),
                "configValueId": require_id(state.config_values["power_standard"], "standard power config value"),
                "isStandard": True,
                "configSource": "FACTORY_STANDARD",
            },
        ],
    }
    purchase_payload = {
        "supplierId": require_id(machine_supplier, "machine supplier"),
        "resourceType": "MACHINE",
        "warehouseId": require_id(state.warehouse, "main warehouse"),
        "quantity": 1,
        "unit": "EA",
        "unitPrice": purchase_price,
        "totalAmount": purchase_price,
        "freightAmount": "300.00",
        "orderDate": dates.procurement.isoformat(),
        "receivedDate": dates.procurement.isoformat(),
        "status": "ORDERED",
        "operator": OPERATOR,
        "remark": f"{NAMESPACE} serialized machine purchase for {key}",
    }
    created = client.request(
        "POST",
        "/api/workflows/machine-inbound-purchase",
        {"inbound": inbound, "purchaseOrder": purchase_payload},
    )
    if not isinstance(created, dict):
        raise SeedError(f"Machine purchase workflow {key} did not return a result")
    machine = created.get("machine")
    purchase = created.get("purchaseOrder")
    if not isinstance(machine, dict) or not isinstance(purchase, dict):
        raise SeedError(f"Machine purchase workflow {key} returned incomplete result: {created!r}")
    received = client.request(
        "PUT",
        f"/api/purchase-orders/{require_id(purchase, f'machine purchase {key}')}/received?"
        + urlencode({"received": "true", "version": require_version(purchase, f"machine purchase {key}")}),
    )
    if not isinstance(received, dict):
        raise SeedError(f"Machine purchase receipt {key} was not returned")
    require_equal(received.get("status"), "RECEIVED", f"Machine purchase {key} receipt status")
    state.machines[key] = client.request("GET", f"/api/inventory/{require_id(machine, f'machine {key}')}")
    state.purchase_orders[f"machine_{key}"] = received
    pay_purchase_order(client, received, f"machine-{key}", dates.today)


def pay_purchase_order(client: ApiClient, purchase: Mapping[str, Any], request_suffix: str, payment_date: date) -> dict[str, Any]:
    total = decimal_value(purchase.get("totalAmount"), "purchaseOrder.totalAmount")
    freight = decimal_value(purchase.get("freightAmount"), "purchaseOrder.freightAmount")
    payment = client.request(
        "POST",
        "/api/payments",
        {
            "requestId": f"{NAMESPACE}-PAY-{request_suffix}",
            "direction": "PAYMENT",
            "amount": money(total + freight),
            "paymentDate": payment_date.isoformat(),
            "accountName": "Closed Loop Test Payables",
            "paymentMethod": "BANK_TRANSFER",
            "sourceType": "PURCHASE_ORDER",
            "sourceId": require_id(purchase, f"purchase {request_suffix}"),
            "remark": f"Settle received purchase order for {request_suffix}",
        },
    )
    if not isinstance(payment, dict):
        raise SeedError(f"Purchase payment {request_suffix} was not returned")
    return payment


def create_purchased_inventory(client: ApiClient, state: SeedState, dates: SeedDates) -> None:
    for key, code, name, category, quantity, unit_price, sale_price, freight, specification in PART_PURCHASE_SPECS:
        create_part_and_receive(
            client,
            state,
            dates,
            key=key,
            code=code,
            name=name,
            category=category,
            quantity=quantity,
            unit_price=unit_price,
            sale_price=sale_price,
            freight=freight,
            specification=specification,
        )
    for index, key in enumerate(MACHINE_KEYS, start=1):
        create_machine_and_receive(
            client,
            state,
            dates,
            key=key,
            vehicle_number=f"{NAMESPACE}-MACHINE-{key.upper().replace('_', '-')}",
            purchase_price=money(Decimal("11000.00") + Decimal(index * 100)),
            sale_price=money(Decimal("23000.00") + Decimal(index * 100)),
        )


def refresh_part(client: ApiClient, state: SeedState, key: str) -> dict[str, Any]:
    part = client.request("GET", f"/api/parts/{require_id(state.parts[key], f'part {key}')}")
    if not isinstance(part, dict):
        raise SeedError(f"Part {key} could not be refreshed")
    state.parts[key] = part
    return part


def refresh_machine(client: ApiClient, state: SeedState, key: str) -> dict[str, Any]:
    machine = client.request("GET", f"/api/inventory/{require_id(state.machines[key], f'machine {key}')}")
    if not isinstance(machine, dict):
        raise SeedError(f"Machine {key} could not be refreshed")
    state.machines[key] = machine
    return machine


def transfer_for_service_and_stocktake(client: ApiClient, state: SeedState) -> None:
    if state.service_warehouse is None:
        raise SeedError("Service warehouse is missing")
    service_part = refresh_part(client, state, "service")
    client.request(
        "POST",
        "/api/warehouses/transfer",
        {
            "version": require_version(service_part, "service part"),
            "resourceType": "PART",
            "resourceId": require_id(service_part, "service part"),
            "fromWarehouseId": require_id(state.warehouse, "main warehouse"),
            "toWarehouseId": require_id(state.service_warehouse, "service warehouse"),
            "quantity": len(MACHINE_GROUPS["rental_repair"]) * 2,
            "operator": OPERATOR,
            "remark": "Transfer repair material for six returned-rental repairs",
        },
    )
    for key in MACHINE_GROUPS["transfer"]:
        transfer_machine = refresh_machine(client, state, key)
        client.request(
            "POST",
            "/api/warehouses/transfer",
            {
                "version": require_version(transfer_machine, f"transfer machine {key}"),
                "resourceType": "MACHINE",
                "resourceId": require_id(transfer_machine, f"transfer machine {key}"),
                "fromWarehouseId": require_id(state.warehouse, "main warehouse"),
                "toWarehouseId": require_id(state.service_warehouse, "service warehouse"),
                "quantity": 1,
                "operator": OPERATOR,
                "remark": f"Move transferred vehicle fixture {key}",
            },
        )
        state.machines[key] = client.request(
            "GET", f"/api/inventory/{require_id(transfer_machine, f'transfer machine {key}')}"
        )


def complete_pre_sale_modification_one(
    client: ApiClient, state: SeedState, dates: SeedDates, key: str, index: int
) -> None:
    machine = refresh_machine(client, state, key)
    detail = client.request("GET", f"/api/inventory/{require_id(machine, f'modification machine {key}')}/detail")
    if not isinstance(detail, dict):
        raise SeedError(f"Modification machine detail {key} was not returned")
    configs = as_list(detail.get("configs"))
    tire_config = next(
        (config for config in configs if config.get("configItemId") == require_id(state.config_items["tire"], "tire item")),
        None,
    )
    if tire_config is None:
        raise SeedError(f"Modification machine {key} is missing its tire configuration")
    tire_part = refresh_part(client, state, "tire")
    work_order = client.request(
        "POST",
        "/api/modification-work-orders",
        {
            "machineId": require_id(machine, f"modification machine {key}"),
            "machineVersion": require_version(machine, f"modification machine {key}"),
            "customerName": state.customers["sale"].get("companyName"),
            "salesOrderNo": f"{NAMESPACE}-PRE-SALE-{index:02d}",
            "workOrderType": "PRE_SALE",
            "warehouseId": require_id(state.warehouse, "main warehouse"),
            "businessDate": (dates.modification + timedelta(days=index)).isoformat(),
            "operator": OPERATOR,
            # Do not supply optional work-order/line/action remarks here.  The
            # service records its generated work-order reference on the removed
            # part and valuation appends its own audit note; together they must
            # remain inside part_inventory.remarks (VARCHAR(255)).
            "lines": [
                {
                    "machineConfigId": require_id(tire_config, f"machine tire config {key}"),
                    "machineConfigVersion": require_version(tire_config, f"machine tire config {key}"),
                    "newPartId": require_id(tire_part, "replacement tire"),
                    "newPartVersion": require_version(tire_part, "replacement tire"),
                    "quantity": 1,
                    "oldPartAction": "STOCK_IN",
                    "oldPartCondition": "USABLE",
                    "warehouseId": require_id(state.warehouse, "main warehouse"),
                    "chargeUnitPrice": "0.00",
                    "discountAmount": "0.00",
                }
            ],
        },
    )
    if not isinstance(work_order, dict):
        raise SeedError(f"Pre-sale modification work order {key} was not returned")
    completed = client.request(
        "PUT",
        f"/api/modification-work-orders/{require_id(work_order, f'pre-sale work order {key}')}/complete",
        {
            "version": require_version(work_order, f"pre-sale work order {key}"),
            "operator": OPERATOR,
        },
    )
    if not isinstance(completed, dict):
        raise SeedError(f"Completed pre-sale modification {key} was not returned")
    require_equal(completed.get("status"), "COMPLETED", f"Pre-sale modification status {key}")
    state.completed_modifications[key] = completed

    removed_candidates = [
        part
        for part in list_resource(client, "/api/parts?paged=false")
        if part.get("source") == "REMOVED"
        and part.get("sourceMachineId") == require_id(machine, f"modification machine {key}")
        and part.get("isLocked") is True
    ]
    if len(removed_candidates) != 1:
        raise SeedError(f"Expected one locked removed part after modification {key}, found {removed_candidates!r}")
    removed = removed_candidates[0]
    valued = client.request(
        "PUT",
        f"/api/parts/{require_id(removed, f'removed part {key}')}/valuation",
        {
            "version": require_version(removed, f"removed part {key}"),
            "unitCost": "95.00",
            "valuationSource": "SEED",
            "condition": "USABLE",
            "businessDate": (dates.modification + timedelta(days=index)).isoformat(),
            "operator": OPERATOR,
        },
    )
    if not isinstance(valued, dict):
        raise SeedError(f"Removed part valuation {key} was not returned")
    require_equal(valued.get("isLocked"), False, f"Removed part {key} must be unlocked after valuation")
    state.removed_parts[key] = valued
    state.machines[key] = client.request(
        "GET", f"/api/inventory/{require_id(machine, f'modification machine {key}')}"
    )


def complete_pre_sale_modification(client: ApiClient, state: SeedState, dates: SeedDates) -> None:
    for index, key in enumerate(MACHINE_GROUPS["sold"], start=1):
        complete_pre_sale_modification_one(client, state, dates, key, index)


def cancel_discount_modification_one(
    client: ApiClient, state: SeedState, dates: SeedDates, key: str, index: int
) -> None:
    machine = refresh_machine(client, state, key)
    detail = client.request("GET", f"/api/inventory/{require_id(machine, f'canceled modification machine {key}')}/detail")
    if not isinstance(detail, dict):
        raise SeedError(f"Canceled-modification machine detail {key} was not returned")
    configs = as_list(detail.get("configs"))
    power_config = next(
        (config for config in configs if config.get("configItemId") == require_id(state.config_items["power"], "power item")),
        None,
    )
    if power_config is None:
        raise SeedError(f"Canceled-modification machine {key} is missing its power configuration")
    target_value = state.config_values["power_upgrade"]
    work_order = client.request(
        "POST",
        "/api/modification-work-orders",
        {
            "machineId": require_id(machine, f"canceled modification machine {key}"),
            "machineVersion": require_version(machine, f"canceled modification machine {key}"),
            "workOrderType": "PRE_SALE",
            "warehouseId": require_id(state.warehouse, "main warehouse"),
            "businessDate": (dates.sale + timedelta(days=index)).isoformat(),
            "operator": OPERATOR,
            "remark": "Cancellable DISCOUNT branch; no physical part is consumed",
            "lines": [
                {
                    "machineConfigId": require_id(power_config, "machine power config"),
                    "machineConfigVersion": require_version(power_config, "machine power config"),
                    "newConfigValueId": require_id(target_value, "power upgrade config value"),
                    "newConfigValueVersion": require_version(target_value, "power upgrade config value"),
                    "quantity": 1,
                    "oldPartAction": "DISCOUNT",
                    "priceDifference": "0.00",
                    "warehouseId": require_id(state.warehouse, "main warehouse"),
                    "chargeUnitPrice": "0.00",
                    "discountAmount": "0.00",
                    "remark": "Canceled before execution for lifecycle coverage",
                }
            ],
        },
    )
    if not isinstance(work_order, dict):
        raise SeedError(f"Cancellable modification work order {key} was not returned")
    canceled = client.request(
        "PUT",
        f"/api/modification-work-orders/{require_id(work_order, f'cancellable work order {key}')}/cancel",
        {
            "version": require_version(work_order, f"cancellable work order {key}"),
            "operator": OPERATOR,
            "remark": "Close cancellation workflow without consuming inventory",
        },
    )
    if not isinstance(canceled, dict):
        raise SeedError(f"Canceled modification result {key} was not returned")
    require_equal(canceled.get("status"), "CANCELED", f"Canceled modification status {key}")
    state.canceled_modifications[key] = canceled
    state.machines[key] = client.request(
        "GET", f"/api/inventory/{require_id(machine, f'canceled modification machine {key}')}"
    )


def cancel_discount_modification(client: ApiClient, state: SeedState, dates: SeedDates) -> None:
    for index, key in enumerate(MACHINE_GROUPS["mod_cancel"], start=1):
        cancel_discount_modification_one(client, state, dates, key, index)


def create_vehicle_sale_and_pay(
    client: ApiClient, state: SeedState, dates: SeedDates, key: str, index: int
) -> None:
    machine = refresh_machine(client, state, key)
    vehicle_order = client.request(
        "POST",
        "/api/outbound-orders/vehicle",
        {
            "machineId": require_id(machine, f"sold machine {key}"),
            "machineVersion": require_version(machine, f"sold machine {key}"),
            "warehouseId": require_id(state.warehouse, "main warehouse"),
            "customerId": require_id(state.customers["sale"], "sales customer"),
            "settlementPrice": "26000.00",
            "unitSalePrice": "26000.00",
            "lineAmount": "26000.00",
            "salesDate": (dates.sale + timedelta(days=index)).isoformat(),
            "paymentDueDate": dates.today.isoformat(),
            "paymentSettled": False,
            "operator": OPERATOR,
            "orderRemark": f"Vehicle sale after valued modification {key}",
        },
    )
    if not isinstance(vehicle_order, dict):
        raise SeedError(f"Vehicle outbound order {key} was not returned")
    state.outbound_orders[f"vehicle_{key}"] = vehicle_order
    order_total = decimal_value(vehicle_order.get("lineAmount"), f"vehicle order {key} lineAmount")
    partial_amount = Decimal("5000.00")
    partial_payload = {
        "requestId": f"{NAMESPACE}-PAY-VEHICLE-PARTIAL-{index:02d}",
        "direction": "RECEIPT",
        "amount": "5000.00",
        "paymentDate": dates.today.isoformat(),
        "accountName": "Closed Loop Test Receivables",
        "paymentMethod": "BANK_TRANSFER",
        "sourceType": "OUTBOUND_ORDER",
        "sourceId": require_id(vehicle_order, f"vehicle outbound order {key}"),
        "remark": "Idempotent partial-receipt fixture",
    }
    if index == 1:
        partial = client.request("POST", "/api/payments", partial_payload)
        duplicate = client.request("POST", "/api/payments", partial_payload)
        if not isinstance(partial, dict) or not isinstance(duplicate, dict):
            raise SeedError("Partial vehicle payment did not return a payment record")
        require_equal(
            require_id(duplicate, "duplicate payment"),
            require_id(partial, "partial payment"),
            "Duplicate payment idempotency",
        )
    final_amount = order_total - partial_amount if index == 1 else order_total
    if final_amount <= Decimal("0.00"):
        raise SeedError(f"Vehicle order {key} has no positive final settlement amount")
    client.request(
        "POST",
        "/api/payments",
        {
            "requestId": f"{NAMESPACE}-PAY-VEHICLE-FINAL-{index:02d}",
            "direction": "RECEIPT",
            "amount": money(final_amount),
            "paymentDate": dates.today.isoformat(),
            "accountName": "Closed Loop Test Receivables",
            "paymentMethod": "BANK_TRANSFER",
            "sourceType": "OUTBOUND_ORDER",
            "sourceId": require_id(vehicle_order, f"vehicle outbound order {key}"),
            "remark": "Final settlement after partial receipt" if index == 1 else "Final vehicle-sale settlement",
        },
    )

def create_part_sale_and_pay(
    client: ApiClient, state: SeedState, dates: SeedDates, part_key: str, index: int
) -> None:
    sale_part = refresh_part(client, state, part_key)
    unit_sale_price = decimal_value(sale_part.get("salePrice"), f"sale part {part_key} salePrice")
    line_amount = unit_sale_price * 2
    part_order = client.request(
        "POST",
        "/api/outbound-orders/part",
        {
            "partCode": sale_part.get("partCode"),
            "partVersion": require_version(sale_part, f"sale part {part_key}"),
            "warehouseId": require_id(state.warehouse, "main warehouse"),
            "quantity": 2,
            "customerId": require_id(state.customers["sale"], "sales customer"),
            "settlementPrice": money(unit_sale_price),
            "unitSalePrice": money(unit_sale_price),
            "lineAmount": money(line_amount),
            # Keep the fifteenth sale on or before today's settlement;
            # otherwise daily reconciliation would see a receipt before sale.
            "salesDate": (dates.sale + timedelta(days=index - 1)).isoformat(),
            "paymentSettled": False,
            "operator": OPERATOR,
            "orderRemark": f"Traceable part sale {index:02d}",
        },
    )
    if not isinstance(part_order, dict):
        raise SeedError(f"Part outbound order {index:02d} was not returned")
    state.outbound_orders[f"part_{index:02d}"] = part_order
    client.request(
        "POST",
        "/api/payments",
        {
            "requestId": f"{NAMESPACE}-PAY-PART-SALE-{index:02d}",
            "direction": "RECEIPT",
            "amount": money(decimal_value(part_order.get("lineAmount"), f"part order {index:02d} lineAmount")),
            "paymentDate": dates.today.isoformat(),
            "accountName": "Closed Loop Test Receivables",
            "paymentMethod": "CASH",
            "sourceType": "OUTBOUND_ORDER",
            "sourceId": require_id(part_order, f"part outbound order {index:02d}"),
            "remark": "Settle traceable part sale",
        },
    )


def create_sales_and_payments(client: ApiClient, state: SeedState, dates: SeedDates) -> None:
    for index, key in enumerate(MACHINE_GROUPS["sold"], start=1):
        create_vehicle_sale_and_pay(client, state, dates, key, index)
    sale_part_keys = ("sale_a", "sale_b", "sale_c")
    for index in range(1, 16):
        create_part_sale_and_pay(client, state, dates, sale_part_keys[(index - 1) % len(sale_part_keys)], index)


def create_returned_rental_and_pay_one(
    client: ApiClient, state: SeedState, dates: SeedDates, key: str, index: int
) -> None:
    machine = refresh_machine(client, state, key)
    rental = client.request(
        "POST",
        "/api/rentals",
        {
            "machineId": require_id(machine, f"rental machine {key}"),
            "machineVersion": require_version(machine, f"rental machine {key}"),
            "warehouseId": require_id(state.warehouse, "main warehouse"),
            "customerId": require_id(state.customers["rental"], "rental customer"),
            "destination": "Synthetic rental job site",
            "monthlyRentalPrice": money(Decimal("2100.00") + Decimal(index * 50)),
            "startDate": (dates.rental_start + timedelta(days=index)).isoformat(),
            "operator": OPERATOR,
            "remark": f"Historical rental {key}; returned before repair",
        },
    )
    if not isinstance(rental, dict):
        raise SeedError(f"Rental record {key} was not returned")
    current_rental = client.request("GET", f"/api/rentals/{require_id(rental, f'rental {key}')}")
    if not isinstance(current_rental, dict):
        raise SeedError(f"Rental {key} could not be refreshed")
    returned = client.request(
        "PUT",
        f"/api/rentals/{require_id(current_rental, f'rental {key}')}",
        {
            "version": require_version(current_rental, f"rental {key}"),
            "warehouseId": require_id(state.warehouse, "main warehouse"),
            "customerId": require_id(state.customers["rental"], "rental customer"),
            "destination": "Synthetic rental job site",
            "monthlyRentalPrice": money(Decimal("2100.00") + Decimal(index * 50)),
            "startDate": (dates.rental_start + timedelta(days=index)).isoformat(),
            "endDate": (dates.today - timedelta(days=7)).isoformat(),
            "returnDate": (dates.today - timedelta(days=7)).isoformat(),
            "status": "RETURNED",
            "operator": OPERATOR,
            "remark": "Return before repair fixture",
        },
    )
    if not isinstance(returned, dict):
        raise SeedError(f"Returned rental {key} was not returned")
    require_equal(returned.get("status"), "RETURNED", f"Rental return status {key}")
    client.request("POST", f"/api/rentals/{require_id(returned, f'returned rental {key}')}/bills/refresh")
    bills = list_resource(client, f"/api/rentals/{require_id(returned, f'returned rental {key}')}/bills")
    if not bills:
        raise SeedError(f"Returned rental {key} did not produce any bill")
    for bill in bills:
        client.request(
            "POST",
            "/api/payments",
            {
                "requestId": f"{NAMESPACE}-PAY-RENTAL-BILL-{require_id(bill, f'rental bill {key}')}",
                "direction": "RECEIPT",
                "amount": money(decimal_value(bill.get("amount"), f"rental bill {key} amount")),
                "paymentDate": dates.today.isoformat(),
                "accountName": "Closed Loop Test Receivables",
                "paymentMethod": "BANK_TRANSFER",
                "sourceType": "RENTAL_BILL",
                "sourceId": require_id(bill, f"rental bill {key}"),
                "remark": "Settle generated rental bill",
            },
        )
    state.rentals[key] = returned
    state.machines[key] = client.request(
        "GET", f"/api/inventory/{require_id(machine, f'rental machine {key}')}"
    )


def create_returned_rental_and_pay(client: ApiClient, state: SeedState, dates: SeedDates) -> None:
    for index, key in enumerate(MACHINE_GROUPS["rental_repair"], start=1):
        create_returned_rental_and_pay_one(client, state, dates, key, index)


def create_completed_repair_and_pay_one(
    client: ApiClient, state: SeedState, dates: SeedDates, key: str, index: int
) -> None:
    if state.service_warehouse is None:
        raise SeedError("Service warehouse is missing")
    machine = refresh_machine(client, state, key)
    service_part = refresh_part(client, state, "service")
    repair = client.request(
        "POST",
        "/api/repairs",
        {
            # Warehouse transfer has no business-date input and is recorded
            # today, so keep the repair after the returned rental and on the
            # same business date to avoid a chronological negative balance.
            "repairDate": dates.timestamp(dates.today, hour=10 + index),
            "machineId": require_id(machine, f"repair machine {key}"),
            "customerId": require_id(state.customers["repair"], "repair customer"),
            "faultDescription": "Synthetic hydraulic diagnostic and preventative service",
            "repairContent": "Consume one traceable service-kit unit from the service warehouse and close the repair",
            "repairPerson": "External Test Technician",
            "repairExternal": True,
            "partUsages": [
                {
                    "partId": require_id(service_part, "service part"),
                    "warehouseId": require_id(state.service_warehouse, "service warehouse"),
                    "quantity": 1,
                    "chargeUnitPrice": "125.00",
                    "discountAmount": "0.00",
                    "remark": "FIFO-traceable repair material",
                }
            ],
            "repairFee": "300.00",
            "repairExpense": "80.00",
            "passThroughAmount": "0.00",
            "status": "PENDING",
            "remarks": f"Returned-rental repair {key}; settled through payment API",
        },
    )
    if not isinstance(repair, dict):
        raise SeedError(f"Repair record {key} was not returned")
    completed = client.request(
        "PUT",
        f"/api/repairs/{require_id(repair, f'repair {key}')}/status",
        {"version": require_version(repair, f"repair {key}"), "status": "COMPLETED"},
    )
    if not isinstance(completed, dict):
        raise SeedError(f"Completed repair {key} was not returned")
    require_equal(completed.get("status"), "COMPLETED", f"Repair completion status {key}")
    client.request(
        "POST",
        "/api/payments",
        {
            "requestId": f"{NAMESPACE}-PAY-REPAIR-{index:02d}",
            "direction": "RECEIPT",
            "amount": money(decimal_value(completed.get("receivableAmount"), f"repair {key} receivableAmount")),
            "paymentDate": dates.today.isoformat(),
            "accountName": "Closed Loop Test Receivables",
            "paymentMethod": "BANK_TRANSFER",
            "sourceType": "REPAIR",
            "sourceId": require_id(completed, f"completed repair {key}"),
            "remark": "Settle completed repair AR",
        },
    )
    state.repairs[key] = completed


def create_completed_repair_and_pay(client: ApiClient, state: SeedState, dates: SeedDates) -> None:
    for index, key in enumerate(MACHINE_GROUPS["rental_repair"], start=1):
        create_completed_repair_and_pay_one(client, state, dates, key, index)


def upload_and_verify_attachment(client: ApiClient, state: SeedState) -> None:
    if not state.repairs:
        raise SeedError("At least one repair is required before its attachments can be uploaded")
    for key, repair in state.repairs.items():
        uploaded = client.multipart(
            "POST",
            "/api/attachments",
            {
                "resourceType": "REPAIR",
                "resourceId": require_id(repair, f"repair attachment resource {key}"),
                "category": "PHOTO",
                "attachmentLabel": f"Closed Loop Repair Photo {key}",
                "uploadNote": "Synthetic 1x1 PNG; verifies attachment storage and resource linkage",
            },
            file_field="files",
            filename=f"closed-loop-repair-photo-{key}.png",
            content_type="image/png",
            content=ONE_PIXEL_PNG,
        )
        attachments = as_list(uploaded)
        if len(attachments) != 1:
            raise SeedError(f"Attachment upload {key} did not return exactly one attachment: {uploaded!r}")
        verified = list_resource(
            client,
            "/api/attachments/resource?"
            + urlencode({"resourceType": "REPAIR", "resourceId": require_id(repair, f"repair {key}")}),
        )
        attachment_id = require_id(attachments[0], f"uploaded attachment {key}")
        if not any(require_id(row, f"verified attachment {key}") == attachment_id for row in verified):
            raise SeedError(f"Uploaded repair attachment {key} was not visible from its resource endpoint")
        state.attachments[key] = attachments[0]


def create_and_complete_stocktake_one(
    client: ApiClient, state: SeedState, dates: SeedDates, key: str, imported_seed: Mapping[str, Any]
) -> None:
    imported = client.request("GET", f"/api/parts/{require_id(imported_seed, f'imported part {key}')}")
    if not isinstance(imported, dict):
        raise SeedError(f"Imported part {key} could not be refreshed for stocktake")
    actual = imported.get("quantity")
    if not isinstance(actual, int) or actual < 0:
        raise SeedError(f"Imported part {key} has invalid stocktake quantity: {actual!r}")
    draft = client.request(
        "POST",
        "/api/stocktaking-records",
        {
            "resourceType": "PART",
            "resourceId": require_id(imported, f"imported part {key}"),
            "warehouseId": require_id(state.warehouse, "main warehouse"),
            "actualQuantity": actual,
            "stocktakingDate": dates.today.isoformat(),
            "status": "DRAFT",
            "operator": OPERATOR,
            "remark": f"Equal-count imported-part stocktake {key}",
        },
    )
    if not isinstance(draft, dict):
        raise SeedError(f"Stocktaking draft {key} was not returned")
    require_equal(draft.get("bookQuantity"), actual, f"Stocktake {key} book quantity")
    completed = client.request(
        "PUT",
        f"/api/stocktaking-records/{require_id(draft, f'stocktake draft {key}')}/complete?"
        + urlencode({"version": require_version(draft, f"stocktake draft {key}")}),
    )
    if not isinstance(completed, dict):
        raise SeedError(f"Stocktaking completion {key} was not returned")
    require_equal(completed.get("status"), "COMPLETED", f"Stocktaking completion status {key}")
    state.stocktakes[key] = completed


def create_and_complete_stocktake(client: ApiClient, state: SeedState, dates: SeedDates) -> None:
    if not state.imported_parts:
        raise SeedError("Imported parts are required before stocktake")
    for key, imported_seed in state.imported_parts.items():
        create_and_complete_stocktake_one(client, state, dates, key, imported_seed)


def validate_quality(client: ApiClient) -> dict[str, Any]:
    exceptions = list_resource(client, "/api/data-quality/migration-exceptions?status=OPEN")
    if exceptions:
        raise SeedError(f"Closed-loop seed generated open migration exceptions: {exceptions!r}")
    reconciliation = client.request("GET", "/api/statistics/reconciliation/daily")
    if not isinstance(reconciliation, dict):
        raise SeedError(f"Daily reconciliation did not return an object: {reconciliation!r}")
    summary = reconciliation.get("summary")
    if not isinstance(summary, dict):
        raise SeedError(f"Daily reconciliation summary is missing: {reconciliation!r}")
    require_equal(summary.get("errorCount"), 0, "Daily reconciliation errors")
    return reconciliation


def build_report(client: ApiClient, state: SeedState, reconciliation: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "namespace": NAMESPACE,
        "baseUrl": client.base_url,
        "safety": {
            "databaseWrites": "REST API only",
            "businessResetCalled": False,
            "precondition": "empty business data; one warehouse before import",
        },
        "ids": {
            "warehouse": require_id(state.warehouse, "main warehouse"),
            "serviceWarehouse": require_id(state.service_warehouse, "service warehouse") if state.service_warehouse else None,
            "machines": {key: require_id(value, f"machine {key}") for key, value in state.machines.items()},
            "parts": {key: require_id(value, f"part {key}") for key, value in state.parts.items()},
            "importedParts": {key: require_id(value, f"imported part {key}") for key, value in state.imported_parts.items()},
            "removedParts": {key: require_id(value, f"removed part {key}") for key, value in state.removed_parts.items()},
            "rentals": {key: require_id(value, f"rental {key}") for key, value in state.rentals.items()},
            "repairs": {key: require_id(value, f"repair {key}") for key, value in state.repairs.items()},
            "stocktakes": {key: require_id(value, f"stocktake {key}") for key, value in state.stocktakes.items()},
            "attachments": {key: require_id(value, f"attachment {key}") for key, value in state.attachments.items()},
            "importJob": require_id(state.import_job, "import job") if state.import_job else None,
        },
        "fixtureMatrix": {
            "machineInventory": len(MACHINE_KEYS),
            "vehicleSales": len(MACHINE_GROUPS["sold"]),
            "partSales": 15,
            "returnedRentals": len(state.rentals),
            "completedRepairs": len(state.repairs),
            "completedModifications": len(state.completed_modifications),
            "canceledModifications": len(state.canceled_modifications),
            "transferredVehicles": len(MACHINE_GROUPS["transfer"]),
            "availableVehicles": len(MACHINE_GROUPS["available"]),
            "purchasedPartSkus": len(state.parts),
            "importedPartSkus": len(state.imported_parts),
            "valuedRemovedParts": len(state.removed_parts),
            "purchaseOrders": len(state.purchase_orders),
            "outboundOrders": len(state.outbound_orders),
            "attachments": len(state.attachments),
            "stocktakes": len(state.stocktakes),
            "importRows": len(IMPORTED_PART_SPECS),
        },
        "quality": {
            "openMigrationExceptions": 0,
            "dailyReconciliation": reconciliation.get("summary"),
        },
        "tableCoverage": TABLE_COVERAGE,
    }


def run_seed(args: argparse.Namespace) -> dict[str, Any]:
    dates = SeedDates(today=date.today())
    client = ApiClient(args.base_url, args.username, args.password, args.timeout_seconds)
    warehouses = assert_empty_business_database(client)
    primary = ensure_primary_warehouse(client, warehouses)
    state = SeedState(warehouse=primary)
    create_masters(client, state)

    # Keep exactly one warehouse until the importer confirms the opening lot:
    # StockLedgerService.resolveWarehouseId(null) intentionally rejects imports
    # when there is more than one warehouse.
    import_opening_part(client, state, dates)
    state.service_warehouse = create_service_warehouse(client)
    create_purchased_inventory(client, state, dates)
    transfer_for_service_and_stocktake(client, state)
    complete_pre_sale_modification(client, state, dates)
    cancel_discount_modification(client, state, dates)
    create_sales_and_payments(client, state, dates)
    create_returned_rental_and_pay(client, state, dates)
    create_completed_repair_and_pay(client, state, dates)
    upload_and_verify_attachment(client, state)
    create_and_complete_stocktake(client, state, dates)
    reconciliation = validate_quality(client)
    return build_report(client, state, reconciliation)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Seed a complete REST-only closed-loop fixture into an empty forklift ERP database."
    )
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin123")
    parser.add_argument("--timeout-seconds", type=int, default=30)
    return parser.parse_args()


def main() -> None:
    try:
        report = run_seed(parse_args())
    except SeedError as exc:
        print(f"Closed-loop seed failed safely: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
