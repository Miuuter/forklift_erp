import { describe, expect, it, vi } from "vitest";
import { createApiClient } from "../../main/resources/static/assets/modules/session.js";

function response(payload, status = 200, requestId = "server-request-id") {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: name => name === "X-Request-ID" ? requestId : null },
    json: async () => payload
  };
}

describe("createApiClient request context", () => {
  it("sends the caller request id and exposes the server id on API errors", async () => {
    const fetchMock = vi.fn().mockResolvedValue(response({
      code: 409,
      message: "版本冲突"
    }, 409));
    vi.stubGlobal("fetch", fetchMock);

    const api = createApiClient(() => "token-value");

    await expect(api("/api/repairs/7", {
      method: "PUT",
      requestId: "repair-submit-7",
      body: { version: 3 }
    })).rejects.toMatchObject({
      status: 409,
      requestId: "server-request-id"
    });

    expect(fetchMock).toHaveBeenCalledWith("/api/repairs/7", expect.objectContaining({
      headers: expect.objectContaining({
        Authorization: "Bearer token-value",
        "X-Request-ID": "repair-submit-7"
      })
    }));
  });

  it("prefers a response-body request id when a proxy omits the response header", async () => {
    const fetchMock = vi.fn().mockResolvedValue(response({
      code: 409,
      message: "重复请求",
      requestId: "body-request-id"
    }, 409, null));
    vi.stubGlobal("fetch", fetchMock);

    const api = createApiClient(() => "token-value");

    await expect(api("/api/payments", { method: "POST", body: {} }))
      .rejects.toMatchObject({ requestId: "body-request-id" });
  });
});
