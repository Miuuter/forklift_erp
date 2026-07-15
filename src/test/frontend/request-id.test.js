import { describe, expect, it } from "vitest";
import { createRequestId } from "../../main/resources/static/assets/modules/request-id.js";

describe("createRequestId", () => {
  it("creates a reusable non-empty request identifier with the requested prefix", () => {
    const requestId = createRequestId("payment");

    expect(requestId).toMatch(/^payment-[A-Za-z0-9-]+$/);
    expect(requestId.length).toBeLessThanOrEqual(120);
  });

  it("creates a different identifier for a separate business submission", () => {
    expect(createRequestId("import")).not.toBe(createRequestId("import"));
  });
});
