import { describe, expect, it } from "vitest";
import { versionedBatchPayload } from "../../main/resources/static/assets/modules/batch-operations.js";

describe("versionedBatchPayload", () => {
  it("keeps id/version pairs and orders them by id for deterministic locking", () => {
    expect(versionedBatchPayload([
      { id: 9, version: 2 },
      { id: 3, version: 7 }
    ])).toEqual({
      items: [
        { id: 3, version: 7 },
        { id: 9, version: 2 }
      ]
    });
  });

  it("drops incomplete rows instead of sending an unsafe batch item", () => {
    expect(versionedBatchPayload([
      { id: 1, version: 0 },
      { id: null, version: 2 },
      { id: 3 }
    ])).toEqual({ items: [{ id: 1, version: 0 }] });
  });
});
