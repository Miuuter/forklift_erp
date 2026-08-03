import { describe, expect, it, vi } from "vitest";
import { createDownloadActions } from "../../main/resources/static/assets/modules/downloads.js";

function response({ status = 200, payload = null, requestId = "download-server-id", disposition = null } = {}) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: {
      get: name => name === "X-Request-ID" ? requestId
        : name === "Content-Disposition" ? disposition
          : null
    },
    json: async () => payload,
    blob: async () => new Blob(["file-content"], { type: "text/plain" })
  };
}

describe("download request context", () => {
  it("sends an authenticated request id and returns it on failure", async () => {
    const fetchMock = vi.fn().mockResolvedValue(response({
      status: 409,
      payload: { message: "附件不可下载" }
    }));
    vi.stubGlobal("fetch", fetchMock);
    const actions = createDownloadActions({ getToken: () => "token-value" });

    await expect(actions.fetchProtectedBlob("/api/attachments/7/download", "file.pdf"))
      .rejects.toMatchObject({ requestId: "download-server-id" });
    expect(fetchMock.mock.calls[0][1].headers).toMatchObject({
      Authorization: "Bearer token-value",
      "X-Request-ID": expect.stringMatching(/^download-/)
    });
  });

  it("returns a response blob and decodes an UTF-8 filename", async () => {
    const fetchMock = vi.fn().mockResolvedValue(response({
      disposition: "attachment; filename*=UTF-8''%E5%90%88%E5%90%8C.pdf"
    }));
    vi.stubGlobal("fetch", fetchMock);
    const actions = createDownloadActions({ getToken: () => undefined });

    const result = await actions.fetchProtectedBlob("/api/contracts/7", "fallback.pdf");

    expect(result.filename).toBe("合同.pdf");
    expect(result.blob).toBeInstanceOf(Blob);
    expect(fetchMock.mock.calls[0][1].headers).toEqual({
      "X-Request-ID": expect.stringMatching(/^download-/)
    });
  });

  it("marks protected-file authentication expiry separately", async () => {
    const fetchMock = vi.fn().mockResolvedValue(response({ status: 401 }));
    vi.stubGlobal("fetch", fetchMock);
    const actions = createDownloadActions({ getToken: () => "expired-token" });

    await expect(actions.fetchProtectedBlob("/api/backup", "backup.json"))
      .rejects.toMatchObject({ authExpired: true, requestId: "download-server-id" });
  });

  it("uses a payload request id and falls back to the status message", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response({ status: 500, payload: { message: "服务失败", requestId: "payload-id" } }))
      .mockResolvedValueOnce(response({ status: 403, requestId: "header-id" }));
    vi.stubGlobal("fetch", fetchMock);
    const actions = createDownloadActions({ getToken: () => "token" });

    await expect(actions.fetchProtectedBlob("/api/one", "one.pdf"))
      .rejects.toMatchObject({ message: "服务失败", requestId: "payload-id" });
    await expect(actions.downloadProtectedFile("/api/two", "two.pdf"))
      .rejects.toMatchObject({ message: "下载失败：403", requestId: "header-id" });
  });

  it("uses a plain filename and fallback when disposition is absent", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response({ disposition: 'attachment; filename="plain.csv"' }))
      .mockResolvedValueOnce(response());
    vi.stubGlobal("fetch", fetchMock);
    const actions = createDownloadActions({ getToken: () => "token" });

    await expect(actions.fetchProtectedBlob("/api/plain", "fallback.csv"))
      .resolves.toMatchObject({ filename: "plain.csv" });
    await expect(actions.fetchProtectedBlob("/api/fallback", "fallback.csv"))
      .resolves.toMatchObject({ filename: "fallback.csv" });
  });

  it("shows validation errors for unavailable invoice and contract files", async () => {
    const showToast = vi.fn();
    const actions = createDownloadActions({
      getToken: () => "token",
      showToast,
      endpoints: { outboundOrder: {} }
    });

    await actions.downloadInvoice({ id: 1, invoiceFileAvailable: false });
    await actions.downloadContract({ id: 1, contractFileAvailable: false });

    expect(showToast).toHaveBeenNthCalledWith(1, "该订单还没有发票文件", "error");
    expect(showToast).toHaveBeenNthCalledWith(2, "该订单还没有合同文件", "error");
  });

  it("downloads invoice and contract files with their original names", async () => {
    const click = vi.fn();
    const link = { click, remove: vi.fn() };
    vi.stubGlobal("document", {
      createElement: vi.fn(() => link),
      body: { appendChild: vi.fn() }
    });
    vi.stubGlobal("URL", {
      createObjectURL: vi.fn(() => "blob:download"),
      revokeObjectURL: vi.fn()
    });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response()));
    const endpoints = {
      outboundOrder: {
        downloadInvoice: vi.fn(id => `/invoice/${id}`),
        downloadContract: vi.fn(id => `/contract/${id}`)
      }
    };
    const actions = createDownloadActions({
      getToken: () => "token",
      endpoints
    });

    await actions.downloadInvoice({ id: 7, invoiceFileAvailable: true, invoiceOriginalName: "invoice.pdf" });
    await actions.downloadContract({ id: 7, contractFileAvailable: true, contractOriginalName: "contract.pdf" });

    expect(endpoints.outboundOrder.downloadInvoice).toHaveBeenCalledWith(7);
    expect(endpoints.outboundOrder.downloadContract).toHaveBeenCalledWith(7);
    expect(click).toHaveBeenCalledTimes(2);
    expect(link.remove).toHaveBeenCalledTimes(2);
  });

  it("supports exports, backups, and unsupported export types", async () => {
    vi.stubGlobal("document", {
      createElement: vi.fn(() => ({ click: vi.fn(), remove: vi.fn() })),
      body: { appendChild: vi.fn() }
    });
    vi.stubGlobal("URL", {
      createObjectURL: vi.fn(() => "blob:download"),
      revokeObjectURL: vi.fn()
    });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response()));
    const showToast = vi.fn();
    const actions = createDownloadActions({
      getToken: () => "token",
      todayInputDate: () => "2026-08-02",
      showToast,
      endpoints: {
        export: { vehicles: "/export/vehicles" },
        admin: { backup: "/admin/backup" },
        outboundOrder: {}
      }
    });

    await actions.downloadExcel("vehicles");
    await actions.downloadDataBackup();
    await actions.downloadExcel("unknown");

    expect(showToast).toHaveBeenCalledWith("导出已开始", "success");
    expect(showToast).toHaveBeenCalledWith("暂不支持该列表导出", "error");
  });
});
