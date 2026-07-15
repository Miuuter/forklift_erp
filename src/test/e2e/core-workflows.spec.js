import { expect, test } from "@playwright/test";

const username = process.env.E2E_USERNAME;
const password = process.env.E2E_PASSWORD;

test.describe("forklift ERP core workflows", () => {
  test.skip(!username || !password, "Set E2E_USERNAME and E2E_PASSWORD for a deployed test environment.");

  test.beforeEach(async ({ page }) => {
    page.on("pageerror", error => {
      console.error(`[browser pageerror] ${error.stack || error.message}`);
    });
    page.on("console", message => {
      if (message.type() === "error") {
        console.error(`[browser console] ${message.text()}`);
      }
    });
    await page.goto("/");
    await page.getByLabel("用户名").fill(username);
    await page.getByLabel("密码").fill(password);
    await page.getByRole("button", { name: "登录" }).click();
    await expect(page.locator("#appScreen")).toBeVisible();
  });

  for (const workflow of [
    { name: "采购入库", tab: "入库订单" },
    { name: "销售出库与收付款冲销", tab: "出库订单" },
    { name: "维修领料", tab: "维修记录" },
    { name: "仓库调拨", tab: "仓库管理" },
    { name: "库存盘点", tab: "库存盘点" },
    { name: "租赁账单", tab: "租赁管理" },
    { name: "重复导入", tab: "数据维护" }
  ]) {
    test(`${workflow.name}入口可完成端到端加载`, async ({ page }) => {
      await page.getByRole("button", { name: workflow.tab, exact: true }).click();
      await expect(page.locator("#moduleContent")).not.toBeEmpty();
      await expect(page.locator("#moduleContent")).not.toContainText("加载失败");
    });
  }
});
