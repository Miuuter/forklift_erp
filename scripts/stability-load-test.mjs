#!/usr/bin/env node

import { randomUUID } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const READ_ENDPOINTS = [
    { name: "home", path: "/", api: false },
    { name: "app-js", path: "/assets/app.js", api: false },
    { name: "inventory", path: "/api/inventory?page=0&size=20" },
    { name: "parts", path: "/api/parts?page=0&size=20" },
    { name: "customers", path: "/api/customers?page=0&size=20" },
    { name: "repairs", path: "/api/repairs?page=0&size=20" },
    { name: "outbound-orders", path: "/api/outbound-orders?page=0&size=20" },
    { name: "rentals", path: "/api/rentals?page=0&size=20" },
    { name: "purchase-orders", path: "/api/purchase-orders?page=0&size=20" },
    { name: "stocktaking", path: "/api/stocktaking-records?page=0&size=20" },
    { name: "stock-movements", path: "/api/stock-movements?page=0&size=20" },
    { name: "warehouses", path: "/api/warehouses?page=0&size=20" },
    { name: "suppliers", path: "/api/suppliers?page=0&size=20" },
    { name: "modifications", path: "/api/modification-work-orders?page=0&size=20" },
    { name: "imports", path: "/api/imports?page=0&size=20" },
    { name: "attachments", path: "/api/attachments?page=0&size=20" },
    { name: "users", path: "/api/auth/users?paged=true&page=0&size=20" },
    { name: "todos", path: "/api/todos" },
    { name: "finance", path: `/api/statistics/finance?year=${new Date().getFullYear()}` },
    { name: "daily-reconciliation", path: "/api/statistics/reconciliation/daily" }
];

function parseInteger(value, fallback, name) {
    if (value == null) {
        return fallback;
    }
    const parsed = Number.parseInt(value, 10);
    if (!Number.isInteger(parsed) || parsed < 1) {
        throw new Error(`${name} must be a positive integer`);
    }
    return parsed;
}

function parseNumber(value, fallback, name) {
    if (value == null) {
        return fallback;
    }
    const parsed = Number(value);
    if (!Number.isFinite(parsed) || parsed < 0) {
        throw new Error(`${name} must be a non-negative number`);
    }
    return parsed;
}

function parseArguments(argv) {
    const values = new Map();
    for (let index = 0; index < argv.length; index += 1) {
        const argument = argv[index];
        if (!argument.startsWith("--")) {
            throw new Error(`Unexpected argument: ${argument}`);
        }
        const key = argument.slice(2);
        if (key === "allow-writes") {
            values.set(key, "true");
            continue;
        }
        const value = argv[index + 1];
        if (value == null || value.startsWith("--")) {
            throw new Error(`Missing value for --${key}`);
        }
        values.set(key, value);
        index += 1;
    }
    const profile = values.get("profile") ?? "read";
    if (![
        "read",
        "login",
        "customer-crud",
        "payment-idempotency",
        "inventory-flow",
        "attachment-io",
        "import-confirm",
        "rental-billing",
        "batch-transactions"
    ].includes(profile)) {
        throw new Error(`Unsupported profile: ${profile}`);
    }
    return {
        baseUrl: (values.get("base-url") ?? process.env.STABILITY_BASE_URL ?? "http://127.0.0.1:8080").replace(/\/+$/, ""),
        username: values.get("username") ?? process.env.STABILITY_USERNAME ?? "admin",
        password: values.get("password") ?? process.env.STABILITY_PASSWORD,
        profile,
        concurrency: parseInteger(values.get("concurrency"), 20, "concurrency"),
        durationSeconds: parseInteger(values.get("duration-seconds"), 60, "duration-seconds"),
        timeoutMs: parseInteger(values.get("timeout-ms"), 10_000, "timeout-ms"),
        maxErrorRate: parseNumber(values.get("max-error-rate"), 0.001, "max-error-rate"),
        maxP95Ms: parseNumber(values.get("max-p95-ms"), 1_500, "max-p95-ms"),
        output: values.get("output"),
        allowWrites: values.get("allow-writes") === "true",
        sourceId: values.has("source-id") ? parseInteger(values.get("source-id"), null, "source-id") : null,
        testPrefix: values.get("test-prefix") ?? `STABILITY-${Date.now()}`
    };
}

export function percentile(values, fraction) {
    if (values.length === 0) {
        return 0;
    }
    const sorted = [...values].sort((left, right) => left - right);
    const index = Math.min(sorted.length - 1, Math.max(0, Math.ceil(sorted.length * fraction) - 1));
    return sorted[index];
}

function summarizeGroup(records) {
    const latencies = records.map((record) => record.durationMs);
    const failures = records.filter((record) => !record.ok);
    let minimumLatency = 0;
    let maximumLatency = 0;
    if (latencies.length > 0) {
        minimumLatency = latencies[0];
        maximumLatency = latencies[0];
        for (let index = 1; index < latencies.length; index += 1) {
            minimumLatency = Math.min(minimumLatency, latencies[index]);
            maximumLatency = Math.max(maximumLatency, latencies[index]);
        }
    }
    return {
        requests: records.length,
        successful: records.length - failures.length,
        failed: failures.length,
        errorRate: records.length === 0 ? 0 : failures.length / records.length,
        latencyMs: {
            min: minimumLatency,
            p50: percentile(latencies, 0.50),
            p95: percentile(latencies, 0.95),
            p99: percentile(latencies, 0.99),
            max: maximumLatency
        },
        errors: Object.fromEntries(
            [...new Set(failures.map((record) => record.error ?? `HTTP ${record.status}`))]
                .map((error) => [error, failures.filter((record) => (record.error ?? `HTTP ${record.status}`) === error).length])
        )
    };
}

export function summarizeRecords(records, durationMs) {
    const endpoints = {};
    for (const endpoint of [...new Set(records.map((record) => record.name))].sort()) {
        endpoints[endpoint] = summarizeGroup(records.filter((record) => record.name === endpoint));
    }
    const total = summarizeGroup(records);
    return {
        ...total,
        durationMs,
        requestsPerSecond: durationMs <= 0 ? 0 : records.length / (durationMs / 1000),
        endpoints
    };
}

async function timedRequest(options, name, path, requestOptions = {}) {
    const started = performance.now();
    try {
        const headers = new Headers(requestOptions.headers ?? {});
        if (options.token) {
            headers.set("Authorization", `Bearer ${options.token}`);
        }
        const formBody = typeof FormData !== "undefined" && requestOptions.body instanceof FormData;
        const binaryBody = requestOptions.body instanceof ArrayBuffer
            || ArrayBuffer.isView(requestOptions.body)
            || (typeof Blob !== "undefined" && requestOptions.body instanceof Blob);
        if (requestOptions.body != null && !formBody && !binaryBody && !headers.has("Content-Type")) {
            headers.set("Content-Type", "application/json");
        }
        const response = await fetch(`${options.baseUrl}${path}`, {
            ...requestOptions,
            headers,
            body: requestOptions.body == null
                || typeof requestOptions.body === "string"
                || formBody
                || binaryBody
                ? requestOptions.body
                : JSON.stringify(requestOptions.body),
            signal: AbortSignal.timeout(options.timeoutMs)
        });
        const text = await response.text();
        let body = null;
        if (text && (response.headers.get("content-type") ?? "").includes("application/json")) {
            body = JSON.parse(text);
        }
        const expectedStatuses = requestOptions.expectedStatuses ?? [200];
        const apiSuccess = requestOptions.api === false || body?.code === 200;
        const ok = expectedStatuses.includes(response.status) && apiSuccess;
        return {
            name,
            path,
            ok,
            status: response.status,
            durationMs: performance.now() - started,
            error: ok ? null : body?.message ?? `Unexpected response status ${response.status}`,
            body
        };
    } catch (error) {
        return {
            name,
            path,
            ok: false,
            status: 0,
            durationMs: performance.now() - started,
            error: error instanceof Error ? error.message : String(error),
            body: null
        };
    }
}

async function timedBinaryRequest(options, name, path, requestOptions = {}) {
    const started = performance.now();
    try {
        const headers = new Headers(requestOptions.headers ?? {});
        if (options.token) {
            headers.set("Authorization", `Bearer ${options.token}`);
        }
        const response = await fetch(`${options.baseUrl}${path}`, {
            ...requestOptions,
            headers,
            signal: AbortSignal.timeout(options.timeoutMs)
        });
        const bytes = new Uint8Array(await response.arrayBuffer());
        const expectedStatuses = requestOptions.expectedStatuses ?? [200];
        const ok = expectedStatuses.includes(response.status);
        return {
            record: {
                name,
                path,
                ok,
                status: response.status,
                durationMs: performance.now() - started,
                error: ok ? null : `Unexpected response status ${response.status}`,
                body: null
            },
            bytes
        };
    } catch (error) {
        return {
            record: {
                name,
                path,
                ok: false,
                status: 0,
                durationMs: performance.now() - started,
                error: error instanceof Error ? error.message : String(error),
                body: null
            },
            bytes: new Uint8Array()
        };
    }
}

function isoDate(date) {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, "0");
    const day = String(date.getDate()).padStart(2, "0");
    return `${year}-${month}-${day}`;
}

function monthStartMonthsAgo(months) {
    const date = new Date();
    date.setHours(12, 0, 0, 0);
    date.setDate(1);
    date.setMonth(date.getMonth() - months);
    return isoDate(date);
}

async function login(options) {
    const result = await timedRequest(options, "login", "/api/auth/login", {
        method: "POST",
        body: { username: options.username, password: options.password }
    });
    if (!result.ok || !result.body?.data?.token) {
        throw new Error(`Login failed: ${result.error ?? "token missing"}`);
    }
    return result.body.data.token;
}

async function runContinuous(options, operation) {
    const records = [];
    const started = performance.now();
    const deadline = started + options.durationSeconds * 1000;
    const workers = Array.from({ length: options.concurrency }, (_, workerIndex) => (async () => {
        let iteration = 0;
        while (performance.now() < deadline) {
            const operationRecords = await operation(workerIndex, iteration);
            records.push(...(Array.isArray(operationRecords) ? operationRecords : [operationRecords]));
            iteration += 1;
        }
    })());
    await Promise.all(workers);
    return { records, durationMs: performance.now() - started };
}

async function findPagedItem(options, path, predicate, maxPages = 20) {
    const records = [];
    for (let page = 0; page < maxPages; page += 1) {
        const separator = path.includes("?") ? "&" : "?";
        const result = await timedRequest(options, "setup-list", `${path}${separator}page=${page}&size=100`);
        records.push(result);
        if (!result.ok) {
            break;
        }
        const content = result.body?.data?.content ?? [];
        const item = content.find(predicate);
        if (item) {
            return { item, records };
        }
        if (content.length === 0 || page + 1 >= (result.body?.data?.totalPages ?? page + 1)) {
            break;
        }
    }
    return { item: null, records };
}

async function runReadProfile(options) {
    const token = await login(options);
    const authenticated = { ...options, token };
    return runContinuous(authenticated, (workerIndex, iteration) => {
        const endpoint = READ_ENDPOINTS[(workerIndex + iteration) % READ_ENDPOINTS.length];
        return timedRequest(authenticated, endpoint.name, endpoint.path, { api: endpoint.api });
    });
}

async function runLoginProfile(options) {
    return runContinuous(options, () => timedRequest(options, "login", "/api/auth/login", {
        method: "POST",
        body: { username: options.username, password: options.password }
    }));
}

function requireWritePermission(options) {
    if (!options.allowWrites) {
        throw new Error(`Profile ${options.profile} requires --allow-writes and must only target an isolated environment`);
    }
}

async function runCustomerCrudProfile(options) {
    requireWritePermission(options);
    const token = await login(options);
    const authenticated = { ...options, token };
    return runContinuous(authenticated, async (workerIndex, iteration) => {
        const unique = `${options.testPrefix}-${workerIndex}-${iteration}-${randomUUID().slice(0, 8)}`;
        const create = await timedRequest(authenticated, "customer-create", "/api/customers", {
            method: "POST",
            expectedStatuses: [201],
            body: {
                companyName: unique,
                contactName: "Stability test",
                remarks: "Automatically removed by the stability load test"
            }
        });
        if (!create.ok || create.body?.data?.id == null) {
            return create;
        }
        const customer = create.body.data;
        const remove = await timedRequest(
            authenticated,
            "customer-delete",
            `/api/customers/${customer.id}?version=${customer.version}`,
            { method: "DELETE" }
        );
        return [create, remove];
    });
}

async function findPaymentSourceId(options) {
    if (options.sourceId != null) {
        return options.sourceId;
    }
    const token = await login(options);
    const authenticated = { ...options, token };
    const result = await timedRequest(authenticated, "outbound-source", "/api/outbound-orders?page=0&size=1");
    const sourceId = result.body?.data?.content?.[0]?.id;
    if (!result.ok || sourceId == null) {
        throw new Error("No outbound order is available for payment idempotency testing");
    }
    return sourceId;
}

async function runPaymentIdempotencyProfile(options) {
    requireWritePermission(options);
    const token = await login(options);
    const authenticated = { ...options, token };
    const sourceId = await findPaymentSourceId(authenticated);
    const paymentRequestId = `${options.testPrefix}-PAY-${randomUUID()}`;
    const paymentBody = {
        requestId: paymentRequestId,
        direction: "RECEIPT",
        amount: "0.01",
        sourceType: "OUTBOUND_ORDER",
        sourceId,
        remark: "Concurrent payment idempotency stability test"
    };
    const started = performance.now();
    const paymentRecords = await Promise.all(Array.from(
        { length: options.concurrency },
        () => timedRequest(authenticated, "payment-create", "/api/payments", {
            method: "POST",
            expectedStatuses: [201],
            body: paymentBody
        })
    ));
    const paymentIds = new Set(paymentRecords.filter((record) => record.ok).map((record) => record.body?.data?.id));
    if (paymentRecords.some((record) => !record.ok) || paymentIds.size !== 1 || paymentIds.has(undefined)) {
        return { records: paymentRecords, durationMs: performance.now() - started, invariantError: "Concurrent payment requests did not converge to one record" };
    }
    const paymentId = [...paymentIds][0];
    const reversalRequestId = `${options.testPrefix}-REV-${randomUUID()}`;
    const reversalRecords = await Promise.all(Array.from(
        { length: options.concurrency },
        () => timedRequest(authenticated, "payment-reverse", `/api/payments/${paymentId}/reverse`, {
            method: "POST",
            body: { requestId: reversalRequestId, remark: "Concurrent reversal idempotency stability test" }
        })
    ));
    const reversalIds = new Set(reversalRecords.filter((record) => record.ok).map((record) => record.body?.data?.id));
    const invariantError = reversalRecords.some((record) => !record.ok)
        || reversalIds.size !== 1
        || reversalIds.has(undefined)
        ? "Concurrent reversal requests did not converge to one record"
        : null;
    return {
        records: [...paymentRecords, ...reversalRecords],
        durationMs: performance.now() - started,
        invariantError
    };
}

async function runInventoryFlowProfile(options) {
    requireWritePermission(options);
    const token = await login(options);
    const authenticated = { ...options, token };
    const warehousesResult = await timedRequest(
        authenticated,
        "warehouse-list",
        "/api/warehouses?paged=false"
    );
    if (!warehousesResult.ok) {
        throw new Error(`Cannot load warehouses: ${warehousesResult.error}`);
    }
    const sourceWarehouse = (warehousesResult.body?.data ?? []).find((warehouse) => warehouse.defaultWarehouse)
        ?? warehousesResult.body?.data?.[0];
    if (!sourceWarehouse?.id) {
        throw new Error("No source warehouse is available for inventory flow testing");
    }

    let targetWarehouse = (warehousesResult.body?.data ?? [])
        .find((warehouse) => warehouse.id !== sourceWarehouse.id);
    if (!targetWarehouse) {
        const warehouseCode = `${options.testPrefix}-WH-${randomUUID().slice(0, 8)}`;
        const targetWarehouseResult = await timedRequest(authenticated, "warehouse-create", "/api/warehouses", {
            method: "POST",
            expectedStatuses: [201],
            body: {
                warehouseCode,
                warehouseName: `Stability transfer ${warehouseCode}`,
                warehouseType: "TEST",
                defaultWarehouse: false
            }
        });
        if (!targetWarehouseResult.ok || targetWarehouseResult.body?.data?.id == null) {
            throw new Error(`Cannot create target warehouse: ${targetWarehouseResult.error ?? "ID missing"}`);
        }
        targetWarehouse = targetWarehouseResult.body.data;
    }

    const parts = [];
    for (let workerIndex = 0; workerIndex < options.concurrency; workerIndex += 1) {
        const partCode = `${options.testPrefix}-PART-${workerIndex}-${randomUUID().slice(0, 8)}`;
        const create = await timedRequest(authenticated, "part-create", "/api/parts", {
            method: "POST",
            expectedStatuses: [201],
            body: {
                partCode,
                partName: "Stability inventory flow part",
                partCategory: "STABILITY",
                quantity: 2,
                reorderPoint: 1,
                warehouseId: sourceWarehouse.id,
                unit: "pcs",
                purchasePrice: "1.00",
                remarks: "Automatically removed by the stability load test"
            }
        });
        if (!create.ok || create.body?.data?.id == null) {
            throw new Error(`Cannot create inventory-flow part ${workerIndex}: ${create.error ?? "ID missing"}`);
        }
        parts.push({
            id: create.body.data.id,
            partCode,
            version: create.body.data.version,
            currentWarehouseId: sourceWarehouse.id
        });
    }

    const execution = await runContinuous(authenticated, async (workerIndex, iteration) => {
        const part = parts[workerIndex];
        const records = [];
        const inbound = await timedRequest(authenticated, "part-inbound", "/api/parts/inbound", {
            method: "PUT",
            body: {
                partCode: part.partCode,
                quantity: 1,
                warehouseId: part.currentWarehouseId,
                version: part.version,
                operator: "stability-load-test",
                reason: "STABILITY",
                remark: "Concurrent inventory-flow inbound"
            }
        });
        records.push(inbound);
        if (!inbound.ok) {
            return records;
        }
        part.version = inbound.body?.data?.version;

        const outbound = await timedRequest(authenticated, "part-outbound", "/api/parts/outbound", {
            method: "PUT",
            body: {
                partCode: part.partCode,
                quantity: 1,
                warehouseId: part.currentWarehouseId,
                version: part.version,
                operator: "stability-load-test",
                reason: "STABILITY",
                remark: "Concurrent inventory-flow outbound"
            }
        });
        records.push(outbound);
        if (!outbound.ok) {
            return records;
        }
        part.version = outbound.body?.data?.version;

        if (iteration % 4 !== 0) {
            return records;
        }

        const nextWarehouseId = part.currentWarehouseId === sourceWarehouse.id
            ? targetWarehouse.id
            : sourceWarehouse.id;
        const transfer = await timedRequest(authenticated, "part-transfer", "/api/warehouses/transfer", {
            method: "POST",
            body: {
                resourceType: "PART",
                resourceId: part.id,
                fromWarehouseId: part.currentWarehouseId,
                toWarehouseId: nextWarehouseId,
                quantity: 2,
                version: part.version,
                operator: "stability-load-test",
                remark: "Concurrent inventory-flow transfer"
            }
        });
        records.push(transfer);
        if (!transfer.ok) {
            return records;
        }
        part.currentWarehouseId = nextWarehouseId;

        const refreshed = await timedRequest(authenticated, "part-refresh", `/api/parts/${part.id}`);
        records.push(refreshed);
        if (refreshed.ok) {
            part.version = refreshed.body?.data?.version;
        }
        return records;
    });

    const cleanupErrors = [];
    for (const part of parts) {
        const refreshed = await timedRequest(authenticated, "part-cleanup-read", `/api/parts/${part.id}`);
        if (!refreshed.ok) {
            cleanupErrors.push(refreshed.error ?? `Cannot load part ${part.id}`);
            continue;
        }
        part.version = refreshed.body?.data?.version;
        const balances = refreshed.body?.data?.warehouseBalances ?? [];
        for (const balance of balances.filter((item) => Number(item.availableQuantity) > 0)) {
            const outbound = await timedRequest(authenticated, "part-cleanup-outbound", "/api/parts/outbound", {
                method: "PUT",
                body: {
                    partCode: part.partCode,
                    quantity: Number(balance.availableQuantity),
                    warehouseId: balance.warehouseId,
                    version: part.version,
                    operator: "stability-load-test",
                    reason: "STABILITY_CLEANUP"
                }
            });
            if (!outbound.ok) {
                cleanupErrors.push(outbound.error ?? `Cannot empty part ${part.id}`);
                break;
            }
            part.version = outbound.body?.data?.version;
        }
        const remove = await timedRequest(
            authenticated,
            "part-cleanup-delete",
            `/api/parts/${part.id}?version=${encodeURIComponent(part.version)}`,
            { method: "DELETE" }
        );
        if (!remove.ok) {
            cleanupErrors.push(remove.error ?? `Cannot delete part ${part.id}`);
        }
    }
    return {
        ...execution,
        invariantError: cleanupErrors.length > 0
            ? `Inventory flow cleanup failed: ${cleanupErrors.join("; ")}`
            : null
    };
}

async function runAttachmentIoProfile(options) {
    requireWritePermission(options);
    const token = await login(options);
    const authenticated = { ...options, token };
    const candidate = await findPagedItem(
        authenticated,
        "/api/inventory",
        (machine) => !machine.modelOnly && !machine.isLocked
    );
    if (!candidate.item?.id) {
        throw new Error("No unlocked vehicle is available for attachment testing");
    }

    const pdf = new TextEncoder().encode(
        "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n"
    );
    const form = new FormData();
    form.append("files", new Blob([pdf], { type: "application/pdf" }), `${options.testPrefix}.pdf`);
    const upload = await timedRequest(
        authenticated,
        "attachment-upload",
        `/api/attachments?resourceType=MACHINE&resourceId=${candidate.item.id}&category=MANUAL`
            + `&attachmentLabel=${encodeURIComponent("Stability PDF")}`,
        { method: "POST", body: form }
    );
    const attachmentId = upload.body?.data?.[0]?.id;
    if (!upload.ok || attachmentId == null) {
        throw new Error(`Attachment upload failed: ${upload.error ?? "ID missing"}`);
    }

    const execution = await runContinuous(authenticated, (workerIndex, iteration) => {
        const preview = (workerIndex + iteration) % 2 === 0;
        return timedRequest(
            authenticated,
            preview ? "attachment-preview" : "attachment-download",
            `/api/attachments/${attachmentId}/${preview ? "preview" : "download"}`,
            { api: false }
        );
    });
    const remove = await timedRequest(
        authenticated,
        "attachment-cleanup-delete",
        `/api/attachments/${attachmentId}?reason=${encodeURIComponent("Stability test cleanup")}`,
        { method: "DELETE" }
    );
    return {
        ...execution,
        invariantError: remove.ok ? null : `Attachment cleanup failed: ${remove.error}`
    };
}

async function runImportConfirmProfile(options) {
    requireWritePermission(options);
    const token = await login(options);
    const authenticated = { ...options, token };
    const started = performance.now();
    const template = await timedBinaryRequest(
        authenticated,
        "import-template",
        "/api/imports/templates/parts-purchase"
    );
    if (!template.record.ok || template.bytes.length === 0) {
        throw new Error(`Import template download failed: ${template.record.error ?? "empty file"}`);
    }

    const form = new FormData();
    form.append(
        "file",
        new Blob([template.bytes], {
            type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        }),
        `${options.testPrefix}-parts.xlsx`
    );
    const validation = await timedRequest(
        authenticated,
        "import-validate",
        "/api/imports/parts-purchase/validate?mode=MASTER_DATA",
        { method: "POST", body: form }
    );
    const jobId = validation.body?.data?.job?.id;
    if (!validation.ok || !validation.body?.data?.importable || jobId == null) {
        throw new Error(`Blank import template did not validate: ${validation.error ?? "job is not importable"}`);
    }

    const confirmations = await Promise.all(Array.from(
        { length: options.concurrency },
        () => timedRequest(authenticated, "import-confirm", `/api/imports/${jobId}/confirm`, {
            method: "POST",
            expectedStatuses: [200, 400, 409, 422],
            api: false
        })
    ));
    const winners = confirmations.filter((record) => record.status === 200 && record.body?.code === 200);
    const finalJob = await timedRequest(authenticated, "import-job", `/api/imports/${jobId}`);
    const invariantError = winners.length !== 1
        ? `Expected exactly one successful import confirmation, observed ${winners.length}`
        : finalJob.body?.data?.status !== "COMPLETED"
            ? `Import job ended in ${finalJob.body?.data?.status ?? "unknown"} instead of COMPLETED`
            : null;
    return {
        records: [template.record, validation, ...confirmations, finalJob],
        durationMs: performance.now() - started,
        invariantError
    };
}

async function runRentalBillingProfile(options) {
    requireWritePermission(options);
    const token = await login(options);
    const authenticated = { ...options, token };
    const machineCandidate = await findPagedItem(
        authenticated,
        "/api/inventory",
        (machine) => machine.stockStatus === "IN_STOCK"
            && Number(machine.inventoryCount) > 0
            && machine.warehouseId != null
            && !machine.modelOnly
            && !machine.isLocked
    );
    const customerCandidate = await findPagedItem(
        authenticated,
        "/api/customers",
        (customer) => customer.id != null
    );
    if (!machineCandidate.item?.id || !customerCandidate.item?.id) {
        throw new Error("Rental billing test requires an in-stock vehicle and a customer");
    }

    const started = performance.now();
    const create = await timedRequest(authenticated, "rental-create", "/api/rentals", {
        method: "POST",
        expectedStatuses: [201],
        body: {
            machineId: machineCandidate.item.id,
            machineVersion: machineCandidate.item.version,
            warehouseId: machineCandidate.item.warehouseId,
            customerId: customerCandidate.item.id,
            destination: "Stability rental destination",
            monthlyRentalPrice: "3000.00",
            startDate: monthStartMonthsAgo(2),
            operator: "stability-load-test",
            remark: "Rental billing idempotency test"
        }
    });
    const rental = create.body?.data;
    if (!create.ok || rental?.id == null) {
        throw new Error(`Rental setup failed: ${create.error ?? "ID missing"}`);
    }

    const refreshes = await Promise.all(Array.from(
        { length: options.concurrency },
        () => timedRequest(authenticated, "rental-bill-refresh", `/api/rentals/${rental.id}/bills/refresh`, {
            method: "POST"
        })
    ));
    const bills = await timedRequest(authenticated, "rental-bills", `/api/rentals/${rental.id}/bills`);
    const periods = (bills.body?.data ?? []).map((bill) => bill.billPeriod);
    const uniquePeriods = new Set(periods);

    const current = await timedRequest(authenticated, "rental-read", `/api/rentals/${rental.id}`);
    const currentRental = current.body?.data ?? rental;
    const returnDate = isoDate(new Date());
    const returned = await timedRequest(authenticated, "rental-return", `/api/rentals/${rental.id}`, {
        method: "PUT",
        body: {
            version: currentRental.version,
            warehouseId: currentRental.warehouseId,
            customerId: currentRental.customerId,
            destination: currentRental.destination,
            monthlyRentalPrice: currentRental.monthlyRentalPrice,
            rentalPrice: currentRental.rentalPrice,
            startDate: currentRental.startDate,
            endDate: returnDate,
            returnDate,
            status: "RETURNED",
            operator: "stability-load-test",
            remark: "Rental billing idempotency test completed"
        }
    });
    const finalBills = await timedRequest(authenticated, "rental-final-bills", `/api/rentals/${rental.id}/bills`);
    const finalPeriods = (finalBills.body?.data ?? []).map((bill) => bill.billPeriod);
    const finalUniquePeriods = new Set(finalPeriods);
    let invariantError = null;
    if (refreshes.some((record) => !record.ok)) {
        invariantError = "At least one concurrent rental bill refresh failed";
    } else if (!bills.ok || uniquePeriods.size !== periods.length) {
        invariantError = "Concurrent rental refresh created duplicate bill periods";
    } else if (!returned.ok || returned.body?.data?.status !== "RETURNED") {
        invariantError = `Rental return failed: ${returned.error ?? "status did not become RETURNED"}`;
    } else if (!finalBills.ok || finalUniquePeriods.size !== finalPeriods.length) {
        invariantError = "Final rental billing created duplicate bill periods";
    }
    return {
        records: [create, ...refreshes, bills, current, returned, finalBills],
        durationMs: performance.now() - started,
        invariantError
    };
}

async function runBatchTransactionsProfile(options) {
    requireWritePermission(options);
    const token = await login(options);
    const authenticated = { ...options, token };
    const batchSize = Math.min(5, Math.max(2, options.concurrency));
    const started = performance.now();
    const records = [];
    const invariantErrors = [];

    const warehouses = await timedRequest(authenticated, "batch-warehouse-list", "/api/warehouses?paged=false");
    records.push(warehouses);
    const warehouse = (warehouses.body?.data ?? []).find((item) => item.defaultWarehouse)
        ?? warehouses.body?.data?.[0];
    const supplierResult = await findPagedItem(
        authenticated,
        "/api/suppliers",
        (supplier) => supplier.active && supplier.id != null
    );
    records.push(...supplierResult.records.map((record) => ({ ...record, name: "batch-supplier-list" })));
    if (!warehouses.ok || !warehouse?.id) {
        throw new Error("Batch transaction testing requires a warehouse");
    }
    let supplier = supplierResult.item;
    let createdSupplier = false;
    if (!supplier) {
        const createSupplier = await timedRequest(authenticated, "batch-supplier-create", "/api/suppliers", {
            method: "POST",
            expectedStatuses: [201],
            body: {
                supplierName: `${options.testPrefix}-supplier-${randomUUID().slice(0, 8)}`,
                supplierType: "STABILITY",
                active: true,
                remarks: "Automatically removed by the stability load test"
            }
        });
        records.push(createSupplier);
        if (!createSupplier.ok || createSupplier.body?.data?.id == null) {
            throw new Error("Cannot create an active supplier for batch transaction testing");
        }
        supplier = createSupplier.body.data;
        createdSupplier = true;
    }

    const purchaseParts = [];
    const purchaseItems = [];
    for (let index = 0; index < batchSize; index += 1) {
        const partCode = `${options.testPrefix}-BATCH-PO-${index}-${randomUUID().slice(0, 8)}`;
        const part = await timedRequest(authenticated, "batch-purchase-part-create", "/api/parts", {
            method: "POST",
            expectedStatuses: [201],
            body: {
                partCode,
                partName: "Batch purchase stability part",
                partCategory: "STABILITY",
                quantity: 0,
                reorderPoint: 0,
                warehouseId: warehouse.id,
                unit: "pcs",
                purchasePrice: "1.00"
            }
        });
        records.push(part);
        if (!part.ok || part.body?.data?.id == null) {
            throw new Error(`Cannot create batch purchase part ${index}`);
        }
        purchaseParts.push(part.body.data);
        const purchase = await timedRequest(authenticated, "batch-purchase-create", "/api/purchase-orders", {
            method: "POST",
            expectedStatuses: [201],
            body: {
                supplierId: supplier.id,
                resourceType: "PART",
                warehouseId: warehouse.id,
                resourceId: part.body.data.id,
                quantity: 1,
                unit: "pcs",
                unitPrice: "1.00",
                totalAmount: "1.00",
                freightAmount: "0.00",
                status: "ORDERED",
                operator: "stability-load-test",
                remark: "Batch purchase transaction test"
            }
        });
        records.push(purchase);
        if (!purchase.ok || purchase.body?.data?.id == null) {
            throw new Error(`Cannot create batch purchase ${index}`);
        }
        purchaseItems.push({
            id: purchase.body.data.id,
            version: purchase.body.data.version
        });
    }

    const purchaseCalls = await Promise.all(Array.from(
        { length: options.concurrency },
        () => timedRequest(authenticated, "batch-purchase-receive", "/api/purchase-orders/batch-receive", {
            method: "POST",
            expectedStatuses: [200, 400, 404, 409],
            api: false,
            body: { items: purchaseItems }
        })
    ));
    records.push(...purchaseCalls);
    const purchaseWinners = purchaseCalls.filter((record) => record.status === 200 && record.body?.code === 200);
    if (purchaseWinners.length !== 1) {
        invariantErrors.push(`purchase batch winners=${purchaseWinners.length}`);
    }

    const repairItems = [];
    for (let index = 0; index < batchSize; index += 1) {
        const repair = await timedRequest(authenticated, "batch-repair-create", "/api/repairs", {
            method: "POST",
            expectedStatuses: [201],
            body: {
                repairDate: `${isoDate(new Date())}T09:00:00`,
                vehicleNumber: `${options.testPrefix}-MANUAL-${index}`,
                customerName: "Stability repair customer",
                customerAddress: "Stability test",
                faultDescription: "Batch transaction stability test",
                repairContent: "No material usage",
                repairPersonChoice: "OTHER",
                repairExternal: true,
                repairFee: "0.00",
                repairExpense: "0.00",
                partsFee: "0.00",
                totalFee: "0.00",
                receivableAmount: "0.00",
                status: "PENDING",
                remarks: "Automatically removed by the stability load test"
            }
        });
        records.push(repair);
        if (!repair.ok || repair.body?.data?.id == null) {
            throw new Error(`Cannot create batch repair ${index}`);
        }
        repairItems.push({
            id: repair.body.data.id,
            version: repair.body.data.version
        });
    }
    const repairCalls = await Promise.all(Array.from(
        { length: options.concurrency },
        () => timedRequest(authenticated, "batch-repair-complete", "/api/repairs/batch-complete", {
            method: "POST",
            expectedStatuses: [200, 400, 404, 409],
            api: false,
            body: { items: repairItems }
        })
    ));
    records.push(...repairCalls);
    const repairWinners = repairCalls.filter((record) => record.status === 200 && record.body?.code === 200);
    if (repairWinners.length !== 1) {
        invariantErrors.push(`repair batch winners=${repairWinners.length}`);
    }

    const stockPartCode = `${options.testPrefix}-BATCH-ST-${randomUUID().slice(0, 8)}`;
    const stockPart = await timedRequest(authenticated, "batch-stock-part-create", "/api/parts", {
        method: "POST",
        expectedStatuses: [201],
        body: {
            partCode: stockPartCode,
            partName: "Batch stocktaking stability part",
            partCategory: "STABILITY",
            quantity: 1,
            reorderPoint: 0,
            warehouseId: warehouse.id,
            unit: "pcs",
            purchasePrice: "1.00"
        }
    });
    records.push(stockPart);
    if (!stockPart.ok || stockPart.body?.data?.id == null) {
        throw new Error("Cannot create stocktaking batch part");
    }
    const completedStocktakingItems = [];
    const draftStocktakingItems = [];
    for (let index = 0; index < batchSize * 2; index += 1) {
        const stocktaking = await timedRequest(authenticated, "batch-stocktaking-create", "/api/stocktaking-records", {
            method: "POST",
            expectedStatuses: [201],
            body: {
                resourceType: "PART",
                resourceId: stockPart.body.data.id,
                warehouseId: warehouse.id,
                actualQuantity: 1,
                status: "DRAFT",
                operator: "stability-load-test",
                remark: `${options.testPrefix}-batch-stocktaking-${index}`
            }
        });
        records.push(stocktaking);
        if (!stocktaking.ok || stocktaking.body?.data?.id == null) {
            throw new Error(`Cannot create stocktaking batch item ${index}`);
        }
        const item = {
            id: stocktaking.body.data.id,
            version: stocktaking.body.data.version
        };
        if (index < batchSize) {
            completedStocktakingItems.push(item);
        } else {
            draftStocktakingItems.push(item);
        }
    }

    const stockCompleteCalls = await Promise.all(Array.from(
        { length: options.concurrency },
        () => timedRequest(authenticated, "batch-stocktaking-complete", "/api/stocktaking-records/batch-complete", {
            method: "POST",
            expectedStatuses: [200, 400, 404, 409],
            api: false,
            body: { items: completedStocktakingItems }
        })
    ));
    records.push(...stockCompleteCalls);
    const stockCompleteWinners = stockCompleteCalls.filter(
        (record) => record.status === 200 && record.body?.code === 200
    );
    if (stockCompleteWinners.length !== 1) {
        invariantErrors.push(`stocktaking complete batch winners=${stockCompleteWinners.length}`);
    }

    const stockDeleteCalls = await Promise.all(Array.from(
        { length: options.concurrency },
        () => timedRequest(
            authenticated,
            "batch-stocktaking-delete",
            "/api/stocktaking-records/batch-delete-drafts",
            {
                method: "POST",
                expectedStatuses: [200, 400, 404, 409],
                api: false,
                body: { items: draftStocktakingItems }
            }
        )
    ));
    records.push(...stockDeleteCalls);
    const stockDeleteWinners = stockDeleteCalls.filter(
        (record) => record.status === 200 && record.body?.code === 200
    );
    if (stockDeleteWinners.length !== 1) {
        invariantErrors.push(`stocktaking delete batch winners=${stockDeleteWinners.length}`);
    }

    for (const repair of repairWinners[0]?.body?.data ?? []) {
        const remove = await timedRequest(
            authenticated,
            "batch-repair-cleanup",
            `/api/repairs/${repair.id}?version=${encodeURIComponent(repair.version)}`,
            { method: "DELETE" }
        );
        records.push(remove);
        if (!remove.ok) {
            invariantErrors.push(`repair cleanup failed for ${repair.id}`);
        }
    }
    for (const purchase of purchaseWinners[0]?.body?.data ?? []) {
        const unreceive = await timedRequest(
            authenticated,
            "batch-purchase-unreceive",
            `/api/purchase-orders/${purchase.id}/received?received=false`
                + `&version=${encodeURIComponent(purchase.version)}`,
            { method: "PUT" }
        );
        records.push(unreceive);
        if (!unreceive.ok) {
            invariantErrors.push(`purchase unreceive failed for ${purchase.id}`);
            continue;
        }
        const remove = await timedRequest(
            authenticated,
            "batch-purchase-cleanup",
            `/api/purchase-orders/${purchase.id}?version=${encodeURIComponent(unreceive.body?.data?.version)}`,
            { method: "DELETE" }
        );
        records.push(remove);
        if (!remove.ok) {
            invariantErrors.push(`purchase cleanup failed for ${purchase.id}`);
        }
    }
    for (const part of purchaseParts) {
        const current = await timedRequest(authenticated, "batch-purchase-part-read", `/api/parts/${part.id}`);
        records.push(current);
        if (!current.ok || Number(current.body?.data?.quantity) !== 0) {
            invariantErrors.push(`purchase part ${part.id} did not return to zero`);
            continue;
        }
        const remove = await timedRequest(
            authenticated,
            "batch-purchase-part-cleanup",
            `/api/parts/${part.id}?version=${encodeURIComponent(current.body?.data?.version)}`,
            { method: "DELETE" }
        );
        records.push(remove);
        if (!remove.ok) {
            invariantErrors.push(`purchase part cleanup failed for ${part.id}`);
        }
    }
    if (createdSupplier) {
        const remove = await timedRequest(
            authenticated,
            "batch-supplier-cleanup",
            `/api/suppliers/${supplier.id}?version=${encodeURIComponent(supplier.version)}`,
            { method: "DELETE" }
        );
        records.push(remove);
        if (!remove.ok) {
            invariantErrors.push(`supplier cleanup failed for ${supplier.id}`);
        }
    }

    return {
        records,
        durationMs: performance.now() - started,
        invariantError: invariantErrors.length > 0
            ? `Batch transaction invariants failed: ${invariantErrors.join("; ")}`
            : null
    };
}

async function execute(options) {
    if (!options.password) {
        throw new Error("Password is required through --password or STABILITY_PASSWORD");
    }
    return switchProfile(options);
}

async function switchProfile(options) {
    switch (options.profile) {
        case "read":
            return runReadProfile(options);
        case "login":
            return runLoginProfile(options);
        case "customer-crud":
            return runCustomerCrudProfile(options);
        case "payment-idempotency":
            return runPaymentIdempotencyProfile(options);
        case "inventory-flow":
            return runInventoryFlowProfile(options);
        case "attachment-io":
            return runAttachmentIoProfile(options);
        case "import-confirm":
            return runImportConfirmProfile(options);
        case "rental-billing":
            return runRentalBillingProfile(options);
        case "batch-transactions":
            return runBatchTransactionsProfile(options);
        default:
            throw new Error(`Unsupported profile: ${options.profile}`);
    }
}

async function main() {
    const options = parseArguments(process.argv.slice(2));
    const startedAt = new Date().toISOString();
    const execution = await execute(options);
    const summary = summarizeRecords(execution.records, execution.durationMs);
    const report = {
        startedAt,
        completedAt: new Date().toISOString(),
        configuration: {
            baseUrl: options.baseUrl,
            profile: options.profile,
            concurrency: options.concurrency,
            durationSeconds: options.durationSeconds,
            timeoutMs: options.timeoutMs,
            maxErrorRate: options.maxErrorRate,
            maxP95Ms: options.maxP95Ms
        },
        invariantError: execution.invariantError ?? null,
        summary
    };
    const rendered = `${JSON.stringify(report, null, 2)}\n`;
    process.stdout.write(rendered);
    if (options.output) {
        const outputPath = resolve(options.output);
        await mkdir(dirname(outputPath), { recursive: true });
        await writeFile(outputPath, rendered, "utf8");
    }
    if (report.invariantError || summary.errorRate > options.maxErrorRate || summary.latencyMs.p95 > options.maxP95Ms) {
        process.exitCode = 1;
    }
}

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : null;
if (invokedPath === import.meta.url) {
    main().catch((error) => {
        process.stderr.write(`${error instanceof Error ? error.stack : error}\n`);
        process.exitCode = 1;
    });
}
