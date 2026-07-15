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
- 本轮新增迁移为 V41–V45，后续数据库变更从 V46 开始。
- 升级旧库前必须完成备份和历史修复 dry-run；阻塞异常应先处理，再应用直接外键与数量约束。

详见 [迁移说明](docs/MIGRATIONS.md)。

## 发布与恢复

- GitHub Actions 执行前端检查、单元测试、MySQL Testcontainers 集成测试、端到端冒烟和制品打包。
- 发布制品包含可执行 JAR、Git 构建信息和 CycloneDX SBOM。
- Synology 部署脚本会先备份，再部署并核对 `/actuator/health` 与 `/actuator/info` 版本。
- `deploy/synology/backup.sh` 保留 7 个日备份和 4 个周备份，并复制到独立存储。
- `deploy/synology/restore-drill.sh` 使用隔离 MySQL 容器验证数据库和附件可恢复。

发布清单见 [RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md)，恢复手册见 [RESTORE_RUNBOOK.md](docs/RESTORE_RUNBOOK.md)。

本地等价环境的容量、并发和恢复结果见 [0.2.0-rc.1 稳定性报告](docs/STABILITY_REPORT_0.2.0-rc.1.md)。真实 Synology 的 CPU 架构、磁盘和网络性能仍须在目标 NAS 上执行发布清单。

## 架构说明

稳定化阶段的事实来源、事务边界和暂不引入的组件记录在 [ADR-0001](docs/architecture/ADR-0001-stabilization-boundaries.md)。
