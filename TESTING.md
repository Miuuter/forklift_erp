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

当前工作树默认 Java 测试为 222 项；2026-07-19 的 `clean verify` 结果为
`222/222`，无失败、错误或跳过。JaCoCo 指令/分支/行/方法覆盖率分别为
34.6898% / 29.5546% / 36.6567% / 36.2437%，均高于仓库基线。

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

历史 0.2.0-rc.1 的 MySQL Testcontainers 证据为 51 项、V1/V36→V45；当前 V51 变更必须使用下方新增门禁重新取证，不得沿用旧计数。

2026-07-19 当前工作树在 Docker 29.1.3 / MySQL 8.0.43 上发现 64 项、20 个测试类：
63 项执行并通过，0 failures、0 errors；另 1 项仅在设置
`FORKLIFT_ERP_TEST_MYSQL_URL` 时运行的外部 MySQL 升级用例按设计跳过。
套件包含空库 V1→V51、V18/V36/V40→V51、付款并发幂等、租赁车采购
收货拒绝、导入乐观锁、FIFO 尾差、历史主档删除保护和复合配置身份验证。
其中数据导入恢复、采购和租赁三类业务流程分别为 2/2、4/4 和 4/4，
均在真实 MySQL 8.0.43 上执行到服务/API 与持久化边界。
库存流水、收货批次和 FIFO 消耗的幂等键会校验资源、仓库、来源、
数量与成本等不可变 payload；同键异 payload 必须返回冲突，不能复用旧事实。

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
sh scripts/test-synology-backup.sh
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

恢复演练不仅导入 SQL：它还要启动恢复后的应用镜像，并验证当前 Flyway 版本（本轮为 V51）、健康状态、构建版本、登录、库存 API、关键表和样例附件下载。
## V46-V51 reliability verification

The current migration regression starts at V18, inserts a five-unit legacy sale
whose line total is 500, advances through V36/V40 fixtures, and then migrates to
V51 on MySQL 8.0.43. It must prove a 100 unit price, exactly 500 in receipt
facts, non-null optimistic versions, binary request keys, ledger arithmetic,
single-default guards and direct foreign keys. The ledger fixture must also
assert financial-event/payment reversal amount, direction, source and
counterparty identity, self-reference, chain and duplicate guards; reject
unknown event types, unlinked negative financial facts and cash events with a
source-line identity; FIFO reversal lot/resource/source
identity, opposite quantity/total cost, and rejection of an unlinked negative
consumption. Exercise the FIFO precision envelope at a normal one-cent drift
and at quantity boundaries (including 9,999, 10,000 and 20,001); the allowed
error is `ABS(qty) * 0.0000005 + 0.005` because `total_cost` is authoritative
and `unit_cost` is a six-decimal average. The focused V51 fixture must also
reject incomplete historical movement reversals before persistent DDL, backfill
complete out-of-order line pairs, and reject unlinked, quantity/lot/cost-drifted
or duplicate reversal lines.

Required commands for this change set are:

```powershell
.\mvnw.cmd test
.\mvnw.cmd -Pdocker-integration-tests test "-Dfrontend.skip=true"
npm.cmd run check
npm.cmd run test:unit
git diff --check
```

Do not reuse the historical V45/51-test count as evidence for V51. Record the
new counts only after the commands above complete against the current tree.

The 2026-07-19 rerun used the current sources and migrations after the latest
financial event sign/type/source-line/counterparty constraints. Frontend checks
passed; Vitest passed 7/7 with 100% statements/functions/lines and 87.5%
branches; default Java tests passed 222/222; the Docker suite reported 64 tests
across 20 classes, with 0 failures, 0 errors and one explicitly environment-gated skip.
