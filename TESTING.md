# 测试手册

## 前端

安装锁定依赖并执行静态检查：

```powershell
npm.cmd ci --cache target/npm-cache
npm.cmd run check
```

Vitest 单元测试及 V8 覆盖率：

```powershell
npm.cmd run test:unit
```

当前共 7 项前端单元测试，覆盖请求 ID 复用、批量请求按 ID 排序/版本传递，以及压力测试在十万级延迟样本下的汇总与分位数计算。

## Java 单元测试与覆盖率

默认测试不要求 Docker，并排除 `docker-integration` 标签：

```powershell
.\mvnw.cmd clean verify "-Dfrontend.skip=true"
java scripts/CheckCoverageBaseline.java
```

JaCoCo 报告位于 `target/site/jacoco`。基线记录在 `scripts/jacoco-baseline.properties`，CI 禁止仓库级指令、分支、行或方法覆盖率下降。

0.2.0-rc.1 当前默认 Java 单元测试为 140 项。

## MySQL Testcontainers

```powershell
.\mvnw.cmd -Pdocker-integration-tests test "-Dfrontend.skip=true"
```

要求 Docker daemon 可用。测试使用 `mysql:8.0.43`，数据源由 `TestcontainersDatabaseSupport` 动态覆盖，不应指向日常业务库。

重点验证：

- 付款和冲销幂等；
- 导入任务并发确认；
- 采购、销售、维修、改装、调拨和盘点后的分仓/FIFO 对账；
- 统计 MySQL 聚合 SQL；
- Flyway 新建库与升级路径。

0.2.0-rc.1 当前 MySQL Testcontainers 套件为 51 项；新建库从 V1 迁移到 V45，升级回归从 V36 夹具迁移到 V45。

## Playwright

针对已启动的测试环境：

```powershell
$env:E2E_BASE_URL = "http://127.0.0.1:8080"
$env:E2E_USERNAME = "<username>"
$env:E2E_PASSWORD = "<password>"
npx playwright install chromium
npm.cmd run test:e2e
```

未提供账号时测试会明确跳过。CI 使用临时 MySQL 和测试账号启动应用后执行核心模块加载冒烟。

当前 Playwright 为 7 项核心模块入口冒烟；真实写入型浏览器流程仍列在后续计划中。

## 稳定性与压力测试

只读综合压力示例：

```powershell
node scripts/stability-load-test.mjs `
  --base-url http://127.0.0.1:8080 `
  --username admin `
  --password "<password>" `
  --profile read `
  --concurrency 50 `
  --duration-seconds 300 `
  --max-error-rate 0 `
  --max-p95-ms 750 `
  --output dist/stability-results/read-c50-soak.json
```

可用 profile：

- `read`：核心列表、统计、日对账和静态资源轮询；
- `login`：登录吞吐；
- `customer-crud`：客户创建/删除；
- `payment-idempotency`：同一付款及冲销请求 ID 并发收敛；
- `inventory-flow`：配件入库、出库、跨仓 FIFO 调拨和清理；
- `attachment-io`：PDF 上传后并发预览/下载；
- `import-confirm`：同一导入任务并发确认；
- `rental-billing`：并发补账、账期去重和归还；
- `batch-transactions`：采购、维修和盘点批量事务竞争。

除 `read`、`login` 外的 profile 必须显式增加 `--allow-writes`，且只能指向可丢弃的隔离测试环境。结果 JSON 的 `invariantError` 必须为空，不能只看 HTTP 错误率。

`batch-transactions` 会保留已完成盘点记录及其测试配件，因为正式业务规则禁止删除已完成盘点和被引用主档；不要在共享测试库或生产库运行。

本轮完整数据量和结果见 [docs/STABILITY_REPORT_0.2.0-rc.1.md](docs/STABILITY_REPORT_0.2.0-rc.1.md)。

## 发布前命令

```powershell
npm.cmd run check
npm.cmd run test:unit
.\mvnw.cmd clean verify "-Dfrontend.skip=true"
java scripts/CheckCoverageBaseline.java
.\mvnw.cmd -Pdocker-integration-tests test "-Dfrontend.skip=true"
$env:E2E_BASE_URL = "http://127.0.0.1:8080"
$env:E2E_USERNAME = "<username>"
$env:E2E_PASSWORD = "<password>"
npm.cmd run test:e2e
docker compose --env-file deploy/synology/.env -f deploy/synology/compose.yaml config --quiet
sh -n deploy/synology/backup.sh
sh -n deploy/synology/restore-drill.sh
sh -n deploy/synology/update.sh
git diff --check
```

还应验证：

- `target/forklift-erp-0.2.0-rc.1.jar` 存在；
- `target/classes/META-INF/sbom/application.cdx.json` 存在；
- `/actuator/health` 为 `UP`；
- `/actuator/info` 的构建版本和 Git 信息正确。

## 数据恢复测试

应用内数据恢复先调用超级管理员 dry-run，再使用确认短语执行。Synology 数据库和附件恢复演练使用：

```sh
sh deploy/synology/restore-drill.sh [backup-directory]
```

恢复演练不仅导入 SQL：它还要启动恢复后的应用镜像，并验证 V45、健康状态、构建版本、登录、库存 API、关键表和样例附件下载。
