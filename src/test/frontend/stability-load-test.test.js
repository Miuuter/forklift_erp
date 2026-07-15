import { describe, expect, it } from "vitest";

import { percentile, summarizeRecords } from "../../../scripts/stability-load-test.mjs";

describe("stability load test metrics", () => {
    it("calculates nearest-rank percentiles", () => {
        expect(percentile([], 0.95)).toBe(0);
        expect(percentile([10, 20, 30, 40], 0.50)).toBe(20);
        expect(percentile([40, 10, 30, 20], 0.95)).toBe(40);
    });

    it("summarizes throughput, endpoints, and failures", () => {
        const summary = summarizeRecords([
            { name: "inventory", ok: true, status: 200, durationMs: 10 },
            { name: "inventory", ok: false, status: 500, durationMs: 30, error: "boom" },
            { name: "parts", ok: true, status: 200, durationMs: 20 }
        ], 1000);

        expect(summary.requests).toBe(3);
        expect(summary.failed).toBe(1);
        expect(summary.requestsPerSecond).toBe(3);
        expect(summary.latencyMs.p95).toBe(30);
        expect(summary.endpoints.inventory.errors).toEqual({ boom: 1 });
        expect(summary.endpoints.parts.failed).toBe(0);
    });

    it("summarizes large soak-test samples without spreading the full array", () => {
        const records = Array.from({ length: 200_000 }, (_, index) => ({
            name: "inventory",
            ok: true,
            status: 200,
            durationMs: index % 1000
        }));

        const summary = summarizeRecords(records, 300_000);

        expect(summary.requests).toBe(200_000);
        expect(summary.latencyMs.min).toBe(0);
        expect(summary.latencyMs.max).toBe(999);
        expect(summary.latencyMs.p95).toBe(949);
    });
});
