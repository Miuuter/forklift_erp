import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "src/test/e2e",
  timeout: 45_000,
  retries: process.env.CI ? 1 : 0,
  use: {
    baseURL: process.env.E2E_BASE_URL || "http://127.0.0.1:8080",
    trace: "retain-on-failure",
    screenshot: "only-on-failure"
  },
  reporter: process.env.CI ? [["github"], ["html", { open: "never" }]] : "list"
});
