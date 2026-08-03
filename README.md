# 叉车 ERP

当前版本：`0.2.0-rc.1`。这是测试版稳定化分支，技术栈保持为 Java 21、Spring Boot 3.5、MySQL 8、单体应用和原生 ES Modules 前端。

## 核心数据原则

- 库存数量与成本以 `stock_movement`、`stock_movement_line`、`stock_balance`、`stock_lot*` 为准。
- 财务与现金以 `financial_event`、`payment_record` 为准。
- 主档数量、`stock_operation_log` 和历史价格字段只作为兼容缓存，不作为统计报表的事实来源。
- 存在未解决的迁移异常时，统计接口返回 `dataWarnings`，不会静默混用新旧口径。

## 本地运行

准备 MySQL 8 数据库后设置环境变量：

```powershell
$env:FORKLIFT_ERP_DB_URL = "jdbc:mysql://localhost:3306/forklift_erp?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
$env:FORKLIFT_ERP_DB_USERNAME = "root"
$env:FORKLIFT_ERP_DB_PASSWORD = "<password>"
$env:FORKLIFT_ERP_JWT_SECRET = "<至少 32 字节的随机字符串>"
.\mvnw.cmd spring-boot:run
```

生产或共享测试环境应显式设置数据库、JWT 和管理员引导参数。默认管理员账号密码不属于本轮测试版整改范围。

## 完整闭环测试库

本仓库提供的是本机开发/测试库的重建流程，不可用于生产或共享环境。旧的展示 fixture 已默认关闭；它会直接写入部分历史行，不能作为账实可追溯的测试基线。

1. 显式设置本机 MySQL 密码（`FORKLIFT_ERP_DB_PASSWORD` 或 `MYSQL_PWD`），确认目标只能是 `127.0.0.1:3306/forklift_erp`。
2. 执行 `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\rebuild_complete_test_database.ps1 -ConfirmCleanDatabase`。脚本默认先将旧库导出到 `local-db-backups`，并且在发现其他会话、事务或写操作时拒绝继续。
3. 以 `FORKLIFT_ERP_SEED_DEMO_DATA=false` 启动应用，使 Flyway 升级到 V51。
4. 运行 `python ./scripts/seed_closed_loop_test_data.py --base-url http://127.0.0.1:8080`，它只通过业务 API 写入采购、FIFO、销售、回款、租赁账单、维修领料、改装、盘点、附件与导入数据。
5. 运行 `python ./scripts/verify_closed_loop_test_database.py --base-url http://127.0.0.1:8080 --sql`。验收条件为日对账零错误、没有未解决迁移异常，并且关键账本/文件/导入表均有可追溯数据；`--sql` 还会核验 Flyway、余额和 FIFO 账实关系。

当前闭环基线按约 30 台序列化车辆构造：10 台已售、6 台租赁后维修、4 台取消改装、4 台调拨、6 台可售；并配套 35 笔采购、25 笔出库、5 笔盘点、6 个维修附件和 5 条配件导入行。验收还要求销售超收、日对账错误和财务警告均为零。

如需连同旧上传文件一起清除，才额外给重建脚本传 `-CleanUploads`；默认不会删除任何文件。

## 构建与测试

```powershell
npm.cmd ci --cache target/npm-cache
npm.cmd run check
npm.cmd run test:unit
.\mvnw.cmd clean verify "-Dfrontend.skip=true"
java scripts/CheckCoverageBaseline.java
.\mvnw.cmd -Pdocker-integration-tests test "-Dfrontend.skip=true"
$env:E2E_BASE_URL = "http://127.0.0.1:8080"
$env:E2E_USERNAME = "<username>"
$env:E2E_PASSWORD = "<password>"
npm.cmd run test:e2e
git diff --check
```

完整测试说明见 [TESTING.md](TESTING.md)。

## 数据库迁移

- V37–V40 是已发布且不可修改的迁移。
- V41–V45 保持不可修改；本轮新增 V46–V51，后续数据库变更从 V52 开始。
- 升级旧库前必须停止写入、完成数据库/uploads 备份，并运行
  `scripts/mysql-upgrade-preflight-v40.sql`；阻塞异常应先处理，再应用直接外键与数量约束。

详见 [迁移说明](docs/MIGRATIONS.md)。

## 发布与恢复

- GitHub Actions 执行前端检查、单元测试、MySQL Testcontainers 集成测试、端到端冒烟和制品打包。
- 发布制品包含可执行 JAR、Git 构建信息和 CycloneDX SBOM。
- Synology 部署脚本会先备份，再部署并核对 `/actuator/health` 与 `/actuator/info` 版本。
- `deploy/synology/backup.sh` 保留 7 个日备份和 4 个周备份，并复制到独立存储。
- `deploy/synology/restore-drill.sh` 使用隔离 MySQL 容器验证数据库和附件可恢复。

发布清单见 [RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md)，恢复手册见 [RESTORE_RUNBOOK.md](docs/RESTORE_RUNBOOK.md)。

本地等价环境的容量、并发和恢复结果见 [0.2.0-rc.1 稳定性报告](docs/STABILITY_REPORT_0.2.0-rc.1.md)。真实 Synology 的 CPU 架构、磁盘和网络性能仍须在目标 NAS 上执行发布清单。

### V46-V51 data reliability hardening

The latest schema is V51. Production upgrades must stop writers, take a
database/uploads backup, run `scripts/mysql-upgrade-preflight-v40.sql`, and
resolve every ambiguous historical fact. See `docs/MIGRATIONS.md`; prior V45
verification is historical evidence and does not substitute for the V51 gate.

Interrupted imports are recovered from stale `IMPORTING` to `FAILED` after a
24-hour default timeout. Operators may tune this with
`FORKLIFT_ERP_IMPORT_RECOVERY_TIMEOUT_MINUTES`; recovery never replays a
workbook automatically. Billed rentals also freeze customer, start date and
monthly price so posted receivables cannot silently diverge from the contract.

Current local evidence (2026-08-02): 226/226 default Java tests with no
failures/errors/skips, frontend quality checks, and Vitest 17/17. Historical
MySQL evidence remains 64 integration tests across 20 classes with no
failures/errors and one environment-gated skip; rerun it after Docker is
available for this worktree.

## 架构说明

稳定化阶段的事实来源、事务边界和暂不引入的组件记录在 [ADR-0001](docs/architecture/ADR-0001-stabilization-boundaries.md)。
