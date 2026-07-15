import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    environment: "node",
    include: ["src/test/frontend/**/*.test.js"],
    coverage: {
      provider: "v8",
      include: [
        "src/main/resources/static/assets/modules/request-id.js",
        "src/main/resources/static/assets/modules/batch-operations.js"
      ],
      reporter: ["text", "json-summary"],
      thresholds: {
        lines: 85,
        functions: 85,
        statements: 85,
        branches: 75
      }
    }
  }
});
